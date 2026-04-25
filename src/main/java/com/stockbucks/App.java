package com.stockbucks;

public class App {
    public static void main(String[] args) {
        User me = new User();
        TradingEngine tradeTest = new TradingEngine();
        String TSMCID = "2330";

        System.out.println("   ===Welcome===   ");
        System.out.printf("現金餘額: %,.2f\n", me.getCash());
        System.out.println("-------------------");

        System.out.println("\n買入500股台積電: ");
        tradeTest.trading(me, TSMCID, 500, 600.0, true);

        System.out.println("\n當天賣出100股台積電");
        tradeTest.trading(me, TSMCID, 100, 610, false);

        System.err.println("\n買入超額股票");
        tradeTest.trading(me, TSMCID, 3000, 800, true);

        System.out.println("\n   ===結算===   ");
        double currentTSMCPrice = 620.0;
        System.out.println("剩餘現金: "+me.getCash());
        System.out.println("擁有股數: "+me.getStockQuantity(TSMCID));
        System.err.println("平均成本: "+me.getOneAveragePrice(TSMCID));
        System.out.println("當前成本: "+me.getOneTotalCost(TSMCID));
        System.out.println("當前市值: "+me.getOnePresentValue(TSMCID, currentTSMCPrice));
        System.out.println("預計損益: "+me.getOneNetWorth(TSMCID, currentTSMCPrice));

        System.err.println("\n   ===交易清單===   ");
        for (String record: tradeTest.getDailyRecords()) {
            System.out.println(record);
        }
    }
}
