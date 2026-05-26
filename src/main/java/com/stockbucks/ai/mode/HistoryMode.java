package com.stockbucks.ai.mode;

import com.stockbucks.StockData;
import com.stockbucks.ai.data.MarketDataService;
import com.stockbucks.ai.data.MarketDataUpdateResult;
import com.stockbucks.ai.model.MarketSnapshot;

import java.util.List;

public class HistoryMode {

    private final MarketDataService marketDataService;

    public HistoryMode(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    public MarketSnapshot getSnapshot(String stockId, List<StockData> historyData, double currentPrice) {
        List<StockData> effectiveHistory = historyData;
        if (effectiveHistory == null || effectiveHistory.isEmpty()) {
            effectiveHistory = marketDataService.loadOrUpdateHistory(stockId);
        }

        String latestDate = "N/A";
        if (!effectiveHistory.isEmpty()) {
            latestDate = effectiveHistory.get(effectiveHistory.size() - 1).getDate();
        }

        int cachedCount = marketDataService.countHistoricalData(stockId);
        return new MarketSnapshot(
                stockId,
                latestDate,
                "歷史資料模式",
                currentPrice,
                "SQLite 快取筆數：" + cachedCount
        );
    }

    public MarketDataUpdateResult updateHistory(String stockId) {
        return marketDataService.updateHistoricalData(stockId);
    }

    public void saveHistory(List<StockData> historyData) {
        marketDataService.saveHistoricalData(historyData);
    }

    public List<StockData> loadHistory(String stockId) {
        return marketDataService.getHistoricalData(stockId);
    }
}
