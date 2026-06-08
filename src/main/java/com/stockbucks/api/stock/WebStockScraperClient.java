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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公開網頁股票報價爬蟲。
 *
 * 這個來源只讀公開頁面，不處理登入、驗證碼、付費牆或封鎖內容。
 * HTML 結構可能改版，所以它適合當近即時報價備援，不適合當唯一資料來源。
 */
public class WebStockScraperClient implements StockDataClient {
    private static final String DEFAULT_GOOGLE_TEMPLATE = "https://www.google.com/finance/quote/%s:TPE?hl=zh-TW";
    private static final String DEFAULT_YAHOO_TEMPLATE = "https://tw.stock.yahoo.com/quote/%s.TW";
    private static final String DEFAULT_YAHOO_CHART_TEMPLATE = "https://query1.finance.yahoo.com/v8/finance/chart/%s.TW?period1=%d&period2=%d&interval=1d";
    private static final String DEFAULT_YAHOO_INTRADAY_TEMPLATE = "https://query1.finance.yahoo.com/v8/finance/chart/%s.TW?period1=%d&period2=%d&interval=%s";
    private static final String DEFAULT_CNBC_TEMPLATE = "https://www.cnbc.com/quotes/%s.TW";
    private static final String DEFAULT_WANTGOO_TEMPLATE = "https://www.wantgoo.com/stock/%s";

    private final HttpClient httpClient;
    private final String sourceOrder; // 例如 google,yahoo,cnbc,msn,wantgoo。
    private final String googleTemplate; // Google Finance 公開報價頁。
    private final String yahooTemplate; // Yahoo 股市公開報價頁。
    private final String yahooChartTemplate; // Yahoo chart JSON，用來補 web 歷史日 K。
    private final String yahooIntradayTemplate; // Yahoo chart JSON，用來補 web 盤中 K。
    private final String cnbcTemplate; // CNBC 公開報價頁。
    private final String msnTemplate; // 若團隊有穩定 MSN URL 模板，可填這裡。
    private final String wantgooTemplate; // WantGoo 公開股票頁。
    private final String userAgent; // 使用一般瀏覽器 UA，不繞過登入或驗證。

