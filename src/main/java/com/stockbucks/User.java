package com.stockbucks;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class User implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final double DEFAULT_INITIAL_CASH = 1_200_000;

    private double initialCash = DEFAULT_INITIAL_CASH;
    private double cash = DEFAULT_INITIAL_CASH;
    private HashMap<String, StockHoldings> holdings = new HashMap<>();
    private SettlementManager settlement = new SettlementManager();
    private List<TradeRecord> tradeHistory = new ArrayList<>();

    public List<TradeRecord> getTradeHistory() {
        return tradeHistory;
    }

    public void addTradeRecord(TradeRecord record) {
        this.tradeHistory.add(record);
    }

    public void stockBuying(String stockID, int amount, double averageCostPerShare) {
        if (!holdings.containsKey(stockID)) {
            holdings.put(stockID, new StockHoldings(stockID, amount, averageCostPerShare));
        } else {
            holdings.get(stockID).updateAdd(amount, averageCostPerShare);
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
        return data.getQuantity() * currentPrice;
    }

    public double getOneNetWorth(String stockID, double currentPrice) {
        StockHoldings data = holdings.get(stockID);
        if (data == null) return 0;
        return data.getQuantity() * currentPrice - data.getTotalCost();
    }

    public double getOneAveragePrice(String stockID) {
        StockHoldings data = holdings.get(stockID);
        if (data == null || data.getQuantity() == 0) return 0;
        return data.getTotalCost() / data.getQuantity();
    }

    public double getInitialCash() {
        normalizeFinancialState();
        return initialCash;
    }

    public double getAvailableCash() {
        normalizeFinancialState();
        return cash;
    }

    public double getCash() {
        return getAvailableCash();
    }

    public boolean withdrawCash(double amount) {
        normalizeFinancialState();
        if (amount < 0) {
            return false;
        }
        if (cash < amount) {
            return false;
        }
        cash -= amount;
        return true;
    }

    public void depositCash(double amount) {
        normalizeFinancialState();
        this.cash += amount;
    }

    public void addCash(double amount) {
        depositCash(amount);
    }

    public int getStockQuantity(String stockID) {
        StockHoldings data = holdings.get(stockID);
        if (data == null) return 0;
        return data.getQuantity();
    }

    public SettlementManager getSettlementManager() {
        return settlement;
    }

    private void normalizeFinancialState() {
        if (initialCash <= 0) {
            initialCash = DEFAULT_INITIAL_CASH;
        }
    }
}
