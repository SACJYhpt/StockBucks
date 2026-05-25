package com.stockbucks.ai.data;

import com.stockbucks.StockData;

import java.util.List;

public class MarketDataService {

    private final HistoricalDataRepository repository;

    public MarketDataService(HistoricalDataRepository repository) {
        this.repository = repository;
    }

    public void saveHistoricalData(List<StockData> historyData) {
        repository.saveAll(historyData);
    }

    public List<StockData> getHistoricalData(String stockId) {
        return repository.findByStockId(stockId);
    }
}