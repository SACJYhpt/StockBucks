package com.stockbucks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CsvLoading {
    // 資料讀取器
    // 讀取全域日曆 (以此為基準日曆)
    public List <String> loadGlobalCanlendar(String baseStockID) {
        String csvFile = "data/TestDataTSMC.csv";
        // String csvFile = "data/" + baseStockID + ".csv";
        try (Stream <String> lines = Files.lines(Paths.get(csvFile))) {
            return lines.skip(1)
                        .map(line -> line.split(",")[0].split(" ")[0])
                        .distinct()
                        .sorted()
                        .collect(Collectors.toList());
        }
        catch (IOException e) {
            throw new RuntimeException("初始化全域日曆失敗: " + e.getMessage());
        }
    }

    // 讀取股票數據
    public void streamStockData(String stockID, Consumer <StockData> processor) {
        String csvFile = "data/TestDataTSMC.csv";
        // String csvFile = "data/" + baseStockID + ".csv";
        
        try (Stream <String> lines = Files.lines(Paths.get(csvFile))) {
            lines.skip(1)
                 .map(line -> {
                    String[] values = line.split(",");
                    if (values.length < 5) return null;
                    return new StockData(stockID, values[0], values[1], values[2], values[3], values[4]);
                 })
                 .filter(data -> data != null)
                 .forEach(processor);
        } catch (IOException e) {
            System.err.println("讀取股票"+stockID+"失敗: "+e.getMessage());
        }
    }
}
