package com.stockbucks;

import java.io.Serializable;

public class SaveData implements Serializable {
    private static final long serialVersionUID = 1L;

    private User user;
    private int dayIndex;
    private String saveName;

    public SaveData(User user, int dayIndex, String saveName) {
        this.user = user;
        this.dayIndex = dayIndex;
        this.saveName = saveName;
    }

    // Getters
    public User getUser() { return user; }
    public int getDayIndex() { return dayIndex; }
    public String getSaveName() { return saveName; }
}