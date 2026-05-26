package com.stockbucks.ai;

import com.stockbucks.StockData;
import com.stockbucks.TradingEngine;
import com.stockbucks.User;
import com.stockbucks.ai.assistant.MarketAnalysisAssistant;
import com.stockbucks.ai.assistant.QuestionAssistant;
import com.stockbucks.ai.assistant.TradeSummaryAssistant;
import com.stockbucks.ai.client.ApiModelClient;
import com.stockbucks.ai.client.LocalModelClient;
import com.stockbucks.ai.client.ModelClient;
import com.stockbucks.ai.data.HistoricalDataRepository;
import com.stockbucks.ai.data.MarketDataService;
import com.stockbucks.ai.data.MarketDataUpdateResult;
import com.stockbucks.ai.mode.HistoryMode;
import com.stockbucks.ai.mode.MarketMode;
import com.stockbucks.ai.mode.RandomAiMode;
import com.stockbucks.ai.mode.RealtimeMode;
import com.stockbucks.ai.model.AiContext;

import java.io.File;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

public class AIHub {

    public static final String MARKET_DATABASE_PATH = "src/main/java/com/stockbucks/ai/database/stockbucks_market_data.db";
    public static final String MARKET_DATABASE_JDBC_URL = "jdbc:sqlite:" + MARKET_DATABASE_PATH;

    private MarketMode currentMode = MarketMode.HISTORY;

    private final QuestionAssistant questionAssistant;
    private final MarketAnalysisAssistant marketAnalysisAssistant;
    private final TradeSummaryAssistant tradeSummaryAssistant;
    private final HistoricalDataRepository historicalRepo;
    private final MarketDataService marketDataService;
    private final HistoryMode historyMode;
    private final RandomAiMode randomAiMode;
    private final RealtimeMode realtimeMode;

    public AIHub(String clientType) {
        ModelClient modelClient;
        if ("local".equalsIgnoreCase(clientType)) {
            modelClient = new LocalModelClient();
        } else {
            modelClient = new ApiModelClient();
        }

        this.questionAssistant = new QuestionAssistant(modelClient);
        this.marketAnalysisAssistant = new MarketAnalysisAssistant(modelClient);
        this.tradeSummaryAssistant = new TradeSummaryAssistant(modelClient);
        ensureMarketDatabaseDirectory();
        this.historicalRepo = new HistoricalDataRepository(MARKET_DATABASE_JDBC_URL);
        this.marketDataService = new MarketDataService(historicalRepo);
        this.historyMode = new HistoryMode(marketDataService);
        this.randomAiMode = new RandomAiMode(marketDataService);
        this.realtimeMode = new RealtimeMode(marketDataService);
    }

    public AIHub() {
        this("api");
    }

    public String getMarketDatabasePath() {
        return MARKET_DATABASE_PATH;
    }

    public void setMarketMode(MarketMode mode) {
        this.currentMode = mode == null ? MarketMode.HISTORY : mode;
    }

    public MarketMode getMarketMode() {
        return currentMode;
    }

    public void cacheHistoryData(List<StockData> historyData) {
        historicalRepo.saveAll(historyData);
    }

    public List<StockData> loadCachedHistory(String stockId) {
        return historicalRepo.findByStockId(stockId);
    }

    public MarketDataUpdateResult updateHistoricalData(String stockId) {
        return marketDataService.updateHistoricalData(stockId);
    }

    public MarketDataUpdateResult updateHistoricalData(String stockId, LocalDate fromDate, LocalDate toDate) {
        return marketDataService.updateHistoricalData(stockId, fromDate, toDate);
    }

    public MarketDataUpdateResult updateAllListedStocks(LocalDate fromDate, LocalDate toDate, int maxStocks) {
        return marketDataService.updateAllListedStocks(fromDate, toDate, maxStocks);
    }

