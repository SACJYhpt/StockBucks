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
import com.stockbucks.ai.mode.MarketMode;
import com.stockbucks.ai.model.AiContext;

import java.util.Collections;
import java.util.List;

public class AIHub {

    private MarketMode currentMode = MarketMode.HISTORY;

    private final QuestionAssistant questionAssistant;
    private final MarketAnalysisAssistant marketAnalysisAssistant;
    private final TradeSummaryAssistant tradeSummaryAssistant;
    private final HistoricalDataRepository historicalRepo;

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
        this.historicalRepo = new HistoricalDataRepository("jdbc:sqlite:stockbucks_ai.db");
    }

    public AIHub() {
        this("api");
    }

    public void setMarketMode(MarketMode mode) {
        this.currentMode = mode;
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

    public String answerQuestion(User user,
                                 TradingEngine tradingEngine,
                                 List<StockData> historyData,
                                 String stockId,
                                 double currentPrice,
                                 String question) {

        List<StockData> effectiveHistory = prepareHistory(historyData, stockId);
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

        List<StockData> effectiveHistory = prepareHistory(historyData, stockId);
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

        List<StockData> effectiveHistory = prepareHistory(historyData, stockId);
        AiContext context = AiContext.from(
                user, tradingEngine, effectiveHistory, stockId, currentPrice, getModeDescription()
        );
        return tradeSummaryAssistant.summarize(context);
    }

    private List<StockData> prepareHistory(List<StockData> historyData, String stockId) {
        if (historyData != null && !historyData.isEmpty()) {
            return historyData;
        }

        List<StockData> cached = historicalRepo.findByStockId(stockId);
        return cached == null ? Collections.emptyList() : cached;
    }

    private String getModeDescription() {
        return switch (currentMode) {
            case REALTIME -> "即時股票模式";
            case HISTORY -> "歷年股票資料模式";
            case AI_RANDOM -> "AI 全隨機股市模式";
        };
    }
}