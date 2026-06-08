package com.stockbucks.api.debug;

import com.stockbucks.StockData;
import com.stockbucks.api.AIHub;
import com.stockbucks.api.config.EnvironmentConfig;
import com.stockbucks.api.stock.BrokerAccountSnapshot;
import com.stockbucks.api.stock.BrokerPosition;
import com.stockbucks.api.stock.IntradayBar;
import com.stockbucks.api.stock.StockHistoryAttempt;
import com.stockbucks.api.stock.StockIntradayAttempt;
import com.stockbucks.api.stock.StockQuote;
import com.stockbucks.api.stock.StockQuoteAttempt;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.paint.Color;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * API 範圍的臨時診斷介面。
 * 只檢查 API、爬蟲、AI、環境變數與資料對接，不接正式交易 UI。
 */
public class ApiDebugDashboard extends Application {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<String> AI_PROVIDERS = List.of(
            "openai", "anthropic", "gemini", "openrouter", "openai-compatible", "ollama"
    );

    private final AIHub hub = new AIHub();
    private final ObservableList<QuoteRow> quoteRows = FXCollections.observableArrayList();
    private final ObservableList<IntradayRow> intradayRows = FXCollections.observableArrayList();
    private final ObservableList<HistorySourceRow> historySourceRows = FXCollections.observableArrayList();
    private final ObservableList<DailyRow> dailyRows = FXCollections.observableArrayList();
    private final ObservableList<FileFunctionRow> fileFunctionRows = FXCollections.observableArrayList();
    private final ObservableList<FileStatusRow> fileStatusRows = FXCollections.observableArrayList();

    private TextField quoteSymbolsField;
    private TextField intradaySymbolField;
    private TextField intradayIntervalField;
    private TextField intradayFromDateField;
    private TextField intradayToDateField;
    private TextField intradayLimitField;
    private Canvas intradayChartCanvas;
    private Label intradayChartLabel;
    private TextField historySymbolField;
    private TextField historyFromDateField;
    private TextField historyToDateField;
    private TextField aiPromptField;
    private TextArea overviewArea;
    private TextArea environmentArea;
    private TextArea aiArea;
    private TextArea storageArea;
    private TextArea brokerArea;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("StockBucks API Debug Dashboard");
        stage.setScene(new Scene(createRoot(), 1260, 780));
        stage.show();

