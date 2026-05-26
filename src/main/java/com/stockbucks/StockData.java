package com.stockbucks;

public class StockData {
    private String stockID, stockName, date;
    private double open, high, low, close, priceChange;
    private long volume, turnover, transactionCount;

    public StockData(String stockID, String date, String open, String high, String low, String close) {
        this(stockID, "", date, open, high, low, close, "0", "0", "0", "0");
    }

    public StockData(String stockID,
                     String stockName,
                     String date,
                     String open,
                     String high,
                     String low,
                     String close,
                     String volume,
                     String turnover,
                     String transactionCount,
                     String priceChange) {
        this.stockID = stockID;
        this.stockName = stockName == null ? "" : stockName;
        this.date = normalizeDate(date);
        this.open = parseDouble(open);
        this.high = parseDouble(high);
        this.low = parseDouble(low);
        this.close = parseDouble(close);
        this.volume = parseLong(volume);
        this.turnover = parseLong(turnover);
        this.transactionCount = parseLong(transactionCount);
        this.priceChange = parseDouble(priceChange);
    }

    public String getStockID() {
        return stockID;
    }

    public String getStockName() {
        return stockName;
    }

    public String getDate() {
        return date;
    }

    public double getOpen() {
        return open;
    }

    public double getHigh() {
        return high;
    }

    public double getLow() {
        return low;
    }

    public double getClose() {
        return close;
    }

    public long getVolume() {
        return volume;
    }

    public long getTurnover() {
        return turnover;
    }

    public long getTransactionCount() {
        return transactionCount;
    }

    public double getPriceChange() {
        return priceChange;
    }

    private String normalizeDate(String value) {
        if (value == null) {
            return "";
        }
        return value.contains(" ") ? value.split(" ")[0] : value;
    }

    private double parseDouble(String value) {
        String cleaned = cleanNumber(value);
        if (cleaned.isBlank()) {
            return 0;
        }
        return Double.parseDouble(cleaned);
    }

    private long parseLong(String value) {
        String cleaned = cleanNumber(value);
        if (cleaned.isBlank()) {
            return 0;
        }
        return Long.parseLong(cleaned);
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
}
