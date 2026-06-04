package com.stockbucks.api.stock;

import com.stockbucks.StockData;
import com.stockbucks.api.config.EnvironmentConfig;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 股票資料的集中服務。
 *
 * 這裡負責隱藏資料來源細節。外部只要要求報價、歷史資料、券商帳戶資料，
 * 本服務會依照設定的順序逐一嘗試，直到某個來源成功回傳可用資料。
 */
public class MarketDataService {
    private static final LocalDate DEFAULT_START_DATE = LocalDate.of(2010, 1, 4); // TWSE 長期歷史資料的預設起始日。
    private static final String DEFAULT_PROVIDER_CHAIN = "broker,fugle,web,twse,finmind,local"; // 依最小時間單位排序：券商日內資料、Fugle 盤中報價、網頁近即時、日資料、本地備援。

    private final List<StockDataClient> clients; // fallback 引擎會依序嘗試這些資料來源。
    private String lastProviderUsed = ""; // 最近一次成功回傳資料的來源。
    private String lastFallbackReason = ""; // 前面資料來源被跳過或失敗的原因。

    public MarketDataService() {
        this(defaultClients());
    }

    public MarketDataService(TwseHistoricalDataClient twseClient) {
        this(List.of(twseClient));
    }

    public MarketDataService(List<StockDataClient> clients) {
        this.clients = clients == null || clients.isEmpty() ? defaultClients() : List.copyOf(clients);
    }

    // ---------------------------------------------------------------------
    // Provider status
    // ---------------------------------------------------------------------

    public Map<String, String> providerStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        for (StockDataClient client : clients) {
            String missingKey = client.getMissingApiKeyName();
            status.put(client.getProviderName(), missingKey.isBlank() ? "ready" : "missing " + missingKey); // UI 可以直接顯示這段狀態。
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

    // ---------------------------------------------------------------------
    // Quote and market data
    // ---------------------------------------------------------------------

    public StockQuote fetchQuote(String stockId) {
        return firstSuccessful(client -> client.fetchQuote(stockId)); // 即時報價優先走券商，再走公開資料備援。
    }

    public List<StockData> fetchDailyHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        List<StockData> data = firstSuccessful(client -> client.fetchDailyHistory(stockId, fromDate, toDate));
        return data == null ? List.of() : data;
    }

    public List<StockData> fetchDailyMarketAll() {
        List<StockData> data = firstSuccessful(StockDataClient::fetchDailyMarketAll);
        return data == null ? List.of() : data;
    }

    public List<StockProfile> getStockProfiles() {
        List<StockProfile> profiles = firstSuccessful(StockDataClient::fetchListedStockProfiles);
        return profiles == null ? List.of() : profiles;
    }

    public List<StockData> getHistoricalData(String stockId) {
        return updateHistoricalData(stockId).getData();
    }

    // ---------------------------------------------------------------------
    // Broker account data
    // ---------------------------------------------------------------------

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
        return broker == null ? null : broker.fetchAccountSnapshot(); // 只查詢帳戶，不在這裡做下單。
    }

    public List<BrokerPosition> fetchBrokerPositions() {
        BrokerAccountClient broker = firstBrokerClient();
        return broker == null ? List.of() : broker.fetchPositions();
    }

    public List<IntradayBar> fetchBrokerIntradayBars(String stockId, String interval) {
        BrokerAccountClient broker = firstBrokerClient();
        return broker == null ? List.of() : broker.fetchIntradayBars(stockId, interval);
    }

    // ---------------------------------------------------------------------
    // Backward-compatible update methods used by older code
    // ---------------------------------------------------------------------

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
                "Fetched listed stock history with fallback chain.");
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

    // ---------------------------------------------------------------------
    // Raw fetch helpers for troubleshooting provider responses
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Fallback engine
    // ---------------------------------------------------------------------

    private <T> T firstSuccessful(ClientCall<T> call) {
        List<String> failures = new ArrayList<>();
        for (StockDataClient client : clients) {
            if (!client.isConfigured()) {
                failures.add(client.getProviderName() + " missing " + client.getMissingApiKeyName()); // 缺金鑰不應中斷流程，繼續找下一個來源。
                continue;
            }

            try {
                T result = call.fetch(client);
                if (hasData(result)) {
                    lastProviderUsed = client.getProviderName(); // 記住成功來源，方便 UI 或除錯顯示。
                    lastFallbackReason = String.join("; ", failures); // 保留前面失敗原因，方便追蹤 fallback。
                    return result;
                }
                failures.add(client.getProviderName() + " returned no data");
            } catch (RuntimeException ex) {
                failures.add(client.getProviderName() + " failed: " + ex.getMessage());
            }
        }

        lastProviderUsed = "";
        lastFallbackReason = String.join("; ", failures);
        return null;
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
