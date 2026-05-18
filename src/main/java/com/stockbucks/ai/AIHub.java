package com.stockbucks.ai;

import com.stockbucks.StockData;
import com.stockbucks.TradeRecord;
import com.stockbucks.TradingEngine;
import com.stockbucks.User;

import java.util.List;

public class AIHub {

    // AI 模式：local / api
    private String mode;

    // AI 後端
    private final LocalModelEngine localEngine;
    private final ApiModelEngine apiEngine;

    public AIHub(String mode) {
        this.mode = mode;
        this.localEngine = new LocalModelEngine();
        this.apiEngine = new ApiModelEngine();
    }

    // =========================
    // 模式管理
    // =========================
    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getMode() {
        return mode;
    }

    // =========================
    // 對外入口 1：市場模擬分析
    // =========================
    public String analyzeMarket(User user,
                                TradingEngine tradingEngine,
                                List<StockData> historyData,
                                String stockId,
                                double currentPrice) {

        AiContext context = buildContext(user, tradingEngine, historyData, stockId, currentPrice);

        String prompt = """
                你是股票模擬市場分析助理。

                請根據以下系統資料，分析目前模擬市場狀態。

                股票代號：%s
                最新日期：%s
                當前價格：%.2f

                歷史資料摘要：
                %s

                交易摘要：
                %s

                持倉摘要：
                %s

                請輸出：
                1. 目前市場模擬狀態
                2. 價格可能行為解釋
                3. 持倉風險提醒
                4. 是否有需要特別注意的交易現象
                """.formatted(
                context.stockId,
                context.latestDate,
                context.currentPrice,
                context.historySummary,
                context.tradeSummary,
                context.holdingSummary
        );

        return askModel(prompt);
    }

    // =========================
    // 對外入口 2：股票問答
    // =========================
    public String answerStockQuestion(User user,
                                      TradingEngine tradingEngine,
                                      List<StockData> historyData,
                                      String stockId,
                                      double currentPrice,
                                      String question) {

        AiContext context = buildContext(user, tradingEngine, historyData, stockId, currentPrice);

        String prompt = """
                你是股票模擬系統的 AI 股票解惑助理。

                系統背景資料：
                股票代號：%s
                最新日期：%s
                當前價格：%.2f

                歷史資料摘要：
                %s

                交易摘要：
                %s

                持倉摘要：
                %s

                使用者問題：
                %s

                請用繁體中文回答，內容要清楚、精簡、可讀。
                """.formatted(
                context.stockId,
                context.latestDate,
                context.currentPrice,
                context.historySummary,
                context.tradeSummary,
                context.holdingSummary,
                question
        );

        return askModel(prompt);
    }

    // =========================
    // 建立 AI 上下文
    // =========================
    private AiContext buildContext(User user,
                                   TradingEngine tradingEngine,
                                   List<StockData> historyData,
                                   String stockId,
                                   double currentPrice) {

        AiContext context = new AiContext();
        context.stockId = stockId;
        context.currentPrice = currentPrice;
        context.latestDate = getLatestDate(historyData);
        context.historySummary = buildHistorySummary(historyData, stockId);
        context.tradeSummary = buildTradeSummary(tradingEngine);
        context.holdingSummary = buildHoldingSummary(user, stockId, currentPrice);

        return context;
    }

    // =========================
    // 歷史資料摘要
    // =========================
    private String buildHistorySummary(List<StockData> historyData, String stockId) {
        if (historyData == null || historyData.isEmpty()) {
            return "無歷史資料";
        }

        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (int i = historyData.size() - 1; i >= 0 && count < 5; i--) {
            StockData data = historyData.get(i);

            if (stockId == null || stockId.equals(data.getStockID())) {
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

        if (count == 0) {
            return "無符合股票代號的歷史資料";
        }

        return sb.toString();
    }

    // =========================
    // 交易摘要
    // =========================
    private String buildTradeSummary(TradingEngine tradingEngine) {
        if (tradingEngine == null) {
            return "無交易引擎資料";
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

    // =========================
    // 持倉摘要
    // =========================
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
                """.formatted(
                cash,
                quantity,
                avgPrice,
                totalCost,
                presentValue,
                pnl
        );
    }

    // =========================
    // 最新日期
    // =========================
    private String getLatestDate(List<StockData> historyData) {
        if (historyData == null || historyData.isEmpty()) {
            return "N/A";
        }
        return historyData.get(historyData.size() - 1).getDate();
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
    // AI 內部上下文模型
    // =========================
    private static class AiContext {
        String stockId;
        double currentPrice;
        String latestDate;
        String historySummary;
        String tradeSummary;
        String holdingSummary;
    }

    // =========================
    // 本地模型引擎
    // =========================
    private static class LocalModelEngine {
        public String ask(String prompt) {
            // TODO:
            // 之後改成真正串接本地大模型
            // 例如：Ollama / LM Studio / vLLM / 本地 HTTP API
            return "[LOCAL MODEL 回應]\n" + prompt;
        }
    }

    // =========================
    // API 模型引擎
    // =========================
    private static class ApiModelEngine {
        public String ask(String prompt) {
            // TODO:
            // 之後改成真正串接外部 API
            // 例如：OpenAI API
            return "[API MODEL 回應]\n" + prompt;
        }
    }
}