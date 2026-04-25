package com.stockbucks;

public class App {
    public static void main(String[] args) {
        TradingEngine engine = new TradingEngine();
        engine.buy("2330", 600, 10);
    }
}
