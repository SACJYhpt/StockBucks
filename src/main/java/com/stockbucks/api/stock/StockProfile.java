package com.stockbucks.api.stock;

/**
 * 股票基本資料。
 *
 * 目前主要用來列出可查詢股票清單，例如 TWSE 上市股票。
 */
public class StockProfile {

    private final String stockId; // 股票代號。
    private final String stockName; // 股票名稱。
    private final String marketType; // 市場別，例如 TWSE、LOCAL。

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
