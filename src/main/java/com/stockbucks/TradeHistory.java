package com.stockbucks;

import java.util.ArrayList;
import java.util.List;

public class TradeHistory {
    private List <TradingEngine> logs = new ArrayList<>();

    public void addRecord(TradingEngine record) {
        logs.add(record);
    }

    public void exportToTxt(String fileName) {
        System.out.println("將第"+logs.size()+"筆紀錄匯出至"+fileName);
        // TODO 寫入檔案
    }
}
