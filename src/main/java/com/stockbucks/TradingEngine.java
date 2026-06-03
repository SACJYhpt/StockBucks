package com.stockbucks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class TradingEngine {
    // 處理交易 (保留引擎內部的 dailyRecords，方便單次模擬調閱)
    private List <TradeRecord> dailyRecords = new ArrayList<>();
    // 追蹤今日零股 零股無法當沖
    private HashMap <String, Integer> todayOddsShares = new HashMap<>();
    // 維護尚未成交的委託單 等待撮合
    private List <Order> pendingOrders = new ArrayList<>();
    private List <Order> allOrders = new ArrayList<>();

    private List <String> notificationQueue = new ArrayList<>();

    private List <String> globalCalender;
    private String currentDate = "";

    public TradingEngine(List <String> globalCalender) {
        this.globalCalender = globalCalender;
    }

    // isBuy: 0: sell, 1: buy
    public void trading(User user, String stockID, String date, int currentMinute, int shares, double price, boolean isBuy) {
        date = date.contains(" ") ? date.split(" ")[0] : date;
        // 日期切換與交割清算
        if (currentDate.isEmpty()) {
            currentDate = date;
            user.getSettlementManager().SettlementClearing(date, user);
        }
        if (date.compareTo(currentDate) > 0) {
            currentDate = date;
            todayOddsShares.clear();
            user.getSettlementManager().SettlementClearing(date, user);
            for (Order oldOrder: pendingOrders) {
                oldOrder.setStatus(Order.OrderStatus.INVALID);
                oldOrder.getUser().getOrderHistory().add(oldOrder);
            }
            pendingOrders.clear();
            allOrders.clear();
        }
        else if (date.compareTo(currentDate) < 0) {
            System.out.println("引擎時間: " + currentDate + "，傳入時間: " + date);
            return; // 錯誤 不予委託
        }

        double totalCost = shares * price;
        long commission = (long) Math.max(1, Math.floor(totalCost * 0.001425));
        
        Order order = new Order(user, stockID, date, currentMinute, shares, price, isBuy);
        order.setStatus(Order.OrderStatus.PENDING);
        
        if (isBuy) {
            if (user.getCash() < (totalCost + commission)) {
                String msg = String.format("【提醒】：目前可用現金 %.2f 元，預估需 %.2f 元。本金不足，請留意 T+2 違約交割風險！\n", user.getCash(), (totalCost + commission));
                notificationQueue.add(msg);
                System.out.println(msg);
            }
        }
        else {
            int totalHoldings = user.getStockQuantity(stockID);
            int todayOdds = todayOddsShares.getOrDefault(stockID, 0);
            if (shares > totalHoldings) {
                notificationQueue.add("【委託】委託失敗，持有庫存不足");
                System.out.println("【委託】委託失敗，持有庫存不足");
                order.setStatus(Order.OrderStatus.FAILED);
                pendingOrders.add(order);
                return;
            }
            else if (shares > totalHoldings - todayOdds) {
                notificationQueue.add("【委託】委託失敗，零股無法當沖");
                System.out.println("【委託】委託失敗，零股無法當沖");
                order.setStatus(Order.OrderStatus.FAILED);
                pendingOrders.add(order);
                return;
            }
        }

        pendingOrders.add(order);
        allOrders.add(order);
        String log = String.format("【委託】%s 代號 %s: 限價 %.2f 元，共 %d 股，不含手續費總共%.2f元", isBuy ? "買入" : "賣出", stockID, price, shares, totalCost);
        notificationQueue.add(log);
        System.out.println(log);

        // if (isBuy) {
        //     buying(user, stockId, date, shares, price, totalCost);
        // }
        // else {
        //     selling(user, stockId, date, shares, price, totalCost);
        // }
    }

    public void onPriceUpdate(String stockID, double currentPrice, int currentMinute) {
        Iterator <Order> iterator = pendingOrders.iterator();

        while(iterator.hasNext()) {
            Order order = iterator.next();

            if (!order.getStockID().equals(stockID)) {
                continue;
            }

            if (order.getStatus() != Order.OrderStatus.PENDING) {
                iterator.remove();
                continue;
            }

            boolean isMatch = false;

            if (order.isBuy()) {
                if (currentPrice <= order.getLimitPrice()) {
                    isMatch = true;
                }
            }
            else {
                if (currentPrice >= order.getLimitPrice()) {
                    isMatch = true;
                }
            }

            if (isMatch) {
                order.setStatus(Order.OrderStatus.FILLED);
                actualTrade(order, currentPrice, currentMinute); // 真正交易
                order.getUser().getOrderHistory().add(order);
                iterator.remove();
            }
        }
    }

    private void actualTrade(Order order, double matchPrice, int currentMinute) {
        User user = order.getUser();
        String stockID = order.getStockID();
        int shares = order.getShares();
        String date = order.getDate().contains(" ") ? order.getDate().split(" ")[0] : order.getDate();

        double totalCost = shares * matchPrice;
        long commission = (long) Math.max(1, Math.floor(totalCost * 0.001425));

        if (order.isBuy()) {
            totalCost += commission;

            String dateTplus2 = getDateTplus2(date);
            user.getSettlementManager().addSettlement(dateTplus2, totalCost * -1);
            user.stockBuying(stockID, shares, totalCost);

            if (shares < 1000) {
                todayOddsShares.put(stockID, todayOddsShares.getOrDefault(stockID, 0) + shares);
            }

            String record = String.format("買入代號 %s: 成交價 %.2f 元共 %d 股，手續費 %d 元，總共%.2f元", stockID, matchPrice, shares, commission, totalCost);
            TradeRecord log = new TradeRecord(stockID, date, currentMinute, "買入", matchPrice, shares, commission, 0, totalCost);
            dailyRecords.add(log);
            user.addTradeRecord(log);
            notificationQueue.add("【交易】交易成功 " + record);
            System.out.println("【交易】交易成功 " + record);
        }
        else {
            long tax = (long) Math.floor(totalCost*0.003);
            totalCost -= (commission + tax);

            String dateTplus2 = getDateTplus2(date);
            user.getSettlementManager().addSettlement(dateTplus2, totalCost);
            user.stockSelling(stockID, shares);

            String record = String.format("賣出代號 %s: 成交價 %.2f 元共 %d 股，手續費 %d 元，證交稅 %d 元，總共%.2f元", stockID, matchPrice, shares, commission, tax, totalCost);
            TradeRecord log = new TradeRecord(stockID, date, currentMinute, "賣出", matchPrice, shares, commission, tax, totalCost);
            dailyRecords.add(log);
            user.addTradeRecord(log);
            notificationQueue.add("【交易】交易成功 " + record);
            System.out.println("【交易】交易成功 " + record);
        }
    }

    public List <TradeRecord> getDailyRecords() {
        return this.dailyRecords;
    }

    public List <Order> getPendingOrders() {
        return this.pendingOrders;
    }

    public List <Order> getAllOrders() {
        return this.allOrders;
    }

    public String getReturnMsg() {
        if (notificationQueue == null || notificationQueue.isEmpty()) {
            return null;
        }
        return notificationQueue.remove(0);
    }

    public String getDateTplus2(String date) {
        String pureDate = date.contains(" ") ? date.split(" ")[0] : date;
        int currentIndex = globalCalender.indexOf(pureDate);
        if (currentIndex == -1) {
            System.err.println("找不到交易日: " + pureDate);
            return pureDate;
        }
        if (currentIndex + 2 >= globalCalender.size()) {
            return globalCalender.get(globalCalender.size() - 1);
        }
        return globalCalender.get(currentIndex + 2);
    }
}
