package com.stockbucks.api.stock;

import com.stockbucks.StockData;
import com.stockbucks.api.config.EnvironmentConfig;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 股票資料來源總控。
 * 即時/盤中資料優先選最小時間單位；歷史資料先用 TWSE/TPEx 官方日資料，再由 Web、FinMind、本地資料補缺。
 */
public class MarketDataService {
    private static final LocalDate DEFAULT_START_DATE = LocalDate.of(2010, 1, 4);
    private static final String DEFAULT_PROVIDER_CHAIN = "broker,fugle,web,twse,tpex,finmind,local";
    private static final String DEFAULT_HISTORY_PROVIDER_CHAIN = "twse,tpex,web,finmind,local";
    private static final String DEFAULT_INTRADAY_PROVIDER_CHAIN = "broker,web,fugle,twse,tpex,finmind,local";

    private final List<StockDataClient> clients;
    private String lastProviderUsed = "";
    private String lastFallbackReason = "";

    public MarketDataService() {
        this(defaultClients());
    }

    public MarketDataService(TwseHistoricalDataClient twseClient) {
        this(List.of(twseClient));
    }

    public MarketDataService(List<StockDataClient> clients) {
        this.clients = clients == null || clients.isEmpty() ? defaultClients() : List.copyOf(clients);
    }

