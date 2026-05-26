package com.stockbucks.gui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.Styles;
import com.stockbucks.*;
import com.stockbucks.ai.AIHub;
import com.stockbucks.ai.data.MarketDataUpdateResult;
import com.stockbucks.ai.mode.MarketMode;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class MainApp extends Application {
    private static final String STOCK_ID = "2330";

    // 接收 WelcomeUI 傳遞過來的存檔資料
    private SaveData initialSaveData = null;

    private User user;
    private TradingEngine tradingEngine;
    private final CsvLoading csvLoader = new CsvLoading();
    private final PriceSimulator simulator = new PriceSimulator();

    private final TableView<TradeRecord> historyTable = new TableView<>();
    private final ObservableList<TradeRecord> observableRecords = FXCollections.observableArrayList();
    private final Label currentPriceLabel = new Label("當前市價: --");
    private final Label infoLabel = new Label();
    private final TextField sharesField = new TextField("1000");

    private LineChart<Number, Number> lineChart;
    private XYChart.Series<Number, Number> priceSeries = new XYChart.Series<>();
    private int tickCount = 0;

    private final List<StockData> historyData = new ArrayList<>();
    private int dayIndex = 0;
    private Timeline timeline;
    private double xOffset = 0;
    private double yOffset = 0;

    // ===== AI 區塊 =====
    private final AIHub aiHub = new AIHub("api");
    private final TextField aiQuestionField = new TextField();
    private final TextArea aiOutputArea = new TextArea();
    private final ComboBox<MarketMode> marketModeBox = new ComboBox<>();

    // 提供給 WelcomeUI 注入存檔資料的方法
    public void setInitialSaveData(SaveData data) {
        this.initialSaveData = data;
    }

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        // 1. 根據是否有舊存檔，初始化 User 與進度
        if (initialSaveData != null) {
            this.user = initialSaveData.getUser();
            this.dayIndex = initialSaveData.getDayIndex();
            aiHub.setMarketMode(initialSaveData.getMarketMode());
        } else {
            this.user = new User();
            this.dayIndex = 0;
        }

        // 2. 初始化交易引擎與載入 CSV 數據
        List<String> globalCanlendar = csvLoader.loadGlobalCanlendar(STOCK_ID);
        this.tradingEngine = new TradingEngine(globalCanlendar);

        csvLoader.streamStockData(STOCK_ID, data -> {
            historyData.add(data);
        });

        // 3. 將歷史資料快取給 AI 模組
        aiHub.cacheHistoryData(historyData);

        // 4. 如果是舊存檔，把過去的交易紀錄同步到 UI 表格畫面上
        if (this.user.getTradeHistory() != null) {
            List<TradeRecord> pastRecords = this.user.getTradeHistory();
            for (int i = pastRecords.size() - 1; i >= 0; i--) {
                observableRecords.add(pastRecords.get(i));
            }
        }

        setupTable();
        updateInfoLabel();

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(15));
        root.setStyle("-fx-background-radius: 15; -fx-border-radius: 15; -fx-border-color: #444; -fx-background-color: #1c2128;");

        root.setOnMousePressed(e -> {
            xOffset = e.getSceneX();
            yOffset = e.getSceneY();
        });
        root.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - xOffset);
            stage.setY(e.getScreenY() - yOffset);
        });

        // --- Top Bar ---
        HBox topBar = new HBox(15);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(5, 10, 15, 10));

        Button startBtn = new Button("開始模擬");
        startBtn.getStyleClass().add(Styles.ACCENT);
        startBtn.setOnAction(e -> handleStartSimulation());

        sharesField.setPrefWidth(80);

        Button buyBtn = new Button("買入");
        buyBtn.getStyleClass().addAll(Styles.SUCCESS, Styles.BUTTON_OUTLINED);
        buyBtn.setOnAction(e -> handleTrade(true));

        Button sellBtn = new Button("賣出");
        sellBtn.getStyleClass().addAll(Styles.DANGER, Styles.BUTTON_OUTLINED);
        sellBtn.setOnAction(e -> handleTrade(false));

        Button saveBtn = new Button("儲存模擬");
        saveBtn.getStyleClass().addAll(Styles.ACCENT, Styles.BUTTON_OUTLINED);
        saveBtn.setOnAction(e -> handleSaveGame());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.DANGER, Styles.FLAT);
        closeBtn.setOnAction(e -> Platform.exit());

        topBar.getChildren().addAll(
                startBtn,
                new Label("股數:"),
                sharesField,
                buyBtn,
                sellBtn,
                saveBtn,
                currentPriceLabel,
                spacer,
                infoLabel,
                closeBtn
        );

        // --- Center Layout ---
        VBox centerBox = new VBox(10);
        lineChart = createPriceChart();

        VBox.setVgrow(lineChart, Priority.ALWAYS);
        historyTable.setMinHeight(200);
        historyTable.setMaxHeight(200);

        centerBox.getChildren().addAll(lineChart, historyTable);

        // --- AI Panel ---
        VBox aiPanel = createAiPanel();

        root.setTop(topBar);
        root.setCenter(centerBox);
        root.setRight(aiPanel);

        stage.setScene(new Scene(root, 1560, 800));
        stage.setTitle("StockBucks 模擬交易系統");
        stage.show();
    }

    /**
     * 觸發存檔對話框
     */
    private void handleSaveGame() {
        TextInputDialog dialog = new TextInputDialog("mysave");
        dialog.setTitle("儲存模擬進度");
        dialog.setHeaderText("工具內建存檔系統");
        dialog.setContentText("請輸入存檔名稱:");

        dialog.getDialogPane().setStyle("-fx-background-color: #1c2128;");

        dialog.showAndWait().ifPresent(saveName -> {
            String trimmedName = saveName.trim();
            if (!trimmedName.isEmpty()) {
                saveGame(trimmedName);

                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("儲存成功");
                alert.setHeaderText(null);
                alert.setContentText("模擬檔案 [" + trimmedName + ".dat] 已成功儲存至本地端！");
                alert.showAndWait();
            } else {
                showWarning("存檔失敗：名稱不能為空！");
            }
        });
    }

    private void saveGame(String fileName) {
        File dir = new File("saves");
        if (!dir.exists()) {
            dir.mkdir();
        }

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(new File(dir, fileName + ".dat")))) {
            SaveData data = new SaveData(this.user, this.dayIndex, fileName, aiHub.getMarketMode());
            oos.writeObject(data);
        } catch (IOException e) {
            e.printStackTrace();
            showWarning("存檔寫入硬碟時發生錯誤！");
        }
    }

    private LineChart<Number, Number> createPriceChart() {
        NumberAxis xAxis = new NumberAxis();
        NumberAxis yAxis = new NumberAxis();

        xAxis.setLabel("時間 (Ticks)");
        xAxis.setForceZeroInRange(false);

        yAxis.setSide(Side.RIGHT);
        yAxis.setForceZeroInRange(false);

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("即時價格走勢");
        chart.setCreateSymbols(false);
        chart.setAnimated(false);

        priceSeries = new XYChart.Series<>();
        priceSeries.setName("股價");
        chart.getData().add(priceSeries);

        return chart;
    }

    private void handleTrade(boolean isBuy) {
        if (currentPriceLabel.getText().contains("--")) {
            return;
        }

        try {
            int shares = Integer.parseInt(sharesField.getText());
            double price = Double.parseDouble(currentPriceLabel.getText().replace("當前市價: ", ""));
            String date = (historyData != null && dayIndex < historyData.size())
                    ? historyData.get(dayIndex).getDate()
                    : "2024-01-01";

            tradingEngine.trading(user, STOCK_ID, date, shares, price, isBuy);

            List<TradeRecord> records = tradingEngine.getDailyRecords();
            if (!records.isEmpty()) {
                TradeRecord lastRecord = records.get(records.size() - 1);
                if (!observableRecords.contains(lastRecord)) {
                    observableRecords.add(0, lastRecord);
                }
            }

            updateInfoLabel();
        } catch (Exception ex) {
            ex.printStackTrace();
            showWarning("交易執行失敗");
        }
    }

    private void updateInfoLabel() {
        infoLabel.setText(String.format(
                "初始資金: $%,.0f | 可用資金: $%,.0f | 持有股數: %d 股",
                user.getInitialCash(),
                user.getAvailableCash(),
                user.getStockQuantity(STOCK_ID)
        ));
    }

    @SuppressWarnings("unchecked")
    private void setupTable() {
        TableColumn<TradeRecord, String> dateCol = new TableColumn<>("日期");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));

        TableColumn<TradeRecord, String> typeCol = new TableColumn<>("類型");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));

        TableColumn<TradeRecord, Double> priceCol = new TableColumn<>("成交價");
        priceCol.setCellValueFactory(new PropertyValueFactory<>("price"));

        TableColumn<TradeRecord, Integer> sharesCol = new TableColumn<>("股數");
        sharesCol.setCellValueFactory(new PropertyValueFactory<>("shares"));

        TableColumn<TradeRecord, Double> costCol = new TableColumn<>("結算金額");
        costCol.setCellValueFactory(new PropertyValueFactory<>("totalCost"));

        historyTable.getColumns().addAll(dateCol, typeCol, priceCol, sharesCol, costCol);
        historyTable.setItems(observableRecords);
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void handleStartSimulation() {
        List<StockData> modeHistory = aiHub.loadSimulationHistory(STOCK_ID, historyData);
        if (modeHistory != null && !modeHistory.isEmpty() && modeHistory != historyData) {
            historyData.clear();
            historyData.addAll(modeHistory);
            dayIndex = 0;
        }

        if (historyData == null || dayIndex >= historyData.size()) {
            return;
        }

        StockData today = historyData.get(dayIndex);
        double yesterdayClose = (dayIndex == 0)
                ? today.getOpen()
                : historyData.get(dayIndex - 1).getClose();

        simulator.generateDayPath(today, yesterdayClose);

        priceSeries.getData().clear();
        tickCount = 0;

        if (timeline != null) {
            timeline.stop();
        }

        timeline = new Timeline(new KeyFrame(Duration.millis(800), e -> {
            double currentPrice = simulator.getNextPrice();
            if (currentPrice != -1) {
                currentPriceLabel.setText("當前市價: " + String.format("%.2f", currentPrice));
                priceSeries.getData().add(new XYChart.Data<>(tickCount++, currentPrice));
            } else {
                timeline.stop();
                dayIndex++;
            }
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    private VBox createAiPanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(10));
        box.setPrefWidth(360);
        box.setStyle("-fx-background-color: #22272e; -fx-background-radius: 10;");

        Label title = new Label("AI 助手");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #adbac7;");

        marketModeBox.getItems().addAll(MarketMode.REALTIME, MarketMode.HISTORY, MarketMode.AI_RANDOM);
        marketModeBox.setValue(aiHub.getMarketMode());
        marketModeBox.setOnAction(e -> aiHub.setMarketMode(marketModeBox.getValue()));

        aiQuestionField.setPromptText("輸入問題，例如：我現在的持倉風險大嗎？");

        aiOutputArea.setWrapText(true);
        aiOutputArea.setEditable(false);
        aiOutputArea.setPrefHeight(320);

        Button askBtn = new Button("AI 問答");
        askBtn.getStyleClass().addAll(Styles.ACCENT);

        Button analyzeBtn = new Button("市場分析");
        analyzeBtn.getStyleClass().addAll(Styles.SUCCESS, Styles.BUTTON_OUTLINED);

        Button summaryBtn = new Button("交易摘要");
        summaryBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED);

        Button updateDataBtn = new Button("更新市場資料");
        updateDataBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED);

        askBtn.setOnAction(e -> handleAiAsk());
        analyzeBtn.setOnAction(e -> handleAiAnalyze());
        summaryBtn.setOnAction(e -> handleAiSummary());
        updateDataBtn.setOnAction(e -> handleMarketDataUpdate());

        HBox btnRow = new HBox(10, askBtn, analyzeBtn, summaryBtn);

        box.getChildren().addAll(
                title,
                new Label("市場模式"),
                marketModeBox,
                new Label("提問"),
                aiQuestionField,
                btnRow,
                updateDataBtn,
                new Label("AI 回應"),
                aiOutputArea
        );

        return box;
    }

    private double getCurrentPriceValue() {
        try {
            if (!currentPriceLabel.getText().contains("--")) {
                return Double.parseDouble(currentPriceLabel.getText().replace("當前市價: ", ""));
            }
        } catch (Exception ignored) {
        }

        if (historyData != null && !historyData.isEmpty()) {
            int index = Math.max(0, Math.min(dayIndex, historyData.size() - 1));
            return historyData.get(index).getClose();
        }

        return 0.0;
    }

    private void handleAiAsk() {
        String question = aiQuestionField.getText().trim();
        if (question.isEmpty()) {
            showWarning("請先輸入問題");
            return;
        }

        runAiTask("AI 正在回答中...", () ->
                aiHub.answerQuestion(
                        user,
                        tradingEngine,
                        historyData,
                        STOCK_ID,
                        getCurrentPriceValue(),
                        question
                )
        );
    }

    private void handleAiAnalyze() {
        runAiTask("AI 正在分析市場...", () ->
                aiHub.analyzeCurrentMarket(
                        user,
                        tradingEngine,
                        historyData,
                        STOCK_ID,
                        getCurrentPriceValue()
                )
        );
    }

    private void handleAiSummary() {
        runAiTask("AI 正在整理交易摘要...", () ->
                aiHub.summarizeTrades(
                        user,
                        tradingEngine,
                        historyData,
                        STOCK_ID,
                        getCurrentPriceValue()
                )
        );
    }

    private void handleMarketDataUpdate() {
        runAiTask("正在更新市場資料庫：" + aiHub.getMarketDatabasePath() + "...", () -> {
            MarketDataUpdateResult result = aiHub.updateHistoricalData(STOCK_ID);
            return "市場資料更新完成\n"
                    + "資料庫位置：" + aiHub.getMarketDatabasePath() + "\n"
                    + "股票代號：" + result.getStockId() + "\n"
                    + "更新範圍：" + result.getFromDate() + " 到 " + result.getToDate() + "\n"
                    + "擷取筆數：" + result.getFetchedCount() + "\n"
                    + "寫入筆數：" + result.getSavedCount() + "\n"
                    + result.getMessage();
        });
    }

    private void runAiTask(String loadingText, Callable<String> action) {
        aiOutputArea.setText(loadingText);

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return action.call();
            }
        };

        task.setOnSucceeded(e -> aiOutputArea.setText(task.getValue()));
        task.setOnFailed(e -> aiOutputArea.setText("[AI 執行失敗] " + task.getException().getMessage()));

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private void showWarning(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    public static void startApp(String[] args) {
        launch(args);
    }
}
