# StockBucks API 使用手冊

這份手冊說明 `com.stockbucks.api` 內的功能如何使用，主要給同學對接正式 UI、測試股票資料、測試 AI、檢查環境變數與查看本地快取。

## 1. API 主要入口

主要入口是：

```java
import com.stockbucks.api.AIHub;

AIHub aiHub = new AIHub();
```

同學正式 UI 原則上只需要接 `AIHub`，不用直接碰底層的 `MarketDataService`、TWSE、Web 爬蟲或 AI provider。

## 2. 股票即時報價

取得目前最適合使用的報價：

```java
StockQuote quote = aiHub.fetchStockQuote("2330");
```

回傳資料包含：

```java
quote.getStockId();      // 股票代號
quote.getStockName();    // 股票名稱
quote.getLastPrice();    // 最新價
quote.getOpenPrice();    // 開盤價
quote.getHighPrice();    // 最高價
quote.getLowPrice();     // 最低價
quote.getVolume();       // 成交量
quote.getProvider();     // 實際來源
quote.getFetchedAt();    // 抓取時間
```

如果要知道每個來源的狀態，例如 web 成功、TWSE 無資料、Fugle 缺 Key：

```java
List<StockQuoteAttempt> attempts = aiHub.fetchStockQuoteAttempts("2330");
```

這適合 debug 或顯示「目前資料來自哪裡」。

## 3. 歷史資料

取得指定區間的歷史日資料：

```java
LocalDate fromDate = LocalDate.of(2026, 6, 1);
LocalDate toDate = LocalDate.of(2026, 6, 9);

List<StockData> history = aiHub.fetchStockHistory("2330", fromDate, toDate);
```

`StockData` 會提供：

```java
data.getDate();              // 日期
data.getStockID();           // 股票代號
data.getStockName();         // 股票名稱
data.getOpen();              // 開盤
data.getHigh();              // 最高
data.getLow();               // 最低
data.getClose();             // 收盤
data.getVolume();            // 成交量
data.getTurnover();          // 成交金額
data.getTransactionCount();  // 成交筆數
data.getPriceChange();       // 漲跌
```

歷史資料會自動做：

- 跳過週末。
- 選到非交易日時，往最近有資料的交易日修正。
- 優先使用本地快取。
- 快取不足時只補抓缺少的前段或後段。
- 回傳完整區間資料。

如果要告訴使用者系統跳過了什麼日期：

```java
String message = aiHub.describeHistoryDateAdjustment(fromDate, toDate);
```

範例文字：

```text
已自動跳過非交易日：結束日 2026-06-07 非交易日，已改查 2026-06-05。
```

如果同學 UI 選到某一天，但那天沒有資料，可以找最近可用交易日：

```java
LocalDate availableDate = aiHub.resolveAvailableHistoryDate("2330", selectedDate);
```

## 4. 盤中 K 線

取得最適合的盤中 K 線資料：

```java
List<IntradayBar> bars = aiHub.fetchBestIntradayBars("2330", "1m");
```

每筆 `IntradayBar` 包含：

```java
bar.getStockId();   // 股票代號
bar.getTime();      // K 線時間
bar.getOpen();      // 開
bar.getHigh();      // 高
bar.getLow();       // 低
bar.getClose();     // 收
bar.getVolume();    // 量
bar.getProvider();  // 來源
```

如果要查看每個來源有沒有拿到 K 線：

```java
List<StockIntradayAttempt> attempts = aiHub.fetchStockIntradayAttempts("2330", "1m");
```

目前邏輯會優先找更細的時間單位資料，例如：

```text
broker / web 1m > 5m > 1h > TWSE 日資料備援
```

TWSE 沒有當天盤中 K 線時，會被標成 `daily only`，不是拿來假裝即時 K 線。

## 5. 股票資料快取

API 目前有自己的股票資料快取，不使用同學的 `SaveData` / `SaveManager`。

快取位置：

```text
data/api_cache
```

快取檔案大致會長這樣：

```text
quote_2330.tsv
intraday_2330_1m.tsv
history_2330.tsv
```

快取用途：

- 報價快取：減少短時間重複抓即時報價。
- 盤中 K 線快取：加快 K 線圖表開啟。
- 歷史資料快取：加速歷史模式，不用每次都從 2010 年重新抓。

