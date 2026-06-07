package com.stockbucks;

import java.util.Random;
import java.util.ArrayList;
import java.util.List;

public class PriceSimulator {
    // 價格模擬器
    private Random random = new Random();
    private List <Double> dayPath = new ArrayList<>();
    private int currentIndex = 0;

    public double getTickChangeSize(double price) {
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

    public double calculateLimitPrice(double yesterdayClose, boolean isUp) {
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

        int totalTime = 270; // 09:00 - 13:30， 早盤 10:30 午盤 12:30 晚盤
        int firstTime = random.nextInt(90) + 30;
        int secondTime = random.nextInt(80) + 160;
        int highTime = 0;
        int lowTime = 0;
        boolean wash = random.nextDouble() < 0.30;
        double priceDiff = close - open;
        double threshold = yesterdayClose*0.005; // 0.5%內算平盤

        if (priceDiff > threshold) {
            if (wash) { // 先噴高，後殺低，再拉高
                highTime = firstTime;
                lowTime = secondTime;
            }
            else { // 整體漲，先探底後拉高
                lowTime = firstTime;
                highTime = secondTime;
            }
        }
        else if (priceDiff < threshold*-1) {
            if (wash) { // 先探底，後拉高，再殺低
                lowTime = firstTime;
                highTime = secondTime;
            }
            else { // 整體跌，先噴高後殺低
                highTime = firstTime;
                lowTime = secondTime;
            }
        }
        else {
            // 平盤，隨機
            if (random.nextBoolean()) {
                highTime = firstTime;
                lowTime = secondTime;
            }
            else {
                lowTime = firstTime;
                highTime = secondTime;
            }
        }

        int firstTargetTime = Math.min(highTime, lowTime);
        double firstTargetPrice = (highTime < lowTime) ? high : low;
        int secondTargetTime = Math.max(highTime, lowTime);
        double secondTargetPrice = (highTime > lowTime) ? high : low;
        
        double momentum = 0.0; // 動能因子
        double currentPrice = open;
        dayPath.add(currentPrice);

        for (int i=1; i<=totalTime; i++) {
            if (i == 270 || (high == low && low == open && open == close)) {
                dayPath.add(close);
                continue;
            }

            double targetPrice;
            int targetTime;
            if (i <= firstTargetTime) {
                targetPrice = firstTargetPrice;
                targetTime = firstTargetTime;
            }
            else if (i <= secondTargetTime) {
                targetPrice = secondTargetPrice;
                targetTime = secondTargetTime;
            }
            else {
                targetPrice = close;
                targetTime = totalTime - 5;
            }

            double baseVolatility = 1.0;
            if (i <= 40 || i >= 240) {
                baseVolatility = 1.8; // 09:00 - 09:40、13:00 - 13:30 波動較大
            }

            double intervalProbability = (targetPrice > currentPrice) ? 0.54 : 0.46;

            int tickLeft = targetTime - i;
            double pullForce = 0.0;
            if (tickLeft > 0) {
                double priceGap = targetPrice - currentPrice;
                double needTick = priceGap/getTickChangeSize(currentPrice);
                pullForce = (needTick/tickLeft) * 0.1;
                // baseProbability += pullForce;
            }

            if (targetPrice == high && currentPrice >= high-(2*getTickChangeSize(currentPrice))) {
                intervalProbability = 0.58;
            }
            else if (targetPrice == low && currentPrice <= low+(2*getTickChangeSize(currentPrice))) {
                intervalProbability = 0.42;
            }

            double upProbability = intervalProbability + momentum + pullForce;
            upProbability = Math.max(0.01, Math.min(0.99, upProbability));
            
            if (currentPrice >= limitUp || currentPrice >= high) {
                upProbability = 0.0;
            }
            else if (currentPrice <= limitDown || currentPrice <= low) {
                upProbability = 1.0;
            }
            
            boolean isUp = false;
            boolean isDown = false;
            if (random.nextDouble() > 0.25) {
                isUp = random.nextDouble() < upProbability;
                isDown = !isUp;
            }

            double distanceToTarget = Math.abs(targetPrice - currentPrice);
            double currentTickSize = getTickChangeSize(currentPrice);
            double maxExpectedVolatility = baseVolatility + Math.min(1.5, distanceToTarget/(10 * currentTickSize));

            int moveTick = 1;
            if (maxExpectedVolatility > 1.0 && random.nextDouble() < 0.4) {
                moveTick = (int)(random.nextDouble() * maxExpectedVolatility) + 1;
            }

            for (int t=0; t<moveTick; t++) {
                double tick = getTickChangeSize(currentPrice);
                if (isUp) {
                    currentPrice += tick;
                    if (currentPrice > high) {
                        currentPrice = high;
                        break;
                    }
                }
                else if (isDown) {
                    currentPrice -= tick;
                    if (currentPrice < low) {
                        currentPrice = low;
                        break;
                    }
                }
            }

            if (isUp) {
                momentum = Math.min(momentum + 0.03, 0.15);
            }
            else if (isDown) {
                momentum = Math.max(momentum - 0.03, -0.15);
            }
            else {
                momentum *= 0.5;
            }

            // if (i == firstTargetTime) currentPrice = firstTargetPrice;
            // if (i == secondTargetTime) currentPrice = secondTargetPrice;
            // if (i == totalTime) currentPrice = close;
            currentPrice = Math.round(currentPrice*100.0)/100.0;
            dayPath.add(currentPrice);
        }
    }

    public double getNextPrice() {
        if (currentIndex < dayPath.size()) {
            return dayPath.get(currentIndex++);
        }
        return -1;
    }
}
