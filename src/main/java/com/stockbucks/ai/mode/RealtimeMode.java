package com.stockbucks.ai.mode;

import com.stockbucks.ai.data.MarketDataService;
import com.stockbucks.ai.model.MarketSnapshot;

public class RealtimeMode {

    private final MarketDataService marketDataService;

    public RealtimeMode(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    public MarketSnapshot getSnapshot(String stockId, double currentPrice) {
        return new MarketSnapshot(
                stockId,
                "REALTIME",
                "即時股票模式",
                currentPrice,
                "即時模式：目前可串接外部 API 或即時報價來源"
        );
    }
}