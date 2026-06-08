package com.stockbucks.api.stock;

import com.stockbucks.StockData;

import java.util.List;

/**
 * 單一歷史資料來源的抓取結果。
 * 歷史資料會互補合併，dataGranularity 用來說明這批資料的時間單位。
 */
public class StockHistoryAttempt {
    private final String stockId; // 使用者輸入的股票代號。
    private final String providerName; // 嘗試的資料來源，例如 twse、web、finmind、local。
    private final boolean configured; // 來源是否已完成必要設定。
    private final String status; // success、missing、no data、failed。
    private final String message; // 給 UI 或 debug 顯示的簡短說明。
    private final List<StockData> data; // 成功時回傳的歷史資料。
    private final String dataGranularity; // daily、local。
    private final int sourceRank; // 越小越優先。

    public StockHistoryAttempt(String stockId,
                               String providerName,
                               boolean configured,
                               String status,
                               String message,
                               List<StockData> data) {
        this(stockId, providerName, configured, status, message, data, "", Integer.MAX_VALUE);
    }

    public StockHistoryAttempt(String stockId,
                               String providerName,
                               boolean configured,
                               String status,
                               String message,
                               List<StockData> data,
                               String dataGranularity,
                               int sourceRank) {
        this.stockId = stockId == null ? "" : stockId;
        this.providerName = providerName == null ? "" : providerName;
        this.configured = configured;
        this.status = status == null ? "" : status;
        this.message = message == null ? "" : message;
        this.data = data == null ? List.of() : List.copyOf(data);
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

    public List<StockData> getData() {
        return data;
    }

    public int getRowCount() {
        return data.size();
    }

    public String getFirstDate() {
        return data.isEmpty() ? "" : data.get(0).getDate();
    }

    public String getLastDate() {
        return data.isEmpty() ? "" : data.get(data.size() - 1).getDate();
    }

    public String getDataGranularity() {
        return dataGranularity;
    }

    public int getSourceRank() {
        return sourceRank;
    }
}
