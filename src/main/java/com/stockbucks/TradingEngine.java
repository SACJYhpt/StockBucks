package com.stockbucks;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;

public class TradingEngine {
    // 處理交易 (保留引擎內部的 dailyRecords，方便單次模擬調閱)
    private List <TradeRecord> dailyRecords = new ArrayList<>();
    // 追蹤今日零股 零股無法當沖
    private HashMap <String, Integer> todayOddsShares = new HashMap<>();

    private List <String> globalCalender;
    private String currentDate = "";

    public TradingEngine(List <String> globalCalender) {
        this.globalCalender = globalCalender;
    }

    // isBuy: 0: sell, 1: buy
    public void trading(User user,String stockId, String date, int shares, double price, boolean isBuy) {
        date = date.contains(" ") ? date.split(" ")[0] : date;
        if (currentDate.isEmpty()) {
            currentDate = date;
            user.getSettlementManager().SettlementClearing(date, user);
        }
        if (date.compareTo(currentDate) > 0) {
            currentDate = date;
            todayOddsShares.clear();
            user.getSettlementManager().SettlementClearing(date, user);
        }
        // 🛠️ 修正筆誤：將原本的 date.compareTo(date) 改為與 currentDate 比較
        else if (date.compareTo(currentDate) < 0) {
            System.out.println("引擎時間: " + currentDate + "，傳入時間: " + date);
        }
        
        double totalCost = shares*price;

        if (isBuy) {
            buying(user, stockId, date, shares, price, totalCost);
        } else {
            selling(user, stockId, date, shares, price, totalCost);
        }
    }

    private void buying(User user, String stockID, String date, int shares, double price, double totalCost) {
        long commission = (long) Math.floor(totalCost*0.001425);
        if (commission < 1){
            commission = 1;
        }
        totalCost += commission;
        
        if (user.getCash() < totalCost) {
            System.out.println("本金不足 請留意違約交割風險");
        }
        
        String dateTplus2 = getDateTplus2(date);
        user.getSettlementManager().addSettlement(dateTplus2, totalCost*-1);
        user.stockBuying(stockID, shares, totalCost);

        if (shares < 1000) {
            todayOddsShares.put(stockID, todayOddsShares.getOrDefault(stockID, 0)+shares);
        }

        String record = String.format("買入代號 %s: 成交價 %.2f 元共 %d 股，手續費 %d 元，總共%.2f元", stockID, price, shares, commission, totalCost);
        TradeRecord log = new TradeRecord(stockID, date, "買入", price, shares, commission, 0, totalCost);
        
        // 1. 紀錄到引擎
        dailyRecords.add(log);
   
        user.addTradeRecord(log); 
        System.out.println("交易成功: " + record);
    }

    private void selling(User user, String stockID, String date, int shares, double price, double totalCost) {
        int totalHoldings = user.getStockQuantity(stockID);
        int todayOdds = todayOddsShares.getOrDefault(stockID, 0);

        if (shares > user.getStockQuantity(stockID)) {
            System.out.println("持有庫存不足");
            return;
        }
        else if (shares > totalHoldings-todayOdds) {
            System.out.println("交易失敗，零股無法當沖");
            return;
        }

        long commission = (long) Math.floor(totalCost*0.001425);
        if (commission < 1) {
            commission = 1;
        }
        long tax = (long) Math.floor(totalCost*0.003);
        totalCost -= commission+tax;

        String dateTplus2 = getDateTplus2(date);
        user.getSettlementManager().addSettlement(dateTplus2, totalCost);
        user.stockSelling(stockID, shares);

        String record = String.format("賣出代號 %s: 成交價 %.2f 元共 %d 股，手續費 %d 元，證交稅 %d 元，總共%.2f元", stockID, price, shares, commission, tax, totalCost);
        TradeRecord log = new TradeRecord(stockID, date, "賣出", price, shares, commission, tax, totalCost);
        
        // 1. 紀錄到引擎
        dailyRecords.add(log);

        user.addTradeRecord(log);

        System.out.println("交易成功: " + record);
    }

    public List <TradeRecord> getDailyRecords() {
        return dailyRecords;
    }

    public String getDateTplus2(String date) {
        String pureDate = date.contains(" ") ? date.split(" ")[0] : date;
        int currentIndex = globalCalender.indexOf(pureDate);
        if (currentIndex == -1) {
            System.err.println("找不到交易日: "+pureDate);
            return pureDate;
        }
        if (currentIndex+2 >= globalCalender.size()) {
            return globalCalender.get(globalCalender.size()-1);
        }
        return globalCalender.get(currentIndex+2);
    }
}