    public MarketDataUpdateResult updateAllListedStocks(LocalDate fromDate,
                                                        LocalDate toDate,
                                                        int maxStocks,
                                                        Consumer<String> progressCallback) {
        return marketDataService.updateAllListedStocks(fromDate, toDate, maxStocks, progressCallback);
    }

    public MarketDataUpdateResult backfillAllListedStocksYearByYear(int startYear,
                                                                    int endYear,
                                                                    int maxStocks,
                                                                    Consumer<String> progressCallback) {
        return marketDataService.backfillAllListedStocksYearByYear(startYear, endYear, maxStocks, progressCallback);
    }

    public List<StockData> loadSimulationHistory(String stockId, List<StockData> fallbackHistory) {
        try {
            return switch (currentMode) {
                case REALTIME -> {
                    marketDataService.updateHistoricalData(stockId);
                    yield marketDataService.getHistoricalData(stockId);
                }
                case HISTORY -> marketDataService.loadOrUpdateHistory(stockId);
                case AI_RANDOM -> randomAiMode.loadRandomWindow(stockId, 60);
            };
        } catch (RuntimeException ex) {
            List<StockData> cached = historicalRepo.findByStockId(stockId);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
            return fallbackHistory == null ? Collections.emptyList() : fallbackHistory;
        }
    }

    public String answerQuestion(User user,
                                 TradingEngine tradingEngine,
                                 List<StockData> historyData,
                                 String stockId,
                                 double currentPrice,
                                 String question) {

        List<StockData> effectiveHistory = prepareHistory(historyData, stockId, currentPrice);
        AiContext context = AiContext.from(
                user, tradingEngine, effectiveHistory, stockId, currentPrice, getModeDescription()
        );
        return questionAssistant.answer(context, question);
    }

    public String analyzeCurrentMarket(User user,
                                       TradingEngine tradingEngine,
                                       List<StockData> historyData,
                                       String stockId,
                                       double currentPrice) {

        List<StockData> effectiveHistory = prepareHistory(historyData, stockId, currentPrice);
        AiContext context = AiContext.from(
                user, tradingEngine, effectiveHistory, stockId, currentPrice, getModeDescription()
        );
        return marketAnalysisAssistant.analyze(context);
    }

    public String summarizeTrades(User user,
                                  TradingEngine tradingEngine,
                                  List<StockData> historyData,
                                  String stockId,
                                  double currentPrice) {

        List<StockData> effectiveHistory = prepareHistory(historyData, stockId, currentPrice);
        AiContext context = AiContext.from(
                user, tradingEngine, effectiveHistory, stockId, currentPrice, getModeDescription()
        );
        return tradeSummaryAssistant.summarize(context);
    }

    private List<StockData> prepareHistory(List<StockData> historyData, String stockId, double currentPrice) {
        try {
            return switch (currentMode) {
                case AI_RANDOM -> randomAiMode.loadRandomWindow(stockId, 20);
                case REALTIME -> {
                    realtimeMode.getSnapshot(stockId, currentPrice);
                    yield marketDataService.getHistoricalData(stockId);
                }
                case HISTORY -> {
                    if (historyData != null && !historyData.isEmpty()) {
                        historyMode.saveHistory(historyData);
                        yield historyData;
                    }
                    yield marketDataService.loadOrUpdateHistory(stockId);
                }
            };
        } catch (RuntimeException ex) {
            List<StockData> cached = historicalRepo.findByStockId(stockId);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
            return historyData == null ? Collections.emptyList() : historyData;
        }
    }

    private String getModeDescription() {
        return switch (currentMode) {
            case REALTIME -> "即時模式：從 TWSE 增量更新資料後進行長線模擬";
            case HISTORY -> "歷史模式：使用 SQLite 歷史資料進行高速模擬";
            case AI_RANDOM -> "AI 隨機模式：從 SQLite 歷史資料抽樣並生成市場情境";
        };
    }

    private void ensureMarketDatabaseDirectory() {
        File dbFile = new File(MARKET_DATABASE_PATH);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
    }
}
