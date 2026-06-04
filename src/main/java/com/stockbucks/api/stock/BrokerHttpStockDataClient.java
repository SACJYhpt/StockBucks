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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 HTTP 券商 API 轉接器。
 *
 * 這是過渡用版本；等確定券商後，可以再寫專用 client。
 * 端點模板與憑證都從環境變數或 .env 讀取。
 */
public class BrokerHttpStockDataClient implements StockDataClient, BrokerAccountClient {
    private final HttpClient httpClient;
    private final BrokerCredentials credentials; // 從本機環境變數或 .env 讀取券商憑證。
    private final String baseUrl; // 券商 API 主機。
    private final String loginEndpoint; // 使用帳密登入時的可選端點。
    private final String quoteEndpoint; // 報價端點模板，支援 {symbol} 與 {stockId}。
    private final String intradayBarsEndpoint; // 日內 K 線端點模板，支援 {symbol}、{stockId}、{interval}。
    private final String accountEndpoint; // 帳戶摘要端點。
    private final String positionsEndpoint; // 庫存/持倉端點。
    private final String authTokenField; // 從登入回應 JSON 讀取 token 的欄位名稱。
    private String sessionToken; // 僅在執行期間保存，不寫入檔案。

    public BrokerHttpStockDataClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.credentials = BrokerCredentials.fromEnvironment();
        this.baseUrl = EnvironmentConfig.first("", "BROKER_BASE_URL", "STOCKBUCKS_BROKER_BASE_URL").replaceAll("/+$", "");
        this.loginEndpoint = EnvironmentConfig.first("", "BROKER_LOGIN_ENDPOINT", "STOCKBUCKS_BROKER_LOGIN_ENDPOINT");
        this.quoteEndpoint = EnvironmentConfig.first("", "BROKER_QUOTE_ENDPOINT", "STOCKBUCKS_BROKER_QUOTE_ENDPOINT");
        this.intradayBarsEndpoint = EnvironmentConfig.first("", "BROKER_INTRADAY_BARS_ENDPOINT", "STOCKBUCKS_BROKER_INTRADAY_BARS_ENDPOINT");
        this.accountEndpoint = EnvironmentConfig.first("", "BROKER_ACCOUNT_ENDPOINT", "STOCKBUCKS_BROKER_ACCOUNT_ENDPOINT");
        this.positionsEndpoint = EnvironmentConfig.first("", "BROKER_POSITIONS_ENDPOINT", "STOCKBUCKS_BROKER_POSITIONS_ENDPOINT");
        this.authTokenField = EnvironmentConfig.first("token", "BROKER_AUTH_TOKEN_FIELD", "STOCKBUCKS_BROKER_AUTH_TOKEN_FIELD");
        this.sessionToken = credentials.getAuthToken();
    }

    @Override
    public String getProviderName() {
        return "broker:" + credentials.getProvider();
    }

    @Override
    public boolean isConfigured() {
        return !getMissingApiKeyName().isBlank() ? false : !baseUrl.isBlank();
    }

    @Override
    public String getMissingApiKeyName() {
        if (baseUrl.isBlank()) {
            return "BROKER_BASE_URL";
        }
        if (credentials.getAuthToken().isBlank()
                && credentials.getApiKey().isBlank()
                && (credentials.getUsername().isBlank() || credentials.getPassword().isBlank())) {
            return "BROKER_AUTH_TOKEN or BROKER_API_KEY or BROKER_USERNAME/BROKER_PASSWORD";
        }
        return "";
    }

    @Override
    public StockQuote fetchQuote(String stockId) {
        if (quoteEndpoint.isBlank() || stockId == null || stockId.isBlank()) {
            return null;
        }
        String json = get(formatEndpoint(quoteEndpoint, stockId, "1h")); // 通用端點；之後可由專用券商 client 取代。
        double lastPrice = firstPrice(json, "lastPrice", "price", "close", "closePrice");
        if (lastPrice <= 0) {
            return null;
        }
        return new StockQuote(
                firstNonBlank(JsonText.value(json, "stockId"), JsonText.value(json, "symbol"), stockId),
                firstNonBlank(JsonText.value(json, "stockName"), JsonText.value(json, "name")),
                lastPrice,
                firstPrice(json, "open", "openPrice"),
                firstPrice(json, "high", "highPrice"),
                firstPrice(json, "low", "lowPrice"),
                JsonText.parseLong(firstNonBlank(JsonText.value(json, "volume"), JsonText.value(json, "tradeVolume"))),
                getProviderName()
        );
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
        if (!quoteEndpoint.isBlank()) {
            endpoints.put("brokerQuote", absoluteUrl(quoteEndpoint));
        }
        if (!intradayBarsEndpoint.isBlank()) {
            endpoints.put("brokerIntradayBars", absoluteUrl(intradayBarsEndpoint));
        }
        if (!accountEndpoint.isBlank()) {
            endpoints.put("brokerAccount", absoluteUrl(accountEndpoint));
        }
        if (!positionsEndpoint.isBlank()) {
            endpoints.put("brokerPositions", absoluteUrl(positionsEndpoint));
        }
        return endpoints;
    }

    @Override
    public boolean isBrokerConfigured() {
        return isConfigured();
    }

    @Override
    public String getMissingBrokerConfigName() {
        return getMissingApiKeyName();
    }

    @Override
    public BrokerAccountSnapshot fetchAccountSnapshot() {
        if (accountEndpoint.isBlank()) {
            return null;
        }
        // 通用格式只能猜常見欄位；正式券商整合建議寫專用 client。
        String json = get(accountEndpoint);
        List<BrokerPosition> positions = fetchPositions();
        double marketValue = firstPrice(json, "marketValue", "stockMarketValue", "positionValue");
        if (marketValue <= 0 && !positions.isEmpty()) {
            marketValue = positions.stream().mapToDouble(BrokerPosition::getMarketValue).sum();
        }
        double cash = firstPrice(json, "cashBalance", "cash", "availableCash");
        double totalEquity = firstPrice(json, "totalEquity", "equity", "netWorth");
        if (totalEquity <= 0) {
            totalEquity = cash + marketValue;
        }
        return new BrokerAccountSnapshot(
                firstNonBlank(JsonText.value(json, "accountId"), JsonText.value(json, "account")),
                cash,
                marketValue,
                totalEquity,
                positions,
                getProviderName()
        );
    }

    @Override
    public List<BrokerPosition> fetchPositions() {
        if (positionsEndpoint.isBlank()) {
            return List.of();
        }
        String json = get(positionsEndpoint);
        List<BrokerPosition> positions = new ArrayList<>();
        for (String objectText : JsonText.objects(json)) {
            String stockId = firstNonBlank(JsonText.value(objectText, "stockId"), JsonText.value(objectText, "symbol"));
            int quantity = (int) JsonText.parseLong(firstNonBlank(JsonText.value(objectText, "quantity"), JsonText.value(objectText, "qty")));
            if (stockId.isBlank() || quantity == 0) {
                continue;
            }
            positions.add(new BrokerPosition(
                    stockId,
                    firstNonBlank(JsonText.value(objectText, "stockName"), JsonText.value(objectText, "name")),
                    quantity,
                    firstPrice(objectText, "averagePrice", "avgPrice", "costPrice"),
                    firstPrice(objectText, "marketValue", "value"),
                    getProviderName()
            ));
        }
        return positions;
    }

    @Override
    public List<IntradayBar> fetchIntradayBars(String stockId, String interval) {
        if (intradayBarsEndpoint.isBlank() || stockId == null || stockId.isBlank()) {
            return List.of();
        }
        // interval 例如 1m、5m、1h，實際支援程度取決於券商 API。
        String json = get(formatEndpoint(intradayBarsEndpoint, stockId, interval == null ? "1h" : interval));
        List<IntradayBar> bars = new ArrayList<>();
        for (String objectText : JsonText.objects(json)) {
            double close = firstPrice(objectText, "close", "closePrice");
            if (close <= 0) {
                continue;
            }
            bars.add(new IntradayBar(
                    stockId,
                    parseDateTime(firstNonBlank(JsonText.value(objectText, "time"), JsonText.value(objectText, "timestamp"), JsonText.value(objectText, "dateTime"))),
                    firstPrice(objectText, "open", "openPrice"),
                    firstPrice(objectText, "high", "highPrice"),
                    firstPrice(objectText, "low", "lowPrice"),
                    close,
                    JsonText.parseLong(firstNonBlank(JsonText.value(objectText, "volume"), JsonText.value(objectText, "tradeVolume"))),
                    getProviderName()
            ));
        }
        return bars;
    }

    private String get(String endpoint) {
        return send("GET", endpoint, "");
    }

    private String send(String method, String endpoint, String body) {
        ensureSessionToken();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(absoluteUrl(endpoint)))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "StockBucks/1.0");
        addAuthHeaders(builder);

        HttpRequest request = "POST".equalsIgnoreCase(method)
                ? builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build()
                : builder.GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Broker HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch broker data: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Broker fetch interrupted", e);
        }
    }

    private void ensureSessionToken() {
        if (!sessionToken.isBlank() || loginEndpoint.isBlank() || credentials.getUsername().isBlank() || credentials.getPassword().isBlank()) {
            return;
        }
        // 若使用帳密流程，第一次請求前先登入並把 token 留在記憶體中。
        String body = """
                {
                  "username": %s,
                  "password": %s
                }
                """.formatted(toJsonString(credentials.getUsername()), toJsonString(credentials.getPassword()));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(absoluteUrl(loginEndpoint)))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "StockBucks/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Broker login HTTP " + response.statusCode() + ": " + response.body());
            }
            sessionToken = JsonText.value(response.body(), authTokenField);
        } catch (IOException e) {
            throw new RuntimeException("Failed to login broker API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Broker login interrupted", e);
        }
    }

    private void addAuthHeaders(HttpRequest.Builder builder) {
        if (!sessionToken.isBlank()) {
            builder.header("Authorization", "Bearer " + sessionToken);
        }
        if (!credentials.getApiKey().isBlank()) {
            builder.header("X-API-Key", credentials.getApiKey());
        }
    }

    private String absoluteUrl(String endpoint) {
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return endpoint;
        }
        String path = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return baseUrl + path;
    }

    private String formatEndpoint(String endpoint, String stockId, String interval) {
        return endpoint
                .replace("{symbol}", urlEncode(stockId))
                .replace("{stockId}", urlEncode(stockId))
                .replace("{interval}", urlEncode(interval == null ? "1h" : interval));
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private double firstPrice(String json, String... fields) {
        for (String field : fields) {
            double value = JsonText.parseDouble(JsonText.value(json, field));
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        String normalized = value.replace("Z", "").replace("T", " ");
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        )) {
            try {
                return LocalDateTime.parse(normalized, formatter);
            } catch (RuntimeException ignored) {
                // Try the next common broker timestamp shape.
            }
        }
        return LocalDateTime.now();
    }

    private String toJsonString(String text) {
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }
}
