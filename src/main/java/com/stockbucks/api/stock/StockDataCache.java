package com.stockbucks.api.stock;

import com.stockbucks.StockData;
import com.stockbucks.api.config.EnvironmentConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API 層的股票資料快取。
 * 記憶體快取用來加速同一次執行，檔案快取用來讓下次啟動也能復用資料。
 */
public class StockDataCache {
    private static final String SEP = "\t";

    private final boolean enabled;
    private final Path cacheDir;
    private final Duration quoteTtl;
    private final Duration intradayTtl;
    private final Duration historyTtl;
    private final Map<String, CacheEntry<StockQuote>> quoteMemory = new LinkedHashMap<>();
    private final Map<String, CacheEntry<List<IntradayBar>>> intradayMemory = new LinkedHashMap<>();
    private final Map<String, CacheEntry<List<StockData>>> historyMemory = new LinkedHashMap<>();

    public StockDataCache() {
        this.enabled = Boolean.parseBoolean(EnvironmentConfig.first("true", "STOCK_CACHE_ENABLED", "STOCKBUCKS_STOCK_CACHE_ENABLED"));
        this.cacheDir = Path.of(EnvironmentConfig.first("data/api_cache", "STOCK_CACHE_DIR", "STOCKBUCKS_STOCK_CACHE_DIR")).toAbsolutePath().normalize();
        this.quoteTtl = Duration.ofSeconds(parseLong(EnvironmentConfig.first("30", "STOCK_CACHE_QUOTE_TTL_SECONDS", "STOCKBUCKS_STOCK_CACHE_QUOTE_TTL_SECONDS"), 30));
        this.intradayTtl = Duration.ofSeconds(parseLong(EnvironmentConfig.first("300", "STOCK_CACHE_INTRADAY_TTL_SECONDS", "STOCKBUCKS_STOCK_CACHE_INTRADAY_TTL_SECONDS"), 300));
        this.historyTtl = Duration.ofSeconds(parseLong(EnvironmentConfig.first("86400", "STOCK_CACHE_HISTORY_TTL_SECONDS", "STOCKBUCKS_STOCK_CACHE_HISTORY_TTL_SECONDS"), 86400));
        ensureCacheDir();
    }

    public Optional<StockQuote> getQuote(String stockId) {
        if (!enabled) {
            return Optional.empty();
        }
        String key = normalize(stockId);
        CacheEntry<StockQuote> memory = quoteMemory.get(key);
        if (memory != null && !memory.isExpired(quoteTtl)) {
            return Optional.of(memory.value());
        }
        Optional<StockQuote> fileValue = readQuote(key);
        fileValue.ifPresent(value -> quoteMemory.put(key, new CacheEntry<>(value, LocalDateTime.now())));
        return fileValue;
    }

    public void putQuote(StockQuote quote) {
        if (!enabled || quote == null || quote.getStockId().isBlank()) {
            return;
        }
        String key = normalize(quote.getStockId());
        quoteMemory.put(key, new CacheEntry<>(quote, LocalDateTime.now()));
        writeLines(quoteFile(key), List.of(String.join(SEP,
                escape(quote.getStockId()),
                escape(quote.getStockName()),
                String.valueOf(quote.getLastPrice()),
                String.valueOf(quote.getOpenPrice()),
                String.valueOf(quote.getHighPrice()),
                String.valueOf(quote.getLowPrice()),
                String.valueOf(quote.getVolume()),
                escape(quote.getProvider()),
                quote.getFetchedAt().toString()
        )));
    }

