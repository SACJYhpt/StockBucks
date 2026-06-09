package com.stockbucks.api;

import com.stockbucks.StockData;
import com.stockbucks.TradingEngine;
import com.stockbucks.User;
import com.stockbucks.api.ai.ApiModelClient;
import com.stockbucks.api.ai.ModelClient;
import com.stockbucks.api.stock.MarketDataService;
import com.stockbucks.api.stock.MarketDataUpdateResult;
import com.stockbucks.api.stock.BrokerAccountSnapshot;
import com.stockbucks.api.stock.BrokerPosition;
import com.stockbucks.api.stock.IntradayBar;
import com.stockbucks.api.stock.StockHistoryAttempt;
import com.stockbucks.api.stock.StockIntradayAttempt;
import com.stockbucks.api.stock.StockQuote;
import com.stockbucks.api.stock.StockQuoteAttempt;
import com.stockbucks.api.stock.StockProfile;
import com.stockbucks.api.mode.MarketMode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI 模組的統一入口。
 *
 * UI 或其他模組只需要呼叫這個類別，不需要直接接觸各種 AI 供應商、
 * 券商 API、TWSE API 或網頁爬蟲。
 */
public class AIHub {
    private static final List<String> AI_PROVIDER_FALLBACK_CHAIN = List.of(
            "openai",
            "anthropic",
            "gemini",
            "openrouter",
            "openai-compatible",
            "ollama"
    ); // AI 失敗時優先改用付費雲端 API，最後才退回本機 Ollama。

    private final ModelClient aiClient; // 處理 AI 文字請求。
    private final MarketDataService stockApi; // 處理券商/API/爬蟲/本地 CSV 股票資料。
    private MarketMode currentMode = MarketMode.HISTORY; // 保留給舊存檔與舊 UI 相容。

    public AIHub() {
        this(new ApiModelClient(), new MarketDataService());
    }

    public AIHub(String clientType) {
        this(new ApiModelClient(clientType), new MarketDataService());
    }

    public AIHub(ModelClient aiClient, MarketDataService stockApi) {
        this.aiClient = aiClient == null ? new ApiModelClient() : aiClient;
        this.stockApi = stockApi == null ? new MarketDataService() : stockApi;
    }

    public String askAi(String prompt) {
        String firstAnswer = aiClient.ask(prompt);
        if (!shouldFallbackAi(firstAnswer) || !(aiClient instanceof ApiModelClient currentClient)) {
            return cleanAiUiText(withAiResponderLabel(resolveAiProviderName(aiClient), firstAnswer));
        }

        StringBuilder attempts = new StringBuilder();
        attempts.append("目前 AI 來源 ")
                .append(currentClient.getProvider())
                .append(" 無法使用：")
                .append(summarizeAiFailure(firstAnswer))
                .append('\n');

        for (String provider : AI_PROVIDER_FALLBACK_CHAIN) {
            if (provider.equals(currentClient.getProvider())) {
                continue;
            }

            ApiModelClient fallbackClient = new ApiModelClient(provider);
            String missingKey = fallbackClient.getMissingApiKeyName();
            if (!missingKey.isBlank()) {
                attempts.append(provider).append("：缺 ").append(missingKey).append('\n');
                continue;
            }

            String fallbackAnswer = fallbackClient.ask(prompt);
            if (!shouldFallbackAi(fallbackAnswer)) {
                return cleanAiUiText("AI 已自動改用 " + provider + " 回覆。\n"
                        + withAiResponderLabel(provider, fallbackAnswer));
            }
            attempts.append(provider)
                    .append("：")
                    .append(summarizeAiFailure(fallbackAnswer))
                    .append('\n');
        }

        return cleanAiUiText("所有可嘗試的 AI 來源都無法完成。\n" + attempts);
    }

    public boolean isAiReady() {
        return aiClient instanceof ApiModelClient client && client.isReady();
    }

    public String getMissingApiKeyName() {
        if (aiClient instanceof ApiModelClient client) {
            return client.getMissingApiKeyName();
        }
        return "";
    }

    public String getAiConfigurationStatus() {
        if (aiClient instanceof ApiModelClient client) {
            return client.getConfigurationStatus();
        }
        return "AI provider: custom";
    }

    public List<String> getAllAiProviderStatusLines() {
        return List.of(
                new ApiModelClient("openai").getShortConfigurationStatus(),
                new ApiModelClient("anthropic").getShortConfigurationStatus(),
                new ApiModelClient("gemini").getShortConfigurationStatus(),
                new ApiModelClient("openrouter").getShortConfigurationStatus(),
                new ApiModelClient("openai-compatible").getShortConfigurationStatus(),
                new ApiModelClient("ollama").getShortConfigurationStatus()
        );
    }

