package com.stockbucks;

import java.util.Random;
import java.util.ArrayList;
import java.util.List;

public class PriceSimulator {
    // 價格模擬器
    private Random random = new Random();
    private List <Double> dayPath = new ArrayList<>();
    private int currentIndex = 0;

    private double getTickChangeSize(double price) {
        if (price < 10) {
            return 0.01;
        }
        if (price < 50) {
            return 0.05;
        }
        if (price < 100) {
            return 0.1;
        }
        if (price < 500) {
            return 0.5;
        }
        if (price < 1000) {
            return 1.0;
        }
        return 5.0;
    }

    private double calculateLimitPrice(double yesterdayClose, boolean isUp) {
        double percentage = isUp ? 1.1 : 0.9;
        double limitPrice = yesterdayClose*percentage;
        double tick = getTickChangeSize(limitPrice);
        limitPrice = Math.round(limitPrice*100.0)/100.0;

        if (isUp) {
            return Math.floor(limitPrice/tick)*tick;
        }
        else {
            return Math.ceil(limitPrice/tick)*tick;
        }
    }

    public void generateDayPath(StockData data, double yesterdayClose) {
        dayPath.clear();
        currentIndex = 0;

        double open = data.getOpen();
        double high = data.getHigh();
        double low = data.getLow();
        double close = data.getClose();
        double limitUp = calculateLimitPrice(yesterdayClose, true);
        double limitDown = calculateLimitPrice(yesterdayClose, false);

        int totalTime = 270; // 09:00 - 13:30;
        double currentPrice = open;
        dayPath.add(currentPrice);

        for (int i=1; i<=totalTime; i++) {
            double tick = getTickChangeSize(currentPrice);
            double progress = (double) i/totalTime;

            if (close == high && currentPrice >= high) {
                if (random.nextDouble() < 0.96) {
                    dayPath.add(currentPrice);
                    continue;
                }
            }
            else if (close == low && currentPrice <= low) {
                if (random.nextDouble() < 0.96) {
                    dayPath.add(currentPrice);
                    continue;
                }
            }

            double baseProbability = 0.5;
            double bias = (close > currentPrice) ? 0.27 : -0.27;
            double upProbability = baseProbability+(bias*progress);
            
            if (currentPrice >= limitUp || currentPrice >= high) {
                upProbability = 0.0;
            }
            else if (currentPrice <= limitDown || currentPrice <= low) {
                upProbability = 1.0;
            }
            

            if (random.nextDouble() < upProbability) {
                currentPrice += tick;
            }
            else {
                currentPrice -= tick;
            }

            currentPrice = Math.round(currentPrice*100.0)/100.0;
            dayPath.add(currentPrice);
        }

        dayPath.set(dayPath.size()-1, close);
    }

    public double getNextPrice() {
        if (currentIndex < dayPath.size()) {
            return dayPath.get(currentIndex++);
        }
        return -1;
    }
}
