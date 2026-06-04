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
    private static final String DEFAULT_CNBC_TEMPLATE = "https://www.cnbc.com/quotes/%s.TW";
    private static final String DEFAULT_WANTGOO_TEMPLATE = "https://www.wantgoo.com/stock/%s";

    private final HttpClient httpClient;
    private final String sourceOrder; // 例如 google,yahoo,cnbc,msn,wantgoo。
    private final String googleTemplate; // Google Finance 公開報價頁。
    private final String yahooTemplate; // Yahoo 股市公開報價頁。
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
        endpoints.put("googleFinanceQuote", googleTemplate);
        endpoints.put("yahooTwQuote", yahooTemplate);
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
        return price <= 0 ? null : new StockQuote(stockId, cleanText(name), price, 0, 0, 0, 0, "web:google");
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
                firstRegex(html, "<fin-streamer[^>]+data-field=[\"']regularMarketPrice[\"'][^>]*>(.*?)</fin-streamer>"),
                firstRegex(html, "\"price\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]+)?)\"?")
        );
        return price <= 0 ? null : new StockQuote(stockId, cleanText(name), price, 0, 0, 0, 0, "web:yahoo");
    }

    private StockQuote fetchCnbcQuote(String stockId) {
        if (cnbcTemplate.isBlank()) {
            return null;
        }

        String html = get(formatTemplate(cnbcTemplate, stockId));
        String name = firstNonBlank(
                extractMeta(html, "og:title"),
                firstRegex(html, "\"name\"\\s*:\\s*\"([^\"]+)\"")
        );
        double price = firstPositivePrice(
                firstRegex(html, "\"last\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]+)?)\"?"),
                firstRegex(html, "\"price\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]+)?)\"?"),
                firstRegex(html, "QuoteStrip-lastPrice[^>]*>\\s*([0-9,]+(?:\\.[0-9]+)?)")
        );
        return price <= 0 ? null : new StockQuote(stockId, cleanText(name), price, 0, 0, 0, 0, "web:cnbc");
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
        return price <= 0 ? null : new StockQuote(stockId, cleanText(name), price, 0, 0, 0, 0, "web:msn");
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
                firstRegex(html, "\"price\"\\s*:\\s*\"?([0-9,]+(?:\\.[0-9]+)?)\"?"),
                firstRegex(html, "成交價[^0-9]{0,80}([0-9,]+(?:\\.[0-9]+)?)")
        );
        return price <= 0 ? null : new StockQuote(stockId, cleanText(name), price, 0, 0, 0, 0, "web:wantgoo");
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
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Web stock HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch web stock page: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Web stock page fetch interrupted", e);
        }
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
                .replace("&nbsp;", " ");
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? (second == null ? "" : second) : first;
    }
}
