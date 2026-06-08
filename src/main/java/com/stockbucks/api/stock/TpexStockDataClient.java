package com.stockbucks.api.stock;

import com.stockbucks.StockData;
import com.stockbucks.api.config.EnvironmentConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TPEx 櫃買中心公開資料來源。
 * 這個來源用來補 TWSE 只涵蓋上市股票的缺口，主要支援上櫃股票與部分上櫃 ETF/債券。
 */
public class TpexStockDataClient implements StockDataClient {
    private static final String DEFAULT_TPEX_OPENAPI_BASE_URL = "https://www.tpex.org.tw/openapi/v1";
    private static final DateTimeFormatter STOCK_DATA_DATE = DateTimeFormatter.ofPattern("yyyy/M/d");

    private final HttpClient httpClient;
    private final String tpexOpenApiBaseUrl;

    public TpexStockDataClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.tpexOpenApiBaseUrl = EnvironmentConfig.first(DEFAULT_TPEX_OPENAPI_BASE_URL, "TPEX_OPENAPI_BASE_URL", "STOCKBUCKS_TPEX_OPENAPI_BASE_URL");
    }

    @Override
    public String getProviderName() {
        return "tpex";
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
        for (StockData data : fetchDailyMarketAll()) {
            if (stockId.equals(data.getStockID())) {
                return StockQuote.fromStockData(data, getProviderName());
            }
        }
        return null;
    }

    @Override
    public List<StockData> fetchDailyHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        if (stockId == null || stockId.isBlank() || fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
            return List.of();
        }

        for (StockData data : fetchDailyMarketAll()) {
            if (!stockId.equals(data.getStockID())) {
                continue;
            }
            LocalDate date = LocalDate.parse(data.getDate(), STOCK_DATA_DATE);
            if (!date.isBefore(fromDate) && !date.isAfter(toDate)) {
                return List.of(data);
            }
        }
        return List.of();
    }

    @Override
    public List<StockData> fetchDailyMarketAll() {
        List<StockData> result = new ArrayList<>();
        result.addAll(fetchRows("/tpex_mainboard_quotes", this::parseMainboardQuoteRow));
        result.addAll(fetchRows("/tpex_esb_latest_statistics", this::parseEmergingQuoteRow));
        return result;
    }

    @Override
    public List<StockProfile> fetchListedStockProfiles() {
        List<StockProfile> result = new ArrayList<>();
        result.addAll(fetchProfiles("/tpex_mainboard_quotes", "TPEX"));
        result.addAll(fetchProfiles("/tpex_esb_latest_statistics", "TPEX-ESB"));
        return result;
    }

    @Override
    public Map<String, String> supportedApiEndpoints() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("mainboardQuotes", tpexOpenApiBaseUrl + "/tpex_mainboard_quotes");
        endpoints.put("mainboardDailyCloseQuotes", tpexOpenApiBaseUrl + "/tpex_mainboard_daily_close_quotes");
        endpoints.put("emergingLatestStatistics", tpexOpenApiBaseUrl + "/tpex_esb_latest_statistics");
        return endpoints;
    }

    private List<StockData> fetchRows(String path, RowParser parser) {
        List<StockData> result = new ArrayList<>();
        String json = get(tpexOpenApiBaseUrl.replaceAll("/+$", "") + path);
        for (String objectText : JsonText.objects(json)) {
            StockData data = parser.parse(objectText);
            if (data != null) {
                result.add(data);
            }
        }
        return result;
    }

    private List<StockProfile> fetchProfiles(String path, String marketType) {
        List<StockProfile> result = new ArrayList<>();
        String json = get(tpexOpenApiBaseUrl.replaceAll("/+$", "") + path);
        for (String objectText : JsonText.objects(json)) {
            String code = JsonText.value(objectText, "SecuritiesCompanyCode");
            String name = JsonText.value(objectText, "CompanyName");
            if (isTradableCode(code)) {
                result.add(new StockProfile(code, name, marketType));
            }
        }
        return result;
    }

    private StockData parseMainboardQuoteRow(String objectText) {
        String code = JsonText.value(objectText, "SecuritiesCompanyCode");
        if (!isTradableCode(code)) {
            return null;
        }

        String close = JsonText.cleanNumber(JsonText.value(objectText, "Close"));
        String open = JsonText.cleanNumber(JsonText.value(objectText, "Open"));
        String high = JsonText.cleanNumber(JsonText.value(objectText, "High"));
        String low = JsonText.cleanNumber(JsonText.value(objectText, "Low"));
        if (close.isBlank() || open.isBlank() || high.isBlank() || low.isBlank()) {
            return null;
        }

        return new StockData(
                code,
                JsonText.value(objectText, "CompanyName"),
                convertRocDate(JsonText.value(objectText, "Date")),
                open,
                high,
                low,
                close,
                JsonText.value(objectText, "TradingShares"),
                JsonText.value(objectText, "TransactionAmount"),
                JsonText.value(objectText, "TransactionNumber"),
                JsonText.value(objectText, "Change")
        );
    }

    private StockData parseEmergingQuoteRow(String objectText) {
        String code = JsonText.value(objectText, "SecuritiesCompanyCode");
        if (!isTradableCode(code)) {
            return null;
        }

        String latest = JsonText.cleanNumber(JsonText.value(objectText, "LatestPrice"));
        String high = JsonText.cleanNumber(JsonText.value(objectText, "Highest"));
        String low = JsonText.cleanNumber(JsonText.value(objectText, "Lowest"));
        String average = JsonText.cleanNumber(JsonText.value(objectText, "Average"));
        if (latest.isBlank() || "0".equals(latest)) {
            return null;
        }

        String open = average.isBlank() || "0".equals(average) ? latest : average;
        if (high.isBlank() || "0".equals(high)) {
            high = latest;
        }
        if (low.isBlank() || "0".equals(low)) {
            low = latest;
        }

        return new StockData(
                code,
                JsonText.value(objectText, "CompanyName"),
                convertRocDate(JsonText.value(objectText, "Date")),
                open,
                high,
                low,
                latest,
                JsonText.value(objectText, "TransactionVolume"),
                "0",
                "0",
                "0"
        );
    }

    private String get(String uri) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "StockBucks/1.0")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("TPEx HTTP " + response.statusCode() + ": " + summarizeResponseBody(response.body()));
            }
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch TPEx data: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("TPEx fetch interrupted", e);
        }
    }

    private String convertRocDate(String rocDate) {
        String trimmed = rocDate == null ? "" : rocDate.trim();
        if (!trimmed.matches("\\d{7}")) {
            return LocalDate.now().format(STOCK_DATA_DATE);
        }
        int year = Integer.parseInt(trimmed.substring(0, 3)) + 1911;
        int month = Integer.parseInt(trimmed.substring(3, 5));
        int day = Integer.parseInt(trimmed.substring(5, 7));
        return LocalDate.of(year, month, day).format(STOCK_DATA_DATE);
    }

    private boolean isTradableCode(String code) {
        return code != null && code.matches("[0-9A-Z]{4,6}");
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

    private interface RowParser {
        StockData parse(String objectText);
    }
}
