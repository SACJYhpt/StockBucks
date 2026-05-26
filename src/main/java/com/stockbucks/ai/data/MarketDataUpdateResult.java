package com.stockbucks.ai.data;

import java.time.LocalDate;

public class MarketDataUpdateResult {

    private final String stockId;
    private final LocalDate fromDate;
    private final LocalDate toDate;
    private final int fetchedCount;
    private final int savedCount;
    private final String message;

    public MarketDataUpdateResult(String stockId,
                                  LocalDate fromDate,
                                  LocalDate toDate,
                                  int fetchedCount,
                                  int savedCount,
                                  String message) {
        this.stockId = stockId;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.fetchedCount = fetchedCount;
        this.savedCount = savedCount;
        this.message = message;
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
}