    public WebStockScraperClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.sourceOrder = EnvironmentConfig.first("google,yahoo,cnbc,msn,wantgoo", "WEB_STOCK_SOURCES", "STOCKBUCKS_WEB_STOCK_SOURCES");
        this.googleTemplate = EnvironmentConfig.first(DEFAULT_GOOGLE_TEMPLATE, "WEB_STOCK_GOOGLE_URL_TEMPLATE", "STOCKBUCKS_WEB_STOCK_GOOGLE_URL_TEMPLATE");
        this.yahooTemplate = EnvironmentConfig.first(DEFAULT_YAHOO_TEMPLATE, "WEB_STOCK_YAHOO_URL_TEMPLATE", "STOCKBUCKS_WEB_STOCK_YAHOO_URL_TEMPLATE");
        this.yahooChartTemplate = EnvironmentConfig.first(DEFAULT_YAHOO_CHART_TEMPLATE, "WEB_STOCK_YAHOO_CHART_URL_TEMPLATE", "STOCKBUCKS_WEB_STOCK_YAHOO_CHART_URL_TEMPLATE");
        this.yahooIntradayTemplate = EnvironmentConfig.first(DEFAULT_YAHOO_INTRADAY_TEMPLATE, "WEB_STOCK_YAHOO_INTRADAY_URL_TEMPLATE", "STOCKBUCKS_WEB_STOCK_YAHOO_INTRADAY_URL_TEMPLATE");
        this.cnbcTemplate = EnvironmentConfig.first(DEFAULT_CNBC_TEMPLATE, "WEB_STOCK_CNBC_URL_TEMPLATE", "STOCKBUCKS_WEB_STOCK_CNBC_URL_TEMPLATE");
        this.msnTemplate = EnvironmentConfig.first("", "WEB_STOCK_MSN_URL_TEMPLATE", "STOCKBUCKS_WEB_STOCK_MSN_URL_TEMPLATE");
        this.wantgooTemplate = EnvironmentConfig.first(DEFAULT_WANTGOO_TEMPLATE, "WEB_STOCK_WANTGOO_URL_TEMPLATE", "STOCKBUCKS_WEB_STOCK_WANTGOO_URL_TEMPLATE");
        this.userAgent = EnvironmentConfig.first(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36 StockBucks/1.0",
                "WEB_STOCK_USER_AGENT",
                "STOCKBUCKS_WEB_STOCK_USER_AGENT"
        );
    }

    @Override
    public String getProviderName() {
        return "web";
    }

    @Override
    public boolean isConfigured() {
        return !googleTemplate.isBlank()
                || !yahooTemplate.isBlank()
                || !cnbcTemplate.isBlank()
                || !msnTemplate.isBlank()
                || !wantgooTemplate.isBlank();
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

        RuntimeException lastFailure = null;
        for (String source : sourceOrder.split(",")) {
            String normalized = source.trim().toLowerCase();
            if (normalized.isBlank()) {
                continue;
            }
            try {
                StockQuote quote = switch (normalized) {
                    case "google", "google-finance" -> fetchGoogleFinanceQuote(stockId);
                    case "yahoo", "yahoo-tw", "yahoo-finance" -> fetchYahooQuote(stockId);
                    case "cnbc" -> fetchCnbcQuote(stockId);
                    case "msn", "msn-money" -> fetchMsnQuote(stockId);
                    case "wantgoo" -> fetchWantGooQuote(stockId);
                    default -> null;
                };
                if (quote != null) {
                    return quote;
                }
            } catch (RuntimeException ex) {
                lastFailure = ex;
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }
        return null;
    }

    @Override
    public List<StockData> fetchDailyHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        return fetchYahooChartHistory(stockId, fromDate, toDate);
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
        endpoints.put("googleFinanceQuote", googleTemplate);
        endpoints.put("yahooTwQuote", yahooTemplate);
        endpoints.put("yahooChartHistory", yahooChartTemplate);
        endpoints.put("yahooIntradayBars", yahooIntradayTemplate);
        endpoints.put("cnbcQuote", cnbcTemplate);
        if (!msnTemplate.isBlank()) {
            endpoints.put("msnMoneyQuote", msnTemplate);
        }
        endpoints.put("wantgooQuote", wantgooTemplate);
        return endpoints;
    }

    public String fetchRawPage(String url) {
        return get(url);
    }

    public List<IntradayBar> fetchIntradayBars(String stockId, String interval) {
        return fetchYahooChartIntraday(stockId, interval);
    }

    private StockQuote fetchGoogleFinanceQuote(String stockId) {
        if (googleTemplate.isBlank()) {
            return null;
        }

        String html = get(formatTemplate(googleTemplate, stockId));
        String name = firstNonBlank(
                extractByClass(html, "zzDege"),
                extractMeta(html, "og:title")
        );
        double price = firstPositivePrice(
                extractByClass(html, "YMlKec fxKbKc"),
                firstRegex(html, "\"price\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]+)?)\"?")
        );
        return price <= 0 ? null : new StockQuote(stockId, cleanStockName(name), price, 0, 0, 0, 0, "web:google");
    }

    private StockQuote fetchYahooQuote(String stockId) {
        if (yahooTemplate.isBlank()) {
            return null;
        }

        String html = get(formatTemplate(yahooTemplate, stockId));
        String name = firstNonBlank(
                extractMeta(html, "og:title"),
                firstRegex(html, "<h1[^>]*>(.*?)</h1>")
        );
        double price = firstPositivePrice(
                firstRegex(html, "\"regularMarketPrice\"\\s*:\\s*\\{\\s*\"raw\"\\s*:\\s*([0-9,]+(?:\\.[0-9]+)?)"),
                firstRegex(html, "<fin-streamer[^>]+data-field=[\"']regularMarketPrice[\"'][^>]*>(.*?)</fin-streamer>")
        );
        return price <= 0 ? null : new StockQuote(stockId, cleanStockName(name), price, 0, 0, 0, 0, "web:yahoo");
    }

    private StockQuote fetchCnbcQuote(String stockId) {
        if (cnbcTemplate.isBlank()) {
            return null;
        }

        String html = get(formatTemplate(cnbcTemplate, stockId));
        if (isCnbcNotFoundPage(html)) {
            return null;
        }
        String name = firstNonBlank(
                extractMeta(html, "og:title"),
                firstRegex(html, "\"name\"\\s*:\\s*\"([^\"]+)\"")
        );
        double price = firstPositivePrice(
                firstRegex(html, "QuoteStrip-lastPrice[^>]*>\\s*([0-9,]+(?:\\.[0-9]+)?)"),
                firstRegex(html, "\"last\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]+)?)\"?")
        );
        return price <= 0 ? null : new StockQuote(stockId, cleanStockName(name), price, 0, 0, 0, 0, "web:cnbc");
    }

    private StockQuote fetchMsnQuote(String stockId) {
        if (msnTemplate.isBlank()) {
            return null;
        }

        String html = get(formatTemplate(msnTemplate, stockId));
        String name = firstNonBlank(
                extractMeta(html, "og:title"),
                firstRegex(html, "\"name\"\\s*:\\s*\"([^\"]+)\"")
        );
        double price = firstPositivePrice(
                firstRegex(html, "\"lastPrice\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]+)?)\"?"),
                firstRegex(html, "\"price\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]+)?)\"?"),
                firstRegex(html, "\"last\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]+)?)\"?")
        );
        return price <= 0 ? null : new StockQuote(stockId, cleanStockName(name), price, 0, 0, 0, 0, "web:msn");
    }

    private StockQuote fetchWantGooQuote(String stockId) {
        if (wantgooTemplate.isBlank()) {
            return null;
        }

        String html = get(formatTemplate(wantgooTemplate, stockId));
        String name = firstNonBlank(
                extractMeta(html, "og:title"),
                firstRegex(html, "<title[^>]*>(.*?)</title>")
        );
        double price = firstPositivePrice(
                firstRegex(html, "\"closePrice\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]+)?)\"?"),
                firstRegex(html, "\"lastPrice\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]+)?)\"?"),
                firstRegex(html, "\"latestPrice\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]+)?)\"?")
        );
        return price <= 0 ? null : new StockQuote(stockId, cleanStockName(name), price, 0, 0, 0, 0, "web:wantgoo");
    }

    private List<StockData> fetchYahooChartHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        if (stockId == null || stockId.isBlank() || yahooChartTemplate.isBlank()) {
            return List.of();
        }

        LocalDate start = fromDate == null ? LocalDate.now().minusYears(1) : fromDate;
        LocalDate end = toDate == null ? LocalDate.now() : toDate;
        long period1 = start.atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        long period2 = end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        String symbol = stockId.replaceAll("\\.(TW|TWO|TPE)$", "");
        String url = yahooChartTemplate.formatted(URLEncoder.encode(symbol, StandardCharsets.UTF_8), period1, period2);
        String json = get(url);
        if (json.isBlank() || json.contains("\"error\":{\"code\"")) {
            return List.of();
        }

        List<String> timestamps = csvArrayAfter(json, "\"timestamp\"");
        String quoteObject = firstRegex(json, "\"quote\"\\s*:\\s*\\[\\s*\\{(.*?)\\}\\s*\\]");
        List<String> opens = csvArrayAfter(quoteObject, "\"open\"");
        List<String> highs = csvArrayAfter(quoteObject, "\"high\"");
        List<String> lows = csvArrayAfter(quoteObject, "\"low\"");
        List<String> closes = csvArrayAfter(quoteObject, "\"close\"");
        List<String> volumes = csvArrayAfter(quoteObject, "\"volume\"");

        int rows = minSize(timestamps, opens, highs, lows, closes, volumes);
        List<StockData> result = new ArrayList<>();
        double previousClose = 0;
        for (int i = 0; i < rows; i++) {
            if (isNullPrice(opens.get(i)) || isNullPrice(highs.get(i)) || isNullPrice(lows.get(i)) || isNullPrice(closes.get(i))) {
                continue;
            }

            LocalDate date = Instant.ofEpochSecond(Long.parseLong(timestamps.get(i).trim()))
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
            if (date.isBefore(start) || date.isAfter(end)) {
                continue;
            }

            double close = JsonText.parseDouble(closes.get(i));
            double change = previousClose <= 0 ? 0 : close - previousClose;
            previousClose = close;
            result.add(new StockData(
                    symbol,
                    "",
                    date.toString(),
                    opens.get(i),
                    highs.get(i),
                    lows.get(i),
                    closes.get(i),
                    volumes.get(i),
                    "0",
                    "0",
                    String.valueOf(change)
            ));
        }
        return result;
    }

    private List<IntradayBar> fetchYahooChartIntraday(String stockId, String interval) {
        if (stockId == null || stockId.isBlank() || yahooIntradayTemplate.isBlank()) {
            return List.of();
        }

        String yahooInterval = normalizeYahooInterval(interval);
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(intradayLookbackDays(yahooInterval));
        long period1 = start.atZone(ZoneId.systemDefault()).toEpochSecond();
        long period2 = end.atZone(ZoneId.systemDefault()).toEpochSecond();
        String symbol = stockId.replaceAll("\\.(TW|TWO|TPE)$", "");
        String url = yahooIntradayTemplate.formatted(
                URLEncoder.encode(symbol, StandardCharsets.UTF_8),
                period1,
                period2,
                yahooInterval
        );
        String json = get(url);
        if (json.isBlank() || json.contains("\"error\":{\"code\"")) {
            return List.of();
        }

        List<String> timestamps = csvArrayAfter(json, "\"timestamp\"");
        String quoteObject = firstRegex(json, "\"quote\"\\s*:\\s*\\[\\s*\\{(.*?)\\}\\s*\\]");
        List<String> opens = csvArrayAfter(quoteObject, "\"open\"");
        List<String> highs = csvArrayAfter(quoteObject, "\"high\"");
        List<String> lows = csvArrayAfter(quoteObject, "\"low\"");
        List<String> closes = csvArrayAfter(quoteObject, "\"close\"");
        List<String> volumes = csvArrayAfter(quoteObject, "\"volume\"");

        int rows = minSize(timestamps, opens, highs, lows, closes, volumes);
        List<IntradayBar> result = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            if (isNullPrice(opens.get(i)) || isNullPrice(highs.get(i)) || isNullPrice(lows.get(i)) || isNullPrice(closes.get(i))) {
                continue;
            }

            LocalDateTime time = Instant.ofEpochSecond(Long.parseLong(timestamps.get(i).trim()))
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
            result.add(new IntradayBar(
                    symbol,
                    time,
                    JsonText.parseDouble(opens.get(i)),
                    JsonText.parseDouble(highs.get(i)),
                    JsonText.parseDouble(lows.get(i)),
                    JsonText.parseDouble(closes.get(i)),
                    JsonText.parseLong(volumes.get(i)),
                    "web:yahoo-chart"
            ));
        }
        return result;
    }

    private String normalizeYahooInterval(String interval) {
        String normalized = interval == null || interval.isBlank() ? "1m" : interval.trim().toLowerCase();
        return switch (normalized) {
            case "1", "1m", "minute" -> "1m";
            case "5", "5m" -> "5m";
            case "15", "15m" -> "15m";
            case "30", "30m" -> "30m";
            case "60", "60m", "1h", "hour" -> "1h";
            default -> "1m";
        };
    }

    private int intradayLookbackDays(String interval) {
        // Yahoo 盤中資料在假日或收盤後可能切換保留區間，抓寬一點比較容易拿到最近交易日。
        return switch (interval) {
            case "1m" -> 5;
            case "5m", "15m", "30m" -> 10;
            default -> 30;
        };
    }

    private String get(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/html,application/xhtml+xml,application/xml,application/json")
                .header("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.7")
                .header("User-Agent", userAgent)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 404) {
                return "";
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Web stock HTTP " + response.statusCode()
                        + " at " + request.uri()
                        + ": " + summarizeResponseBody(response.body()));
            }
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch web stock page: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Web stock page fetch interrupted", e);
        }
    }

    private boolean isCnbcNotFoundPage(String html) {
        String lower = html == null ? "" : html.toLowerCase();
        return lower.contains("404|not-found")
                || lower.contains("do not delete - 404 page")
                || lower.contains("page you were looking for cannot be found");
    }

    private String summarizeResponseBody(String body) {
        String cleaned = cleanText(body);
        if (cleaned.isBlank()) {
            return "empty response body";
        }
        return cleaned.length() > 160 ? cleaned.substring(0, 160) + "..." : cleaned;
    }

    private String formatTemplate(String template, String stockId) {
        String plainStockId = stockId.replaceAll("\\.(TW|TWO|TPE)$", "");
        String encoded = URLEncoder.encode(plainStockId, StandardCharsets.UTF_8);
        if (template.contains("%s")) {
            return template.formatted(encoded);
        }
        return template + encoded;
    }

    private String extractByClass(String html, String className) {
        String escapedClass = Pattern.quote(className);
        String pattern = "<[^>]*class=[\"'][^\"']*" + escapedClass + "[^\"']*[\"'][^>]*>(.*?)</[^>]+>";
        return cleanText(firstRegex(html, pattern));
    }

    private String extractMeta(String html, String propertyName) {
        String property = Pattern.quote(propertyName);
        String pattern = "<meta[^>]+(?:property|name)=[\"']" + property + "[\"'][^>]+content=[\"']([^\"']+)[\"'][^>]*>";
        String value = firstRegex(html, pattern);
        if (!value.isBlank()) {
            return value;
        }
        String reversePattern = "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']" + property + "[\"'][^>]*>";
        return firstRegex(html, reversePattern);
    }

    private List<String> csvArrayAfter(String text, String key) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Pattern pattern = Pattern.compile(Pattern.quote(key) + "\\s*:\\s*\\[(.*?)\\]", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String value : matcher.group(1).split(",")) {
            values.add(value.trim());
        }
        return values;
    }

    @SafeVarargs
    private final int minSize(List<String>... lists) {
        int min = Integer.MAX_VALUE;
        for (List<String> list : lists) {
            min = Math.min(min, list.size());
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private boolean isNullPrice(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value.trim());
    }

    private String firstRegex(String text, String patternText) {
        Pattern pattern = Pattern.compile(patternText, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? htmlDecode(matcher.group(1)) : "";
    }

    private double firstPositivePrice(String... values) {
        for (String value : values) {
            double price = parsePrice(value);
            if (price > 0) {
                return price;
            }
        }
        return 0;
    }

    private double parsePrice(String value) {
        String cleaned = JsonText.cleanNumber(cleanText(value));
        return cleaned.isBlank() ? 0 : Double.parseDouble(cleaned);
    }

    private String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return htmlDecode(value)
                .replaceAll("<[^>]+>", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String cleanStockName(String value) {
        String cleaned = cleanText(value);
        if (cleaned.isBlank() || isMarketingTitle(cleaned)) {
            return ""; // 名稱不可靠時留空，避免把網頁宣傳標題誤當股票名稱。
        }

        String withoutSuffix = cleaned
                .replaceAll("\\s*[-|｜].*$", "")
                .replaceAll("\\s*股價.*$", "")
                .replaceAll("\\s*Stock Price.*$", "")
                .replaceAll("\\s*Quote.*$", "")
                .trim();
        return withoutSuffix.length() > 40 ? "" : withoutSuffix;
    }

    private boolean isMarketingTitle(String value) {
        String lower = value.toLowerCase();
        return lower.contains("check out")
                || lower.contains("latest news")
                || lower.contains("stock quote")
                || lower.contains("cnbc")
                || lower.contains("finance")
                || lower.contains("yahoo")
                || lower.contains("wantgoo")
                || lower.contains("google");
    }

    private String htmlDecode(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&nbsp;", " ");
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }
}
