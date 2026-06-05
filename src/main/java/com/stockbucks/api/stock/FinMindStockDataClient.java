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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FinMind 台股歷史資料備援來源。
 */
public class FinMindStockDataClient implements StockDataClient {
    private static final String DEFAULT_BASE_URL = "https://api.finmindtrade.com/api/v4/data";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String token;

    public FinMindStockDataClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.baseUrl = EnvironmentConfig.first(DEFAULT_BASE_URL, "FINMIND_BASE_URL", "STOCKBUCKS_FINMIND_BASE_URL");
        this.token = EnvironmentConfig.first("", "FINMIND_TOKEN", "STOCKBUCKS_FINMIND_TOKEN");
    }

    @Override
    public String getProviderName() {
        return "finmind";
    }

    @Override
    public boolean isConfigured() {
        return !token.isBlank();
    }

    @Override
    public String getMissingApiKeyName() {
        return isConfigured() ? "" : "FINMIND_TOKEN";
    }

    @Override
    public StockQuote fetchQuote(String stockId) {
        // FinMind 主要做歷史資料；即時報價用最近一筆日資料近似。
        List<StockData> data = fetchDailyHistory(stockId, LocalDate.now().minusDays(10), LocalDate.now());
        if (data.isEmpty()) {
            return null;
        }
        return StockQuote.fromStockData(data.get(data.size() - 1), getProviderName());
    }

    @Override
    public List<StockData> fetchDailyHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        if (!isConfigured() || stockId == null || stockId.isBlank() || fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
            return List.of();
        }

        // TaiwanStockPrice 是 FinMind 常用的台股日資料 dataset。
        String uri = baseUrl
                + "?dataset=TaiwanStockPrice"
                + "&data_id=" + URLEncoder.encode(stockId, StandardCharsets.UTF_8)
                + "&start_date=" + fromDate
                + "&end_date=" + toDate;

        String json = get(uri);
        List<StockData> result = new ArrayList<>();
        for (String objectText : JsonText.objects(json)) {
            String id = JsonText.firstNonBlank(JsonText.value(objectText, "stock_id"), stockId);
            String date = JsonText.value(objectText, "date").replace("-", "/");
            String open = JsonText.value(objectText, "open");
            String high = JsonText.value(objectText, "max");
            String low = JsonText.value(objectText, "min");
            String close = JsonText.value(objectText, "close");
            String volume = JsonText.value(objectText, "Trading_Volume");
            String turnover = JsonText.value(objectText, "Trading_money");
            if (!date.isBlank() && JsonText.parseDouble(close) > 0) {
                result.add(new StockData(id, "", date, open, high, low, close, volume, turnover, "0", "0"));
            }
        }
        return result;
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
        endpoints.put("taiwanStockPrice", baseUrl + "?dataset=TaiwanStockPrice&data_id=2330&start_date=yyyy-MM-dd&end_date=yyyy-MM-dd");
        return endpoints;
    }

    private String get(String uri) {
        // FinMind token 用 Bearer token 傳遞。
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "StockBucks/1.0");
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("FinMind HTTP " + response.statusCode() + ": " + summarizeResponseBody(response.body()));
            }
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch FinMind data: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("FinMind fetch interrupted", e);
        }
    }

    private String summarizeResponseBody(String body) {
        if (body == null || body.isBlank()) {
            return "empty response body";
        }
        String cleaned = body
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > 160 ? cleaned.substring(0, 160) + "..." : cleaned;
    }
}