查快取狀態：

```java
String status = aiHub.getStockCacheStatus();
String path = aiHub.getMarketDatabasePath();
```

注意：

```text
SaveData / SaveManager 是同學的模擬進度存檔。
data/api_cache 是我們 API 的股票資料快取。
兩者不要混在一起，避免舊存檔不相容。
```

## 6. AI 問答

最簡單的對接方式：

```java
String answer = aiHub.askGeneralAi("請用繁體中文說明目前 AI 是否可用。");
```

如果要讓 AI 直接分析某檔股票，推薦同學使用這個方法：

```java
String answer = aiHub.answerStockQuestion("2330", "請分析這檔股票目前適不適合短線觀察。");
```

這個方法會自動：

- 抓股票報價。
- 抓最近 30 天歷史資料。
- 組成 AI prompt。
- 回傳 AI 回覆。
- 標示回答來源是哪個 AI。

直接問 AI：

```java
String answer = aiHub.askAi("請用繁體中文分析台積電目前資料。");
```

AI 回覆會標示實際回答來源，例如：

```text
回答 AI：gemini
```

如果主要 AI 失敗，系統會依序 fallback：

```text
openai -> anthropic -> gemini -> openrouter -> openai-compatible -> ollama
```

查 AI 設定狀態：

```java
String status = aiHub.getAiConfigurationStatus();
List<String> allStatus = aiHub.getAllAiProviderStatusLines();
```

## 7. 環境變數

系統會讀取：

```text
.env
stockbucks.local.env
stockbucks.env
使用者家目錄 ~/.stockbucks/.env
```

建議把真 Key 放在：

```text
stockbucks.local.env
```

不要提交真 Key 到 Git。

常用設定：

```text
AI_PROVIDER=gemini
GEMINI_API_KEY=你的 Key

OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=stockbucks-qwen2.5-tw

STOCK_PROVIDER_CHAIN=broker,fugle,web,twse,tpex,finmind,local
STOCK_HISTORY_PROVIDER_CHAIN=twse,tpex,web,finmind,local
STOCK_INTRADAY_PROVIDER_CHAIN=broker,web,fugle,twse,tpex,finmind,local

STOCK_CACHE_ENABLED=true
STOCK_CACHE_DIR=data/api_cache
STOCK_CACHE_QUOTE_TTL_SECONDS=30
STOCK_CACHE_INTRADAY_TTL_SECONDS=300
STOCK_CACHE_HISTORY_TTL_SECONDS=86400

FINMIND_TOKEN=你的 FinMind token
FINMIND_BASE_URL=https://api.finmindtrade.com/api/v4/data
FINMIND_SNAPSHOT_URL=https://api.finmindtrade.com/api/v4/taiwan_stock_tick_snapshot
```

## 8. Debug 介面

Debug 介面：

```java
com.stockbucks.api.debug.ApiDebugDashboard
```

可以檢查：

- 總覽
- 檔案功能
- 抓取檔案
- 報價來源
- 盤中 K 線圖
- 歷史資料
- 環境變數
- AI 狀態
- 儲存 / 券商

建議測試流程：

1. 先看 `環境變數`，確認 Key 是否缺少。
2. 看 `報價來源`，確認股票即時資料從哪裡來。
3. 看 `盤中 K 線`，確認 K 線圖和 OHLCV 表格。
4. 看 `歷史資料`，確認是否自動跳過非交易日。
5. 看 `抓取檔案`，確認快取檔是否產生。
6. 看 `AI 狀態`，確認回答來源是哪個 AI。

## 9. CLI 快速測試

如果不開 GUI，可以用：

```text
com.stockbucks.api.debug.AiSystemCheck
```

常用參數：

```text
quote 2330
history 2330
intraday 2330 1m
ai ollama
```

## 10. FinMind 即時快照

FinMind 報價來源會優先使用：

```text
https://api.finmindtrade.com/api/v4/taiwan_stock_tick_snapshot
```

用途：

- 補台股即時或近即時報價。
- 有 `FINMIND_TOKEN` 時才會啟用。
- snapshot 沒資料時會退回 `TaiwanStockPrice` 最近日資料。

## 11. 給同學的對接重點

