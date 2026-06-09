package com.stockbucks.api.stock;

import com.stockbucks.StockData;
import com.stockbucks.api.config.EnvironmentConfig;

import java.time.DayOfWeek;
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
    private final StockDataCache cache;
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
        this.cache = new StockDataCache();
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
        StockQuote cached = cache.getQuote(stockId).orElse(null);
        if (cached != null) {
            lastProviderUsed = cached.getProvider() + ":cache";
            lastFallbackReason = "使用 API 股票快取，加速報價讀取。";
            return cached;
        }
        StockQuote quote = firstSuccessful(clients, client -> client.fetchQuote(stockId));
        cache.putQuote(quote);
        return quote;
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
        LocalDate adjustedFromDate = nextPotentialTradingDay(fromDate);
        LocalDate adjustedToDate = previousPotentialTradingDay(toDate);
        if (adjustedFromDate == null || adjustedToDate == null || adjustedFromDate.isAfter(adjustedToDate)) {
            lastProviderUsed = "";
            lastFallbackReason = describeHistoryDateAdjustment(fromDate, toDate);
            return List.of();
        }

        List<StockData> cached = cache.getHistory(stockId, adjustedFromDate, adjustedToDate).orElse(List.of());
        if (historyCacheCoversRange(cached, adjustedFromDate, adjustedToDate)) {
            lastProviderUsed = "cache(" + cached.size() + ")";
            lastFallbackReason = describeHistoryDateAdjustment(fromDate, toDate) + " 使用完整 API 歷史資料快取。";
            return cached;
        }

        List<StockHistoryAttempt> attempts;
        if (cached.isEmpty()) {
            attempts = fetchDailyHistoryAttempts(stockId, fromDate, toDate);
        } else {
            attempts = fetchMissingHistoryAttempts(stockId, adjustedFromDate, adjustedToDate, cached);
        }
        List<StockData> merged = mergeHistoryRows(cached, mergeHistoryAttempts(attempts));
        lastProviderUsed = historyProviderSummary(attempts);
        lastFallbackReason = describeHistoryDateAdjustment(fromDate, toDate)
                + (cached.isEmpty() ? "" : " 已先使用本地快取 " + cached.size() + " 筆，只補抓缺少區間。")
                + historyFallbackSummary(attempts);
        cache.putHistory(stockId, adjustedFromDate, adjustedToDate, merged);
        return merged;
    }

    private List<StockHistoryAttempt> fetchMissingHistoryAttempts(String stockId,
                                                                  LocalDate fromDate,
                                                                  LocalDate toDate,
                                                                  List<StockData> cached) {
        LocalDate firstCachedDate = firstHistoryDate(cached);
        LocalDate lastCachedDate = lastHistoryDate(cached);
        List<StockHistoryAttempt> attempts = new ArrayList<>();

        if (firstCachedDate != null && firstCachedDate.isAfter(fromDate)) {
            LocalDate missingEnd = previousPotentialTradingDay(firstCachedDate.minusDays(1));
            if (missingEnd != null && !missingEnd.isBefore(fromDate)) {
                attempts.addAll(fetchDailyHistoryAttempts(stockId, fromDate, missingEnd));
            }
        }

        if (lastCachedDate != null && lastCachedDate.isBefore(toDate)) {
            LocalDate missingStart = nextPotentialTradingDay(lastCachedDate.plusDays(1));
            if (missingStart != null && !missingStart.isAfter(toDate)) {
                attempts.addAll(fetchDailyHistoryAttempts(stockId, missingStart, toDate));
            }
        }

        if (attempts.isEmpty()) {
            attempts.add(new StockHistoryAttempt(stockId, "cache", true, "success",
                    "本地快取已覆蓋主要區間，沒有額外補抓", cached, "daily", Integer.MAX_VALUE));
        }
        return attempts;
    }

    public List<StockHistoryAttempt> fetchDailyHistoryAttempts(String stockId, LocalDate fromDate, LocalDate toDate) {
        List<StockHistoryAttempt> attempts = new ArrayList<>();
        LocalDate adjustedFromDate = nextPotentialTradingDay(fromDate);
        LocalDate adjustedToDate = previousPotentialTradingDay(toDate);
        if (adjustedFromDate == null || adjustedToDate == null || adjustedFromDate.isAfter(adjustedToDate)) {
            return List.of(new StockHistoryAttempt(stockId, "calendar", true, "no data",
                    "查詢區間沒有可用交易日，已自動跳過週末或休市日",
                    List.of(), "daily", Integer.MAX_VALUE));
        }
        for (StockDataClient client : historyClients()) {
            String provider = client.getProviderName();
            if (!client.isConfigured()) {
                attempts.add(new StockHistoryAttempt(stockId, provider, false, "missing",
                        missingMessage(client.getMissingApiKeyName()), List.of(), historyGranularity(provider), historyRank(provider)));
                continue;
            }

            try {
                List<StockData> data = client.fetchDailyHistory(stockId, adjustedFromDate, adjustedToDate);
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

    public String describeHistoryDateAdjustment(LocalDate fromDate, LocalDate toDate) {
        LocalDate adjustedFromDate = nextPotentialTradingDay(fromDate);
        LocalDate adjustedToDate = previousPotentialTradingDay(toDate);
        if (adjustedFromDate == null || adjustedToDate == null || adjustedFromDate.isAfter(adjustedToDate)) {
            return "查詢區間沒有可用交易日，已跳過週末或休市日。";
        }

        List<String> notes = new ArrayList<>();
        if (!adjustedFromDate.equals(fromDate)) {
            notes.add("起始日 " + fromDate + " 非交易日，已改查 " + adjustedFromDate);
        }
        if (!adjustedToDate.equals(toDate)) {
            notes.add("結束日 " + toDate + " 非交易日，已改查 " + adjustedToDate);
        }
        return notes.isEmpty()
                ? "查詢區間未跳過日期。"
                : "已自動跳過非交易日：" + String.join("；", notes) + "。";
    }

    public LocalDate resolveAvailableHistoryDate(String stockId, LocalDate targetDate) {
        if (stockId == null || stockId.isBlank() || targetDate == null) {
            return null;
        }

        LocalDate searchEnd = previousPotentialTradingDay(targetDate);
        if (searchEnd == null) {
            return null;
        }
        LocalDate searchStart = searchEnd.minusDays(21);
        List<StockData> history = fetchDailyHistory(stockId, searchStart, searchEnd);
        LocalDate best = null;
        for (StockData data : history) {
            LocalDate date = parseHistoryDate(data.getDate());
            if (date == null || date.isAfter(targetDate)) {
                continue;
            }
            if (best == null || date.isAfter(best)) {
                best = date;
            }
        }
        return best;
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
        List<IntradayBar> cached = cache.getIntraday(stockId, interval).orElse(null);
        if (cached != null) {
            lastProviderUsed = "cache";
            lastFallbackReason = "使用 API 盤中 K 線快取，加速圖表讀取。";
            return cached;
        }
        List<String> failures = new ArrayList<>();
        for (StockIntradayAttempt attempt : fetchIntradayBarAttempts(stockId, interval)) {
            if ("success".equals(attempt.getStatus()) && !attempt.getBars().isEmpty()) {
                lastProviderUsed = attempt.getProviderName();
                lastFallbackReason = String.join("; ", failures);
                cache.putIntraday(stockId, interval, attempt.getBars());
                return attempt.getBars();
            }
            failures.add(attempt.getProviderName() + " " + attempt.getStatus() + ": " + attempt.getMessage());
        }
        lastProviderUsed = "";
        lastFallbackReason = failures.isEmpty() ? "沒有來源回傳盤中 K 線" : String.join("; ", failures);
        return List.of();
    }

    public String getCacheStatus() {
        return cache.status();
    }

    public String getCacheDirectory() {
        return cache.cacheDir().toString();
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

    private List<StockData> mergeHistoryRows(List<StockData> firstRows, List<StockData> secondRows) {
        Map<String, StockData> byDate = new TreeMap<>();
        for (StockData row : firstRows) {
            if (row.getDate() != null && !row.getDate().isBlank()) {
                byDate.put(normalizeHistoryDate(row.getDate()), row);
            }
        }
        for (StockData row : secondRows) {
            if (row.getDate() != null && !row.getDate().isBlank()) {
                byDate.put(normalizeHistoryDate(row.getDate()), row);
            }
        }
        return new ArrayList<>(byDate.values());
    }

    private boolean historyCacheCoversRange(List<StockData> cached, LocalDate fromDate, LocalDate toDate) {
        LocalDate first = firstHistoryDate(cached);
        LocalDate last = lastHistoryDate(cached);
        return first != null && last != null && !first.isAfter(fromDate) && !last.isBefore(toDate);
    }

    private LocalDate firstHistoryDate(List<StockData> rows) {
        LocalDate first = null;
        for (StockData row : rows) {
            LocalDate date = parseHistoryDate(row.getDate());
            if (date != null && (first == null || date.isBefore(first))) {
                first = date;
            }
        }
        return first;
    }

    private LocalDate lastHistoryDate(List<StockData> rows) {
        LocalDate last = null;
        for (StockData row : rows) {
            LocalDate date = parseHistoryDate(row.getDate());
            if (date != null && (last == null || date.isAfter(last))) {
                last = date;
            }
        }
        return last;
    }

    private String normalizeHistoryDate(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.matches("\\d{4}/\\d{1,2}/\\d{1,2}")) {
            String[] parts = trimmed.split("/");
            return "%s-%02d-%02d".formatted(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
        return trimmed;
    }

    public boolean isPotentialTradingDay(LocalDate date) {
        if (date == null) {
            return false;
        }
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    private LocalDate nextPotentialTradingDay(LocalDate date) {
        if (date == null) {
            return null;
        }
        LocalDate cursor = date;
        while (!isPotentialTradingDay(cursor)) {
            cursor = cursor.plusDays(1);
        }
        return cursor;
    }

    private LocalDate previousPotentialTradingDay(LocalDate date) {
        if (date == null) {
            return null;
        }
        LocalDate cursor = date;
        while (!isPotentialTradingDay(cursor)) {
            cursor = cursor.minusDays(1);
        }
        return cursor;
    }

    private LocalDate parseHistoryDate(String value) {
        String normalized = normalizeHistoryDate(value);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(normalized);
        } catch (RuntimeException ex) {
            return null;
        }
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
