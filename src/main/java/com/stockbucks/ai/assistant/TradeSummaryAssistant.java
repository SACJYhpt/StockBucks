package com.stockbucks.ai.assistant;

import com.stockbucks.ai.client.ModelClient;
import com.stockbucks.ai.model.AiContext;

public class TradeSummaryAssistant {

    private final ModelClient modelClient;

    public TradeSummaryAssistant(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    public String summarize(AiContext context) {
        String prompt = """
                你是 StockBucks 的交易摘要助手。

                股票代號：%s
                日期：%s
                當前價格：%.2f

                交易摘要：
                %s

                持倉摘要：
                %s

                請整理：
                1. 今日交易重點
                2. 目前持倉狀況
                3. 風險與注意事項
                """.formatted(
                context.getStockId(),
                context.getLatestDate(),
                context.getCurrentPrice(),
                context.getTradeSummary(),
                context.getHoldingSummary()
        );

        return modelClient.ask(prompt);
    }
}