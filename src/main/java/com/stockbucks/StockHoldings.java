package com.stockbucks;

import java.util.LinkedList;

public class StockHoldings {
    private String stockID;
    private LinkedList <HoldingsList> lst = new LinkedList<>();

    private static class HoldingsList {
        int quantity;
        double price;
        HoldingsList(int quantity, double price) {
            this.quantity = quantity;
            this.price = price;
        }
    }

    public StockHoldings(String stockID, int quentity, double price) {
        this.stockID = stockID;
        this.updateAdd(quentity, price);
    }

    public void updateAdd(int quantity, double price) {
        lst.add(new HoldingsList(quantity, price));
    }

    public double updateRemove(int quantity) {
        double cost = 0;
        while (quantity > 0 && !lst.isEmpty()) {
            HoldingsList oldest = lst.peek();
            if (oldest.quantity <= quantity) {
                cost += oldest.quantity * oldest.price;
                quantity -= oldest.quantity;
                lst.poll();
            }
            else {
                cost += quantity*oldest.price;
                oldest.quantity -= quantity;
                quantity = 0;
            }
        }
        return cost;
    }

    public String getStockID() {
        return stockID;
    }

    public int getQuantity() {
        return lst.stream().mapToInt(l -> l.quantity).sum();
    }

    public double getTotalCost() {
        return lst.stream().mapToDouble(l -> l.quantity*l.price).sum();
    }
}
