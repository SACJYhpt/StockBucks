package com.stockbucks.ai.mode;

import com.stockbucks.ai.model.MarketSnapshot;

public class RandomAiMode {

    public MarketSnapshot getSnapshot(String stockId, double currentPrice) {
        return new MarketSnapshot(
                stockId,
                "AI_RANDOM",
                "AI 全隨機股市模式",
                currentPrice,
                "AI 全隨機股市模式：由 AI 或演算法生成市場情境"
        );
    }
}