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
 * 股票資料統一入口。
 * 即時報價重視資料時間粒度，歷史資料重視官方完整度，因此兩者使用不同 fallback 順序。
 */
public class MarketDataService {
    private static final LocalDate DEFAULT_START_DATE = LocalDate.of(2010, 1, 4); // TWSE 可回補較完整日資料。
    private static final String DEFAULT_PROVIDER_CHAIN = "broker,fugle,web,twse,finmind,local"; // 即時：券商/Fugle/web 優先，TWSE 只是官方備援。
    private static final String DEFAULT_HISTORY_PROVIDER_CHAIN = "twse,web,finmind,local"; // 歷史：TWSE 打底，web/FinMind/local 補缺日期。

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
                    endpoints.put(client.getProviderName() + "." + name, endpoint)
            );
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
            if (!client.isConfigured()) {
                String missing = client.getMissingApiKeyName();
                attempts.add(new StockQuoteAttempt(
                        stockId,
                        client.getProviderName(),
                        false,
                        "missing",
                        missing == null || missing.isBlank() ? "缺少必要設定" : "缺少 " + missing,
                        null
                ));
                continue;
            }

            try {
                StockQuote quote = client.fetchQuote(stockId);
                attempts.add(quote == null
                        ? new StockQuoteAttempt(stockId, client.getProviderName(), true, "no data", "此來源沒有回傳可用報價", null)
                        : new StockQuoteAttempt(stockId, client.getProviderName(), true, "success", "成功取得資料", quote));
            } catch (RuntimeException ex) {
                attempts.add(new StockQuoteAttempt(
                        stockId,
                        client.getProviderName(),
                        true,
                        "failed",
                        cleanErrorMessage(ex),
                        null
                ));
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
            if (!client.isConfigured()) {
                String missing = client.getMissingApiKeyName();
                attempts.add(new StockHistoryAttempt(
                        stockId,
                        client.getProviderName(),
                        false,
                        "missing",
                        missing == null || missing.isBlank() ? "缺少必要設定" : "缺少 " + missing,
                        List.of()
                ));
                continue;
            }

            try {
                List<StockData> data = client.fetchDailyHistory(stockId, fromDate, toDate);
                attempts.add(data == null || data.isEmpty()
                        ? new StockHistoryAttempt(stockId, client.getProviderName(), true, "no data", "此來源沒有回傳可用歷史資料", List.of())
                        : new StockHistoryAttempt(stockId, client.getProviderName(), true, "success", "成功取得歷史資料", data));
            } catch (RuntimeException ex) {
                attempts.add(new StockHistoryAttempt(
                        stockId,
                        client.getProviderName(),
                        true,
                        "failed",
                        cleanErrorMessage(ex),
                        List.of()
                ));
            }
        }
        return attempts;
    }

    public List<StockData> fetchDailyMarketAll() {
        List<StockData> data = firstSuccessful(historyClients(), StockDataClient::fetchDailyMarketAll);
        return data == null ? List.of() : data;
    }

    public List<StockProfile> getStockProfiles() {
        List<StockProfile> profiles = firstSuccessful(historyClients(), StockDataClient::fetchListedStockProfiles);
        return profiles == null ? List.of() : profiles;
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
        String chain = EnvironmentConfig.first(
                DEFAULT_HISTORY_PROVIDER_CHAIN,
                "STOCK_HISTORY_PROVIDER_CHAIN",
                "STOCKBUCKS_STOCK_HISTORY_PROVIDER_CHAIN"
        );
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
        if (provider == null || provider.isBlank()) {
            return null;
        }
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
