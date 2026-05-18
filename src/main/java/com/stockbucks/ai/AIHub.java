package com.stockbucks.ai;

import com.stockbucks.StockData;
import com.stockbucks.TradeRecord;
import com.stockbucks.TradingEngine;
import com.stockbucks.User;

import java.util.List;

public class AIHub {

    // 模式：local / api
    private String mode;

    // 內部引擎
    private final LocalModelEngine localEngine;
    private final ApiModelEngine apiEngine;

    public AIHub(String mode) {
        this.mode = mode;
        this.localEngine = new LocalModelEngine();
        this.apiEngine = new ApiModelEngine();
    }

    // =========================
    // 模式切換
    // =========================
    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getMode() {
        return mode;
    }

    // =========================
    // 對外統一入口 1：
    // AI 市場模擬分析
    // =========================
    public String analyzeMarket(User user,
                                TradingEngine engine,
                                List<StockData> historyData,
                                double currentPrice,
                                String stockId) {

        String latestDate = getLatestDate(historyData);
        String tradesSummary = buildTradesSummary(engine);
        String holdingSummary = buildHoldingSummary(user, stockId, currentPrice);

        String prompt = """
                你是股票模擬市場分析助理。

                股票代號：%s
                最新日期：%s
                當前價格：%.2f
                最近交易摘要：
                %s

                持倉摘要：
                %s

                請輸出：
                1. 目前模擬市場狀態
                2. 價格可能行為解釋
                3. 持倉風險提醒
                """.formatted(
                stockId,
                latestDate,
                currentPrice,
                tradesSummary,
                holdingSummary
        );

        return askModel(prompt);
    }

    // =========================
    // 對外統一入口 2：
    // AI 股票解惑
    // =========================
    public String answerStockQuestion(User user,
                                      TradingEngine engine,
                                      List<StockData> historyData,
                                      double currentPrice,
                                      String stockId,
                                      String question) {

        String latestDate = getLatestDate(historyData);
        String tradesSummary = buildTradesSummary(engine);
        String holdingSummary = buildHoldingSummary(user, stockId, currentPrice);

        String prompt = """
                你是股票模擬系統的 AI 股票解惑助理。

                股票代號：%s
                最新日期：%s
                當前價格：%.2f
                最近交易摘要：
                %s

                持倉摘要：
                %s

                使用者問題：
                %s

                請用繁體中文回答，回答要清楚、精簡、可讀。
                """.formatted(
                stockId,
                latestDate,
                currentPrice,
                tradesSummary,
                holdingSummary,
                question
        );

        return askModel(prompt);
    }

    // =========================
    // 統一模型呼叫入口
    // =========================
    private String askModel(String prompt) {
        if ("api".equalsIgnoreCase(mode)) {
            return apiEngine.ask(prompt);
        }
        return localEngine.ask(prompt);
    }

    // =========================
    // 內部資料整理區
    // =========================
    private String getLatestDate(List<StockData> historyData) {
        if (historyData == null || historyData.isEmpty()) {
            return "N/A";
        }
        return historyData.get(historyData.size() - 1).getDate();
    }

    private String buildTradesSummary(TradingEngine engine) {
        if (engine == null) {
            return "無交易資料";
        }

        List<TradeRecord> records = engine.getDailyRecords();
        if (records == null || records.isEmpty()) {
            return "目前尚無交易紀錄";
        }

        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, records.size() - 5); // 只取最近 5 筆，避免太長

        for (int i = start; i < records.size(); i++) {
            TradeRecord r = records.get(i);
            sb.append("- ")
              .append(r.getDate()).append(" ")
              .append(r.getType()).append(" ")
              .append(r.getStockID()).append(" ")
              .append(r.getShares()).append("股 ")
              .append("@ ").append(String.format("%.2f", r.getPrice()))
              .append("，結算 ").append(String.format("%.2f", r.getTotalCost()))
              .append("\n");
        }

        return sb.toString();
    }

    private String buildHoldingSummary(User user, String stockId, double currentPrice) {
        if (user == null) {
            return "無帳戶資料";
        }

        int quantity = user.getStockQuantity(stockId);
        double cash = user.getCash();
        double totalCost = user.getOneTotalCost(stockId);
        double presentValue = user.getOnePresentValue(stockId, currentPrice);
        double pnl = user.getOneNetWorth(stockId, currentPrice);
        double avgPrice = user.getOneAveragePrice(stockId);

        return """
                可用現金：%.2f
                持股數量：%d
                平均成本：%.2f
                總成本：%.2f
                目前市值：%.2f
                未實現損益：%.2f
                """.formatted(cash, quantity, avgPrice, totalCost, presentValue, pnl);
    }

    // =========================
    // 內部 AI 後端
    // =========================
    private static class LocalModelEngine {
        public String ask(String prompt) {
            // TODO:
            // 之後改成串接本地模型
            // 例如 Ollama / LM Studio / vLLM / 本地 HTTP API
            return "[LOCAL MODEL 回應]\n" + prompt;
        }
    }

    private static class ApiModelEngine {
        public String ask(String prompt) {
            // TODO:
            // 之後改成串接外部 API
            // 例如 OpenAI API
            return "[API MODEL 回應]\n" + prompt;
        }
    }
}