    public List<StockData> fetchStockHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        return stockApi.fetchDailyHistory(stockId, fromDate, toDate);
    }

    public List<StockHistoryAttempt> fetchStockHistoryAttempts(String stockId, LocalDate fromDate, LocalDate toDate) {
        return stockApi.fetchDailyHistoryAttempts(stockId, fromDate, toDate); // 給同學/debug 查每個歷史來源的資料量與狀態。
    }

    public LocalDate resolveAvailableHistoryDate(String stockId, LocalDate targetDate) {
        return stockApi.resolveAvailableHistoryDate(stockId, targetDate); // 選到週末或休市日時，找最近一個有歷史資料的交易日。
    }

    public boolean isPotentialTradingDay(LocalDate date) {
        return stockApi.isPotentialTradingDay(date); // 先排除週末；特殊休市日由 resolveAvailableHistoryDate 依實際資料修正。
    }

    public String describeHistoryDateAdjustment(LocalDate fromDate, LocalDate toDate) {
        return stockApi.describeHistoryDateAdjustment(fromDate, toDate); // 告訴使用者是否有自動跳過非交易日。
    }

    public StockQuote fetchStockQuote(String stockId) {
        return stockApi.fetchQuote(stockId); // 依序嘗試：券商 -> Fugle -> 網頁 -> TWSE -> TPEx -> FinMind -> 本地。
    }

    public List<StockQuoteAttempt> fetchStockQuoteAttempts(String stockId) {
        return stockApi.fetchQuoteAttempts(stockId); // DEBUG 用：列出每個來源的成功、缺 key、無資料或失敗狀態。
    }

    public Map<String, String> getStockProviderStatus() {
        return stockApi.providerStatus();
    }

    public String getLastStockProviderUsed() {
        return stockApi.getLastProviderUsed();
    }

    public String getLastStockFallbackReason() {
        return stockApi.getLastFallbackReason();
    }

    public BrokerAccountSnapshot fetchBrokerAccountSnapshot() {
        return stockApi.fetchBrokerAccountSnapshot();
    }

    public List<BrokerPosition> fetchBrokerPositions() {
        return stockApi.fetchBrokerPositions();
    }

    public List<IntradayBar> fetchBrokerHourlyBars(String stockId) {
        return stockApi.fetchBrokerIntradayBars(stockId, "1h"); // 只從券商端抓小時級資料。
    }

    public List<IntradayBar> fetchBrokerIntradayBars(String stockId, String interval) {
        return stockApi.fetchBrokerIntradayBars(stockId, interval);
    }

    public List<IntradayBar> fetchBestIntradayBars(String stockId, String interval) {
        return stockApi.fetchBestIntradayBars(stockId, interval);
    }

    public List<StockIntradayAttempt> fetchStockIntradayAttempts(String stockId, String interval) {
        return stockApi.fetchIntradayBarAttempts(stockId, interval);
    }

    public String getBrokerStatus() {
        return stockApi.getBrokerStatus();
    }

    public List<StockData> fetchAllStocksDailyMarket() {
        return stockApi.fetchDailyMarketAll();
    }

    public List<StockProfile> fetchAllListedStocks() {
        return stockApi.getStockProfiles();
    }

    public Map<String, String> supportedStockApiEndpoints() {
        return stockApi.supportedApiEndpoints();
    }

    public String fetchRawStockApi(String endpointPath) {
        return stockApi.fetchRaw(endpointPath);
    }

    public String fetchRawStockWebPage(String url) {
        return stockApi.fetchRawWebPage(url);
    }

    public String getMarketDatabasePath() {
        return stockApi.getCacheDirectory(); // API 股票快取資料夾；不是同學的 SaveData 模擬存檔。
    }

    public String getStockCacheStatus() {
        return stockApi.getCacheStatus(); // DEBUG/UI 顯示目前股票快取是否啟用與 TTL。
    }

    public void setMarketMode(MarketMode mode) {
        this.currentMode = mode == null ? MarketMode.HISTORY : mode;
    }

    public MarketMode getMarketMode() {
        return currentMode;
    }

    public void cacheHistoryData(List<StockData> historyData) {
        // The simplified AI layer is API-only and no longer writes a local market database.
    }

    public List<StockData> loadCachedHistory(String stockId) {
        return List.of();
    }

    public MarketDataUpdateResult updateHistoricalData(String stockId) {
        return stockApi.updateHistoricalData(stockId);
    }

    public MarketDataUpdateResult updateHistoricalData(String stockId, LocalDate fromDate, LocalDate toDate) {
        return stockApi.updateHistoricalData(stockId, fromDate, toDate);
    }

    public MarketDataUpdateResult updateAllListedStocks(LocalDate fromDate, LocalDate toDate, int maxStocks) {
        return stockApi.updateAllListedStocks(fromDate, toDate, maxStocks);
    }

    public MarketDataUpdateResult updateAllListedStocks(LocalDate fromDate,
                                                        LocalDate toDate,
                                                        int maxStocks,
                                                        Consumer<String> progressCallback) {
        return stockApi.updateAllListedStocks(fromDate, toDate, maxStocks, progressCallback);
    }

    public MarketDataUpdateResult backfillAllListedStocksYearByYear(int startYear,
                                                                    int endYear,
                                                                    int maxStocks,
                                                                    Consumer<String> progressCallback) {
        return stockApi.backfillAllListedStocksYearByYear(startYear, endYear, maxStocks, progressCallback);
    }

    public List<StockData> loadSimulationHistory(String stockId, List<StockData> fallbackHistory) {
        try {
            List<StockData> fetched = stockApi.getHistoricalData(stockId);
            return fetched.isEmpty() && fallbackHistory != null ? fallbackHistory : fetched;
        } catch (RuntimeException ex) {
            return fallbackHistory == null ? List.of() : fallbackHistory;
        }
    }

    public String answerQuestion(User user,
                                 TradingEngine tradingEngine,
                                 List<StockData> historyData,
                                 String stockId,
                                 double currentPrice,
                                 String question) {
        return askAi(buildPrompt("回答使用者的股票問題。", stockId, currentPrice, historyData, question));
    }

    public String analyzeCurrentMarket(User user,
                                       TradingEngine tradingEngine,
                                       List<StockData> historyData,
                                       String stockId,
                                       double currentPrice) {
        return askAi(buildPrompt("分析目前股票資料。", stockId, currentPrice, historyData, ""));
    }

    public String summarizeTrades(User user,
                                  TradingEngine tradingEngine,
                                  List<StockData> historyData,
                                  String stockId,
                                  double currentPrice) {
        return askAi(buildPrompt("整理使用者近期交易狀況。", stockId, currentPrice, historyData, ""));
    }

    private String buildPrompt(String task,
                               String stockId,
                               double currentPrice,
                               List<StockData> historyData,
                               String userQuestion) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("任務：").append(task).append('\n');
        prompt.append("股票代號：").append(stockId == null ? "" : stockId).append('\n');
        prompt.append("目前價格：").append(currentPrice).append('\n');
        prompt.append("市場模式：").append(currentMode).append('\n');
        prompt.append("回答要求：請使用繁體中文與台灣常見用語；不要使用簡體字；不要用粗體 Markdown；回答要精簡、可讀、直接。\n");
        if (userQuestion != null && !userQuestion.isBlank()) {
            prompt.append("使用者問題：").append(userQuestion).append('\n');
        }

        if (historyData != null && !historyData.isEmpty()) {
            StockData latest = historyData.get(historyData.size() - 1);
            prompt.append("最近一筆歷史資料：日期=").append(latest.getDate())
                    .append(" 開盤=").append(latest.getOpen())
                    .append(" 最高=").append(latest.getHigh())
                    .append(" 最低=").append(latest.getLow())
                    .append(" 收盤=").append(latest.getClose())
                    .append(" 成交量=").append(latest.getVolume())
                    .append('\n');
        }
        return prompt.toString();
    }

    private boolean shouldFallbackAi(String answer) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        return answer.contains("[AI config]")
                || answer.contains("[AI 設定]")
                || answer.contains("[AI API error]")
                || answer.contains("[AI API request failed]")
                || answer.contains("[AI API request interrupted]")
                || answer.contains("insufficient_quota")
                || answer.contains("HTTP 429");
    }

    private String summarizeAiFailure(String answer) {
        if (answer == null || answer.isBlank()) {
            return "空白回覆";
        }
        if (answer.contains("insufficient_quota")) {
            return "額度不足或付款方案未啟用";
        }
        if (answer.contains("HTTP 429")) {
            return "請求過多或額度限制";
        }
        String firstLine = answer.lines().findFirst().orElse(answer).trim();
        return firstLine.length() > 90 ? firstLine.substring(0, 90) + "..." : firstLine;
    }

    private String cleanAiUiText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return text
                .replace("**", "")
                .replace("__", "")
                .replace("###", "")
                .replace("##", "")
                .replace("#", "")
                .replace("`", "")
                .replace("> ", "");
    }

    private String withAiResponderLabel(String provider, String answer) {
        String name = provider == null || provider.isBlank() ? "custom" : provider;
        return "回答 AI：" + name + "\n\n" + (answer == null ? "" : answer);
    }

    private String resolveAiProviderName(ModelClient client) {
        if (client instanceof ApiModelClient apiClient) {
            return apiClient.getProvider();
        }
        return "custom";
    }
}
