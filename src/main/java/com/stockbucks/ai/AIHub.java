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
import com.stockbucks.ai.data.SQLiteManager;
import com.stockbucks.ai.mode.HistoryMode;
import com.stockbucks.ai.mode.MarketMode;
import com.stockbucks.ai.mode.RandomAiMode;
import com.stockbucks.ai.mode.RealtimeMode;
import com.stockbucks.ai.model.AiContext;
import com.stockbucks.ai.model.MarketSnapshot;

import java.util.List;

public class AIHub {

    private MarketMode currentMode;

    private final RealtimeMode realtimeMode;
    private final HistoryMode historyMode;
    private final RandomAiMode randomAiMode;

    private final QuestionAssistant questionAssistant;
    private final MarketAnalysisAssistant marketAnalysisAssistant;
    private final TradeSummaryAssistant tradeSummaryAssistant;

    private final HistoricalDataRepository historicalDataRepository;
    private final MarketDataService marketDataService;

    public AIHub() {
        this("local");
    }

    public AIHub(String modelType) {
        ModelClient modelClient = createModelClient(modelType);

        SQLiteManager sqliteManager = new SQLiteManager("jdbc:sqlite:stockbucks_ai.db");
        this.historicalDataRepository = new HistoricalDataRepository(sqliteManager);
        this.marketDataService = new MarketDataService(historicalDataRepository);

        this.realtimeMode = new RealtimeMode(marketDataService);
        this.historyMode = new HistoryMode(historicalDataRepository);
        this.randomAiMode = new RandomAiMode();

        this.questionAssistant = new QuestionAssistant(modelClient);
        this.marketAnalysisAssistant = new MarketAnalysisAssistant(modelClient);
        this.tradeSummaryAssistant = new TradeSummaryAssistant(modelClient);

        this.currentMode = MarketMode.HISTORY;
    }

    private ModelClient createModelClient(String modelType) {
        if ("api".equalsIgnoreCase(modelType)) {
            return new ApiModelClient();
        }
        return new LocalModelClient();
    }

    public void setMarketMode(MarketMode mode) {
        this.currentMode = mode;
    }

    public MarketMode getMarketMode() {
        return currentMode;
    }

    public HistoricalDataRepository getHistoricalDataRepository() {
        return historicalDataRepository;
    }

    public MarketDataService getMarketDataService() {
        return marketDataService;
    }

    public MarketSnapshot getCurrentMarketSnapshot(String stockId,
                                                   List<StockData> historyData,
                                                   double currentPrice) {
        return switch (currentMode) {
            case REALTIME -> realtimeMode.getSnapshot(stockId, currentPrice);
            case HISTORY -> historyMode.getSnapshot(stockId, historyData, currentPrice);
            case AI_RANDOM -> randomAiMode.getSnapshot(stockId, currentPrice);
        };
    }

    public String analyzeCurrentMarket(User user,
                                       TradingEngine tradingEngine,
                                       List<StockData> historyData,
                                       String stockId,
                                       double currentPrice) {

        MarketSnapshot snapshot = getCurrentMarketSnapshot(stockId, historyData, currentPrice);
        AiContext context = AiContext.from(user, tradingEngine, historyData, snapshot);

        return marketAnalysisAssistant.analyze(context);
    }

    public String answerQuestion(User user,
                                 TradingEngine tradingEngine,
                                 List<StockData> historyData,
                                 String stockId,
                                 double currentPrice,
                                 String question) {

        MarketSnapshot snapshot = getCurrentMarketSnapshot(stockId, historyData, currentPrice);
        AiContext context = AiContext.from(user, tradingEngine, historyData, snapshot);

        return questionAssistant.answer(context, question);
    }

    public String summarizeTrades(User user,
                                  TradingEngine tradingEngine,
                                  List<StockData> historyData,
                                  String stockId,
                                  double currentPrice) {

        MarketSnapshot snapshot = getCurrentMarketSnapshot(stockId, historyData, currentPrice);
        AiContext context = AiContext.from(user, tradingEngine, historyData, snapshot);

        return tradeSummaryAssistant.summarize(context);
    }
}