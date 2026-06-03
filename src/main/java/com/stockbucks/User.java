package com.stockbucks;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.io.Serializable;

public class User implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final double DEFAULT_INITIAL_CASH = 1_200_000;

    private double cash = DEFAULT_INITIAL_CASH;
    private LinkedHashMap <String, StockHoldings> holdings = new LinkedHashMap<>();
    private SettlementManager settlement = new SettlementManager();
    private List <TradeRecord> tradeHistory = new ArrayList<>();

    public List <TradeRecord> getTradeHistory() {
        return tradeHistory;
    }

    public void addTradeRecord(TradeRecord record) {
        this.tradeHistory.add(record);
    }

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
        return data.getQuantity()*currentPrice - data.getTotalCost();
    }

    public double getOneAveragePrice(String stockID) {
        StockHoldings data = holdings.get(stockID);
        if (data == null || data.getQuantity() == 0) return 0;
        return data.getTotalCost()/data.getQuantity();
    }

    public double getTotalPresentValue(String mainStockID, double currentPrice) {
        double totalValue = 0;
        for (String stockID: holdings.keySet()) {
            if (stockID.equals(mainStockID)) {
                totalValue += getOnePresentValue(mainStockID, currentPrice);
            }
            else {
                totalValue += getOneTotalCost(stockID);
            }
        }
        return totalValue;
    }

    public double getCash() {
        return cash;
    }

    public void addCash(double amount) {
        this.cash += amount;
        if (this.cash < 0) {
            System.out.println("違約交割，信用破產");
        }
    }

    public int getStockQuantity(String stockID) {
        StockHoldings data = holdings.get(stockID);
        if (data == null) return 0;
        return data.getQuantity();
    }

    public SettlementManager getSettlementManager() {
        return settlement;
    }

    public LinkedHashMap <String, StockHoldings> getHolding() {
        return holdings;
    }
}
