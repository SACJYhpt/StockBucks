package com.stockbucks.api.stock;

import java.util.List;

/**
 * 單一盤中 K 線來源的抓取結果。
 * 盤中資料比日資料更細，拿不到時才退回 TWSE 日資料做備援。
 */
public class StockIntradayAttempt {
    private final String stockId;
    private final String providerName;
    private final boolean configured;
    private final String status; // success、missing、no data、failed、daily only。
    private final String message;
    private final List<IntradayBar> bars;
    private final String dataGranularity; // minute、hour、daily only。
    private final int sourceRank;

    public StockIntradayAttempt(String stockId,
                                String providerName,
                                boolean configured,
                                String status,
                                String message,
                                List<IntradayBar> bars,
                                String dataGranularity,
                                int sourceRank) {
        this.stockId = stockId == null ? "" : stockId;
        this.providerName = providerName == null ? "" : providerName;
        this.configured = configured;
        this.status = status == null ? "" : status;
        this.message = message == null ? "" : message;
        this.bars = bars == null ? List.of() : List.copyOf(bars);
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

    public List<IntradayBar> getBars() {
        return bars;
    }

    public int getBarCount() {
        return bars.size();
    }

    public String getFirstTime() {
        return bars.isEmpty() || bars.get(0).getTime() == null ? "" : bars.get(0).getTime().toString();
    }

    public String getLastTime() {
        return bars.isEmpty() || bars.get(bars.size() - 1).getTime() == null ? "" : bars.get(bars.size() - 1).getTime().toString();
    }

    public String getDataGranularity() {
        return dataGranularity;
    }

    public int getSourceRank() {
        return sourceRank;
    }
}
