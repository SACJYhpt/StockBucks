package com.stockbucks.ai.mode;

public enum MarketMode {
    REALTIME("真實即時模式"),
    HISTORY("歷史回測模式"),
    AI_RANDOM("AI模擬盤模式");

    private final String displayName;

    MarketMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
