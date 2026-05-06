package com.stockbucks.gui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.Styles;
import com.stockbucks.CsvLoading;
import com.stockbucks.PriceSimulator;
import com.stockbucks.StockData;
import com.stockbucks.TradeRecord;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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

public class MainApp extends Application {

    private TableView<TradeRecord> historyTable = new TableView<>();
    private ObservableList<TradeRecord> observableRecords = FXCollections.observableArrayList();
    private Label currentPriceLabel = new Label("當前市價: --");
    
    // 圖表相關
    private XYChart.Series<Number, Number> priceSeries = new XYChart.Series<>();
    private int tickCount = 0;

    private CsvLoading csvLoader = new CsvLoading();
    private PriceSimulator simulator = new PriceSimulator();
    private List<StockData> historyData;
    private int dayIndex = 0;
    private Timeline timeline;

    private double xOffset = 0;
    private double yOffset = 0;

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        historyData = csvLoader.loadDate();
        setupTable();

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(15));
        root.setStyle("-fx-background-radius: 15; -fx-border-radius: 15; -fx-border-color: #333; -fx-border-width: 2; -fx-background-color: #1c2128;");

        // --- 視窗拖動 ---
        root.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        root.setOnMouseDragged(event -> {
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        });

        // --- Top Bar (包含關閉鍵) ---
        HBox topBar = new HBox(15);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPrefHeight(50);

        Button startBtn = new Button("開始模擬");
        startBtn.getStyleClass().add(Styles.ACCENT);

        currentPriceLabel.getStyleClass().add(Styles.TITLE_3);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button("✕");
        closeBtn.setPrefSize(35, 35); // 強制給予尺寸
        closeBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.DANGER, Styles.FLAT);
        closeBtn.setOnAction(e -> {
            Platform.exit();
            System.exit(0);
        });

        topBar.getChildren().addAll(startBtn, currentPriceLabel, spacer, closeBtn);

        // --- Center Area (圖表 + 表格) ---
        VBox centerBox = new VBox(15);
        
        // 初始化價格圖表
        LineChart<Number, Number> lineChart = createPriceChart();
        VBox.setVgrow(lineChart, Priority.ALWAYS);
        VBox.setVgrow(historyTable, Priority.SOMETIMES);
        
        centerBox.getChildren().addAll(lineChart, historyTable);
        
        root.setTop(topBar);
        root.setCenter(centerBox);

        stage.initStyle(StageStyle.UNDECORATED);
        Scene scene = new Scene(root, 900, 600);
        scene.setFill(null); // 配合圓角
        stage.setScene(scene);
        stage.show();

        startBtn.setOnAction(e -> handleStartSimulation());
    }

    private LineChart<Number, Number> createPriceChart() {
        NumberAxis xAxis = new NumberAxis();
        NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("時間 (Ticks)");
        yAxis.setForceZeroInRange(false);
        yAxis.setLabel("價格");

        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("即時股價走勢");
        chart.setCreateSymbols(false); // 不顯示數據點的小圓圈，效能較好
        chart.setAnimated(false);      // 關閉圖表自帶動畫，更新會更平滑
        priceSeries.setName("股價");
        chart.getData().add(priceSeries);
        return chart;
    }

    private void setupTable() {
        TableColumn<TradeRecord, String> dateCol = new TableColumn<>("日期");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));
        
        TableColumn<TradeRecord, Double> priceCol = new TableColumn<>("價格");
        priceCol.setCellValueFactory(new PropertyValueFactory<>("price"));

        historyTable.getColumns().addAll(dateCol, priceCol);
        historyTable.setItems(observableRecords);
    }

    private void handleStartSimulation() {
        if (historyData == null || dayIndex >= historyData.size()) return;

        StockData today = historyData.get(dayIndex);
        double yesterdayClose = (dayIndex == 0) ? today.getOpen() : historyData.get(dayIndex - 1).getClose();
        simulator.generateDayPath(today, yesterdayClose);
        
        // 換天時清空圖表舊線段
        priceSeries.getData().clear();
        tickCount = 0;

        if (timeline != null) timeline.stop();

        timeline = new Timeline(new KeyFrame(Duration.millis(800), e -> {
            double currentPrice = simulator.getNextPrice();
            if (currentPrice != -1) {
                currentPriceLabel.setText("當前市價: " + String.format("%.2f", currentPrice));
                
                // 更新圖表數據
                priceSeries.getData().add(new XYChart.Data<>(tickCount++, currentPrice));
            } else {
                timeline.stop();
                dayIndex++;
            }
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    public static void startApp(String[] args) { launch(args); }
}