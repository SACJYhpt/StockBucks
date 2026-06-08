package com.stockbucks.api.stock;

/**
 * 單一報價來源的抓取結果。
 * 同學可以用 status 判斷能不能用，用 dataGranularity 判斷資料夠不夠細。
 */
public class StockQuoteAttempt {
    private final String stockId; // 使用者輸入的股票代號。
    private final String providerName; // 嘗試的資料來源，例如 broker、fugle、web、twse。
    private final boolean configured; // 來源是否已完成必要設定。
    private final String status; // success、missing、no data、failed。
    private final String message; // 給 UI 或 debug 顯示的簡短說明。
    private final StockQuote quote; // 成功時的報價資料。
    private final String dataGranularity; // tick、minute、hour、near-realtime、daily、local。
    private final int sourceRank; // 越小越優先，代表越接近「最真實、最詳細」。

    public StockQuoteAttempt(String stockId,
                             String providerName,
                             boolean configured,
                             String status,
                             String message,
                             StockQuote quote) {
        this(stockId, providerName, configured, status, message, quote, "", Integer.MAX_VALUE);
    }

    public StockQuoteAttempt(String stockId,
                             String providerName,
                             boolean configured,
                             String status,
                             String message,
                             StockQuote quote,
                             String dataGranularity,
                             int sourceRank) {
        this.stockId = stockId == null ? "" : stockId;
        this.providerName = providerName == null ? "" : providerName;
        this.configured = configured;
        this.status = status == null ? "" : status;
        this.message = message == null ? "" : message;
        this.quote = quote;
        this.dataGranularity = dataGranularity == null ? "" : dataGranularity;
        this.sourceRank = sourceRank;
    }

    public String getStockId() {
        return stockId;
    }

    public String getProviderName() {
        return providerName;
    }

    public boolean isConfigured() {
        return configured;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public StockQuote getQuote() {
        return quote;
    }

    public String getDataGranularity() {
        return dataGranularity;
    }

    public int getSourceRank() {
        return sourceRank;
    }
}
