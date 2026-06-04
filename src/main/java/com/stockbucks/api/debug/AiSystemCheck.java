package com.stockbucks.api.debug;

import com.stockbucks.api.AIHub;
import com.stockbucks.api.stock.StockQuote;

import java.util.Map;

/**
 * AI 模組的獨立檢查入口。
 *
 * 這個類別只放在 ai/debug 內，不接 JavaFX，也不改同學的檔案。
 * 預設只印設定狀態；如果要真的抓股票報價，執行時加上：quote 2330。
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

        if (args.length >= 2 && "quote".equalsIgnoreCase(args[0])) {
            printQuote(hub, args[1]);
        } else {
            printTitle("報價測試");
            System.out.println("未執行報價抓取。若要測試，請執行參數：quote 2330");
        }
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

    private static void printTitle(String title) {
        System.out.println();
        System.out.println("========== " + title + " ==========");
    }
}
