package com.stockbucks.ai.assistant;

import com.stockbucks.ai.client.ModelClient;
import com.stockbucks.ai.model.AiContext;

public class MarketAnalysisAssistant {

    private final ModelClient modelClient;

    public MarketAnalysisAssistant(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    public String analyze(AiContext context) {
        String prompt = """
                你是 StockBucks 的市場模擬分析助手。

                模式：%s
                股票代號：%s
                日期：%s
                當前價格：%.2f

                歷史摘要：
                %s

                交易摘要：
                %s

                持倉摘要：
                %s

                請分析：
                1. 目前市場狀態
                2. 價格可能行為解釋
                3. 風險提醒
                """.formatted(
                context.getModeDescription(),
                context.getStockId(),
                context.getLatestDate(),
                context.getCurrentPrice(),
                context.getHistorySummary(),
                context.getTradeSummary(),
                context.getHoldingSummary()
        );

        return modelClient.ask(prompt);
    }
}