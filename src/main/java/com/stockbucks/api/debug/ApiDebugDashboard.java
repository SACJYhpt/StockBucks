package com.stockbucks.api.debug;

import com.stockbucks.api.AIHub;
import com.stockbucks.api.ai.ApiModelClient;
import com.stockbucks.api.config.EnvironmentConfig;
import com.stockbucks.api.stock.BrokerAccountSnapshot;
import com.stockbucks.api.stock.BrokerPosition;
import com.stockbucks.api.stock.IntradayBar;
import com.stockbucks.api.stock.StockQuote;
import com.stockbucks.api.stock.StockQuoteAttempt;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * API debug 用的臨時測試介面。
 *
 * 這個畫面只放在 api/debug，不接同學正式 UI。
 * 用途是快速檢查股票即時報價、資料來源 fallback、API Key 狀態與 AI 問答。
 */
public class ApiDebugDashboard extends Application {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AIHub hub = new AIHub();
    private final ObservableList<QuoteRow> quoteRows = FXCollections.observableArrayList();

    private TextField symbolsField;
    private TextArea stockStatusArea;
    private TextArea aiStatusArea;
    private TextArea fullTestArea;
    private TextArea aiPromptArea;
    private TextArea aiAnswerArea;
    private Button refreshQuotesButton;
    private Button fullTestButton;
    private Button askAiButton;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("StockBucks API Debug Dashboard");
        stage.setScene(new Scene(createRoot(), 1180, 720));
        stage.show();

