package com.stockbucks.ai.data;

import com.stockbucks.StockData;

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
import java.util.List;

public class TwseHistoricalDataClient {

    private static final DateTimeFormatter TWSE_REQUEST_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter STOCK_DATA_DATE = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('/')
            .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .appendLiteral('/')
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .toFormatter();
    private static final String STOCK_DAY_URL = "https://www.twse.com.tw/exchangeReport/STOCK_DAY";
    private static final String STOCK_DAY_ALL_URL = "https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL";

    private final HttpClient httpClient;

    public TwseHistoricalDataClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<StockData> fetchDailyHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        if (fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
            return List.of();
        }

        List<StockData> result = new ArrayList<>();
        YearMonth cursor = YearMonth.from(fromDate);
        YearMonth end = YearMonth.from(toDate);

        while (!cursor.isAfter(end)) {
            LocalDate requestDate = cursor.atDay(1);
            List<StockData> monthData = fetchMonth(stockId, requestDate);
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
        String uri = STOCK_DAY_URL
                + "?response=json"
                + "&date=" + monthDate.format(TWSE_REQUEST_DATE)
                + "&stockNo=" + URLEncoder.encode(stockId, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "StockBucks/1.0")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new RuntimeException("TWSE HTTP " + response.statusCode() + ": " + response.body());
            }
            return parseStockDayResponse(stockId, response.body());
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch TWSE data: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("TWSE fetch interrupted", e);
        }
    }

    public List<StockProfile> fetchListedStockProfiles() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(STOCK_DAY_ALL_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "StockBucks/1.0")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new RuntimeException("TWSE HTTP " + response.statusCode() + ": " + response.body());
            }
            return parseStockProfiles(response.body());
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch TWSE stock list: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("TWSE stock list fetch interrupted", e);
        }
    }

    private List<StockData> parseStockDayResponse(String stockId, String json) {
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

            if (!open.isBlank() && !high.isBlank() && !low.isBlank() && !close.isBlank()) {
                result.add(new StockData(stockId, "", date, open, high, low, close, volume, turnover, transactionCount, priceChange));
            }
        }

        return result;
    }

    private List<StockProfile> parseStockProfiles(String json) {
        List<StockProfile> result = new ArrayList<>();
        for (String objectText : extractJsonObjects(json)) {
            String code = firstNonBlank(
                    extractJsonValue(objectText, "Code"),
                    extractJsonValue(objectText, "證券代號")
            );
            String name = firstNonBlank(
                    extractJsonValue(objectText, "Name"),
                    extractJsonValue(objectText, "證券名稱")
            );
            if (isListedCommonStockCode(code)) {
                result.add(new StockProfile(code, name, "TWSE"));
            }
        }
        return result;
    }

    private boolean isListedCommonStockCode(String code) {
        return code != null && code.matches("[1-9][0-9]{3}");
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

    private String extractJsonValue(String objectText, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyIndex = objectText.indexOf(key);
        if (keyIndex < 0) {
            return "";
        }

        int colon = objectText.indexOf(':', keyIndex + key.length());
        if (colon < 0) {
            return "";
        }

        int start = objectText.indexOf('"', colon);
        if (start < 0) {
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

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
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
