package com.stockbucks;

public class TradeRecord {
    private String stockID;
    private String date;
    private String type;
    private double price;
    private int shares;
    private double commission;
    private double tax;
    private double totalCost;

    public TradeRecord(String stockID, String date, String type, double price, int shares, double commission, double tax, double totalCost) {
        this.stockID = stockID;
        this.date = date;
        this.type = type;
        this.price = price;
        this.shares = shares;
        this.commission = commission;
        this.tax = tax;
        this.totalCost = totalCost;
    }

    @Override
    public String toString() {
        return String.format("%s，%s代號 %s: 成交價 %.2f 元共 %d 股，手續費 %d 元，證交稅 %d 元，總共%.2f元", date, type, stockID, price, shares, commission, tax, totalCost);
    }

    public String getStockID() {
        return stockID;
    }

    public String getDate() {
        return date;
    }

    public String getType() {
        return type;
    }

    public double getPrice() {
        return price;
    }

    public int getShares() {
        return shares;
    }

    public double getCommission() {
        return commission;
    }

    public double getTax() {
        return tax;
    }

    public double getTotalCost() {
        return totalCost;
    }
}