        refreshStatus();
        refreshQuotes();
    }

    private BorderPane createRoot() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(14));
        root.setStyle("-fx-background-color: #161b22; -fx-text-fill: white;");

        Label title = new Label("StockBucks API 測試介面");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 22px; -fx-font-weight: bold;");
        Label subtitle = new Label("臨時檢查股票即時資料、fallback 狀態與 AI API，不接正式 UI。");
        subtitle.setStyle("-fx-text-fill: #8b949e;");
        VBox header = new VBox(4, title, subtitle);
        header.setPadding(new Insets(0, 0, 12, 0));
        root.setTop(header);

        SplitPane splitPane = new SplitPane(createStockPane(), createAiPane());
        splitPane.setDividerPositions(0.62);
        root.setCenter(splitPane);
        return root;
    }

    private VBox createStockPane() {
        symbolsField = new TextField("2330,0050,0052,00881");
        symbolsField.setPromptText("輸入股票代號，用逗號分隔，例如 2330,0050,0052");
        HBox.setHgrow(symbolsField, Priority.ALWAYS);

        refreshQuotesButton = new Button("更新報價");
        refreshQuotesButton.setOnAction(event -> refreshQuotes());

        Button refreshStatusButton = new Button("更新狀態");
        refreshStatusButton.setOnAction(event -> refreshStatus());

        HBox toolbar = new HBox(8, new Label("股票："), symbolsField, refreshQuotesButton, refreshStatusButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        TableView<QuoteRow> table = createQuoteTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        stockStatusArea = createReadOnlyArea("股票資料來源狀態");
        stockStatusArea.setPrefRowCount(7);

        VBox pane = new VBox(10, sectionTitle("股票即時資料"), toolbar, table, labeledBox("來源狀態", stockStatusArea));
        pane.setPadding(new Insets(12));
        return pane;
    }

    private VBox createAiPane() {
        aiStatusArea = createReadOnlyArea("AI 狀態");
        fullTestArea = createReadOnlyArea("全方位 API 測試結果");
        fullTestArea.setPrefRowCount(12);

        fullTestButton = new Button("全方位 API 測試");
        fullTestButton.setOnAction(event -> runFullApiTest());

        aiPromptArea = new TextArea("請用繁體中文摘要目前台積電報價資料，並提醒資料來源可能有延遲。");
        aiPromptArea.setWrapText(true);
        aiPromptArea.setPrefRowCount(5);

        askAiButton = new Button("送出 AI 測試");
        askAiButton.setOnAction(event -> askAi());

        aiAnswerArea = createReadOnlyArea("AI 回覆");
        VBox.setVgrow(aiAnswerArea, Priority.ALWAYS);

        VBox pane = new VBox(
                10,
                sectionTitle("AI API 測試"),
                labeledBox("AI 設定 / 缺少 Key", aiStatusArea),
                fullTestButton,
                labeledBox("全方位測試", fullTestArea),
                labeledBox("測試問題", aiPromptArea),
                askAiButton,
                labeledBox("回覆", aiAnswerArea)
        );
        pane.setPadding(new Insets(12));
        return pane;
    }

    private TableView<QuoteRow> createQuoteTable() {
        TableView<QuoteRow> table = new TableView<>(quoteRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<QuoteRow, String> stockId = new TableColumn<>("股票代號");
        stockId.setCellValueFactory(new PropertyValueFactory<>("stockId"));

        TableColumn<QuoteRow, String> provider = new TableColumn<>("來源");
        provider.setCellValueFactory(new PropertyValueFactory<>("provider"));

        TableColumn<QuoteRow, String> status = new TableColumn<>("狀態");
        status.setCellValueFactory(new PropertyValueFactory<>("status"));

        TableColumn<QuoteRow, String> name = new TableColumn<>("名稱");
        name.setCellValueFactory(new PropertyValueFactory<>("stockName"));

        TableColumn<QuoteRow, String> price = new TableColumn<>("最新價");
        price.setCellValueFactory(new PropertyValueFactory<>("lastPrice"));

        TableColumn<QuoteRow, String> open = new TableColumn<>("開盤");
        open.setCellValueFactory(new PropertyValueFactory<>("openPrice"));

        TableColumn<QuoteRow, String> high = new TableColumn<>("最高");
        high.setCellValueFactory(new PropertyValueFactory<>("highPrice"));

        TableColumn<QuoteRow, String> low = new TableColumn<>("最低");
        low.setCellValueFactory(new PropertyValueFactory<>("lowPrice"));

        TableColumn<QuoteRow, String> volume = new TableColumn<>("成交量");
        volume.setCellValueFactory(new PropertyValueFactory<>("volume"));

        TableColumn<QuoteRow, String> fetchedAt = new TableColumn<>("抓取時間");
        fetchedAt.setCellValueFactory(new PropertyValueFactory<>("fetchedAt"));

        TableColumn<QuoteRow, String> message = new TableColumn<>("訊息");
        message.setCellValueFactory(new PropertyValueFactory<>("message"));

        table.getColumns().addAll(stockId, provider, status, name, price, open, high, low, volume, fetchedAt, message);
        return table;
    }

    private void refreshQuotes() {
        List<String> symbols = parseSymbols(symbolsField.getText());
        if (symbols.isEmpty()) {
            return;
        }

        refreshQuotesButton.setDisable(true);
        Task<List<QuoteRow>> task = new Task<>() {
            @Override
            protected List<QuoteRow> call() {
                List<QuoteRow> rows = new ArrayList<>();
                for (String symbol : symbols) {
                    rows.addAll(fetchQuoteRows(symbol));
                }
                return rows;
            }
        };
        task.setOnSucceeded(event -> {
            quoteRows.setAll(task.getValue());
            refreshQuotesButton.setDisable(false);
        });
        task.setOnFailed(event -> {
            quoteRows.setAll(List.of(new QuoteRow("", "", "failed", "更新失敗", "", "", "", "", "", "", displayMessage(task.getException().getMessage()))));
            refreshQuotesButton.setDisable(false);
        });
        new Thread(task, "stockbucks-api-debug-quotes").start();
    }

    private List<QuoteRow> fetchQuoteRows(String symbol) {
        try {
            List<StockQuoteAttempt> attempts = hub.fetchStockQuoteAttempts(symbol);
            if (attempts.isEmpty()) {
                return List.of(new QuoteRow(symbol, "", "no data", "無資料", "", "", "", "", "", "", "沒有任何資料來源可嘗試"));
            }
            List<QuoteRow> rows = new ArrayList<>();
            for (StockQuoteAttempt attempt : attempts) {
                rows.add(toQuoteRow(attempt));
            }
            return rows;
        } catch (RuntimeException ex) {
            return List.of(new QuoteRow(symbol, "", "failed", "抓取失敗", "", "", "", "", "", "", displayMessage(ex.getMessage())));
        }
    }

    private QuoteRow toQuoteRow(StockQuoteAttempt attempt) {
        StockQuote quote = attempt.getQuote();
        if (quote == null) {
            return new QuoteRow(
                    attempt.getStockId(),
                    attempt.getProviderName(),
                    localizeStatus(attempt.getStatus()),
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    displayMessage(attempt.getMessage())
            );
        }

        return new QuoteRow(
                quote.getStockId().isBlank() ? attempt.getStockId() : quote.getStockId(),
                quote.getProvider().isBlank() ? attempt.getProviderName() : quote.getProvider(),
                localizeStatus(attempt.getStatus()),
                quote.getStockName(),
                formatDouble(quote.getLastPrice()),
                formatDouble(quote.getOpenPrice()),
                formatDouble(quote.getHighPrice()),
                formatDouble(quote.getLowPrice()),
                quote.getVolume() == 0 ? "" : String.valueOf(quote.getVolume()),
                quote.getFetchedAt().format(TIME_FORMAT),
                displayMessage(attempt.getMessage())
        );
    }

    private void refreshStatus() {
        StringBuilder stockStatus = new StringBuilder();
        for (Map.Entry<String, String> entry : hub.getStockProviderStatus().entrySet()) {
            stockStatus.append(entry.getKey()).append(" -> ").append(entry.getValue()).append('\n');
        }
        stockStatus.append('\n').append("券商狀態：").append(hub.getBrokerStatus()).append('\n');
        stockStatusArea.setText(stockStatus.toString());

        StringBuilder aiStatus = new StringBuilder();
        aiStatus.append("目前選擇：").append(hub.getAiConfigurationStatus()).append('\n');
        for (String statusLine : hub.getAllAiProviderStatusLines()) {
            aiStatus.append(statusLine).append('\n');
        }
        aiStatusArea.setText(aiStatus.toString());
    }

    private void runFullApiTest() {
        List<String> symbols = parseSymbols(symbolsField.getText());
        if (symbols.isEmpty()) {
            symbols = List.of("2330");
        }

        fullTestButton.setDisable(true);
        fullTestArea.setText("全方位 API 測試中...");
        List<String> testSymbols = List.copyOf(symbols);
        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                return buildFullApiTestReport(testSymbols);
            }
        };
        task.setOnSucceeded(event -> {
            fullTestArea.setText(task.getValue());
            fullTestButton.setDisable(false);
            refreshStatus();
        });
        task.setOnFailed(event -> {
            fullTestArea.setText("全方位 API 測試失敗：" + displayMessage(task.getException().getMessage()));
            fullTestButton.setDisable(false);
        });
        new Thread(task, "stockbucks-api-debug-full-test").start();
    }

    private String buildFullApiTestReport(List<String> symbols) {
        ApiTestSummary summary = new ApiTestSummary();
        StringBuilder report = new StringBuilder();
        StringBuilder detail = new StringBuilder();

        appendLine(detail, "== AI API ==");
        for (String provider : List.of("gemini", "anthropic", "openrouter", "ollama", "openai-compatible", "openai")) {
            appendLine(detail, testAiProvider(provider, summary));
        }

        appendLine(detail, "");
        appendLine(detail, "== 環境變數查缺 ==");
        appendEnvironmentLine(detail, "AI_PROVIDER", "目前 AI 來源", true);
        appendEnvironmentLine(detail, "AI_MODEL", "雲端 AI 共用模型", false);
        appendEnvironmentLine(detail, "AI_BASE_URL", "雲端/相容 API 共用 base URL", false);
        appendEnvironmentLine(detail, "GEMINI_API_KEY", "Gemini API Key", false);
        appendEnvironmentLine(detail, "GOOGLE_API_KEY", "Gemini API Key 別名", false);
        appendEnvironmentLine(detail, "ANTHROPIC_API_KEY", "Anthropic API Key", false);
        appendEnvironmentLine(detail, "ANTHROPIC_VERSION", "Anthropic API 版本", true);
        appendEnvironmentLine(detail, "OPENROUTER_API_KEY", "OpenRouter API Key", false);
        appendEnvironmentLine(detail, "OPENAI_API_KEY", "OpenAI API Key", false);
        appendEnvironmentLine(detail, "AI_API_KEY", "OpenAI-compatible API Key", false);
        appendEnvironmentLine(detail, "OLLAMA_BASE_URL", "Ollama 本機服務 URL", true);
        appendEnvironmentLine(detail, "OLLAMA_MODEL", "Ollama 模型", true);
        appendEnvironmentLine(detail, "OLLAMA_EXE_PATH", "Ollama 執行檔路徑", false);
        appendEnvironmentLine(detail, "OLLAMA_MODELS", "Ollama 模型資料夾", false);
        appendEnvironmentLine(detail, "STOCK_PROVIDER_CHAIN", "股票來源排序", true);
        appendEnvironmentLine(detail, "BROKER_PROVIDER", "券商 provider 名稱", true);
        appendEnvironmentLine(detail, "BROKER_BASE_URL", "券商 API base URL", false);
        appendEnvironmentLine(detail, "BROKER_USERNAME", "券商帳號", false);
        appendEnvironmentLine(detail, "BROKER_PASSWORD", "券商密碼", false);
        appendEnvironmentLine(detail, "BROKER_API_KEY", "券商 API Key", false);
        appendEnvironmentLine(detail, "BROKER_AUTH_TOKEN", "券商既有 token", false);
        appendEnvironmentLine(detail, "BROKER_AUTH_TOKEN_FIELD", "券商登入 token 欄位", true);
        appendEnvironmentLine(detail, "BROKER_CERT_PATH", "券商憑證路徑", false);
        appendEnvironmentLine(detail, "BROKER_CERT_PASSWORD", "券商憑證密碼", false);
        appendEnvironmentLine(detail, "BROKER_LOGIN_ENDPOINT", "券商登入端點", false);
        appendEnvironmentLine(detail, "BROKER_QUOTE_ENDPOINT", "券商報價端點", false);
        appendEnvironmentLine(detail, "BROKER_INTRADAY_BARS_ENDPOINT", "券商日內 K 線端點", false);
        appendEnvironmentLine(detail, "BROKER_ACCOUNT_ENDPOINT", "券商帳戶端點", false);
        appendEnvironmentLine(detail, "BROKER_POSITIONS_ENDPOINT", "券商庫存端點", false);
        appendEnvironmentLine(detail, "FUGLE_API_KEY", "Fugle API Key", false);
        appendEnvironmentLine(detail, "FUGLE_BASE_URL", "Fugle base URL", true);
        appendEnvironmentLine(detail, "TWSE_WEB_BASE_URL", "TWSE Web base URL", true);
        appendEnvironmentLine(detail, "TWSE_OPENAPI_BASE_URL", "TWSE OpenAPI base URL", true);
        appendEnvironmentLine(detail, "WEB_STOCK_SOURCES", "網頁爬蟲來源順序", true);
        appendEnvironmentLine(detail, "WEB_STOCK_GOOGLE_URL_TEMPLATE", "Google Finance URL 模板", true);
        appendEnvironmentLine(detail, "WEB_STOCK_YAHOO_URL_TEMPLATE", "Yahoo 股市 URL 模板", true);
        appendEnvironmentLine(detail, "WEB_STOCK_CNBC_URL_TEMPLATE", "CNBC URL 模板", true);
        appendEnvironmentLine(detail, "WEB_STOCK_MSN_URL_TEMPLATE", "MSN URL 模板", false);
        appendEnvironmentLine(detail, "WEB_STOCK_WANTGOO_URL_TEMPLATE", "WantGoo URL 模板", true);
        appendEnvironmentLine(detail, "WEB_STOCK_USER_AGENT", "網頁爬蟲 User-Agent", true);
        appendEnvironmentLine(detail, "FINMIND_TOKEN", "FinMind Token", false);
        appendEnvironmentLine(detail, "FINMIND_BASE_URL", "FinMind base URL", true);
        appendEnvironmentLine(detail, "LOCAL_STOCK_CSV_NAME", "本地 CSV 檔名", true);

        appendLine(detail, "");
        appendLine(detail, "== 股票來源設定 ==");
        for (Map.Entry<String, String> entry : hub.getStockProviderStatus().entrySet()) {
            String status = entry.getValue();
            countProviderStatus(summary, status);
            appendLine(detail, entry.getKey() + "：" + localizeProviderStatus(status));
        }

        appendLine(detail, "");
        appendLine(detail, "== 股票報價來源測試 ==");
        for (String symbol : symbols) {
            appendLine(detail, "[" + symbol + "]");
            for (StockQuoteAttempt attempt : hub.fetchStockQuoteAttempts(symbol)) {
                validateAttempt(summary, symbol, attempt);
                StockQuote quote = attempt.getQuote();
                String price = quote == null ? "" : " | 價格 " + formatDouble(quote.getLastPrice());
                appendLine(detail, attempt.getProviderName()
                        + "："
                        + localizeStatus(attempt.getStatus())
                        + price
                        + " | "
                        + expectedLabel(symbol, attempt)
                        + " | "
                        + displayMessage(attempt.getMessage()));
            }
        }

        appendLine(detail, "");
        appendLine(detail, "== 歷史/全市場資料 ==");
        String firstSymbol = symbols.get(0);
        appendCountLine(detail, summary, "歷史資料 " + firstSymbol, () ->
                hub.fetchStockHistory(firstSymbol, LocalDate.now().minusDays(10), LocalDate.now()).size());
        appendCountLine(detail, summary, "上市股票清單", () -> hub.fetchAllListedStocks().size());
        appendCountLine(detail, summary, "全市場日資料", () -> hub.fetchAllStocksDailyMarket().size());

        appendLine(detail, "");
        appendLine(detail, "== 券商 API ==");
        String brokerStatus = hub.getBrokerStatus();
        appendLine(detail, "券商狀態：" + brokerStatus);
        if (brokerStatus.contains("ready")) {
            appendTextLine(detail, summary, "帳戶總覽", () -> summarizeBrokerSnapshot(hub.fetchBrokerAccountSnapshot()));
            appendCountLine(detail, summary, "庫存", () -> hub.fetchBrokerPositions().size());
            appendCountLine(detail, summary, "小時線 " + firstSymbol, () -> hub.fetchBrokerHourlyBars(firstSymbol).size());
        } else {
            summary.skipped++;
            summary.expectedOk++;
            summary.addImportant("券商未設定，略過帳戶/庫存/小時線");
            appendLine(detail, "券商查詢：略過，尚未完成券商設定");
        }

        appendLine(detail, "");
        appendLine(detail, "== 端點登記 ==");
        appendLine(detail, "已登記股票端點：" + hub.supportedStockApiEndpoints().size() + " 個");

        appendSummary(report, summary);
        appendLine(report, "");
        appendLine(report, "== 詳細結果 ==");
        report.append(detail);
        return report.toString();
    }

    private void appendSummary(StringBuilder report, ApiTestSummary summary) {
        appendLine(report, "== API 狀態總結 ==");
        appendLine(report, "成功：" + summary.success + " | 缺設定：" + summary.missing + " | 失敗：" + summary.failed + " | 無資料：" + summary.noData + " | 略過：" + summary.skipped);
        appendLine(report, "符合預期：" + summary.expectedOk + " | 需處理：" + summary.needsAction);
        if (summary.importantItems.isEmpty()) {
            appendLine(report, "重點：目前沒有需要優先處理的缺項。");
            return;
        }

        appendLine(report, "重點：");
        for (String item : summary.importantItems) {
            appendLine(report, "- " + item);
        }
    }

    private void appendEnvironmentLine(StringBuilder detail, String key, String label, boolean hasDefault) {
        String value = EnvironmentConfig.get(key);
        if (value == null || value.isBlank()) {
            appendLine(detail, key + "：未設定" + (hasDefault ? "，會使用預設值" : "，需要時再填") + " | " + label);
            return;
        }
        appendLine(detail, key + "：已設定 | " + label);
    }

    private void countProviderStatus(ApiTestSummary summary, String status) {
        if (status == null || status.isBlank()) {
            summary.failed++;
            summary.needsAction++;
            return;
        }
        if (status.equals("ready")) {
            summary.success++;
            summary.expectedOk++;
            return;
        }
        if (status.startsWith("missing ")) {
            summary.missing++;
            summary.expectedOk++;
            summary.addImportant("缺 " + status.substring("missing ".length()));
            return;
        }
        summary.failed++;
        summary.needsAction++;
    }

    private void validateAttempt(ApiTestSummary summary, String requestedSymbol, StockQuoteAttempt attempt) {
        switch (attempt.getStatus()) {
            case "success" -> {
                if (isQuoteExpected(requestedSymbol, attempt)) {
                    summary.expectedOk++;
                } else {
                    summary.needsAction++;
                    summary.addImportant(attempt.getProviderName() + " 成功但資料不合理：" + requestedSymbol);
                }
            }
            case "missing" -> {
                summary.expectedOk++;
            }
            case "no data" -> {
                summary.expectedOk++;
            }
            case "failed" -> {
                summary.needsAction++;
                summary.addImportant(attempt.getProviderName() + " 失敗：" + displayMessage(attempt.getMessage()));
            }
            default -> {
                summary.needsAction++;
            }
        }
    }

    private void appendCountLine(StringBuilder detail, ApiTestSummary summary, String label, CountSupplier supplier) {
        try {
            int count = supplier.get();
            if (count > 0) {
                summary.success++;
            } else {
                summary.noData++;
            }
            summary.expectedOk++;
            appendLine(detail, label + "：" + count + " 筆");
        } catch (RuntimeException ex) {
            summary.failed++;
            summary.needsAction++;
            summary.addImportant(label + " 失敗：" + displayMessage(ex.getMessage()));
            appendLine(detail, label + "：失敗：" + displayMessage(ex.getMessage()));
        }
    }

    private void appendTextLine(StringBuilder detail, ApiTestSummary summary, String label, TextSupplier supplier) {
        try {
            String text = supplier.get();
            summary.success++;
            summary.expectedOk++;
            appendLine(detail, label + "：" + text);
        } catch (RuntimeException ex) {
            summary.failed++;
            summary.needsAction++;
            summary.addImportant(label + " 失敗：" + displayMessage(ex.getMessage()));
            appendLine(detail, label + "：失敗：" + displayMessage(ex.getMessage()));
        }
    }

    private String testAiProvider(String provider, ApiTestSummary summary) {
        ApiModelClient client = new ApiModelClient(provider);
        String missing = client.getMissingApiKeyName();
        if (!missing.isBlank()) {
            summary.missing++;
            summary.expectedOk++;
            summary.addImportant(provider + " 缺 " + missing);
            return provider + "：缺 " + missing;
        }

        String answer = client.ask("請只回覆 OK，用來測試 API 是否可用。");
        if (answer == null || answer.isBlank()) {
            summary.failed++;
            summary.needsAction++;
            summary.addImportant(provider + " 空白回覆");
            return provider + "：失敗，空白回覆";
        }
        if (answer.contains("[AI API error]") || answer.contains("[AI API request failed]") || answer.contains("[AI config]")) {
            summary.failed++;
            summary.needsAction++;
            String reason = summarizeAiTestFailure(answer);
            summary.addImportant(provider + " " + reason);
            return provider + "：失敗，" + reason;
        }
        summary.success++;
        summary.expectedOk++;
        return provider + "：成功";
    }

    private String summarizeAiTestFailure(String answer) {
        if (answer.contains("insufficient_quota")) {
            return "OpenAI 額度不足或付款方案未啟用";
        }
        if (answer.toLowerCase().contains("model") && answer.toLowerCase().contains("not found")) {
            return "本機 Ollama 缺少模型，請先下載 OLLAMA_MODEL";
        }
        if (answer.contains("HTTP 429")) {
            return "請求過多或額度限制";
        }
        if (answer.contains("無法連線到") && answer.contains("localhost")) {
            return "本機 AI 服務未啟動或連不上";
        }
        return firstLine(answer);
    }

    private String expectedLabel(String requestedSymbol, StockQuoteAttempt attempt) {
        return isAttemptExpected(requestedSymbol, attempt) ? "符合預期" : "需處理";
    }

    private boolean isAttemptExpected(String requestedSymbol, StockQuoteAttempt attempt) {
        if (!"success".equals(attempt.getStatus())) {
            return !"failed".equals(attempt.getStatus());
        }
        return isQuoteExpected(requestedSymbol, attempt);
    }

    private boolean isQuoteExpected(String requestedSymbol, StockQuoteAttempt attempt) {
        StockQuote quote = attempt.getQuote();
        if (quote == null || quote.getLastPrice() <= 0) {
            return false;
        }

        String quoteSymbol = quote.getStockId() == null ? "" : quote.getStockId().trim();
        String requested = requestedSymbol == null ? "" : requestedSymbol.trim();
        return quoteSymbol.isBlank()
                || requested.isBlank()
                || quoteSymbol.equalsIgnoreCase(requested);
    }

    private String summarizeBrokerSnapshot(BrokerAccountSnapshot snapshot) {
        if (snapshot == null) {
            return "無資料";
        }
        return "帳戶 " + snapshot.getAccountId()
                + "，現金 " + formatDouble(snapshot.getCashBalance())
                + "，市值 " + formatDouble(snapshot.getMarketValue())
                + "，庫存 " + snapshot.getPositions().size() + " 筆";
    }

    private String safeCount(CountSupplier supplier) {
        try {
            return String.valueOf(supplier.get());
        } catch (RuntimeException ex) {
            return "失敗：" + displayMessage(ex.getMessage());
        }
    }

    private String safeText(TextSupplier supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            return "失敗：" + displayMessage(ex.getMessage());
        }
    }

    private String localizeProviderStatus(String status) {
        if (status == null || status.isBlank()) {
            return "";
        }
        if (status.startsWith("missing ")) {
            return "缺 " + status.substring("missing ".length());
        }
        return status.equals("ready") ? "可用" : status;
    }

    private String firstLine(String text) {
        return cleanAndLimit(text, 80);
    }

    private String displayMessage(String text) {
        return cleanAndLimit(text, 140);
    }

    private String cleanAndLimit(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = text
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#x27;", "'")
                .replaceAll("&#39;", "'")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) + "..." : cleaned;
    }

    private void appendLine(StringBuilder builder, String line) {
        builder.append(line).append('\n');
    }

    private void askAi() {
        askAiButton.setDisable(true);
        aiAnswerArea.setText("AI 回覆中...");
        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                return hub.askAi(aiPromptArea.getText());
            }
        };
        task.setOnSucceeded(event -> {
            aiAnswerArea.setText(task.getValue());
            askAiButton.setDisable(false);
        });
        task.setOnFailed(event -> {
            aiAnswerArea.setText("AI 測試失敗：" + displayMessage(task.getException().getMessage()));
            askAiButton.setDisable(false);
        });
        new Thread(task, "stockbucks-api-debug-ai").start();
    }

    private List<String> parseSymbols(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> symbols = new ArrayList<>();
        for (String part : text.split("[,，\\s]+")) {
            String symbol = part.trim();
            if (!symbol.isBlank()) {
                symbols.add(symbol);
            }
        }
        return symbols;
    }

    private String formatDouble(double value) {
        return value <= 0 ? "" : String.format("%.2f", value);
    }

    private String localizeStatus(String status) {
        return switch (status == null ? "" : status) {
            case "success" -> "成功";
            case "missing" -> "缺少設定";
            case "no data" -> "無資料";
            case "failed" -> "失敗";
            default -> status == null ? "" : status;
        };
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold;");
        return label;
    }

    private TextArea createReadOnlyArea(String prompt) {
        TextArea area = new TextArea();
        area.setPromptText(prompt);
        area.setEditable(false);
        area.setWrapText(true);
        return area;
    }

    private VBox labeledBox(String labelText, javafx.scene.Node content) {
        Label label = new Label(labelText);
        label.setStyle("-fx-text-fill: #c9d1d9; -fx-font-weight: bold;");
        VBox box = new VBox(5, label, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    public static class QuoteRow {
        private final String stockId;
        private final String provider;
        private final String status;
        private final String stockName;
        private final String lastPrice;
        private final String openPrice;
        private final String highPrice;
        private final String lowPrice;
        private final String volume;
        private final String fetchedAt;
        private final String message;

        public QuoteRow(String stockId,
                        String provider,
                        String status,
                        String stockName,
                        String lastPrice,
                        String openPrice,
                        String highPrice,
                        String lowPrice,
                        String volume,
                        String fetchedAt,
                        String message) {
            this.stockId = stockId;
            this.provider = provider;
            this.status = status;
            this.stockName = stockName;
            this.lastPrice = lastPrice;
            this.openPrice = openPrice;
            this.highPrice = highPrice;
            this.lowPrice = lowPrice;
            this.volume = volume;
            this.fetchedAt = fetchedAt;
            this.message = message;
        }

        public String getStockId() {
            return stockId;
        }

        public String getProvider() {
            return provider;
        }

        public String getStatus() {
            return status;
        }

        public String getStockName() {
            return stockName;
        }

        public String getLastPrice() {
            return lastPrice;
        }

        public String getOpenPrice() {
            return openPrice;
        }

        public String getHighPrice() {
            return highPrice;
        }

        public String getLowPrice() {
            return lowPrice;
        }

        public String getVolume() {
            return volume;
        }

        public String getFetchedAt() {
            return fetchedAt;
        }

        public String getMessage() {
            return message;
        }
    }

    private interface CountSupplier {
        int get();
    }

    private interface TextSupplier {
        String get();
    }

    private static class ApiTestSummary {
        private int success;
        private int missing;
        private int failed;
        private int noData;
        private int skipped;
        private int expectedOk;
        private int needsAction;
        private final List<String> importantItems = new ArrayList<>();

        private void addImportant(String item) {
            if (item == null || item.isBlank() || importantItems.contains(item) || importantItems.size() >= 8) {
                return;
            }
            importantItems.add(item);
        }
    }
}
