package com.stockbucks.api.stock;

import com.stockbucks.StockData;
import com.stockbucks.api.config.EnvironmentConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fugle 行情資料來源。
 *
 * 設定 FUGLE_API_KEY 後，可作為較真實的台股即時報價來源。
 */
public class FugleStockDataClient implements StockDataClient {
    private static final String DEFAULT_BASE_URL = "https://api.fugle.tw/marketdata/v1.0/stock";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;

    public FugleStockDataClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.baseUrl = EnvironmentConfig.first(DEFAULT_BASE_URL, "FUGLE_BASE_URL", "STOCKBUCKS_FUGLE_BASE_URL").replaceAll("/+$", "");
        this.apiKey = EnvironmentConfig.first("", "FUGLE_API_KEY", "STOCKBUCKS_FUGLE_API_KEY");
    }

    @Override
    public String getProviderName() {
        return "fugle";
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    @Override
    public String getMissingApiKeyName() {
        return isConfigured() ? "" : "FUGLE_API_KEY";
    }

    @Override
    public StockQuote fetchQuote(String stockId) {
        if (!isConfigured() || stockId == null || stockId.isBlank()) {
            return null;
        }

        // Fugle intraday quote 是目前較接近真實即時報價的來源之一。
        String uri = baseUrl + "/intraday/quote/" + URLEncoder.encode(stockId, StandardCharsets.UTF_8);
        String json = get(uri);
        String symbol = JsonText.firstNonBlank(JsonText.value(json, "symbol"), stockId);
        String name = JsonText.value(json, "name");
        double lastPrice = firstPrice(json, "lastPrice", "closePrice", "previousClose");
        double openPrice = firstPrice(json, "openPrice", "referencePrice", "previousClose");
        double highPrice = firstPrice(json, "highPrice", "lastPrice", "closePrice");
        double lowPrice = firstPrice(json, "lowPrice", "lastPrice", "closePrice");
        long volume = JsonText.parseLong(JsonText.value(json, "tradeVolume"));

        if (lastPrice <= 0) {
            return null;
        }
        return new StockQuote(symbol, name, lastPrice, openPrice, highPrice, lowPrice, volume, getProviderName());
    }

    @Override
    public List<StockData> fetchDailyHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        return List.of();
    }

    @Override
    public List<StockData> fetchDailyMarketAll() {
        return List.of();
    }

    @Override
    public List<StockProfile> fetchListedStockProfiles() {
        return List.of();
    }

    @Override
    public Map<String, String> supportedApiEndpoints() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("intradayQuote", baseUrl + "/intraday/quote/{symbol}");
        return endpoints;
    }

    private double firstPrice(String json, String... fields) {
        // 不同 API 版本或商品可能使用不同欄位名稱，依序找第一個有效價格。
        for (String field : fields) {
            double value = JsonText.parseDouble(JsonText.value(json, field));
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private String get(String uri) {
        // Fugle 使用 X-API-Key header。
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("X-API-Key", apiKey)
                .header("User-Agent", "StockBucks/1.0")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Fugle HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch Fugle data: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Fugle fetch interrupted", e);
        }
    }
}
