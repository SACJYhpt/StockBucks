package com.stockbucks.gui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.Styles;
import com.stockbucks.*; 
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
import javafx.stage.StageStyle;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import java.util.List;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.File;
import java.util.ArrayList;

public class MainApp extends Application {
    // 接收 WelcomeUI 傳遞過來的存檔資料
    private SaveData initialSaveData = null;

    private User user;
    private TradingEngine tradingEngine;
    private CsvLoading csvLoader = new CsvLoading();
    private PriceSimulator simulator = new PriceSimulator();
    
    private TableView<TradeRecord> historyTable = new TableView<>();
    private ObservableList<TradeRecord> observableRecords = FXCollections.observableArrayList();
    private Label currentPriceLabel = new Label("當前市價: --");
    private Label infoLabel = new Label();
    private TextField sharesField = new TextField("1000");

    private LineChart<Number, Number> lineChart;
    private XYChart.Series<Number, Number> priceSeries = new XYChart.Series<>();
    private int tickCount = 0;

    private List<StockData> historyData = new ArrayList<>();
    private int dayIndex = 0;
    private Timeline timeline;
    private double xOffset = 0;
    private double yOffset = 0;

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
        } else {
            this.user = new User();
            this.dayIndex = 0;
        }

        // 2. 初始化交易引擎與載入 CSV 數據
        List<String> globalCanlendar = csvLoader.loadGlobalCanlendar("TestDataTSMC");
        this.tradingEngine = new TradingEngine(globalCanlendar);

        csvLoader.streamStockData("TestDataTSMC", data -> {
            historyData.add(data);
        });
        
        // 3. 如果是舊存檔，把過去的交易紀錄同步到 UI 表格畫面上
        if (this.user.getTradeHistory() != null) {
            List<TradeRecord> pastRecords = this.user.getTradeHistory();
            // 倒序排列，讓最新的交易紀錄顯示在最上面
            for (int i = pastRecords.size() - 1; i >= 0; i--) {
                observableRecords.add(pastRecords.get(i));
            }
        }
        
        setupTable();
        updateInfoLabel();

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(15));
        root.setStyle("-fx-background-radius: 15; -fx-border-radius: 15; -fx-border-color: #444; -fx-background-color: #1c2128;");

        root.setOnMousePressed(e -> { xOffset = e.getSceneX(); yOffset = e.getSceneY(); });
        root.setOnMouseDragged(e -> { stage.setX(e.getScreenX() - xOffset); stage.setY(e.getScreenY() - yOffset); });

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

        // 新增的「儲存模擬」按鈕
        Button saveBtn = new Button("儲存模擬");
        saveBtn.getStyleClass().addAll(Styles.ACCENT, Styles.BUTTON_OUTLINED);
        saveBtn.setOnAction(e -> handleSaveGame());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.DANGER, Styles.FLAT);
        closeBtn.setOnAction(e -> Platform.exit());

        // 將儲存按鈕塞入工具列中
        topBar.getChildren().addAll(startBtn, new Label("股數:"), sharesField, buyBtn, sellBtn, saveBtn, currentPriceLabel, spacer, infoLabel, closeBtn);

        // --- Center Layout ---
        VBox centerBox = new VBox(10);
        lineChart = createPriceChart();
        
        VBox.setVgrow(lineChart, Priority.ALWAYS);
        historyTable.setMinHeight(200);
        historyTable.setMaxHeight(200);

        centerBox.getChildren().addAll(lineChart, historyTable);
        root.setTop(topBar);
        root.setCenter(centerBox);

        //stage.initStyle(StageStyle.UNDECORATED);
        stage.setScene(new Scene(root, 1200, 800));
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
        
        // 漂亮地套用暗色系風格
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
        // 確保 saves 資料夾存在
        File dir = new File("saves");
        if (!dir.exists()) {
            dir.mkdir();
        }
        
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(new File(dir, fileName + ".dat")))) {
            SaveData data = new SaveData(this.user, this.dayIndex, fileName);
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
        if (currentPriceLabel.getText().contains("--")) return;
        try {
            int shares = Integer.parseInt(sharesField.getText());
            double price = Double.parseDouble(currentPriceLabel.getText().replace("當前市價: ", ""));
            String date = (historyData != null && dayIndex < historyData.size()) ? historyData.get(dayIndex).getDate() : "2024-01-01";

            tradingEngine.trading(user, "TestDataTSMC", date, shares, price, isBuy);
            
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
        infoLabel.setText(String.format("可用現金: $%,.0f | 庫存: %d 股", 
            user.getCash(), user.getStockQuantity("TestDataTSMC")));
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
        if (historyData == null || dayIndex >= historyData.size()) return;

        StockData today = historyData.get(dayIndex);
        double yesterdayClose = (dayIndex == 0) ? today.getOpen() : historyData.get(dayIndex - 1).getClose();
        simulator.generateDayPath(today, yesterdayClose);
        
        priceSeries.getData().clear();
        tickCount = 0;

        if (timeline != null) timeline.stop();

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