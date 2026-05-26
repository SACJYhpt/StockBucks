package com.stockbucks.ai.mode;

import com.stockbucks.StockData;
import com.stockbucks.ai.data.MarketDataService;
import com.stockbucks.ai.model.MarketSnapshot;

import java.util.List;
import java.util.Random;

public class RandomAiMode {

    private static final int DEFAULT_RANDOM_WINDOW_SIZE = 20;

    private final MarketDataService marketDataService;
    private final Random random = new Random();

    public RandomAiMode(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    public MarketSnapshot getSnapshot(String stockId, double currentPrice) {
        List<StockData> randomWindow = loadRandomWindow(stockId, DEFAULT_RANDOM_WINDOW_SIZE);
        if (randomWindow.isEmpty()) {
            return new MarketSnapshot(
                    stockId,
                    "AI_RANDOM",
                    "AI 隨機歷史模式",
                    currentPrice,
                    "目前尚無可用的市場快取資料。"
            );
        }

        StockData picked = randomWindow.get(random.nextInt(randomWindow.size()));
        return new MarketSnapshot(
                stockId,
                picked.getDate(),
                "AI 隨機歷史模式",
                picked.getClose(),
                buildScenarioDescription(randomWindow, picked)
        );
    }

    public List<StockData> loadRandomWindow(String stockId, int windowSize) {
        try {
            marketDataService.updateHistoricalData(stockId);
        } catch (RuntimeException ignored) {
            // Random mode can still work from the local cache when the network is unavailable.
        }

        List<StockData> randomWindow = marketDataService.getRandomHistoricalWindow(stockId, windowSize);
        if (!randomWindow.isEmpty()) {
            return randomWindow;
        }

        marketDataService.updateHistoricalData(stockId);
        return marketDataService.getRandomHistoricalWindow(stockId, windowSize);
    }

    private String buildScenarioDescription(List<StockData> randomWindow, StockData picked) {
        double intradayRange = picked.getHigh() - picked.getLow();
        double direction = picked.getClose() - picked.getOpen();
        String directionLabel;

        if (direction > 0) {
            directionLabel = "上漲日";
        } else if (direction < 0) {
            directionLabel = "下跌日";
        } else {
            directionLabel = "平盤日";
        }

        return "隨機區間筆數：" + randomWindow.size()
                + "，抽樣日期：" + picked.getDate()
                + "，情境：" + directionLabel
                + "，日內高低差：" + String.format("%.2f", intradayRange);
    }
}
