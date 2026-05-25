package com.stockbucks.ai.mode;

import com.stockbucks.StockData;
import com.stockbucks.ai.data.HistoricalDataRepository;
import com.stockbucks.ai.model.MarketSnapshot;

import java.util.List;

public class HistoryMode {

    private final HistoricalDataRepository repository;

    public HistoryMode(HistoricalDataRepository repository) {
        this.repository = repository;
    }

    public MarketSnapshot getSnapshot(String stockId, List<StockData> historyData, double currentPrice) {
        String latestDate = "N/A";
        if (historyData != null && !historyData.isEmpty()) {
            latestDate = historyData.get(historyData.size() - 1).getDate();
        }

        return new MarketSnapshot(
                stockId,
                latestDate,
                "歷史資料模式",
                currentPrice,
                "歷史資料模式：可使用 CSV 或 SQLite 中的歷史資料"
        );
    }

    public void saveHistory(List<StockData> historyData) {
        repository.saveAll(historyData);
    }

    public List<StockData> loadHistory(String stockId) {
        return repository.findByStockId(stockId);
    }
}