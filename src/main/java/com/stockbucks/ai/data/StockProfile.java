package com.stockbucks.ai.data;

public class StockProfile {

    private final String stockId;
    private final String stockName;
    private final String marketType;

    public StockProfile(String stockId, String stockName, String marketType) {
        this.stockId = stockId;
        this.stockName = stockName == null ? "" : stockName;
        this.marketType = marketType == null ? "" : marketType;
    }

    public String getStockId() {
        return stockId;
    }

    public String getStockName() {
        return stockName;
    }

    public String getMarketType() {
        return marketType;
    }
}
