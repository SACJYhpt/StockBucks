package com.stockbucks;

import com.stockbucks.ai.mode.MarketMode;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;

public class SaveData implements Serializable {
    private static final long serialVersionUID = 1L;

    private User user;
    private int dayIndex;
    private String saveName;
    private MarketMode marketMode;

    //新增這個欄位：用來存儲可序列化的自選股分頁與字卡
    private LinkedHashMap<String, ArrayList<String>> serializableWatchlist;

    public SaveData(User user, int dayIndex, String saveName) {
        this(user, dayIndex, saveName, MarketMode.HISTORY, null);
    }

    public SaveData(User user, int dayIndex, String saveName, MarketMode marketMode) {
        this(user, dayIndex, saveName, marketMode, null);
    }

    public SaveData(User user, int dayIndex, String saveName, MarketMode marketMode, LinkedHashMap<String, ArrayList<String>> watchlist) {
        this.user = user;
        this.dayIndex = dayIndex;
        this.saveName = saveName;
        this.marketMode = marketMode == null ? MarketMode.HISTORY : marketMode;
        this.serializableWatchlist = watchlist;
    }

    // Getters
    public User getUser() { return user; }
    public int getDayIndex() { return dayIndex; }
    public String getSaveName() { return saveName; }
    public MarketMode getMarketMode() { return marketMode == null ? MarketMode.HISTORY : marketMode; }
    public LinkedHashMap<String, ArrayList<String>> getSerializableWatchlist() { return serializableWatchlist; }
}
