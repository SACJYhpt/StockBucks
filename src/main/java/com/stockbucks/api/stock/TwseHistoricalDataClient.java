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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TWSE 公開資料來源。
 *
 * 適合抓公開日資料與市場快照，但不是券商等級的完整即時串流行情。
 */
public class TwseHistoricalDataClient implements StockDataClient {
    private static final DateTimeFormatter TWSE_REQUEST_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter STOCK_DATA_DATE = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('/')
            .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .appendLiteral('/')
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .toFormatter();

    private static final String DEFAULT_TWSE_WEB_BASE_URL = "https://www.twse.com.tw";
    private static final String DEFAULT_TWSE_OPENAPI_BASE_URL = "https://openapi.twse.com.tw/v1";

    private final HttpClient httpClient;
    private final String twseWebBaseUrl;
    private final String twseOpenApiBaseUrl;

    public TwseHistoricalDataClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.twseWebBaseUrl = EnvironmentConfig.first(DEFAULT_TWSE_WEB_BASE_URL, "TWSE_WEB_BASE_URL", "STOCKBUCKS_TWSE_WEB_BASE_URL");
        this.twseOpenApiBaseUrl = EnvironmentConfig.first(DEFAULT_TWSE_OPENAPI_BASE_URL, "TWSE_OPENAPI_BASE_URL", "STOCKBUCKS_TWSE_OPENAPI_BASE_URL");
    }

    public Map<String, String> supportedApiEndpoints() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("stockDayByMonth", twseWebBaseUrl + "/exchangeReport/STOCK_DAY?response=json&date=yyyyMMdd&stockNo=2330");
        endpoints.put("dailyAllStocks", twseOpenApiBaseUrl + "/exchangeReport/STOCK_DAY_ALL");
        endpoints.put("dailyAverageAllStocks", twseOpenApiBaseUrl + "/exchangeReport/STOCK_DAY_AVG_ALL");
        return endpoints;
    }

    @Override
    public String getProviderName() {
        return "twse";
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
        if (stockId == null || stockId.isBlank()) {
            return null;
        }
        for (StockData data : fetchDailyMarketAll()) {
            if (stockId.equals(data.getStockID())) {
                return StockQuote.fromStockData(data, getProviderName());
            }
        }
        return null;
    }

    public List<StockData> fetchDailyHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        if (stockId == null || stockId.isBlank() || fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
            return List.of();
        }

        // TWSE STOCK_DAY 以月份查詢，所以跨日期區間時逐月抓取。
        List<StockData> result = new ArrayList<>();
        YearMonth cursor = YearMonth.from(fromDate);
        YearMonth end = YearMonth.from(toDate);
        while (!cursor.isAfter(end)) {
            List<StockData> monthData = fetchMonth(stockId, cursor.atDay(1));
            for (StockData data : monthData) {
                LocalDate date = parseStockDataDate(data.getDate());
                if (!date.isBefore(fromDate) && !date.isAfter(toDate)) {
                    result.add(data);
                }
            }
            cursor = cursor.plusMonths(1);
            pauseBetweenRequests();
        }
        return result;
    }

    public List<StockData> fetchMonth(String stockId, LocalDate monthDate) {
        String uri = twseWebBaseUrl.replaceAll("/+$", "")
                + "/exchangeReport/STOCK_DAY"
                + "?response=json"
                + "&date=" + monthDate.format(TWSE_REQUEST_DATE)
                + "&stockNo=" + URLEncoder.encode(stockId, StandardCharsets.UTF_8);

        String json = get(uri);
        return parseStockDayResponse(stockId, json);
    }

    public List<StockData> fetchDailyMarketAll() {
        // STOCK_DAY_ALL 是公開日行情快照，適合當免費資料備援。
        String json = get(twseOpenApiBaseUrl.replaceAll("/+$", "") + "/exchangeReport/STOCK_DAY_ALL");
        List<StockData> result = new ArrayList<>();
        for (String objectText : extractJsonObjects(json)) {
            String stockId = firstNonBlank(extractJsonValue(objectText, "Code"), extractJsonValue(objectText, "證券代號"));
            String stockName = firstNonBlank(extractJsonValue(objectText, "Name"), extractJsonValue(objectText, "證券名稱"));
            String open = firstNonBlank(extractJsonValue(objectText, "OpeningPrice"), extractJsonValue(objectText, "開盤價"));
            String high = firstNonBlank(extractJsonValue(objectText, "HighestPrice"), extractJsonValue(objectText, "最高價"));
            String low = firstNonBlank(extractJsonValue(objectText, "LowestPrice"), extractJsonValue(objectText, "最低價"));
            String close = firstNonBlank(extractJsonValue(objectText, "ClosingPrice"), extractJsonValue(objectText, "收盤價"));
            String volume = firstNonBlank(extractJsonValue(objectText, "TradeVolume"), extractJsonValue(objectText, "成交股數"));
            String turnover = firstNonBlank(extractJsonValue(objectText, "TradeValue"), extractJsonValue(objectText, "成交金額"));
            String change = firstNonBlank(extractJsonValue(objectText, "Change"), extractJsonValue(objectText, "漲跌價差"));

            if (isListedSecurityCode(stockId) && hasPrice(open, high, low, close)) {
                result.add(new StockData(stockId, stockName, LocalDate.now().format(STOCK_DATA_DATE),
                        open, high, low, close, volume, turnover, "0", change));
            }
        }
        return result;
    }

    public List<StockProfile> fetchListedStockProfiles() {
        String json = get(twseOpenApiBaseUrl.replaceAll("/+$", "") + "/exchangeReport/STOCK_DAY_ALL");
        List<StockProfile> result = new ArrayList<>();
        for (String objectText : extractJsonObjects(json)) {
            String code = firstNonBlank(extractJsonValue(objectText, "Code"), extractJsonValue(objectText, "證券代號"));
            String name = firstNonBlank(extractJsonValue(objectText, "Name"), extractJsonValue(objectText, "證券名稱"));
            if (isListedSecurityCode(code)) {
                result.add(new StockProfile(code, name, "TWSE"));
            }
        }
        return result;
    }

    public String fetchRaw(String endpointPath) {
        String path = endpointPath == null ? "" : endpointPath.trim();
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return get(path);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return get(twseOpenApiBaseUrl.replaceAll("/+$", "") + path);
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
                throw new RuntimeException("TWSE HTTP " + response.statusCode() + ": " + summarizeResponseBody(response.body()));
            }
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch TWSE data: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("TWSE fetch interrupted", e);
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

    private List<StockData> parseStockDayResponse(String stockId, String json) {
        // TWSE STOCK_DAY 回傳的是二維字串陣列，這裡轉成 StockData。
        List<StockData> result = new ArrayList<>();
        String dataArray = extractJsonArray(json, "\"data\"");
        if (dataArray.isBlank()) {
            return result;
        }

        for (String row : extractRowArrays(dataArray)) {
            List<String> fields = extractStringFields(row);
            if (fields.size() < 7) {
                continue;
            }

            String date = convertRocDate(fields.get(0));
            String volume = cleanNumber(fields.get(1));
            String turnover = cleanNumber(fields.get(2));
            String open = cleanNumber(fields.get(3));
            String high = cleanNumber(fields.get(4));
            String low = cleanNumber(fields.get(5));
            String close = cleanNumber(fields.get(6));
            String priceChange = fields.size() > 7 ? cleanNumber(fields.get(7)) : "0";
            String transactionCount = fields.size() > 8 ? cleanNumber(fields.get(8)) : "0";

            if (hasPrice(open, high, low, close)) {
                result.add(new StockData(stockId, "", date, open, high, low, close, volume, turnover, transactionCount, priceChange));
            }
        }
        return result;
    }

    private boolean hasPrice(String open, String high, String low, String close) {
        return !cleanNumber(open).isBlank()
                && !cleanNumber(high).isBlank()
                && !cleanNumber(low).isBlank()
                && !cleanNumber(close).isBlank();
    }

    private boolean isListedSecurityCode(String code) {
        // TWSE 有 2330 這類普通股票，也有 0050、00881 這類 ETF；測試時都應列入。
        return code != null && code.matches("[0-9A-Z]{4,6}");
    }

    private String extractJsonValue(String objectText, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyIndex = objectText.indexOf(key);
        if (keyIndex < 0) {
            return "";
        }

        int colon = objectText.indexOf(':', keyIndex + key.length());
        int start = objectText.indexOf('"', colon);
        if (colon < 0 || start < 0) {
            return "";
        }

        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < objectText.length(); i++) {
            char c = objectText.charAt(i);
            if (escaped) {
                value.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                return value.toString();
            }
            value.append(c);
        }
        return "";
    }

    private List<String> extractJsonObjects(String json) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int objectStart = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    objects.add(json.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            }
        }
        return objects;
    }

    private String extractJsonArray(String json, String key) {
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            return "";
        }

        int start = json.indexOf('[', keyIndex);
        if (start < 0) {
            return "";
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private List<String> extractRowArrays(String arrayText) {
        List<String> rows = new ArrayList<>();
        int depth = 0;
        int rowStart = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < arrayText.length(); i++) {
            char c = arrayText.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '[') {
                depth++;
                if (depth == 2) {
                    rowStart = i;
                }
            } else if (c == ']') {
                if (depth == 2 && rowStart >= 0) {
                    rows.add(arrayText.substring(rowStart, i + 1));
                    rowStart = -1;
                }
                depth--;
            }
        }
        return rows;
    }

    private List<String> extractStringFields(String rowText) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < rowText.length(); i++) {
            char c = rowText.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                if (inString) {
                    fields.add(current.toString());
                    current.setLength(0);
                }
                inString = !inString;
                continue;
            }
            if (inString) {
                current.append(c);
            }
        }
        return fields;
    }

    private String convertRocDate(String rocDate) {
        String[] parts = rocDate.trim().split("/");
        if (parts.length != 3) {
            return rocDate.trim();
        }

        int year = Integer.parseInt(parts[0]) + 1911;
        int month = Integer.parseInt(parts[1]);
        int day = Integer.parseInt(parts[2]);
        return LocalDate.of(year, month, day).format(STOCK_DATA_DATE);
    }

    private LocalDate parseStockDataDate(String date) {
        return LocalDate.parse(date, STOCK_DATA_DATE);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }

    private String cleanNumber(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value
                .replace(",", "")
                .replace("+", "")
                .replace("--", "")
                .replaceAll("[^0-9.\\-]", "")
                .trim();
        if (cleaned.equals("-") || cleaned.equals(".")) {
            return "";
        }
        return cleaned;
    }

    private void pauseBetweenRequests() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
