package com.stockbucks.api.debug;

import com.stockbucks.api.AIHub;
import com.stockbucks.api.ai.ApiModelClient;
import com.stockbucks.api.stock.StockIntradayAttempt;
import com.stockbucks.api.stock.StockHistoryAttempt;
import com.stockbucks.api.stock.StockQuote;

import java.time.LocalDate;
import java.util.Map;

/**
 * API 狀態快速檢查工具。
 * 可用參數：quote 2330、history 2330、ai ollama。
 */
public final class AiSystemCheck {
    private AiSystemCheck() {
    }

    public static void main(String[] args) {
        AIHub hub = new AIHub();

        printTitle("AI 設定");
        System.out.println(hub.getAiConfigurationStatus());
        String missingAiKey = hub.getMissingApiKeyName();
        System.out.println(missingAiKey == null || missingAiKey.isBlank()
                ? "AI API Key 狀態：已就緒或不需要 Key"
                : "AI API Key 狀態：缺少 " + missingAiKey);

        printTitle("股票資料來源狀態");
        for (Map.Entry<String, String> entry : hub.getStockProviderStatus().entrySet()) {
            System.out.println(entry.getKey() + " -> " + entry.getValue());
        }

        printTitle("券商狀態");
        System.out.println(hub.getBrokerStatus());

        printTitle("支援的股票資料端點");
        for (Map.Entry<String, String> entry : hub.supportedStockApiEndpoints().entrySet()) {
            System.out.println(entry.getKey() + " = " + entry.getValue());
        }

        if (args.length >= 2 && "ai".equalsIgnoreCase(args[0])) {
            printAi(args[1]);
        } else if (args.length >= 2 && "quote".equalsIgnoreCase(args[0])) {
            printQuote(hub, args[1]);
        } else if (args.length >= 2 && "history".equalsIgnoreCase(args[0])) {
            printHistory(hub, args[1]);
        } else if (args.length >= 2 && "intraday".equalsIgnoreCase(args[0])) {
            String interval = args.length >= 3 ? args[2] : "1m";
            printIntraday(hub, args[1], interval);
        } else {
            printTitle("測試方式");
            System.out.println("測即時報價：quote 2330");
            System.out.println("測歷史來源：history 2330");
            System.out.println("測盤中 K 線：intraday 2330 1m");
            System.out.println("測 AI 來源：ai ollama");
        }
    }

    private static void printAi(String provider) {
        printTitle("AI 測試：" + provider);
        ApiModelClient client = new ApiModelClient(provider);
        System.out.println(client.getConfigurationStatus());
        System.out.println(client.ask("請只回覆 OK，用來測試 API 是否可用。"));
    }

    private static void printQuote(AIHub hub, String stockId) {
        printTitle("報價測試：" + stockId);
        StockQuote quote = hub.fetchStockQuote(stockId);
        if (quote == null) {
            System.out.println("抓不到報價。");
            System.out.println("fallback 原因：" + hub.getLastStockFallbackReason());
            return;
        }

        System.out.println("股票代號：" + quote.getStockId());
        System.out.println("股票名稱：" + quote.getStockName());
        System.out.println("最新價格：" + quote.getLastPrice());
        System.out.println("資料來源：" + quote.getProvider());
        System.out.println("成功 provider：" + hub.getLastStockProviderUsed());
        String reason = hub.getLastStockFallbackReason();
        if (reason != null && !reason.isBlank()) {
            System.out.println("前面來源略過/失敗原因：" + reason);
        }
    }

    private static void printHistory(AIHub hub, String stockId) {
        printTitle("歷史資料來源測試：" + stockId);
        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusDays(10);
        for (StockHistoryAttempt attempt : hub.fetchStockHistoryAttempts(stockId, fromDate, toDate)) {
            System.out.println(attempt.getProviderName()
                    + "：" + attempt.getStatus()
                    + " | 筆數 " + attempt.getRowCount()
                    + " | 日期 " + attempt.getFirstDate() + " ~ " + attempt.getLastDate()
                    + " | " + attempt.getMessage());
        }

        System.out.println();
        System.out.println("合併後筆數：" + hub.fetchStockHistory(stockId, fromDate, toDate).size());
        System.out.println("日期調整：" + hub.describeHistoryDateAdjustment(fromDate, toDate));
        System.out.println("今天是否可能開盤：" + (hub.isPotentialTradingDay(toDate) ? "是" : "否，會自動跳過"));
        LocalDate availableDate = hub.resolveAvailableHistoryDate(stockId, toDate);
        System.out.println("最近可用交易日：" + (availableDate == null ? "找不到" : availableDate));
        System.out.println("合併來源：" + hub.getLastStockProviderUsed());
        String reason = hub.getLastStockFallbackReason();
        if (reason != null && !reason.isBlank()) {
            System.out.println("未使用來源狀態：" + reason);
        }
    }

    private static void printIntraday(AIHub hub, String stockId, String interval) {
        printTitle("盤中 K 線來源測試：" + stockId + " / " + interval);
        for (StockIntradayAttempt attempt : hub.fetchStockIntradayAttempts(stockId, interval)) {
            System.out.println(attempt.getProviderName()
                    + "：" + attempt.getStatus()
                    + " | 粒度 " + attempt.getDataGranularity()
                    + " | 筆數 " + attempt.getBarCount()
                    + " | 時間 " + attempt.getFirstTime() + " ~ " + attempt.getLastTime()
                    + " | " + attempt.getMessage());
        }

        System.out.println();
        System.out.println("最佳盤中 K 筆數：" + hub.fetchBestIntradayBars(stockId, interval).size());
        System.out.println("最佳來源：" + hub.getLastStockProviderUsed());
        String reason = hub.getLastStockFallbackReason();
        if (reason != null && !reason.isBlank()) {
            System.out.println("fallback 狀態：" + reason);
        }
    }

    private static void printTitle(String title) {
        System.out.println();
        System.out.println("========== " + title + " ==========");
    }
}
