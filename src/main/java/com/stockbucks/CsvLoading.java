package com.stockbucks;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class CsvLoading {
    // 資料讀取器
    public List <StockData> loadDate() {
        List <StockData> history = new ArrayList<>();
        String csvFile = "data/TestDataTSMC.csv";
        
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line;
            br.readLine();
            while((line = br.readLine()) != null){
                String[] values = line.split(",");
                StockData data = new StockData(values[0], values[1], values[2], values[3], values[4]);
                history.add(data);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return history;
    }
}
