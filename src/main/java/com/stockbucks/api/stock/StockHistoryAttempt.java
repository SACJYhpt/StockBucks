package com.stockbucks.api.stock;

import com.stockbucks.StockData;

import java.util.List;

/**
 * 單一歷史資料來源的抓取結果。
 * 同學要檢查所有來源時，可以從 AIHub 拿到這個物件，不必自己碰各家 API。
 */
public class StockHistoryAttempt {
    private final String stockId; // 使用者輸入的股票代號。
    private final String providerName; // 嘗試的資料來源，例如 twse、finmind、web、local。
    private final boolean configured; // 來源是否已完成必要設定。
    private final String status; // success、missing、no data、failed。
    private final String message; // 給 UI 或 debug 顯示的簡短說明。
    private final List<StockData> data; // 成功時回傳的歷史資料。

    public StockHistoryAttempt(String stockId,
                               String providerName,
                               boolean configured,
                               String status,
                               String message,
                               List<StockData> data) {
        this.stockId = stockId == null ? "" : stockId;
        this.providerName = providerName == null ? "" : providerName;
        this.configured = configured;
        this.status = status == null ? "" : status;
        this.message = message == null ? "" : message;
        this.data = data == null ? List.of() : List.copyOf(data);
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
}
