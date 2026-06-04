package com.stockbucks.api.mode;

/**
 * 舊版市場模式列舉。
 *
 * 目前 AI 資料來源已改成 provider fallback，但 SaveData / WelcomeUI 仍會引用此型別，
 * 所以保留它以維持相容。
 */
public enum MarketMode {
    REALTIME("Realtime API"),
    HISTORY("Historical API"),
    AI_RANDOM("API sample");

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
