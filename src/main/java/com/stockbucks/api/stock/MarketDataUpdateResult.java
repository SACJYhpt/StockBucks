package com.stockbucks.api.stock;

import com.stockbucks.StockData;

import java.time.LocalDate;
import java.util.List;

/**
 * 歷史資料更新/抓取結果。
 *
 * 保留 savedCount 是為了相容舊版資料庫流程；目前 API-only 版本通常為 0。
 */
public class MarketDataUpdateResult {

    private final String stockId; // 股票代號或 ALL_STOCKS。
    private final LocalDate fromDate; // 抓取起始日。
    private final LocalDate toDate; // 抓取結束日。
    private final int fetchedCount; // 取得筆數。
    private final int savedCount; // 寫入筆數；目前不寫 DB，多為 0。
    private final String message; // 給 UI 或 log 顯示的結果訊息。
    private final List<StockData> data; // 實際抓回來的資料。

    public MarketDataUpdateResult(String stockId,
                                  LocalDate fromDate,
                                  LocalDate toDate,
                                  int fetchedCount,
                                  int savedCount,
                                  String message) {
        this(stockId, fromDate, toDate, fetchedCount, savedCount, message, List.of());
    }

    public MarketDataUpdateResult(String stockId,
                                  LocalDate fromDate,
                                  LocalDate toDate,
                                  int fetchedCount,
                                  int savedCount,
                                  String message,
                                  List<StockData> data) {
        this.stockId = stockId;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.fetchedCount = fetchedCount;
        this.savedCount = savedCount;
        this.message = message;
        this.data = data == null ? List.of() : List.copyOf(data);
    }

    public static MarketDataUpdateResult skipped(String stockId, String message) {
        return new MarketDataUpdateResult(stockId, null, null, 0, 0, message);
    }

    public String getStockId() {
        return stockId;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public int getFetchedCount() {
        return fetchedCount;
    }

    public int getSavedCount() {
        return savedCount;
    }

    public String getMessage() {
        return message;
    }

    public List<StockData> getData() {
        return data;
    }
}
