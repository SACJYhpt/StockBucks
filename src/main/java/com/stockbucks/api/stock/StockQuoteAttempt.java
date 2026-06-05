package com.stockbucks.api.stock;

/**
 * 單一股票代號在單一資料來源上的報價嘗試結果。
 *
 * DEBUG UI 會用這個模型顯示所有來源，不只顯示最後成功的 fallback 結果。
 */
public class StockQuoteAttempt {
    private final String stockId; // 使用者輸入的股票代號。
    private final String providerName; // 嘗試的資料來源，例如 broker、fugle、web、twse。
    private final boolean configured; // 這個來源是否已經具備需要的 API Key 或設定。
    private final String status; // success、missing、no data、failed。
    private final String message; // 給 UI 顯示的缺少設定、無資料或錯誤原因。
    private final StockQuote quote; // 成功時的報價資料；失敗或缺設定時為 null。

    public StockQuoteAttempt(String stockId,
                             String providerName,
                             boolean configured,
                             String status,
                             String message,
                             StockQuote quote) {
        this.stockId = stockId == null ? "" : stockId;
        this.providerName = providerName == null ? "" : providerName;
        this.configured = configured;
        this.status = status == null ? "" : status;
        this.message = message == null ? "" : message;
        this.quote = quote;
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
}
