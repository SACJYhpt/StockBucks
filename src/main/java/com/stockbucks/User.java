package com.stockbucks;

import java.util.HashMap;

public class User {
    // 帳戶資料
    private double cash = 1200000;
    private HashMap <String, StockHoldings> holdings = new HashMap<>();
    
    public void stockBuying(String stockID, int amount, double cost) {
        if (!holdings.containsKey(stockID)) {
            holdings.put(stockID, new StockHoldings(stockID, amount, cost));
        }
        else {
            holdings.get(stockID).updateAdd(amount, cost);
        }
    }

    public double stockSelling(String stockID, int amount) {
        if (holdings.containsKey(stockID)) {
            StockHoldings data = holdings.get(stockID);
            double cost = data.updateRemove(amount);
            if (data.getQuantity() == 0) {
                holdings.remove(stockID);
            }
            return cost;
        }
        return 0;
    }

    public double getOneTotalCost(String stockID) {
        StockHoldings data = holdings.get(stockID);
        if (data == null) return 0;
        return data.getTotalCost();
    }

    public double getOnePresentValue(String stockID, double currentPrice) {
        StockHoldings data = holdings.get(stockID);
        if (data == null) return 0;
        return data.getQuantity()*currentPrice;
    }

    public double getOneNetWorth(String stockID, double currentPrice) {
        StockHoldings data = holdings.get(stockID);
        if (data == null) return 0;
        return data.getQuantity()*currentPrice-data.getTotalCost();
    }

    public double getOneAveragePrice(String stockID) {
        StockHoldings data = holdings.get(stockID);
        if (data == null) return 0;
        return data.getTotalCost()/data.getQuantity();
    }

    public double getCash() {
        return cash;
    }

    public void addCash(double amount) {
        this.cash += amount;
    }

    public int getStockQuantity(String stockID) {
        StockHoldings data = holdings.get(stockID);
        if (data == null) return 0;
        return data.getQuantity();
    }
}
