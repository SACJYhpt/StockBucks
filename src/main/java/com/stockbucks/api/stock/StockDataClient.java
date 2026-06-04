package com.stockbucks.api.stock;

import com.stockbucks.StockData;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 所有股票資料來源的共同介面。
 *
 * 資料來源可以是官方 API、券商 API、公開網頁爬蟲或本地 CSV。
 * MarketDataService 會透過這個介面執行 fallback chain。
 */
public interface StockDataClient {
    String getProviderName();

    boolean isConfigured();

    String getMissingApiKeyName();

    StockQuote fetchQuote(String stockId);

    List<StockData> fetchDailyHistory(String stockId, LocalDate fromDate, LocalDate toDate);

    List<StockData> fetchDailyMarketAll();

    List<StockProfile> fetchListedStockProfiles();

    Map<String, String> supportedApiEndpoints();
}
