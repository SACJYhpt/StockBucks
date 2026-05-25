package com.stockbucks.ai.model;

public class MarketSnapshot {

    private final String stockId;
    private final String latestDate;
    private final String modeDescription;
    private final double currentPrice;
    private final String extraInfo;

    public MarketSnapshot(String stockId,
                          String latestDate,
                          String modeDescription,
                          double currentPrice,
                          String extraInfo) {
        this.stockId = stockId;
        this.latestDate = latestDate;
        this.modeDescription = modeDescription;
        this.currentPrice = currentPrice;
        this.extraInfo = extraInfo;
    }

    public String getStockId() {
        return stockId;
    }

    public String getLatestDate() {
        return latestDate;
    }

    public String getModeDescription() {
        return modeDescription;
    }

    public double getCurrentPrice() {
        return currentPrice;
    }

    public String getExtraInfo() {
        return extraInfo;
    }
}