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
 * FinMind 股票資料來源。
 * 先嘗試即時 snapshot；若帳號權限不足或端點失敗，就退回 TaiwanStockPrice 日資料。
 */
public class FinMindStockDataClient implements StockDataClient {
    private static final String DEFAULT_BASE_URL = "https://api.finmindtrade.com/api/v4/data";
    private static final String DEFAULT_SNAPSHOT_URL = "https://api.finmindtrade.com/api/v4/taiwan_stock_tick_snapshot";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String snapshotUrl;
    private final String token;

    public FinMindStockDataClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.baseUrl = EnvironmentConfig.first(DEFAULT_BASE_URL, "FINMIND_BASE_URL", "STOCKBUCKS_FINMIND_BASE_URL");
        this.snapshotUrl = EnvironmentConfig.first(DEFAULT_SNAPSHOT_URL, "FINMIND_SNAPSHOT_URL", "STOCKBUCKS_FINMIND_SNAPSHOT_URL");
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
        RuntimeException snapshotFailure = null;
        try {
            StockQuote snapshotQuote = fetchSnapshotQuote(stockId);
            if (snapshotQuote != null) {
                return snapshotQuote;
            }
        } catch (RuntimeException e) {
            snapshotFailure = e;
        }

        // snapshot 常受帳號等級限制；退回日資料可讓 FinMind 仍然當作備用報價來源。
        List<StockData> data = fetchDailyHistory(stockId, LocalDate.now().minusDays(10), LocalDate.now());
        if (data.isEmpty()) {
            if (snapshotFailure != null) {
                throw snapshotFailure;
            }
            return null;
        }

        StockQuote fallbackQuote = StockQuote.fromStockData(data.get(data.size() - 1), getProviderName());
        return new StockQuote(
                fallbackQuote.getStockId(),
                fallbackQuote.getStockName(),
                fallbackQuote.getLastPrice(),
                fallbackQuote.getOpenPrice(),
                fallbackQuote.getHighPrice(),
                fallbackQuote.getLowPrice(),
                fallbackQuote.getVolume(),
                snapshotFailure == null ? "finmind:daily" : "finmind:daily-after-snapshot-failed"
        );
    }

    @Override
    public List<StockData> fetchDailyHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        if (!isConfigured() || stockId == null || stockId.isBlank() || fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
            return List.of();
        }

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
        endpoints.put("taiwanStockTickSnapshot", snapshotUrl + "?data_id=2330");
        endpoints.put("taiwanStockPrice", baseUrl + "?dataset=TaiwanStockPrice&data_id=2330&start_date=yyyy-MM-dd&end_date=yyyy-MM-dd");
        return endpoints;
    }

    private StockQuote fetchSnapshotQuote(String stockId) {
        if (!isConfigured() || stockId == null || stockId.isBlank()) {
            return null;
        }

        String uri = snapshotUrl + "?data_id=" + URLEncoder.encode(stockId, StandardCharsets.UTF_8);
        String json = get(uri);
        for (String objectText : JsonText.objects(json)) {
            String id = JsonText.firstNonBlank(
                    JsonText.firstNonBlank(JsonText.value(objectText, "stock_id"), JsonText.value(objectText, "stockId")),
                    stockId
            );
            if (!stockId.equals(id)) {
                continue;
            }

            double close = firstDouble(objectText, "close", "price", "last_price", "lastPrice", "last");
            double open = firstDouble(objectText, "open", "open_price", "openPrice");
            double high = firstDouble(objectText, "high", "max", "high_price", "highPrice");
            double low = firstDouble(objectText, "low", "min", "low_price", "lowPrice");
            long volume = firstLong(objectText, "volume", "total_volume", "totalVolume", "Trading_Volume");
            String name = JsonText.firstNonBlank(
                    JsonText.firstNonBlank(JsonText.value(objectText, "stock_name"), JsonText.value(objectText, "name")),
                    ""
            );

            if (close > 0) {
                return new StockQuote(id, name, close, open, high, low, volume, "finmind:snapshot");
            }
        }
        return null;
    }

    private double firstDouble(String objectText, String... names) {
        for (String name : names) {
            String value = JsonText.value(objectText, name);
            if (!value.isBlank()) {
                double parsed = JsonText.parseDouble(value);
                if (parsed > 0) {
                    return parsed;
                }
            }
        }
        return 0;
    }

    private long firstLong(String objectText, String... names) {
        for (String name : names) {
            String value = JsonText.value(objectText, name);
            if (!value.isBlank()) {
                long parsed = JsonText.parseLong(value);
                if (parsed > 0) {
                    return parsed;
                }
            }
        }
        return 0;
    }

    private String get(String uri) {
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
