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
            String open = cleanNumber(fields.get(3));
            String high = cleanNumber(fields.get(4));
            String low = cleanNumber(fields.get(5));
            String close = cleanNumber(fields.get(6));

            if (!open.isBlank() && !high.isBlank() && !low.isBlank() && !close.isBlank()) {
                result.add(new StockData(stockId, date, open, high, low, close));
            }
        }

        return result;
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
        return value.replace(",", "").replace("--", "").trim();
    }

    private void pauseBetweenRequests() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