    public Optional<List<StockData>> getHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        if (!enabled) {
            return Optional.empty();
        }
        String key = historyKey(stockId, fromDate, toDate);
        CacheEntry<List<StockData>> memory = historyMemory.get(key);
        if (memory != null && !memory.isExpired(historyTtl)) {
            return Optional.of(memory.value());
        }
        Optional<List<StockData>> fileValue = readHistory(stockId, fromDate, toDate);
        fileValue.ifPresent(value -> historyMemory.put(key, new CacheEntry<>(value, LocalDateTime.now())));
        return fileValue;
    }

    public void putHistory(String stockId, LocalDate fromDate, LocalDate toDate, List<StockData> rows) {
        if (!enabled || stockId == null || stockId.isBlank() || rows == null || rows.isEmpty()) {
            return;
        }
        String key = historyKey(stockId, fromDate, toDate);
        List<StockData> copy = List.copyOf(rows);
        historyMemory.put(key, new CacheEntry<>(copy, LocalDateTime.now()));
        List<StockData> merged = mergeHistoryRows(readAllHistory(stockId), copy);
        List<String> lines = new ArrayList<>();
        lines.add("# stockId\tstockName\tdate\topen\thigh\tlow\tclose\tvolume\tturnover\ttransactionCount\tpriceChange");
        for (StockData row : merged) {
            lines.add(String.join(SEP,
                    escape(row.getStockID()),
                    escape(row.getStockName()),
                    escape(row.getDate()),
                    String.valueOf(row.getOpen()),
                    String.valueOf(row.getHigh()),
                    String.valueOf(row.getLow()),
                    String.valueOf(row.getClose()),
                    String.valueOf(row.getVolume()),
                    String.valueOf(row.getTurnover()),
                    String.valueOf(row.getTransactionCount()),
                    String.valueOf(row.getPriceChange())
            ));
        }
        writeLines(historyFile(stockId), lines);
    }

    public Optional<List<IntradayBar>> getIntraday(String stockId, String interval) {
        if (!enabled) {
            return Optional.empty();
        }
        String key = intradayKey(stockId, interval);
        CacheEntry<List<IntradayBar>> memory = intradayMemory.get(key);
        if (memory != null && !memory.isExpired(intradayTtl)) {
            return Optional.of(memory.value());
        }
        Optional<List<IntradayBar>> fileValue = readIntraday(key);
        fileValue.ifPresent(value -> intradayMemory.put(key, new CacheEntry<>(value, LocalDateTime.now())));
        return fileValue;
    }

    public void putIntraday(String stockId, String interval, List<IntradayBar> bars) {
        if (!enabled || stockId == null || stockId.isBlank() || bars == null || bars.isEmpty()) {
            return;
        }
        String key = intradayKey(stockId, interval);
        List<IntradayBar> copy = bars.stream()
                .sorted(Comparator.comparing(IntradayBar::getTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        intradayMemory.put(key, new CacheEntry<>(copy, LocalDateTime.now()));
        List<String> lines = new ArrayList<>();
        lines.add("# stockId\ttime\topen\thigh\tlow\tclose\tvolume\tprovider");
        for (IntradayBar bar : copy) {
            lines.add(String.join(SEP,
                    escape(bar.getStockId()),
                    bar.getTime() == null ? "" : bar.getTime().toString(),
                    String.valueOf(bar.getOpen()),
                    String.valueOf(bar.getHigh()),
                    String.valueOf(bar.getLow()),
                    String.valueOf(bar.getClose()),
                    String.valueOf(bar.getVolume()),
                    escape(bar.getProvider())
            ));
        }
        writeLines(intradayFile(key), lines);
    }

    public String status() {
        return "cache " + (enabled ? "enabled" : "disabled")
                + " | dir=" + cacheDir
                + " | quoteTTL=" + quoteTtl.toSeconds() + "s"
                + " | intradayTTL=" + intradayTtl.toSeconds() + "s"
                + " | historyTTL=" + historyTtl.toSeconds() + "s"
                + " | history=partial-by-stock";
    }

    public Path cacheDir() {
        return cacheDir;
    }

    private Optional<StockQuote> readQuote(String key) {
        List<String> lines = readLines(quoteFile(key));
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = split(lines.get(lines.size() - 1));
        if (parts.length < 8) {
            return Optional.empty();
        }
        return Optional.of(new StockQuote(parts[0], parts[1],
                parseDouble(parts[2]), parseDouble(parts[3]), parseDouble(parts[4]), parseDouble(parts[5]),
                parseLong(parts[6], 0), parts[7]));
    }

    private Optional<List<StockData>> readHistory(String stockId, LocalDate fromDate, LocalDate toDate) {
        List<StockData> filtered = readAllHistory(stockId)
                .stream()
                .filter(row -> isInDateRange(row.getDate(), fromDate, toDate))
                .toList();
        return filtered.isEmpty() ? Optional.empty() : Optional.of(filtered);
    }

    private List<StockData> readAllHistory(String stockId) {
        List<StockData> rows = new ArrayList<>();
        for (String line : readLines(historyFile(stockId))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = split(line);
            if (parts.length < 11) {
                continue;
            }
            rows.add(new StockData(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6], parts[7], parts[8], parts[9], parts[10]));
        }
        return rows;
    }

    private List<StockData> mergeHistoryRows(List<StockData> oldRows, List<StockData> newRows) {
        Map<String, StockData> byDate = new LinkedHashMap<>();
        for (StockData row : oldRows) {
            if (row.getDate() != null && !row.getDate().isBlank()) {
                byDate.put(row.getDate(), row);
            }
        }
        for (StockData row : newRows) {
            if (row.getDate() != null && !row.getDate().isBlank()) {
                byDate.put(row.getDate(), row);
            }
        }
        return byDate.values()
                .stream()
                .sorted(Comparator.comparing(StockData::getDate))
                .toList();
    }

    private boolean isInDateRange(String value, LocalDate fromDate, LocalDate toDate) {
        try {
            LocalDate date = LocalDate.parse(value);
            boolean afterStart = fromDate == null || !date.isBefore(fromDate);
            boolean beforeEnd = toDate == null || !date.isAfter(toDate);
            return afterStart && beforeEnd;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private Optional<List<IntradayBar>> readIntraday(String key) {
        List<IntradayBar> rows = new ArrayList<>();
        for (String line : readLines(intradayFile(key))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = split(line);
            if (parts.length < 8) {
                continue;
            }
            rows.add(new IntradayBar(parts[0], parseDateTime(parts[1]),
                    parseDouble(parts[2]), parseDouble(parts[3]), parseDouble(parts[4]), parseDouble(parts[5]),
                    parseLong(parts[6], 0), parts[7]));
        }
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows);
    }

    private List<String> readLines(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return List.of();
        }
    }

    private void writeLines(Path file, List<String> lines) {
        try {
            ensureCacheDir();
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 快取失敗不應影響正式資料查詢。
        }
    }

    private void ensureCacheDir() {
        if (!enabled) {
            return;
        }
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException ignored) {
            // 無法建立快取資料夾時，查詢仍可走線上來源。
        }
    }

    private Path quoteFile(String key) {
        return cacheDir.resolve("quote_" + safeName(key) + ".tsv");
    }

    private Path historyFile(String stockId) {
        return cacheDir.resolve("history_" + safeName(normalize(stockId)) + ".tsv");
    }

    private Path intradayFile(String key) {
        return cacheDir.resolve("intraday_" + safeName(key) + ".tsv");
    }

    private String historyKey(String stockId, LocalDate fromDate, LocalDate toDate) {
        return normalize(stockId) + "_" + dateText(fromDate) + "_" + dateText(toDate);
    }

    private String intradayKey(String stockId, String interval) {
        return normalize(stockId) + "_" + normalize(interval);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String dateText(LocalDate date) {
        return date == null ? "none" : date.toString();
    }

    private String safeName(String key) {
        return key.replaceAll("[^0-9a-zA-Z._-]", "_");
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\t", " ").replace("\r", " ").replace("\n", " ");
    }

    private String[] split(String line) {
        return line.split(SEP, -1);
    }

    private double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private record CacheEntry<T>(T value, LocalDateTime cachedAt) {
        private boolean isExpired(Duration ttl) {
            return cachedAt == null || Duration.between(cachedAt, LocalDateTime.now()).compareTo(ttl) > 0;
        }
    }
}
