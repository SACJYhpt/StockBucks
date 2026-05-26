package com.stockbucks.ai.mode;

public enum MarketMode {
    REALTIME("即時長線模擬"),
    HISTORY("歷史高速模擬"),
    AI_RANDOM("AI 隨機模擬");

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
