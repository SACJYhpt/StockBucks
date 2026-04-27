package com.stockbucks;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;

public class TradingEngine {
    // 處理交易
    private List <String> dailyRecords = new ArrayList<>();
    // 追蹤今日零股 零股無法當沖
    private HashMap <String, Integer> todayOddsShares = new HashMap<>();

    // isBuy: 0: sell, 1: buy
    public void trading(User user,String stockId, int shares, double price, boolean isBuy) {
        double totalCost = shares*price;

        if (isBuy) {
            buying(user, stockId, shares, price, totalCost);
        }
        else {
            selling(user, stockId, shares, price, totalCost);
        }
    }

    private void buying(User user, String stockID, int shares, double price, double totalCost) {
        if (user.getCash() < totalCost) {
            System.out.println("本金不足 請留意違約交割風險");
        }
        user.addCash(totalCost*-1);
        user.stockBuying(stockID, shares, totalCost);

        if (shares < 1000) {
            todayOddsShares.put(stockID, todayOddsShares.getOrDefault(stockID, 0)+shares);
        }

        String record = String.format("買入 %s: %.2f元共%d股, 總共%.2f元", stockID, price, shares, totalCost);
        dailyRecords.add(record);
        System.out.println("交易成功: "+record);
    }

    private void selling(User user, String stockID, int shares, double price, double totalCost) {
        int totalHoldings = user.getStockQuantity(stockID);
        int todayOdds = todayOddsShares.getOrDefault(stockID, 0);

        if (shares > user.getStockQuantity(stockID)) {
            System.out.println("持有庫存不足");
            return;
        }
        else if (shares > totalHoldings-todayOdds) {
            System.out.println("交易失敗 零股無法當沖");
            return;
        }

        user.addCash(totalCost);
        user.stockSelling(stockID, shares);

        String record = String.format("賣出 %s: %.2f元共%d股, 總共%.2f元", stockID, price, shares, totalCost);
        dailyRecords.add(record);
        System.out.println("交易成功: "+record);
    }

    public List <String> getDailyRecords() {
        return dailyRecords;
    }
}
