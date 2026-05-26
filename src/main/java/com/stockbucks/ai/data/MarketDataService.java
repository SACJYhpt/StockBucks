package com.stockbucks.ai.data;

import com.stockbucks.StockData;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Optional;

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