正式 UI 建議只接這些方法：

```java
aiHub.fetchStockQuote(stockId);
aiHub.fetchStockQuoteAttempts(stockId);

aiHub.fetchStockHistory(stockId, fromDate, toDate);
aiHub.describeHistoryDateAdjustment(fromDate, toDate);
aiHub.resolveAvailableHistoryDate(stockId, selectedDate);

aiHub.fetchBestIntradayBars(stockId, interval);
aiHub.fetchStockIntradayAttempts(stockId, interval);

aiHub.askGeneralAi(question);
aiHub.answerStockQuestion(stockId, question);
aiHub.askAi(prompt);
aiHub.getAiConfigurationStatus();
```
```
📂 StockBucks/
├── 📂 data/
│   ├── TestDataTSMC.csv              # 初始測試用的台積電近五年股票數據
│   └── 📂 api_cache/                 # API 股票資料快取，包含報價、歷史資料、盤中 K 線
├── 📂 images/                        # 用以儲存 README 之圖片
├── 📂 src/
│   ├── 📂 main/
│   │   ├── 📂 java/com/stockbucks/
│   │   │   ├── 📂 api/               # 我方 API 與 AI 功能區
│   │   │   │   ├── 📄 AIHub.java     # 給同學對接 AI、股票 API 的主要入口
│   │   │   │   ├── 📂 ai/
│   │   │   │   │   ├── 📄 ApiModelClient.java  # 串接 Gemini、OpenAI、Anthropic、OpenRouter、Ollama
│   │   │   │   │   └── 📄 ModelClient.java     # AI 模型呼叫介面
│   │   │   │   ├── 📂 stock/
│   │   │   │   │   ├── 📄 MarketDataService.java       # 股票資料調度核心，處理來源優先級、fallback、快取
│   │   │   │   │   ├── 📄 WebStockScraperClient.java   # 網路股票資料爬蟲
│   │   │   │   │   ├── 📄 TwseHistoricalDataClient.java # TWSE 官方資料來源
│   │   │   │   │   ├── 📄 TpexStockDataClient.java     # TPEx 資料來源
│   │   │   │   │   ├── 📄 FinMindStockDataClient.java  # FinMind 股票 API
│   │   │   │   │   ├── 📄 FugleStockDataClient.java    # Fugle 股票 API
│   │   │   │   │   ├── 📄 StockDataCache.java          # 我方 API 股票資料快取
│   │   │   │   │   ├── 📄 StockQuote.java              # 股票報價資料物件
│   │   │   │   │   └── 📄 IntradayBar.java             # 盤中 K 線資料物件
│   │   │   │   ├── 📂 config/
│   │   │   │   │   ├── 📄 EnvironmentConfig.java       # 自動讀取/生成 stockbucks.local.env
│   │   │   │   │   ├── 📄 stockbucks.env.example       # 環境變數範本
│   │   │   │   │   └── 📄 setup-stockbucks-api.ps1     # 同學一鍵準備 API/AI 環境腳本
│   │   │   │   └── 📂 debug/
│   │   │   │       ├── 📄 ApiDebugDashboard.java       # API 診斷介面
│   │   │   │       └── 📄 AiSystemCheck.java           # API/AI 命令列測試工具
│   │   │   ├── 📂 gui/
│   │   │   │   ├── 📄 CandleStickChart.java
│   │   │   │   ├── 📄 MainApp.java
│   │   │   │   ├── 📄 MainController.java
│   │   │   │   └── 📄 WelcomeUI.java
│   │   │   ├── 📄 App.java
│   │   │   ├── 📄 CsvLoading.java
│   │   │   ├── 📄 SaveData.java
│   │   │   ├── 📄 SaveManager.java
│   │   │   ├── 📄 StockData.java
│   │   │   └── 📄 TradingEngine.java
│   │   └── 📂 resources/com/stockbucks/gui/
│   │       ├── 📄 main_view.fxml
│   │       └── 📄 stonks-meme.gif
│   └── 📂 test/java/com/stockbucks/
│       └── 📄 AppTest.java
├── 📄 pom.xml
├── 📄 stockbucks.local.env           # 本機私有環境檔，不上傳 Git
└── 📄 stockbucks_ai.db
```