package com.stockbucks;

public class StockData {
    // 股票的資料
    private String date;
    private double open, high, low, close;

    public StockData(String date, String open, String high, String low, String close) {
        this.date = date;
        this.open = Double.parseDouble(open);
        this.high = Double.parseDouble(high);
        this.low = Double.parseDouble(low);
        this.close = Double.parseDouble(close);
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
}
