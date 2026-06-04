package com.stockbucks.api.stock;

import com.stockbucks.StockData;

import java.time.LocalDateTime;

/**
 * 單一股票的即時或近即時報價。
 *
 * provider 會記錄資料來源，例如 broker、fugle、twse、web:google、local。
 */
public class StockQuote {
    private final String stockId; // 股票代號。
    private final String stockName; // 股票名稱，資料來源沒有提供時可為空。
    private final double lastPrice; // 最新成交價或來源能提供的最接近價格。
    private final double openPrice; // 今日開盤價；來源沒有提供時為 0。
    private final double highPrice; // 今日最高價；來源沒有提供時為 0。
    private final double lowPrice; // 今日最低價；來源沒有提供時為 0。
    private final long volume; // 成交量；來源沒有提供時為 0。
    private final String provider; // 實際資料來源。
    private final LocalDateTime fetchedAt; // 本地抓取時間。

    public StockQuote(String stockId,
                      String stockName,
                      double lastPrice,
                      double openPrice,
                      double highPrice,
                      double lowPrice,
                      long volume,
                      String provider) {
        this.stockId = stockId == null ? "" : stockId;
        this.stockName = stockName == null ? "" : stockName;
        this.lastPrice = lastPrice;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.volume = volume;
        this.provider = provider == null ? "" : provider;
        this.fetchedAt = LocalDateTime.now();
    }

    public static StockQuote fromStockData(StockData data, String provider) {
        // 把既有 StockData 轉成報價模型，方便 TWSE/local fallback 共用。
        if (data == null) {
            return null;
        }
        return new StockQuote(
                data.getStockID(),
                data.getStockName(),
                data.getClose(),
                data.getOpen(),
                data.getHigh(),
                data.getLow(),
                data.getVolume(),
                provider
        );
    }

    public String getStockId() {
        return stockId;
    }

    public String getStockName() {
        return stockName;
    }

    public double getLastPrice() {
        return lastPrice;
    }

    public double getOpenPrice() {
        return openPrice;
    }

    public double getHighPrice() {
        return highPrice;
    }

    public double getLowPrice() {
        return lowPrice;
    }

    public long getVolume() {
        return volume;
    }

    public String getProvider() {
        return provider;
    }

    public LocalDateTime getFetchedAt() {
        return fetchedAt;
    }
}
