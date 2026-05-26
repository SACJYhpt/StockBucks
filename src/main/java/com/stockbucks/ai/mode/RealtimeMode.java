package com.stockbucks.ai.mode;

import com.stockbucks.ai.data.MarketDataService;
import com.stockbucks.ai.data.MarketDataUpdateResult;
import com.stockbucks.ai.model.MarketSnapshot;

public class RealtimeMode {

    private final MarketDataService marketDataService;

    public RealtimeMode(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    public MarketSnapshot getSnapshot(String stockId, double currentPrice) {
        MarketDataUpdateResult result = marketDataService.updateHistoricalData(stockId);
        String latestDate = marketDataService.getLatestDate(stockId).orElse("REALTIME");

        return new MarketSnapshot(
                stockId,
                latestDate,
                "即時更新模式",
                currentPrice,
                "擷取筆數：" + result.getFetchedCount() + "，寫入筆數：" + result.getSavedCount()
        );
    }
}
