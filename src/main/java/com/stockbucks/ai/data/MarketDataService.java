package com.stockbucks.ai.data;

import com.stockbucks.StockData;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class MarketDataService {

    private static final DateTimeFormatter STOCK_DATA_DATE = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('/')
            .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .appendLiteral('/')
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, java.time.format.SignStyle.NOT_NEGATIVE)
            .toFormatter();
    private static final LocalDate DEFAULT_START_DATE = LocalDate.of(2010, 1, 4);

    private final HistoricalDataRepository repository;
    private final TwseHistoricalDataClient twseClient;

    public MarketDataService(HistoricalDataRepository repository) {
        this(repository, new TwseHistoricalDataClient());
    }

    public MarketDataService(HistoricalDataRepository repository, TwseHistoricalDataClient twseClient) {
        this.repository = repository;
        this.twseClient = twseClient;
    }

    public void saveHistoricalData(List<StockData> historyData) {
        repository.saveAll(historyData);
    }

    public List<StockData> getHistoricalData(String stockId) {
        return repository.findByStockId(stockId);
    }

    public List<StockData> getRandomHistoricalData(String stockId, int limit) {
        return repository.findRandomByStockId(stockId, limit);
    }

    public List<StockData> getRandomHistoricalWindow(String stockId, int windowSize) {
        return repository.findRandomWindow(stockId, windowSize);
    }

    public List<String> getAvailableStockIds() {
        return repository.findAvailableStockIds();
    }

    public List<StockProfile> updateListedStockProfiles() {
        List<StockProfile> profiles = twseClient.fetchListedStockProfiles();
        repository.saveStockProfiles(profiles);
        return profiles;
    }

    public List<StockProfile> getStockProfiles() {
        List<StockProfile> profiles = repository.findStockProfiles();
        if (!profiles.isEmpty()) {
            return profiles;
        }
        return updateListedStockProfiles();
    }

    public int countHistoricalData(String stockId) {
        return repository.countByStockId(stockId);
    }

    public Optional<String> getLatestDate(String stockId) {
        return repository.findLatestDate(stockId);
    }

    public MarketDataUpdateResult updateHistoricalData(String stockId) {
        LocalDate startDate = repository.findLatestDate(stockId)
                .map(this::parseStockDataDate)
                .map(date -> date.plusDays(1))
                .orElse(DEFAULT_START_DATE);
        return updateHistoricalData(stockId, startDate, LocalDate.now());
    }

    public MarketDataUpdateResult updateHistoricalData(String stockId, LocalDate fromDate, LocalDate toDate) {
        if (stockId == null || stockId.isBlank()) {
            return MarketDataUpdateResult.skipped(stockId, "股票代號不可為空。");
        }
        if (fromDate == null || toDate == null || fromDate.isAfter(toDate)) {
            return MarketDataUpdateResult.skipped(stockId, "目前沒有需要更新的日期範圍。");
        }

        List<StockData> fetched = twseClient.fetchDailyHistory(stockId, fromDate, toDate);
        int saved = repository.saveAllAndCount(fetched);

        return new MarketDataUpdateResult(
                stockId,
                fromDate,
                toDate,
                fetched.size(),
                saved,
                "歷史資料更新完成。"
        );
    }

    public MarketDataUpdateResult updateAllListedStocks(LocalDate fromDate, LocalDate toDate, int maxStocks) {
        return updateAllListedStocks(fromDate, toDate, maxStocks, null);
    }

    public MarketDataUpdateResult updateAllListedStocks(LocalDate fromDate,
                                                        LocalDate toDate,
                                                        int maxStocks,
                                                        Consumer<String> progressCallback) {
        List<StockProfile> profiles = updateListedStockProfiles();
        int limit = maxStocks <= 0 ? profiles.size() : Math.min(maxStocks, profiles.size());
        int fetched = 0;
        int saved = 0;
        int failed = 0;

        notifyProgress(progressCallback, "已取得上市股票清單，共 " + profiles.size() + " 檔，準備更新 " + limit + " 檔。");

        for (int i = 0; i < limit; i++) {
            StockProfile profile = profiles.get(i);
            try {
                notifyProgress(progressCallback, "正在更新第 " + (i + 1) + " / " + limit
                        + " 檔：" + profile.getStockId() + " " + profile.getStockName()
                        + "\n目前累計擷取 " + fetched + " 筆，寫入 " + saved + " 筆，失敗 " + failed + " 檔。");

                MarketDataUpdateResult result = updateHistoricalData(profile.getStockId(), fromDate, toDate);
                fetched += result.getFetchedCount();
                saved += result.getSavedCount();
            } catch (RuntimeException e) {
                failed++;
                notifyProgress(progressCallback, "更新失敗：" + profile.getStockId() + " " + profile.getStockName()
                        + "\n原因：" + e.getMessage()
                        + "\n目前累計擷取 " + fetched + " 筆，寫入 " + saved + " 筆，失敗 " + failed + " 檔。");
            }
        }

        notifyProgress(progressCallback, "批次更新完成。累計擷取 " + fetched + " 筆，寫入 " + saved + " 筆，失敗 " + failed + " 檔。");

        return new MarketDataUpdateResult(
                "ALL_TWSE",
                fromDate,
                toDate,
                fetched,
                saved,
                "上市股票批次更新完成。股票主檔：" + profiles.size()
                        + " 檔，已處理：" + limit + " 檔，失敗：" + failed + " 檔。"
        );
    }

    public MarketDataUpdateResult backfillAllListedStocksYearByYear(int startYear,
                                                                    int endYear,
                                                                    int maxStocks,
                                                                    Consumer<String> progressCallback) {
        List<StockProfile> profiles = updateListedStockProfiles();
        int limit = maxStocks <= 0 ? profiles.size() : Math.min(maxStocks, profiles.size());
        int fromYear = Math.max(DEFAULT_START_DATE.getYear(), Math.min(startYear, endYear));
        int toYear = Math.max(startYear, endYear);
        int fetched = 0;
        int saved = 0;
        int failed = 0;
        int empty = 0;

        notifyProgress(progressCallback, "已取得上市普通股清單，共 " + profiles.size()
                + " 檔。準備從 " + toYear + " 年逐年往回補到 " + fromYear + " 年。");

        for (int year = toYear; year >= fromYear; year--) {
            LocalDate yearStart = year == DEFAULT_START_DATE.getYear()
                    ? DEFAULT_START_DATE
                    : LocalDate.of(year, 1, 1);
            LocalDate yearEnd = year == LocalDate.now().getYear()
                    ? LocalDate.now()
                    : LocalDate.of(year, 12, 31);

            notifyProgress(progressCallback, "開始更新 " + year + " 年資料，股票數：" + limit
                    + "\n累計擷取 " + fetched + " 筆，寫入 " + saved + " 筆，無資料 " + empty + " 檔次，失敗 " + failed + " 檔次。");

            for (int i = 0; i < limit; i++) {
                StockProfile profile = profiles.get(i);
                try {
                    notifyProgress(progressCallback, "年度：" + year + "\n正在更新第 " + (i + 1) + " / " + limit
                            + " 檔：" + profile.getStockId() + " " + profile.getStockName()
                            + "\n累計擷取 " + fetched + " 筆，寫入 " + saved + " 筆，無資料 " + empty + " 檔次，失敗 " + failed + " 檔次。");

                    MarketDataUpdateResult result = updateHistoricalData(profile.getStockId(), yearStart, yearEnd);
                    fetched += result.getFetchedCount();
                    saved += result.getSavedCount();
                    if (result.getFetchedCount() == 0) {
                        empty++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    notifyProgress(progressCallback, "年度：" + year + "\n更新失敗：" + profile.getStockId() + " " + profile.getStockName()
                            + "\n原因：" + e.getMessage()
                            + "\n累計擷取 " + fetched + " 筆，寫入 " + saved + " 筆，無資料 " + empty + " 檔次，失敗 " + failed + " 檔次。");
                }
            }
        }

        notifyProgress(progressCallback, "逐年回補完成。累計擷取 " + fetched + " 筆，寫入 " + saved
                + " 筆，無資料 " + empty + " 檔次，失敗 " + failed + " 檔次。");

        return new MarketDataUpdateResult(
                "ALL_TWSE_BACKFILL",
                LocalDate.of(fromYear, 1, 1),
                LocalDate.of(toYear, 12, 31),
                fetched,
                saved,
                "逐年回補完成。股票主檔：" + profiles.size()
                        + " 檔，已處理股票：" + limit
                        + " 檔，年份：" + fromYear + " 到 " + toYear
                        + "，無資料檔次：" + empty
                        + "，失敗檔次：" + failed + "。"
        );
    }

    private void notifyProgress(Consumer<String> progressCallback, String message) {
        if (progressCallback != null) {
            progressCallback.accept(message);
        }
    }

    public List<StockData> loadOrUpdateHistory(String stockId) {
        List<StockData> cached = getHistoricalData(stockId);
        if (!cached.isEmpty()) {
            return cached;
        }

        updateHistoricalData(stockId);
        return getHistoricalData(stockId);
    }

    private LocalDate parseStockDataDate(String date) {
        return LocalDate.parse(date, STOCK_DATA_DATE);
    }
}
