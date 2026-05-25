package com.stockbucks.ai.model;

import com.stockbucks.StockData;
import com.stockbucks.TradeRecord;
import com.stockbucks.TradingEngine;
import com.stockbucks.User;

import java.util.List;

public class AiContext {

    private final String stockId;
    private final String latestDate;
    private final double currentPrice;
    private final String modeDescription;
    private final String historySummary;
    private final String tradeSummary;
    private final String holdingSummary;

    public AiContext(String stockId,
                     String latestDate,
                     double currentPrice,
                     String modeDescription,
                     String historySummary,
                     String tradeSummary,
                     String holdingSummary) {
        this.stockId = stockId;
        this.latestDate = latestDate;
        this.currentPrice = currentPrice;
        this.modeDescription = modeDescription;
        this.historySummary = historySummary;
        this.tradeSummary = tradeSummary;
        this.holdingSummary = holdingSummary;
    }

    public static AiContext from(User user,
                                 TradingEngine tradingEngine,
                                 List<StockData> historyData,
                                 MarketSnapshot snapshot) {

        String latestDate = snapshot.getLatestDate();
        String historySummary = buildHistorySummary(historyData, snapshot.getStockId());
        String tradeSummary = buildTradeSummary(tradingEngine);
        String holdingSummary = buildHoldingSummary(user, snapshot.getStockId(), snapshot.getCurrentPrice());

        return new AiContext(
                snapshot.getStockId(),
                latestDate,
                snapshot.getCurrentPrice(),
                snapshot.getModeDescription(),
                historySummary,
                tradeSummary,
                holdingSummary
        );
    }

    private static String buildHistorySummary(List<StockData> historyData, String stockId) {
        if (historyData == null || historyData.isEmpty()) {
            return "無歷史資料";
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (int i = historyData.size() - 1; i >= 0 && count < 5; i--) {
            StockData data = historyData.get(i);
            if (stockId.equals(data.getStockID())) {
                sb.append("- ")
                  .append(data.getDate())
                  .append(" 開:")
                  .append(String.format("%.2f", data.getOpen()))
                  .append(" 高:")
                  .append(String.format("%.2f", data.getHigh()))
                  .append(" 低:")
                  .append(String.format("%.2f", data.getLow()))
                  .append(" 收:")
                  .append(String.format("%.2f", data.getClose()))
                  .append("\n");
                count++;
            }
        }

        return count == 0 ? "無符合股票代號的歷史資料" : sb.toString();
    }

    private static String buildTradeSummary(TradingEngine tradingEngine) {
        if (tradingEngine == null) {
            return "無交易資料";
        }

        List<TradeRecord> records = tradingEngine.getDailyRecords();
        if (records == null || records.isEmpty()) {
            return "目前尚無交易紀錄";
        }

        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, records.size() - 5);

        for (int i = start; i < records.size(); i++) {
            TradeRecord r = records.get(i);
            sb.append("- ")
              .append(r.getDate()).append(" ")
              .append(r.getType()).append(" ")
              .append(r.getStockID()).append(" ")
              .append(r.getShares()).append("股 ")
              .append("@ ").append(String.format("%.2f", r.getPrice()))
              .append("，成交額 ").append(String.format("%.2f", r.getTotalCost()))
              .append("\n");
        }

        return sb.toString();
    }

    private static String buildHoldingSummary(User user, String stockId, double currentPrice) {
        if (user == null) {
            return "無帳戶資料";
        }

        return """
                可用現金：%.2f
                持股數量：%d
                平均成本：%.2f
                總成本：%.2f
                目前市值：%.2f
                未實現損益：%.2f
                """.formatted(
                user.getCash(),
                user.getStockQuantity(stockId),
                user.getOneAveragePrice(stockId),
                user.getOneTotalCost(stockId),
                user.getOnePresentValue(stockId, currentPrice),
                user.getOneNetWorth(stockId, currentPrice)
        );
    }

    public String getStockId() {
        return stockId;
    }

    public String getLatestDate() {
        return latestDate;
    }

    public double getCurrentPrice() {
        return currentPrice;
    }

    public String getModeDescription() {
        return modeDescription;
    }

    public String getHistorySummary() {
        return historySummary;
    }

    public String getTradeSummary() {
        return tradeSummary;
    }

    public String getHoldingSummary() {
        return holdingSummary;
    }
}