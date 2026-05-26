package com.stockbucks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class TradingEngine {
    private List<TradeRecord> dailyRecords = new ArrayList<>();
    private HashMap<String, Integer> todayOddsShares = new HashMap<>();

    private List<String> globalCalender;
    private String currentDate = "";

    public TradingEngine(List<String> globalCalender) {
        this.globalCalender = globalCalender;
    }

    public void trading(User user, String stockId, String date, int shares, double price, boolean isBuy) {
        date = date.contains(" ") ? date.split(" ")[0] : date;
        if (currentDate.isEmpty()) {
            currentDate = date;
            user.getSettlementManager().SettlementClearing(date, user);
        }
        if (date.compareTo(currentDate) > 0) {
            currentDate = date;
            todayOddsShares.clear();
            user.getSettlementManager().SettlementClearing(date, user);
        } else if (date.compareTo(currentDate) < 0) {
            System.out.println("Engine date: " + currentDate + ", input date: " + date);
        }

        double grossAmount = shares * price;

        if (isBuy) {
            buying(user, stockId, date, shares, price, grossAmount);
        } else {
            selling(user, stockId, date, shares, price, grossAmount);
        }
    }

    private void buying(User user, String stockID, String date, int shares, double price, double grossAmount) {
        long commission = calculateCommission(grossAmount);
        double totalCost = grossAmount + commission;

        if (!user.withdrawCash(totalCost)) {
            System.out.println("Insufficient available cash.");
            return;
        }

        String dateTplus2 = getDateTplus2(date);
        user.getSettlementManager().addSettlement(dateTplus2, totalCost * -1);
        user.stockBuying(stockID, shares, totalCost / shares);

        if (shares < 1000) {
            todayOddsShares.put(stockID, todayOddsShares.getOrDefault(stockID, 0) + shares);
        }

        TradeRecord log = new TradeRecord(stockID, date, "BUY", price, shares, commission, 0, totalCost);
        dailyRecords.add(log);
        user.addTradeRecord(log);
        System.out.println("Trade success: BUY " + stockID + " shares=" + shares + " price=" + price + " total=" + totalCost);
    }

    private void selling(User user, String stockID, String date, int shares, double price, double grossAmount) {
        int totalHoldings = user.getStockQuantity(stockID);
        int todayOdds = todayOddsShares.getOrDefault(stockID, 0);

        if (shares > totalHoldings) {
            System.out.println("Insufficient holdings.");
            return;
        } else if (shares > totalHoldings - todayOdds) {
            System.out.println("Odd-lot shares bought today cannot be day-traded.");
            return;
        }

        long commission = calculateCommission(grossAmount);
        long tax = (long) Math.floor(grossAmount * 0.003);
        double netProceeds = grossAmount - commission - tax;

        String dateTplus2 = getDateTplus2(date);
        user.getSettlementManager().addSettlement(dateTplus2, netProceeds);
        user.stockSelling(stockID, shares);
        user.depositCash(netProceeds);

        TradeRecord log = new TradeRecord(stockID, date, "SELL", price, shares, commission, tax, netProceeds);
        dailyRecords.add(log);
        user.addTradeRecord(log);
        System.out.println("Trade success: SELL " + stockID + " shares=" + shares + " price=" + price + " total=" + netProceeds);
    }

    public List<TradeRecord> getDailyRecords() {
        return dailyRecords;
    }

    public String getDateTplus2(String date) {
        String pureDate = date.contains(" ") ? date.split(" ")[0] : date;
        int currentIndex = globalCalender.indexOf(pureDate);
        if (currentIndex == -1) {
            System.err.println("Unknown trading date: " + pureDate);
            return pureDate;
        }
        if (currentIndex + 2 >= globalCalender.size()) {
            return globalCalender.get(globalCalender.size() - 1);
        }
        return globalCalender.get(currentIndex + 2);
    }

    private long calculateCommission(double amount) {
        long commission = (long) Math.floor(amount * 0.001425);
        return Math.max(commission, 1);
    }
}
