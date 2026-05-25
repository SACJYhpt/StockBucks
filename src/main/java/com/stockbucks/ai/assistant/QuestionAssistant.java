package com.stockbucks.ai.assistant;

import com.stockbucks.ai.client.ModelClient;
import com.stockbucks.ai.model.AiContext;

public class QuestionAssistant {

    private final ModelClient modelClient;

    public QuestionAssistant(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    public String answer(AiContext context, String question) {
        String prompt = """
                你是 StockBucks 的 AI 股票解惑助手。

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

                使用者問題：
                %s

                請用繁體中文回答，內容要清楚、精簡、可讀。
                """.formatted(
                context.getModeDescription(),
                context.getStockId(),
                context.getLatestDate(),
                context.getCurrentPrice(),
                context.getHistorySummary(),
                context.getTradeSummary(),
                context.getHoldingSummary(),
                question
        );

        return modelClient.ask(prompt);
    }
}