    public Map<String, String> providerStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        for (StockDataClient client : clients) {
            String missingKey = client.getMissingApiKeyName();
            status.put(client.getProviderName(), missingKey.isBlank() ? "ready" : "missing " + missingKey);
        }
        return status;
    }

    public Map<String, String> supportedApiEndpoints() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        for (StockDataClient client : clients) {
            client.supportedApiEndpoints().forEach((name, endpoint) ->
                    endpoints.put(client.getProviderName() + "." + name, endpoint));
        }
        return endpoints;
    }

    public String getLastProviderUsed() {
        return lastProviderUsed;
    }

    public String getLastFallbackReason() {
        return lastFallbackReason;
    }

    public StockQuote fetchQuote(String stockId) {
        return firstSuccessful(clients, client -> client.fetchQuote(stockId));
    }

    public List<StockQuoteAttempt> fetchQuoteAttempts(String stockId) {
        List<StockQuoteAttempt> attempts = new ArrayList<>();
        for (StockDataClient client : clients) {
            String provider = client.getProviderName();
            if (!client.isConfigured()) {
                attempts.add(new StockQuoteAttempt(stockId, provider, false, "missing",
                        missingMessage(client.getMissingApiKeyName()), null, providerGranularity(provider), providerRank(provider)));
                continue;
            }

            try {
                StockQuote quote = client.fetchQuote(stockId);
                attempts.add(quote == null
                        ? new StockQuoteAttempt(stockId, provider, true, "no data", "此來源沒有回傳可用報價", null, providerGranularity(provider), providerRank(provider))
                        : new StockQuoteAttempt(stockId, provider, true, "success", "成功取得資料", quote, providerGranularity(provider), providerRank(provider)));
            } catch (RuntimeException ex) {
                attempts.add(new StockQuoteAttempt(stockId, provider, true, "failed",
                        cleanErrorMessage(ex), null, providerGranularity(provider), providerRank(provider)));
            }
        }
        return attempts;
    }

    public List<StockData> fetchDailyHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        List<StockHistoryAttempt> attempts = fetchDailyHistoryAttempts(stockId, fromDate, toDate);
        List<StockData> merged = mergeHistoryAttempts(attempts);
        lastProviderUsed = historyProviderSummary(attempts);
        lastFallbackReason = historyFallbackSummary(attempts);
        return merged;
    }

    public List<StockHistoryAttempt> fetchDailyHistoryAttempts(String stockId, LocalDate fromDate, LocalDate toDate) {
        List<StockHistoryAttempt> attempts = new ArrayList<>();
        for (StockDataClient client : historyClients()) {
            String provider = client.getProviderName();
            if (!client.isConfigured()) {
                attempts.add(new StockHistoryAttempt(stockId, provider, false, "missing",
                        missingMessage(client.getMissingApiKeyName()), List.of(), historyGranularity(provider), historyRank(provider)));
                continue;
            }

            try {
                List<StockData> data = client.fetchDailyHistory(stockId, fromDate, toDate);
                attempts.add(data == null || data.isEmpty()
                        ? new StockHistoryAttempt(stockId, provider, true, "no data", "此來源沒有回傳可用歷史資料", List.of(), historyGranularity(provider), historyRank(provider))
                        : new StockHistoryAttempt(stockId, provider, true, "success", "成功取得歷史資料", data, historyGranularity(provider), historyRank(provider)));
            } catch (RuntimeException ex) {
                attempts.add(new StockHistoryAttempt(stockId, provider, true, "failed",
                        cleanErrorMessage(ex), List.of(), historyGranularity(provider), historyRank(provider)));
            }
        }
        return attempts;
    }

    public List<StockIntradayAttempt> fetchIntradayBarAttempts(String stockId, String interval) {
        List<StockIntradayAttempt> attempts = new ArrayList<>();
        for (StockDataClient client : intradayClients()) {
            String provider = client.getProviderName();
            if (!client.isConfigured()) {
                attempts.add(new StockIntradayAttempt(stockId, provider, false, "missing",
                        missingMessage(client.getMissingApiKeyName()), List.of(), intradayGranularity(provider, interval), intradayRank(provider)));
                continue;
            }

            try {
                List<IntradayBar> bars = fetchIntradayBarsFromClient(client, stockId, interval);
                if (bars.isEmpty() && isOfficialDailyProvider(provider)) {
                    attempts.add(new StockIntradayAttempt(stockId, provider, true, "daily only",
                            provider.toUpperCase() + " 沒有當天盤中 K 線，只能提供官方日資料備援",
                            List.of(), "daily", intradayRank(provider)));
                } else {
                    attempts.add(bars.isEmpty()
                            ? new StockIntradayAttempt(stockId, provider, true, "no data", "此來源沒有回傳盤中 K 線", List.of(), intradayGranularity(provider, interval), intradayRank(provider))
                            : new StockIntradayAttempt(stockId, provider, true, "success", "成功取得盤中 K 線", bars, intradayGranularity(provider, interval), intradayRank(provider)));
                }
            } catch (RuntimeException ex) {
                attempts.add(new StockIntradayAttempt(stockId, provider, true, "failed",
                        cleanErrorMessage(ex), List.of(), intradayGranularity(provider, interval), intradayRank(provider)));
            }
        }
        return attempts;
    }

    public List<IntradayBar> fetchBestIntradayBars(String stockId, String interval) {
        List<String> failures = new ArrayList<>();
        for (StockIntradayAttempt attempt : fetchIntradayBarAttempts(stockId, interval)) {
            if ("success".equals(attempt.getStatus()) && !attempt.getBars().isEmpty()) {
                lastProviderUsed = attempt.getProviderName();
                lastFallbackReason = String.join("; ", failures);
                return attempt.getBars();
            }
            failures.add(attempt.getProviderName() + " " + attempt.getStatus() + ": " + attempt.getMessage());
        }
        lastProviderUsed = "";
        lastFallbackReason = failures.isEmpty() ? "沒有來源回傳盤中 K 線" : String.join("; ", failures);
        return List.of();
    }

    public List<StockData> fetchDailyMarketAll() {
        Map<String, StockData> byStockId = new LinkedHashMap<>();
        for (StockDataClient client : historyClients()) {
            if (!client.isConfigured()) {
                continue;
            }
            try {
                for (StockData data : client.fetchDailyMarketAll()) {
                    if (data.getStockID() != null && !data.getStockID().isBlank()) {
                        byStockId.putIfAbsent(data.getStockID(), data);
                    }
                }
            } catch (RuntimeException ignored) {
                // 全市場資料採互補策略，單一來源失敗時讓下一個來源繼續補。
            }
        }
        return new ArrayList<>(byStockId.values());
    }

    public List<StockProfile> getStockProfiles() {
        Map<String, StockProfile> byStockId = new LinkedHashMap<>();
        for (StockDataClient client : historyClients()) {
            if (!client.isConfigured()) {
                continue;
            }
            try {
                for (StockProfile profile : client.fetchListedStockProfiles()) {
                    if (profile.getStockId() != null && !profile.getStockId().isBlank()) {
                        byStockId.putIfAbsent(profile.getStockId(), profile);
                    }
                }
            } catch (RuntimeException ignored) {
                // 股票清單也採互補策略，避免單一市場來源失敗就整批拿不到。
            }
        }
        return new ArrayList<>(byStockId.values());
    }

    public List<StockData> getHistoricalData(String stockId) {
        return updateHistoricalData(stockId).getData();
    }

    public String getBrokerStatus() {
        BrokerAccountClient broker = firstBrokerClient();
        if (broker == null) {
            return "broker client unavailable";
        }

        String missing = broker.getMissingBrokerConfigName();
        return missing == null || missing.isBlank() ? "broker ready" : "broker missing " + missing;
    }

    public BrokerAccountSnapshot fetchBrokerAccountSnapshot() {
        BrokerAccountClient broker = firstBrokerClient();
        return broker == null ? null : broker.fetchAccountSnapshot();
    }

    public List<BrokerPosition> fetchBrokerPositions() {
        BrokerAccountClient broker = firstBrokerClient();
        return broker == null ? List.of() : broker.fetchPositions();
    }

    public List<IntradayBar> fetchBrokerIntradayBars(String stockId, String interval) {
        BrokerAccountClient broker = firstBrokerClient();
        return broker == null ? List.of() : broker.fetchIntradayBars(stockId, interval);
    }

    public MarketDataUpdateResult updateHistoricalData(String stockId) {
        return updateHistoricalData(stockId, DEFAULT_START_DATE, LocalDate.now());
    }

    public MarketDataUpdateResult updateHistoricalData(String stockId, LocalDate fromDate, LocalDate toDate) {
        if (stockId == null || stockId.isBlank()) {
            return MarketDataUpdateResult.skipped(stockId, "Stock id is required.");
        }
        if (fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
            return MarketDataUpdateResult.skipped(stockId, "No valid date range to fetch.");
        }

        List<StockData> data = fetchDailyHistory(stockId, fromDate, toDate);
        return new MarketDataUpdateResult(stockId, fromDate, toDate, data.size(), 0,
                "Fetched stock history from " + lastProviderUsed + ".", data);
    }

    public MarketDataUpdateResult updateAllListedStocks(LocalDate fromDate, LocalDate toDate, int maxStocks) {
        return updateAllListedStocks(fromDate, toDate, maxStocks, null);
    }

    public MarketDataUpdateResult updateAllListedStocks(LocalDate fromDate,
                                                        LocalDate toDate,
                                                        int maxStocks,
                                                        Consumer<String> progressCallback) {
        List<StockProfile> profiles = getStockProfiles();
        int limit = maxStocks <= 0 ? profiles.size() : Math.min(maxStocks, profiles.size());
        int fetched = 0;

        for (int i = 0; i < limit; i++) {
            StockProfile profile = profiles.get(i);
            notifyProgress(progressCallback, "Fetching " + (i + 1) + "/" + limit + " " + profile.getStockId());
            fetched += fetchDailyHistory(profile.getStockId(), fromDate, toDate).size();
        }

        return new MarketDataUpdateResult("ALL_STOCKS", fromDate, toDate, fetched, 0,
                "Fetched listed stock history with history fallback chain.");
    }

    public MarketDataUpdateResult backfillAllListedStocksYearByYear(int startYear,
                                                                    int endYear,
                                                                    int maxStocks,
                                                                    Consumer<String> progressCallback) {
        LocalDate fromDate = LocalDate.of(Math.min(startYear, endYear), 1, 1);
        LocalDate toDate = LocalDate.of(Math.max(startYear, endYear), 12, 31);
        if (toDate.isAfter(LocalDate.now())) {
            toDate = LocalDate.now();
        }
        return updateAllListedStocks(fromDate, toDate, maxStocks, progressCallback);
    }

    public String fetchRaw(String endpointPath) {
        for (StockDataClient client : clients) {
            if (client instanceof TwseHistoricalDataClient twseClient) {
                return twseClient.fetchRaw(endpointPath);
            }
        }
        throw new RuntimeException("No TWSE client is available for raw endpoint fetch.");
    }

    public String fetchRawWebPage(String url) {
        for (StockDataClient client : clients) {
            if (client instanceof WebStockScraperClient webClient) {
                return webClient.fetchRawPage(url);
            }
        }
        return new WebStockScraperClient().fetchRawPage(url);
    }

    private List<IntradayBar> fetchIntradayBarsFromClient(StockDataClient client, String stockId, String interval) {
        if (client instanceof BrokerAccountClient brokerClient) {
            return brokerClient.fetchIntradayBars(stockId, interval);
        }
        if (client instanceof WebStockScraperClient webClient) {
            return webClient.fetchIntradayBars(stockId, interval);
        }
        return List.of();
    }

    private <T> T firstSuccessful(List<StockDataClient> orderedClients, ClientCall<T> call) {
        List<String> failures = new ArrayList<>();
        for (StockDataClient client : orderedClients) {
            if (!client.isConfigured()) {
                failures.add(client.getProviderName() + " missing " + client.getMissingApiKeyName());
                continue;
            }

            try {
                T result = call.fetch(client);
                if (hasData(result)) {
                    lastProviderUsed = client.getProviderName();
                    lastFallbackReason = String.join("; ", failures);
                    return result;
                }
                failures.add(client.getProviderName() + " returned no data");
            } catch (RuntimeException ex) {
                failures.add(client.getProviderName() + " failed: " + cleanErrorMessage(ex));
            }
        }

        lastProviderUsed = "";
        lastFallbackReason = String.join("; ", failures);
        return null;
    }

    private List<StockDataClient> historyClients() {
        return clientsForChain(EnvironmentConfig.first(DEFAULT_HISTORY_PROVIDER_CHAIN, "STOCK_HISTORY_PROVIDER_CHAIN", "STOCKBUCKS_STOCK_HISTORY_PROVIDER_CHAIN"));
    }

    private List<StockDataClient> intradayClients() {
        return clientsForChain(EnvironmentConfig.first(DEFAULT_INTRADAY_PROVIDER_CHAIN, "STOCK_INTRADAY_PROVIDER_CHAIN", "STOCKBUCKS_STOCK_INTRADAY_PROVIDER_CHAIN"));
    }

    private List<StockDataClient> clientsForChain(String chain) {
        List<StockDataClient> result = new ArrayList<>();
        for (String provider : chain.split(",")) {
            StockDataClient client = findClient(provider.trim());
            if (client != null) {
                result.add(client);
            }
        }
        return result.isEmpty() ? clients : result;
    }

    private List<StockData> mergeHistoryAttempts(List<StockHistoryAttempt> attempts) {
        Map<String, StockData> byDate = new TreeMap<>();
        for (StockHistoryAttempt attempt : attempts) {
            if (!"success".equals(attempt.getStatus())) {
                continue;
            }
            for (StockData row : attempt.getData()) {
                if (row.getDate() == null || row.getDate().isBlank()) {
                    continue;
                }
                byDate.putIfAbsent(normalizeHistoryDate(row.getDate()), row);
            }
        }
        return new ArrayList<>(byDate.values());
    }

    private String normalizeHistoryDate(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.matches("\\d{4}/\\d{1,2}/\\d{1,2}")) {
            String[] parts = trimmed.split("/");
            return "%s-%02d-%02d".formatted(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
        return trimmed;
    }

    private String historyProviderSummary(List<StockHistoryAttempt> attempts) {
        List<String> providers = new ArrayList<>();
        for (StockHistoryAttempt attempt : attempts) {
            if ("success".equals(attempt.getStatus())) {
                providers.add(attempt.getProviderName() + "(" + attempt.getRowCount() + ")");
            }
        }
        return providers.isEmpty() ? "" : "merged:" + String.join(",", providers);
    }

    private String historyFallbackSummary(List<StockHistoryAttempt> attempts) {
        List<String> notes = new ArrayList<>();
        for (StockHistoryAttempt attempt : attempts) {
            if ("success".equals(attempt.getStatus())) {
                continue;
            }
            notes.add(attempt.getProviderName() + " " + attempt.getStatus() + ": " + attempt.getMessage());
        }
        return String.join("; ", notes);
    }

    private StockDataClient findClient(String provider) {
        StockDataClient created = createClient(provider);
        if (created == null) {
            return null;
        }
        for (StockDataClient client : clients) {
            if (client.getProviderName().equals(created.getProviderName())) {
                return client;
            }
        }
        return created;
    }

    private BrokerAccountClient firstBrokerClient() {
        BrokerAccountClient fallback = null;
        for (StockDataClient client : clients) {
            if (client instanceof BrokerAccountClient brokerClient) {
                if (brokerClient.isBrokerConfigured()) {
                    return brokerClient;
                }
                fallback = brokerClient;
            }
        }
        return fallback;
    }

    private boolean hasData(Object result) {
        if (result == null) {
            return false;
        }
        if (result instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private String missingMessage(String missingKey) {
        return missingKey == null || missingKey.isBlank() ? "缺少設定" : "缺少 " + missingKey;
    }

    private boolean isOfficialDailyProvider(String provider) {
        return "twse".equals(provider) || "tpex".equals(provider);
    }

    private int providerRank(String provider) {
        if (provider.startsWith("broker")) return 1;
        if ("fugle".equals(provider)) return 2;
        if ("web".equals(provider)) return 3;
        if ("twse".equals(provider)) return 4;
        if ("tpex".equals(provider)) return 5;
        if ("finmind".equals(provider)) return 6;
        if ("local".equals(provider)) return 7;
        return 99;
    }

    private String providerGranularity(String provider) {
        if (provider.startsWith("broker")) return "tick/hour";
        if ("fugle".equals(provider)) return "near-realtime";
        if ("web".equals(provider)) return "near-realtime";
        if ("twse".equals(provider)) return "daily";
        if ("tpex".equals(provider)) return "daily";
        if ("finmind".equals(provider)) return "daily";
        if ("local".equals(provider)) return "local";
        return "";
    }

    private int historyRank(String provider) {
        if ("twse".equals(provider)) return 1;
        if ("tpex".equals(provider)) return 2;
        if ("web".equals(provider)) return 3;
        if ("finmind".equals(provider)) return 4;
        if ("local".equals(provider)) return 5;
        return providerRank(provider);
    }

    private String historyGranularity(String provider) {
        if ("local".equals(provider)) return "local";
        return "daily";
    }

    private int intradayRank(String provider) {
        if (provider.startsWith("broker")) return 1;
        if ("web".equals(provider)) return 2;
        if ("fugle".equals(provider)) return 3;
        if ("twse".equals(provider)) return 4;
        if ("tpex".equals(provider)) return 5;
        if ("finmind".equals(provider)) return 6;
        if ("local".equals(provider)) return 7;
        return 99;
    }

    private String intradayGranularity(String provider, String interval) {
        if (provider.startsWith("broker")) return interval == null || interval.isBlank() ? "intraday" : interval;
        if ("web".equals(provider)) return interval == null || interval.isBlank() ? "1m" : interval;
        if (isOfficialDailyProvider(provider)) return "daily";
        return providerGranularity(provider);
    }

    private void notifyProgress(Consumer<String> progressCallback, String message) {
        if (progressCallback != null) {
            progressCallback.accept(message);
        }
    }

    private String cleanErrorMessage(RuntimeException ex) {
        String message = ex == null ? "" : ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex == null ? "unknown error" : ex.getClass().getSimpleName();
        }
        String cleaned = message
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#x27;", "'")
                .replaceAll("&#39;", "'")
                .replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > 180 ? cleaned.substring(0, 180) + "..." : cleaned;
    }

    private static List<StockDataClient> defaultClients() {
        String chain = EnvironmentConfig.first(DEFAULT_PROVIDER_CHAIN, "STOCK_PROVIDER_CHAIN", "STOCKBUCKS_STOCK_PROVIDER_CHAIN");
        List<StockDataClient> result = new ArrayList<>();
        for (String provider : chain.split(",")) {
            StockDataClient client = createClient(provider.trim());
            if (client != null) {
                result.add(client);
            }
        }
        if (result.isEmpty()) {
            result.add(new TwseHistoricalDataClient());
            result.add(new TpexStockDataClient());
            result.add(new LocalFallbackStockDataClient());
        }
        return result;
    }

    private static StockDataClient createClient(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        return switch (provider.toLowerCase()) {
            case "broker", "broker-http", "securities" -> new BrokerHttpStockDataClient();
            case "fugle" -> new FugleStockDataClient();
            case "twse" -> new TwseHistoricalDataClient();
            case "tpex", "otc" -> new TpexStockDataClient();
            case "web", "scraper", "google", "msn" -> new WebStockScraperClient();
            case "finmind" -> new FinMindStockDataClient();
            case "local", "csv" -> new LocalFallbackStockDataClient();
            default -> null;
        };
    }

    private interface ClientCall<T> {
        T fetch(StockDataClient client);
    }
}
