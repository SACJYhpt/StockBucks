package com.stockbucks;

import com.stockbucks.ai.mode.MarketMode;

import java.io.Serializable;

public class SaveData implements Serializable {
    private static final long serialVersionUID = 1L;

    private User user;
    private int dayIndex;
    private String saveName;
    private MarketMode marketMode;

    public SaveData(User user, int dayIndex, String saveName) {
        this(user, dayIndex, saveName, MarketMode.HISTORY);
    }

    public SaveData(User user, int dayIndex, String saveName, MarketMode marketMode) {
        this.user = user;
        this.dayIndex = dayIndex;
        this.saveName = saveName;
        this.marketMode = marketMode == null ? MarketMode.HISTORY : marketMode;
    }

    // Getters
    public User getUser() { return user; }
    public int getDayIndex() { return dayIndex; }
    public String getSaveName() { return saveName; }
    public MarketMode getMarketMode() { return marketMode == null ? MarketMode.HISTORY : marketMode; }
}
