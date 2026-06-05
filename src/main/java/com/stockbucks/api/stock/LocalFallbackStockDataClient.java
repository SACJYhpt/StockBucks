package com.stockbucks.api.stock;

import com.stockbucks.CsvLoading;
import com.stockbucks.StockData;
import com.stockbucks.api.config.EnvironmentConfig;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最後的離線備援來源，使用專案既有 CSV 讀取器。
 */
public class LocalFallbackStockDataClient implements StockDataClient {
    private static final DateTimeFormatter STOCK_DATA_DATE = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('/')
            .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .appendLiteral('/')
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .toFormatter();

    private final String csvName;

    public LocalFallbackStockDataClient() {
        this.csvName = EnvironmentConfig.first("TestDataTSMC", "LOCAL_STOCK_CSV_NAME", "STOCKBUCKS_LOCAL_STOCK_CSV_NAME");
    }

    @Override
    public String getProviderName() {
        return "local";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String getMissingApiKeyName() {
        return "";
    }

    @Override
    public StockQuote fetchQuote(String stockId) {
        // 離線 fallback：使用 CSV 最後一筆收盤價當作目前價格。
        List<StockData> data = fetchDailyHistory(stockId, LocalDate.MIN, LocalDate.MAX);
        if (data.isEmpty()) {
            return null;
        }
        StockData latest = data.get(data.size() - 1);
        return new StockQuote(
                normalizeStockId(stockId),
                "TSMC fallback sample",
                latest.getClose(),
                latest.getOpen(),
                latest.getHigh(),
                latest.getLow(),
                latest.getVolume(),
                getProviderName()
        );
    }

    @Override
    public List<StockData> fetchDailyHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        if (!isSupportedLocalSymbol(stockId)) {
            return List.of(); // 避免 0050、0052 等代號誤用 TestDataTSMC.csv 的台積電測試資料。
        }

        List<StockData> data = new ArrayList<>();
        // CsvLoading 目前固定讀 data/TestDataTSMC.csv，這裡不改同學既有類別。
        new CsvLoading().streamStockData(csvName, data::add);
        if (fromDate == null && toDate == null) {
            return data;
        }
        return data.stream()
                .filter(item -> isInRange(item, fromDate, toDate))
                .toList();
    }

    @Override
    public List<StockData> fetchDailyMarketAll() {
        return fetchDailyHistory(csvName, LocalDate.MIN, LocalDate.MAX);
    }

    @Override
    public List<StockProfile> fetchListedStockProfiles() {
        return List.of(new StockProfile("2330", "TSMC fallback sample", "LOCAL"));
    }

    @Override
    public Map<String, String> supportedApiEndpoints() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("localCsv", "data/" + csvName + ".csv");
        return endpoints;
    }

    private boolean isInRange(StockData item, LocalDate fromDate, LocalDate toDate) {
        LocalDate date = LocalDate.parse(item.getDate(), STOCK_DATA_DATE);
        boolean afterStart = fromDate == null || fromDate.equals(LocalDate.MIN) || !date.isBefore(fromDate);
        boolean beforeEnd = toDate == null || toDate.equals(LocalDate.MAX) || !date.isAfter(toDate);
        return afterStart && beforeEnd;
    }

    private boolean isSupportedLocalSymbol(String stockId) {
        String normalized = normalizeStockId(stockId);
        return normalized.isBlank()
                || normalized.equals("2330")
                || normalized.equalsIgnoreCase(csvName);
    }

    private String normalizeStockId(String stockId) {
        return stockId == null ? "" : stockId.trim();
    }
}
