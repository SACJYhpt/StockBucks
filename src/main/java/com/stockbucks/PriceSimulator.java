package com.stockbucks;

import java.util.Random;

public class PriceSimulator {
    // 價格模擬器
    private Random random = new Random();

    public double getSimulatePrice(StockData data) {
        double range = data.getHigh() - data.getLow();
        return data.getLow() + (random.nextDouble()*range);
    }
}
