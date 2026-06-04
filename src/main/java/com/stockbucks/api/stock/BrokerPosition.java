package com.stockbucks.api.stock;

/**
 * 券商帳戶中的單一持股。
 */
public class BrokerPosition {
    private final String stockId; // 股票代號。
    private final String stockName; // 股票名稱。
    private final int quantity; // 持有股數。
    private final double averagePrice; // 平均成本或券商提供的成本價。
    private final double marketValue; // 目前市值。
    private final String provider; // 券商來源。

    public BrokerPosition(String stockId,
                          String stockName,
                          int quantity,
                          double averagePrice,
                          double marketValue,
                          String provider) {
        this.stockId = stockId == null ? "" : stockId;
        this.stockName = stockName == null ? "" : stockName;
        this.quantity = quantity;
        this.averagePrice = averagePrice;
        this.marketValue = marketValue;
        this.provider = provider == null ? "" : provider;
    }

    public String getStockId() {
        return stockId;
    }

    public String getStockName() {
        return stockName;
    }

    public int getQuantity() {
        return quantity;
    }

    public double getAveragePrice() {
        return averagePrice;
    }

    public double getMarketValue() {
        return marketValue;
    }

    public String getProvider() {
        return provider;
    }
}
