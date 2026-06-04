package com.stockbucks.api.stock;

import java.time.LocalDateTime;

/**
 * 日內 K 線資料。
 *
 * interval 由呼叫端決定，例如 1m、5m、1h；目前主要由券商 API 提供。
 */
public class IntradayBar {
    private final String stockId; // 股票代號。
    private final LocalDateTime time; // K 線起始時間或來源提供的時間戳。
    private final double open; // 開盤價。
    private final double high; // 最高價。
    private final double low; // 最低價。
    private final double close; // 收盤價。
    private final long volume; // 成交量。
    private final String provider; // 資料來源。

    public IntradayBar(String stockId,
                       LocalDateTime time,
                       double open,
                       double high,
                       double low,
                       double close,
                       long volume,
                       String provider) {
        this.stockId = stockId == null ? "" : stockId;
        this.time = time;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.provider = provider == null ? "" : provider;
    }

    public String getStockId() {
        return stockId;
    }

    public LocalDateTime getTime() {
        return time;
    }

    public double getOpen() {
        return open;
    }

    public double getHigh() {
        return high;
    }

    public double getLow() {
        return low;
    }

    public double getClose() {
        return close;
    }

    public long getVolume() {
        return volume;
    }

    public String getProvider() {
        return provider;
    }
}
