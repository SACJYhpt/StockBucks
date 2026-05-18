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
import java.util.ArrayList;

public class MainApp extends Application {

    private User user = new User();
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

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        
        List <String> globalCanlendar = csvLoader.loadGlobalCanlendar("TestDataTSMC");
        this.tradingEngine = new TradingEngine(globalCanlendar);

        csvLoader.streamStockData("TestDataTSMC", data -> {
            historyData.add(data);
        });
        
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

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.DANGER, Styles.FLAT);
        closeBtn.setOnAction(e -> Platform.exit());

        topBar.getChildren().addAll(startBtn, new Label("股數:"), sharesField, buyBtn, sellBtn, currentPriceLabel, spacer, infoLabel, closeBtn);

        // --- Center Layout ---
        VBox centerBox = new VBox(10);
        lineChart = createPriceChart();
        
        VBox.setVgrow(lineChart, Priority.ALWAYS);
        historyTable.setMinHeight(200);
        historyTable.setMaxHeight(200);

        centerBox.getChildren().addAll(lineChart, historyTable);
        root.setTop(topBar);
        root.setCenter(centerBox);

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setScene(new Scene(root, 1200, 800));
        stage.show();
    }

    private LineChart<Number, Number> createPriceChart() {
        NumberAxis xAxis = new NumberAxis();
        NumberAxis yAxis = new NumberAxis();
        
        xAxis.setLabel("時間 (Ticks)");
        xAxis.setForceZeroInRange(false);
        
        yAxis.setSide(Side.RIGHT); // 保持座標軸在右邊
        yAxis.setForceZeroInRange(false);
        
        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("即時價格走勢");
        chart.setCreateSymbols(false); // 不顯示折線上的圓點，畫面比較乾淨
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
//cd "C:\Users\wwumi\Documents\GitHub\StockBucks"
//mvn clean javafx:run