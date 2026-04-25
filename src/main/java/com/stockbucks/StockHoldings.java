package com.stockbucks;

public class StockHoldings {
    private String stockID;
    private int quantity;
    private double totalCost;

    public StockHoldings(String stockID, int quentity, double totalCost) {
        this.stockID = stockID;
        this.quantity = quentity;
        this.totalCost = totalCost;
    }

    public String getStockID() {
        return stockID;
    }

    public int getQuantity() {
        return quantity;
    }

    public double getTotalCost() {
        return totalCost;
    }

    public void updateHoldings(int amount, double cost) {
        this.quantity += amount;
        this.totalCost += cost;
    }
}