        refreshOverview();
        refreshQuotes();
        refreshIntraday();
        refreshHistory();
        refreshEnvironment();
        refreshAiStatus();
        refreshStorageAndBroker();
        refreshFileFunctions();
        refreshDownloadedFiles();
    }

    private BorderPane createRoot() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(14));
        root.setStyle("-fx-background-color: #161b22;");

        Label title = new Label("StockBucks API 診斷介面");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 24px; -fx-font-weight: bold;");
        Label subtitle = new Label("依照檔案功能檢視 API、股票資料、盤中 K 線、AI、環境變數與對接狀態。");
        subtitle.setStyle("-fx-text-fill: #9aa4b2;");
        VBox header = new VBox(4, title, subtitle);
        header.setPadding(new Insets(0, 0, 12, 0));

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                tab("總覽", createOverviewPane()),
                tab("檔案功能", createFileFunctionPane()),
                tab("抓取檔案", createDownloadedFilePane()),
                tab("報價來源", createQuotePane()),
                tab("盤中 K 線", createIntradayPane()),
                tab("歷史資料", createHistoryPane()),
                tab("環境變數", createEnvironmentPane()),
                tab("AI 狀態", createAiPane()),
                tab("儲存 / 券商", createStorageBrokerPane())
        );

        root.setTop(header);
        root.setCenter(tabs);
        return root;
    }

    private Tab tab(String title, VBox content) {
        Tab tab = new Tab(title);
        tab.setContent(content);
        return tab;
    }

    private VBox createOverviewPane() {
        Button refresh = new Button("重新整理總覽");
        refresh.setOnAction(event -> refreshOverview());
        overviewArea = createReadOnlyArea("總覽");
        VBox.setVgrow(overviewArea, Priority.ALWAYS);
        return pane(refresh, overviewArea);
    }

    private VBox createFileFunctionPane() {
        TableView<FileFunctionRow> table = table(fileFunctionRows);
        addColumn(table, "檔案", "file", 240);
        addColumn(table, "負責功能", "feature", 320);
        addColumn(table, "目前檢視重點", "diagnostic", 360);
        addColumn(table, "狀態", "status", 160);

        Button refresh = new Button("重新整理檔案功能");
        refresh.setOnAction(event -> refreshFileFunctions());
        VBox.setVgrow(table, Priority.ALWAYS);
        return pane(refresh, table);
    }

    private VBox createDownloadedFilePane() {
        TableView<FileStatusRow> table = table(fileStatusRows);
        addColumn(table, "檔案", "path", 360);
        addColumn(table, "用途", "role", 240);
        addColumn(table, "狀態", "status", 100);
        addColumn(table, "大小", "size", 100);
        addColumn(table, "最後更新", "lastModified", 170);
        addColumn(table, "備註", "note", 280);

        Button refresh = new Button("重新掃描抓取檔案");
        refresh.setOnAction(event -> refreshDownloadedFiles());
        VBox.setVgrow(table, Priority.ALWAYS);
        return pane(refresh, table);
    }


    private VBox createQuotePane() {
        quoteSymbolsField = new TextField("2330,0050,0052,00881");
        quoteSymbolsField.setPromptText("股票代號，例如 2330,0050,6770,7660");
        HBox.setHgrow(quoteSymbolsField, Priority.ALWAYS);

        Button refresh = new Button("測試報價來源");
        refresh.setOnAction(event -> refreshQuotes());
        HBox toolbar = toolbar(new Label("股票："), quoteSymbolsField, refresh);

        TableView<QuoteRow> table = table(quoteRows);
        addColumn(table, "股票", "stockId", 80);
        addColumn(table, "來源", "provider", 130);
        addColumn(table, "狀態", "status", 90);
        addColumn(table, "粒度", "granularity", 110);
        addColumn(table, "名稱", "stockName", 120);
        addColumn(table, "最新價", "lastPrice", 90);
        addColumn(table, "開盤", "openPrice", 90);
        addColumn(table, "最高", "highPrice", 90);
        addColumn(table, "最低", "lowPrice", 90);
        addColumn(table, "成交量", "volume", 100);
        addColumn(table, "訊息", "message", 260);
        VBox.setVgrow(table, Priority.ALWAYS);
        return pane(toolbar, table);
    }

    private VBox createIntradayPane() {
        intradaySymbolField = new TextField("2330");
        intradayIntervalField = new TextField("1m");
        intradayFromDateField = new TextField(LocalDate.now().minusDays(7).toString());
        intradayToDateField = new TextField(LocalDate.now().toString());
        intradayLimitField = new TextField("300");
        intradaySymbolField.setPromptText("股票代號");
        intradayIntervalField.setPromptText("1m / 5m / 1h");
        intradayFromDateField.setPromptText("yyyy-MM-dd");
        intradayToDateField.setPromptText("yyyy-MM-dd");
        intradayLimitField.setPromptText("最多筆數");

        Button refresh = new Button("測試盤中 K 線");
        refresh.setOnAction(event -> refreshIntraday());
        HBox toolbar = toolbar(
                new Label("股票："), intradaySymbolField,
                new Label("時間單位："), intradayIntervalField,
                new Label("起："), intradayFromDateField,
                new Label("迄："), intradayToDateField,
                new Label("最多："), intradayLimitField,
                refresh
        );

        TableView<IntradayRow> table = table(intradayRows);
        addColumn(table, "股票", "stockId", 80);
        addColumn(table, "來源", "provider", 120);
        addColumn(table, "狀態", "status", 90);
        addColumn(table, "粒度", "granularity", 90);
        addColumn(table, "時間", "time", 170);
        addColumn(table, "開", "open", 80);
        addColumn(table, "高", "high", 80);
        addColumn(table, "低", "low", 80);
        addColumn(table, "收", "close", 80);
        addColumn(table, "量", "volume", 100);
        addColumn(table, "訊息", "message", 280);
        VBox.setVgrow(table, Priority.ALWAYS);

        intradayChartLabel = sectionTitle("K 線圖：等待資料");
        intradayChartCanvas = new Canvas(1120, 260);
        intradayChartCanvas.widthProperty().addListener((obs, oldValue, newValue) -> refreshIntraday());
        VBox chartBox = new VBox(6, intradayChartLabel, intradayChartCanvas);
        chartBox.setStyle("-fx-background-color: #0d1117; -fx-padding: 10;");
        return pane(toolbar, chartBox, table);
    }

    private VBox createHistoryPane() {
        historySymbolField = new TextField("2330");
        historyFromDateField = new TextField(LocalDate.now().minusDays(30).toString());
        historyToDateField = new TextField(LocalDate.now().toString());
        historySymbolField.setPromptText("股票代號");
        historyFromDateField.setPromptText("yyyy-MM-dd");
        historyToDateField.setPromptText("yyyy-MM-dd");
        Button refresh = new Button("測試歷史資料");
        refresh.setOnAction(event -> refreshHistory());
        HBox toolbar = toolbar(
                new Label("股票："), historySymbolField,
                new Label("起："), historyFromDateField,
                new Label("迄："), historyToDateField,
                refresh
        );

        TableView<HistorySourceRow> sourceTable = table(historySourceRows);
        addColumn(sourceTable, "來源", "provider", 130);
        addColumn(sourceTable, "狀態", "status", 90);
        addColumn(sourceTable, "粒度", "granularity", 90);
        addColumn(sourceTable, "筆數", "count", 80);
        addColumn(sourceTable, "第一筆", "firstDate", 110);
        addColumn(sourceTable, "最後一筆", "lastDate", 110);
        addColumn(sourceTable, "訊息", "message", 300);
        sourceTable.setPrefHeight(210);

        TableView<DailyRow> dataTable = table(dailyRows);
        addColumn(dataTable, "日期", "date", 110);
        addColumn(dataTable, "股票", "stockId", 80);
        addColumn(dataTable, "名稱", "stockName", 120);
        addColumn(dataTable, "開", "open", 80);
        addColumn(dataTable, "高", "high", 80);
        addColumn(dataTable, "低", "low", 80);
        addColumn(dataTable, "收", "close", 80);
        addColumn(dataTable, "量", "volume", 110);
        VBox.setVgrow(dataTable, Priority.ALWAYS);
        return pane(toolbar, sectionTitle("來源診斷"), sourceTable, sectionTitle("合併後日資料前 80 筆"), dataTable);
    }

    private VBox createEnvironmentPane() {
        Button refresh = new Button("重新檢查環境變數");
        refresh.setOnAction(event -> refreshEnvironment());
        environmentArea = createReadOnlyArea("環境變數狀態");
        VBox.setVgrow(environmentArea, Priority.ALWAYS);
        return pane(refresh, environmentArea);
    }

    private VBox createAiPane() {
        aiPromptField = new TextField("請用繁體中文簡短說明目前股票資料來源狀態。");
        HBox.setHgrow(aiPromptField, Priority.ALWAYS);
        Button refresh = new Button("重新檢查 AI");
        refresh.setOnAction(event -> refreshAiStatus());
        Button ask = new Button("送出測試問題");
        ask.setOnAction(event -> askAi());

        aiArea = createReadOnlyArea("AI 狀態與回覆");
        VBox.setVgrow(aiArea, Priority.ALWAYS);
        return pane(toolbar(new Label("問題："), aiPromptField, ask, refresh), aiArea);
    }

    private VBox createStorageBrokerPane() {
        Button refresh = new Button("重新檢查儲存與券商");
        refresh.setOnAction(event -> refreshStorageAndBroker());
        storageArea = createReadOnlyArea("本地儲存狀態");
        brokerArea = createReadOnlyArea("券商狀態");
        VBox.setVgrow(storageArea, Priority.ALWAYS);
        VBox.setVgrow(brokerArea, Priority.ALWAYS);
        return pane(refresh, sectionTitle("本地儲存"), storageArea, sectionTitle("券商 API"), brokerArea);
    }

    private void refreshOverview() {
        runTextTask(overviewArea, "總覽檢查中...", () -> {
            StringBuilder text = new StringBuilder();
            text.append("== 簡易診斷 ==\n");
            text.append("AI：").append(hub.getAiConfigurationStatus()).append('\n');
            text.append("缺少 AI Key：").append(blankAsNone(hub.getMissingApiKeyName())).append('\n');
            text.append("券商：").append(hub.getBrokerStatus()).append('\n');
            text.append("股票來源：").append(providerSummary()).append('\n');
            text.append("上市股票清單：").append(safeCount(() -> hub.fetchAllListedStocks().size())).append(" 筆\n");
            text.append("全市場日資料：").append(safeCount(() -> hub.fetchAllStocksDailyMarket().size())).append(" 筆\n");
            text.append("本地市場資料庫：").append(hub.getMarketDatabasePath().isBlank() ? "未啟用正式儲存" : hub.getMarketDatabasePath()).append('\n');
            text.append('\n');
            text.append("== 建議先看 ==\n");
            text.append("1. 報價來源：確認每檔股票實際走哪個 provider。\n");
            text.append("2. 盤中 K 線：確認是否抓到最小時間單位資料。\n");
            text.append("3. 環境變數：確認缺少哪些 Key 或 URL。\n");
            text.append("4. 檔案功能：確認同學要從哪個 API 方法對接。\n");
            return text.toString();
        });
    }

    private void refreshFileFunctions() {
        fileFunctionRows.setAll(List.of(
                new FileFunctionRow("AIHub.java", "統一入口，給同學 UI 呼叫", "報價、歷史、盤中 K、AI、券商都從這裡轉接", "可對接"),
                new FileFunctionRow("MarketDataService.java", "股票資料 fallback 與合併", "依來源排序選最細資料，歷史資料做互補合併", "核心"),
                new FileFunctionRow("WebStockScraperClient.java", "公開網頁與 Yahoo chart 爬蟲", "即時報價與盤中 K 線的主要來源", "需常測"),
                new FileFunctionRow("TwseHistoricalDataClient.java", "TWSE 官方資料", "歷史與全市場資料較完整，但粒度偏日資料", "備援主力"),
                new FileFunctionRow("TpexStockDataClient.java", "TPEx 上櫃/興櫃資料", "補 TWSE 沒有的股票或市場", "互補"),
                new FileFunctionRow("FugleStockDataClient.java", "Fugle API", "有 Key 時可補即時與歷史資料", keyStatus("FUGLE_API_KEY")),
                new FileFunctionRow("FinMindStockDataClient.java", "FinMind API", "有 Token 時補歷史資料", keyStatus("FINMIND_TOKEN")),
                new FileFunctionRow("BrokerHttpStockDataClient.java", "券商帳戶與券商行情", "需要券商 URL、帳密或 token", brokerSimpleStatus()),
                new FileFunctionRow("ApiModelClient.java", "多 AI provider 呼叫", "OpenAI、Anthropic、Gemini、OpenRouter、Ollama 等", "可測"),
                new FileFunctionRow("EnvironmentConfig.java", "讀取環境變數與 env 檔", "避免把 Key 寫死在程式碼", "可用"),
                new FileFunctionRow("ApiDebugDashboard.java", "目前這個診斷介面", "只做 API 範圍檢查，不接正式 UI", "已整理")
        ));
    }

    private void refreshDownloadedFiles() {
        Task<List<FileStatusRow>> task = new Task<>() {
            @Override
            protected List<FileStatusRow> call() {
                List<FileStatusRow> rows = new ArrayList<>();
                rows.add(fileStatus(Path.of("data", "TestDataTSMC.csv"), "本地 CSV 備援", "同學舊資料與 local fallback 來源"));
                rows.add(fileStatus(Path.of("stockbucks.local.env"), "本機私有環境檔", "可放真 Key，已加入 git ignore"));
                rows.add(fileStatus(Path.of("stockbucks.env"), "共享環境檔", "若存在，會被 EnvironmentConfig 讀取"));
                rows.add(fileStatus(Path.of(".env"), "通用環境檔", "若存在，優先於 stockbucks.local.env 讀取"));
                rows.add(fileStatus(Path.of("src", "main", "java", "com", "stockbucks", "api", "config", "stockbucks.env.example"), "環境範本", "給其他電腦照著建立自己的 env"));

                rows.addAll(scanFolder(Path.of("data"), "資料目錄"));
                rows.addAll(scanFolder(Path.of("data", "api_cache"), "API 快取目錄"));
                rows.addAll(scanFolder(Path.of("logs"), "執行紀錄目錄"));
                return rows;
            }
        };
        task.setOnSucceeded(event -> fileStatusRows.setAll(task.getValue()));
        task.setOnFailed(event -> fileStatusRows.setAll(List.of(new FileStatusRow("", "", "失敗", "", "", message(task.getException())))));
        new Thread(task, "stockbucks-debug-files").start();
    }

    private void refreshQuotes() {
        List<String> symbols = parseSymbols(quoteSymbolsField == null ? "2330,0050,0052,00881" : quoteSymbolsField.getText());
        Task<List<QuoteRow>> task = new Task<>() {
            @Override
            protected List<QuoteRow> call() {
                List<QuoteRow> rows = new ArrayList<>();
                for (String symbol : symbols) {
                    for (StockQuoteAttempt attempt : hub.fetchStockQuoteAttempts(symbol)) {
                        rows.add(toQuoteRow(attempt));
                    }
                }
                return rows;
            }
        };
        task.setOnSucceeded(event -> quoteRows.setAll(task.getValue()));
        task.setOnFailed(event -> quoteRows.setAll(List.of(new QuoteRow("", "", "失敗", "", "", "", "", "", "", "", message(task.getException())))));
        new Thread(task, "stockbucks-debug-quotes").start();
    }

    private void refreshIntraday() {
        String symbol = cleanStockId(intradaySymbolField == null ? "2330" : intradaySymbolField.getText());
        String interval = intradayIntervalField == null ? "1m" : intradayIntervalField.getText().trim();
        if (interval.isBlank()) {
            interval = "1m";
        }
        LocalDate fromDate = parseDate(intradayFromDateField == null ? "" : intradayFromDateField.getText(), LocalDate.now().minusDays(7));
        LocalDate toDate = parseDate(intradayToDateField == null ? "" : intradayToDateField.getText(), LocalDate.now());
        int limit = parsePositiveInt(intradayLimitField == null ? "" : intradayLimitField.getText(), 300);
        String finalInterval = interval;
        Task<IntradayResult> task = new Task<>() {
            @Override
            protected IntradayResult call() {
                List<IntradayRow> rows = new ArrayList<>();
                List<IntradayBar> chartBars = new ArrayList<>();
                for (StockIntradayAttempt attempt : hub.fetchStockIntradayAttempts(symbol, finalInterval)) {
                    List<IntradayBar> filteredBars = attempt.getBars()
                            .stream()
                            .filter(bar -> isWithinDateRange(bar, fromDate, toDate))
                            .toList();
                    if (filteredBars.isEmpty()) {
                        rows.add(new IntradayRow(symbol, attempt.getProviderName(), localizeStatus(attempt.getStatus()),
                                attempt.getDataGranularity(), "", "", "", "", "", "",
                                rangeMessage(attempt.getBarCount(), 0, fromDate, toDate, attempt.getMessage())));
                    } else {
                        int start = Math.max(0, filteredBars.size() - limit);
                        for (IntradayBar bar : filteredBars.subList(start, filteredBars.size())) {
                            rows.add(toIntradayRow(attempt, bar));
                        }
                        if (chartBars.isEmpty() && "success".equals(attempt.getStatus())) {
                            chartBars.addAll(filteredBars.subList(start, filteredBars.size()));
                        }
                    }
                }
                return new IntradayResult(rows, chartBars, fromDate, toDate, finalInterval);
            }
        };
        task.setOnSucceeded(event -> {
            intradayRows.setAll(task.getValue().rows());
            drawIntradayChart(task.getValue().bars(), task.getValue().fromDate(), task.getValue().toDate(), task.getValue().interval());
        });
        task.setOnFailed(event -> {
            intradayRows.setAll(List.of(new IntradayRow(symbol, "", "失敗", "", "", "", "", "", "", "", message(task.getException()))));
            drawIntradayChart(List.of(), fromDate, toDate, finalInterval);
        });
        new Thread(task, "stockbucks-debug-intraday").start();
    }

    private void refreshHistory() {
        String symbol = cleanStockId(historySymbolField == null ? "2330" : historySymbolField.getText());
        LocalDate toDate = parseDate(historyToDateField == null ? "" : historyToDateField.getText(), LocalDate.now());
        LocalDate fromDate = parseDate(historyFromDateField == null ? "" : historyFromDateField.getText(), toDate.minusDays(30));
        Task<HistoryResult> task = new Task<>() {
            @Override
            protected HistoryResult call() {
                List<HistorySourceRow> sources = new ArrayList<>();
                for (StockHistoryAttempt attempt : hub.fetchStockHistoryAttempts(symbol, fromDate, toDate)) {
                    sources.add(new HistorySourceRow(
                            attempt.getProviderName(),
                            localizeStatus(attempt.getStatus()),
                            attempt.getDataGranularity(),
                            String.valueOf(attempt.getRowCount()),
                            attempt.getFirstDate(),
                            attempt.getLastDate(),
                            message(attempt.getMessage())
                    ));
                }

                List<DailyRow> rows = hub.fetchStockHistory(symbol, fromDate, toDate)
                        .stream()
                        .limit(80)
                        .map(this::toDailyRow)
                        .toList();
                return new HistoryResult(sources, rows);
            }

            private DailyRow toDailyRow(StockData data) {
                return new DailyRow(
                        data.getDate(),
                        data.getStockID(),
                        data.getStockName(),
                        formatDouble(data.getOpen()),
                        formatDouble(data.getHigh()),
                        formatDouble(data.getLow()),
                        formatDouble(data.getClose()),
                        String.valueOf(data.getVolume())
                );
            }
        };
        task.setOnSucceeded(event -> {
            historySourceRows.setAll(task.getValue().sources());
            dailyRows.setAll(task.getValue().dailyRows());
        });
        task.setOnFailed(event -> {
            historySourceRows.setAll(List.of(new HistorySourceRow("", "失敗", "", "", "", "", message(task.getException()))));
            dailyRows.clear();
        });
        new Thread(task, "stockbucks-debug-history").start();
    }

    private void refreshEnvironment() {
        runTextTask(environmentArea, "環境變數檢查中...", () -> {
            StringBuilder text = new StringBuilder();
            text.append("== env 檔案位置 ==\n");
            for (Path path : envFiles()) {
                text.append(Files.isRegularFile(path) ? "存在：" : "未找到：").append(path).append('\n');
            }

            text.append("\n== AI Key / 模型 ==\n");
            appendEnv(text, "AI_PROVIDER", true);
            appendEnv(text, "AI_MODEL", true);
            appendEnv(text, "AI_BASE_URL", true);
            appendEnv(text, "OPENAI_API_KEY", false);
            appendEnv(text, "ANTHROPIC_API_KEY", false);
            appendEnv(text, "GEMINI_API_KEY", false);
            appendEnv(text, "GOOGLE_API_KEY", false);
            appendEnv(text, "OPENROUTER_API_KEY", false);
            appendEnv(text, "AI_API_KEY", false);
            appendEnv(text, "OLLAMA_BASE_URL", true);
            appendEnv(text, "OLLAMA_MODEL", true);
            appendEnv(text, "OLLAMA_EXE_PATH", false);
            appendEnv(text, "OLLAMA_MODELS", false);

            text.append("\n== 股票來源 ==\n");
            appendEnv(text, "STOCK_PROVIDER_CHAIN", true);
            appendEnv(text, "STOCK_HISTORY_PROVIDER_CHAIN", true);
            appendEnv(text, "STOCK_INTRADAY_PROVIDER_CHAIN", true);
            appendEnv(text, "WEB_STOCK_SOURCES", true);
            appendEnv(text, "WEB_STOCK_USER_AGENT", true);
            appendEnv(text, "FUGLE_API_KEY", false);
            appendEnv(text, "FINMIND_TOKEN", false);

            text.append("\n== 券商 ==\n");
            appendEnv(text, "BROKER_BASE_URL", false);
            appendEnv(text, "BROKER_USERNAME", false);
            appendEnv(text, "BROKER_PASSWORD", false);
            appendEnv(text, "BROKER_API_KEY", false);
            appendEnv(text, "BROKER_AUTH_TOKEN", false);
            appendEnv(text, "BROKER_QUOTE_ENDPOINT", false);
            appendEnv(text, "BROKER_INTRADAY_BARS_ENDPOINT", false);
            appendEnv(text, "BROKER_ACCOUNT_ENDPOINT", false);
            appendEnv(text, "BROKER_POSITIONS_ENDPOINT", false);
            return text.toString();
        });
    }

    private void refreshAiStatus() {
        StringBuilder text = new StringBuilder();
        text.append("== AI provider 狀態 ==\n");
        text.append(hub.getAiConfigurationStatus()).append('\n');
        for (String line : hub.getAllAiProviderStatusLines()) {
            text.append(line).append('\n');
        }
        text.append("\n== 優先順序 ==\n");
        text.append(String.join(" -> ", AI_PROVIDERS)).append('\n');
        text.append("\n按「送出測試問題」可確認實際由哪個 AI 回覆。\n");
        aiArea.setText(text.toString());
    }

    private void askAi() {
        String prompt = aiPromptField.getText();
        runTextTask(aiArea, "AI 回覆中...", () -> hub.askAi(prompt));
    }

    private void refreshStorageAndBroker() {
        storageArea.setText(buildStorageText());
        runTextTask(brokerArea, "券商狀態檢查中...", this::buildBrokerText);
    }

    private String buildStorageText() {
        StringBuilder text = new StringBuilder();
        text.append("== 本地儲存診斷 ==\n");
        String path = hub.getMarketDatabasePath();
        if (path == null || path.isBlank()) {
            text.append("股票資料：未啟用正式本地儲存。\n");
            text.append("AI 回覆：未啟用正式對話紀錄儲存。\n");
            text.append("目前資料主要是即時查詢後直接回傳給 UI。\n");
        } else {
            text.append("市場資料庫：").append(path).append('\n');
        }
        text.append("\n建議之後可在 API 範圍新增 data/api_cache，用 jsonl 保存報價、K 線、歷史資料與 AI 回覆。\n");
        return text.toString();
    }

    private String buildBrokerText() {
        StringBuilder text = new StringBuilder();
        text.append("== 券商診斷 ==\n");
        String status = hub.getBrokerStatus();
        text.append("狀態：").append(status).append('\n');
        if (!status.contains("ready")) {
            text.append("尚未完成券商設定，所以不查帳戶與庫存。\n");
            return text.toString();
        }

        BrokerAccountSnapshot snapshot = hub.fetchBrokerAccountSnapshot();
        text.append("帳戶：").append(snapshot.getAccountId()).append('\n');
        text.append("現金：").append(formatDouble(snapshot.getCashBalance())).append('\n');
        text.append("市值：").append(formatDouble(snapshot.getMarketValue())).append('\n');
        text.append("總權益：").append(formatDouble(snapshot.getTotalEquity())).append('\n');
        text.append("來源：").append(snapshot.getProvider()).append('\n');

        List<BrokerPosition> positions = hub.fetchBrokerPositions();
        text.append("\n庫存筆數：").append(positions.size()).append('\n');
        for (BrokerPosition position : positions.stream().limit(20).toList()) {
            text.append(position.getStockId())
                    .append(' ')
                    .append(position.getStockName())
                    .append(" 股數 ")
                    .append(position.getQuantity())
                    .append(" 均價 ")
                    .append(formatDouble(position.getAveragePrice()))
                    .append('\n');
        }
        return text.toString();
    }

    private QuoteRow toQuoteRow(StockQuoteAttempt attempt) {
        StockQuote quote = attempt.getQuote();
        return new QuoteRow(
                attempt.getStockId(),
                attempt.getProviderName(),
                localizeStatus(attempt.getStatus()),
                attempt.getDataGranularity(),
                quote == null ? "" : quote.getStockName(),
                quote == null ? "" : formatDouble(quote.getLastPrice()),
                quote == null ? "" : formatDouble(quote.getOpenPrice()),
                quote == null ? "" : formatDouble(quote.getHighPrice()),
                quote == null ? "" : formatDouble(quote.getLowPrice()),
                quote == null || quote.getVolume() == 0 ? "" : String.valueOf(quote.getVolume()),
                message(attempt.getMessage())
        );
    }

    private IntradayRow toIntradayRow(StockIntradayAttempt attempt, IntradayBar bar) {
        return new IntradayRow(
                bar.getStockId(),
                bar.getProvider().isBlank() ? attempt.getProviderName() : bar.getProvider(),
                localizeStatus(attempt.getStatus()),
                attempt.getDataGranularity(),
                bar.getTime() == null ? "" : bar.getTime().toString(),
                formatDouble(bar.getOpen()),
                formatDouble(bar.getHigh()),
                formatDouble(bar.getLow()),
                formatDouble(bar.getClose()),
                String.valueOf(bar.getVolume()),
                "共 " + attempt.getBarCount() + " 筆；" + message(attempt.getMessage())
        );
    }

    private void drawIntradayChart(List<IntradayBar> bars, LocalDate fromDate, LocalDate toDate, String interval) {
        if (intradayChartCanvas == null) {
            return;
        }
        GraphicsContext gc = intradayChartCanvas.getGraphicsContext2D();
        double width = intradayChartCanvas.getWidth();
        double height = intradayChartCanvas.getHeight();
        gc.setFill(Color.web("#0d1117"));
        gc.fillRect(0, 0, width, height);

        if (bars == null || bars.isEmpty()) {
            if (intradayChartLabel != null) {
                intradayChartLabel.setText("K 線圖：區間 " + fromDate + " 到 " + toDate + " 沒有可畫資料");
            }
            gc.setFill(Color.web("#9aa4b2"));
            gc.fillText("此區間沒有 K 線資料", 24, height / 2);
            return;
        }

        double min = bars.stream().mapToDouble(IntradayBar::getLow).filter(value -> value > 0).min().orElse(0);
        double max = bars.stream().mapToDouble(IntradayBar::getHigh).filter(value -> value > 0).max().orElse(0);
        if (max <= min) {
            max = min + 1;
        }

        double left = 54;
        double right = 16;
        double top = 18;
        double bottom = 34;
        double plotWidth = Math.max(1, width - left - right);
        double plotHeight = Math.max(1, height - top - bottom);

        gc.setStroke(Color.web("#30363d"));
        gc.setLineWidth(1);
        for (int i = 0; i <= 4; i++) {
            double y = top + plotHeight * i / 4.0;
            gc.strokeLine(left, y, width - right, y);
            double price = max - (max - min) * i / 4.0;
            gc.setFill(Color.web("#9aa4b2"));
            gc.fillText(formatDouble(price), 6, y + 4);
        }

        double slot = plotWidth / Math.max(1, bars.size());
        double candleWidth = Math.max(2, Math.min(10, slot * 0.65));
        for (int i = 0; i < bars.size(); i++) {
            IntradayBar bar = bars.get(i);
            double x = left + slot * i + slot / 2.0;
            double openY = priceToY(bar.getOpen(), min, max, top, plotHeight);
            double closeY = priceToY(bar.getClose(), min, max, top, plotHeight);
            double highY = priceToY(bar.getHigh(), min, max, top, plotHeight);
            double lowY = priceToY(bar.getLow(), min, max, top, plotHeight);
            boolean up = bar.getClose() >= bar.getOpen();
            Color color = up ? Color.web("#ff6b6b") : Color.web("#2ea043");

            gc.setStroke(Color.web("#8b949e"));
            gc.strokeLine(x, highY, x, lowY);
            gc.setFill(color);
            double bodyTop = Math.min(openY, closeY);
            double bodyHeight = Math.max(1, Math.abs(closeY - openY));
            gc.fillRect(x - candleWidth / 2.0, bodyTop, candleWidth, bodyHeight);
        }

        IntradayBar first = bars.get(0);
        IntradayBar last = bars.get(bars.size() - 1);
        gc.setFill(Color.web("#9aa4b2"));
        gc.fillText(first.getTime() == null ? "" : first.getTime().toLocalDate().toString(), left, height - 10);
        gc.fillText(last.getTime() == null ? "" : last.getTime().toLocalDate().toString(), Math.max(left, width - 120), height - 10);

        if (intradayChartLabel != null) {
            intradayChartLabel.setText("K 線圖：" + interval + "，" + bars.size() + " 筆，"
                    + fromDate + " 到 " + toDate
                    + "，價格 " + formatDouble(min) + " - " + formatDouble(max));
        }
    }

    private double priceToY(double price, double min, double max, double top, double plotHeight) {
        if (price <= 0 || max <= min) {
            return top + plotHeight;
        }
        return top + (max - price) / (max - min) * plotHeight;
    }

    private void appendEnv(StringBuilder text, String key, boolean optionalDefault) {
        String value = EnvironmentConfig.get(key);
        if (value == null || value.isBlank()) {
            text.append(key).append("：缺少");
            if (optionalDefault) {
                text.append("，可使用預設值");
            }
            text.append('\n');
            return;
        }
        text.append(key).append("：已設定，").append(maskValue(value)).append('\n');
    }

    private List<Path> envFiles() {
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        Path userHome = Path.of(System.getProperty("user.home", "."));
        return List.of(
                cwd.resolve(".env"),
                cwd.resolve("stockbucks.local.env"),
                cwd.resolve("stockbucks.env"),
                userHome.resolve(".stockbucks").resolve(".env")
        );
    }

    private String providerSummary() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : hub.getStockProviderStatus().entrySet()) {
            if (text.length() > 0) {
                text.append("；");
            }
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.toString();
    }

    private String keyStatus(String key) {
        return EnvironmentConfig.has(key) ? "已設定" : "缺少 " + key;
    }

    private String brokerSimpleStatus() {
        String status = hub.getBrokerStatus();
        return status.contains("ready") ? "可測" : status;
    }

    private List<String> parseSymbols(String input) {
        if (input == null || input.isBlank()) {
            return List.of("2330");
        }
        List<String> symbols = new ArrayList<>();
        for (String item : input.split(",")) {
            String symbol = cleanStockId(item);
            if (!symbol.isBlank()) {
                symbols.add(symbol);
            }
        }
        return symbols.isEmpty() ? List.of("2330") : symbols;
    }

    private String cleanStockId(String value) {
        return value == null ? "" : value.trim().replaceAll("[^0-9A-Za-z]", "");
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private int parsePositiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed <= 0 ? fallback : parsed;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private boolean isWithinDateRange(IntradayBar bar, LocalDate fromDate, LocalDate toDate) {
        if (bar == null || bar.getTime() == null) {
            return false;
        }
        LocalDate date = bar.getTime().toLocalDate();
        return !date.isBefore(fromDate) && !date.isAfter(toDate);
    }

    private String rangeMessage(int totalCount, int filteredCount, LocalDate fromDate, LocalDate toDate, String sourceMessage) {
        return "來源共 " + totalCount + " 筆；區間 " + fromDate + " 到 " + toDate
                + " 符合 " + filteredCount + " 筆；" + message(sourceMessage);
    }

    private String localizeStatus(String status) {
        return switch (status == null ? "" : status) {
            case "success" -> "成功";
            case "missing" -> "缺設定";
            case "no data" -> "無資料";
            case "failed" -> "失敗";
            case "daily only" -> "僅日資料";
            default -> status == null ? "" : status;
        };
    }

    private String maskValue(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= 8) {
            return "***";
        }
        return trimmed.substring(0, 3) + "..." + trimmed.substring(trimmed.length() - 3);
    }

    private String blankAsNone(String value) {
        return value == null || value.isBlank() ? "無" : value;
    }

    private String message(Throwable throwable) {
        return throwable == null || throwable.getMessage() == null ? "未知錯誤" : message(throwable.getMessage());
    }

    private String message(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value == 0) {
            return "";
        }
        return String.format("%.2f", value);
    }

    private FileStatusRow fileStatus(Path path, String role, String note) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            return new FileStatusRow(path.toString(), role, "未建立", "", "", note);
        }
        try {
            String status = Files.isRegularFile(absolute) ? "存在" : "資料夾";
            String size = Files.isRegularFile(absolute) ? formatBytes(Files.size(absolute)) : "";
            String modified = formatModified(Files.getLastModifiedTime(absolute).toInstant());
            return new FileStatusRow(path.toString(), role, status, size, modified, note);
        } catch (Exception ex) {
            return new FileStatusRow(path.toString(), role, "讀取失敗", "", "", message(ex));
        }
    }

    private List<FileStatusRow> scanFolder(Path folder, String role) {
        Path absolute = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute)) {
            return List.of(new FileStatusRow(folder.toString(), role, "未建立", "", "", "目前沒有正式輸出到此目錄"));
        }
        try (Stream<Path> stream = Files.list(absolute)) {
            Path cwd = Path.of(".").toAbsolutePath().normalize();
            return stream
                    .filter(Files::isRegularFile)
                    .sorted()
                    .map(path -> fileStatus(cwd.relativize(path.toAbsolutePath().normalize()), role, fileRole(path)))
                    .toList();
        } catch (Exception ex) {
            return List.of(new FileStatusRow(folder.toString(), role, "讀取失敗", "", "", message(ex)));
        }
    }

    private String fileRole(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".csv")) {
            return "CSV 歷史或備援資料";
        }
        if (name.endsWith(".json") || name.endsWith(".jsonl")) {
            return "API 回應或快取資料";
        }
        if (name.endsWith(".db") || name.endsWith(".sqlite")) {
            return "資料庫檔案";
        }
        if (name.endsWith(".log")) {
            return "執行紀錄";
        }
        return "其他檔案";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private String formatModified(Instant instant) {
        return instant == null ? "" : instant.atZone(ZoneId.systemDefault()).format(TIME_FORMAT);
    }

    private int safeCount(CountSupplier supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private TextArea createReadOnlyArea(String prompt) {
        TextArea area = new TextArea();
        area.setPromptText(prompt);
        area.setWrapText(true);
        area.setEditable(false);
        area.setStyle("-fx-font-family: 'Microsoft JhengHei', 'Consolas'; -fx-font-size: 13px;");
        return area;
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold;");
        return label;
    }

    private HBox toolbar(javafx.scene.Node... nodes) {
        HBox box = new HBox(8, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private VBox pane(javafx.scene.Node... nodes) {
        VBox box = new VBox(10, nodes);
        box.setPadding(new Insets(12));
        box.setStyle("-fx-background-color: #f6f8fa;");
        return box;
    }

    private <T> TableView<T> table(ObservableList<T> rows) {
        TableView<T> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        return table;
    }

    private <T> void addColumn(TableView<T> table, String title, String property, int width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(new PropertyValueFactory<>(property));
        column.setPrefWidth(width);
        table.getColumns().add(column);
    }

    private void runTextTask(TextArea target, String loadingText, TextSupplier supplier) {
        target.setText(loadingText);
        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                return supplier.get();
            }
        };
        task.setOnSucceeded(event -> target.setText(task.getValue()));
        task.setOnFailed(event -> target.setText("檢查失敗：" + message(task.getException())));
        new Thread(task, "stockbucks-debug-text").start();
    }

    private record HistoryResult(List<HistorySourceRow> sources, List<DailyRow> dailyRows) {
    }

    private record IntradayResult(List<IntradayRow> rows,
                                  List<IntradayBar> bars,
                                  LocalDate fromDate,
                                  LocalDate toDate,
                                  String interval) {
    }

    private interface TextSupplier {
        String get();
    }

    private interface CountSupplier {
        int get();
    }

    public static class QuoteRow {
        private final String stockId;
        private final String provider;
        private final String status;
        private final String granularity;
        private final String stockName;
        private final String lastPrice;
        private final String openPrice;
        private final String highPrice;
        private final String lowPrice;
        private final String volume;
        private final String message;

        QuoteRow(String stockId, String provider, String status, String granularity, String stockName,
                 String lastPrice, String openPrice, String highPrice, String lowPrice, String volume, String message) {
            this.stockId = stockId;
            this.provider = provider;
            this.status = status;
            this.granularity = granularity;
            this.stockName = stockName;
            this.lastPrice = lastPrice;
            this.openPrice = openPrice;
            this.highPrice = highPrice;
            this.lowPrice = lowPrice;
            this.volume = volume;
            this.message = message;
        }

        public String getStockId() { return stockId; }
        public String getProvider() { return provider; }
        public String getStatus() { return status; }
        public String getGranularity() { return granularity; }
        public String getStockName() { return stockName; }
        public String getLastPrice() { return lastPrice; }
        public String getOpenPrice() { return openPrice; }
        public String getHighPrice() { return highPrice; }
        public String getLowPrice() { return lowPrice; }
        public String getVolume() { return volume; }
        public String getMessage() { return message; }
    }

    public static class IntradayRow {
        private final String stockId;
        private final String provider;
        private final String status;
        private final String granularity;
        private final String time;
        private final String open;
        private final String high;
        private final String low;
        private final String close;
        private final String volume;
        private final String message;

        IntradayRow(String stockId, String provider, String status, String granularity, String time,
                    String open, String high, String low, String close, String volume, String message) {
            this.stockId = stockId;
            this.provider = provider;
            this.status = status;
            this.granularity = granularity;
            this.time = time;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
            this.message = message;
        }

        public String getStockId() { return stockId; }
        public String getProvider() { return provider; }
        public String getStatus() { return status; }
        public String getGranularity() { return granularity; }
        public String getTime() { return time; }
        public String getOpen() { return open; }
        public String getHigh() { return high; }
        public String getLow() { return low; }
        public String getClose() { return close; }
        public String getVolume() { return volume; }
        public String getMessage() { return message; }
    }

    public static class HistorySourceRow {
        private final String provider;
        private final String status;
        private final String granularity;
        private final String count;
        private final String firstDate;
        private final String lastDate;
        private final String message;

        HistorySourceRow(String provider, String status, String granularity, String count, String firstDate, String lastDate, String message) {
            this.provider = provider;
            this.status = status;
            this.granularity = granularity;
            this.count = count;
            this.firstDate = firstDate;
            this.lastDate = lastDate;
            this.message = message;
        }

        public String getProvider() { return provider; }
        public String getStatus() { return status; }
        public String getGranularity() { return granularity; }
        public String getCount() { return count; }
        public String getFirstDate() { return firstDate; }
        public String getLastDate() { return lastDate; }
        public String getMessage() { return message; }
    }

    public static class DailyRow {
        private final String date;
        private final String stockId;
        private final String stockName;
        private final String open;
        private final String high;
        private final String low;
        private final String close;
        private final String volume;

        DailyRow(String date, String stockId, String stockName, String open, String high, String low, String close, String volume) {
            this.date = date;
            this.stockId = stockId;
            this.stockName = stockName;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }

        public String getDate() { return date; }
        public String getStockId() { return stockId; }
        public String getStockName() { return stockName; }
        public String getOpen() { return open; }
        public String getHigh() { return high; }
        public String getLow() { return low; }
        public String getClose() { return close; }
        public String getVolume() { return volume; }
    }

    public static class FileFunctionRow {
        private final String file;
        private final String feature;
        private final String diagnostic;
        private final String status;

        FileFunctionRow(String file, String feature, String diagnostic, String status) {
            this.file = file;
            this.feature = feature;
            this.diagnostic = diagnostic;
            this.status = status;
        }

        public String getFile() { return file; }
        public String getFeature() { return feature; }
        public String getDiagnostic() { return diagnostic; }
        public String getStatus() { return status; }
    }

    public static class FileStatusRow {
        private final String path;
        private final String role;
        private final String status;
        private final String size;
        private final String lastModified;
        private final String note;

        FileStatusRow(String path, String role, String status, String size, String lastModified, String note) {
            this.path = path;
            this.role = role;
            this.status = status;
            this.size = size;
            this.lastModified = lastModified;
            this.note = note;
        }

        public String getPath() { return path; }
        public String getRole() { return role; }
        public String getStatus() { return status; }
        public String getSize() { return size; }
        public String getLastModified() { return lastModified; }
        public String getNote() { return note; }
    }
}
