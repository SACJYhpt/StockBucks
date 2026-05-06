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
    
    // 資產與交易輸入相關
    private double balance = 1000000.0;
    private int inventory = 0;
    private Label infoLabel = new Label("餘額: $1,000,000 | 持股: 0 股");
    private TextField sharesField = new TextField("1000"); // 股數輸入框

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
        root.setStyle("-fx-background-radius: 15; -fx-border-radius: 15; -fx-border-color: #444; -fx-border-width: 2; -fx-background-color: #1c2128;");

        root.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        root.setOnMouseDragged(event -> {
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        });

        // --- Top Bar ---
        HBox topBar = new HBox(15);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(5, 10, 15, 10));

        Button startBtn = new Button("開始模擬");
        startBtn.getStyleClass().add(Styles.ACCENT);
        startBtn.setOnAction(e -> handleStartSimulation());

        // 股數輸入框樣式與限制
        sharesField.setPrefWidth(100);
        sharesField.setPromptText("輸入股數");
        sharesField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal.matches("\\d*")) {
                sharesField.setText(newVal.replaceAll("[^\\d]", ""));
            }
        });

        Button buyBtn = new Button("買入");
        buyBtn.getStyleClass().addAll(Styles.SUCCESS, Styles.BUTTON_OUTLINED);
        buyBtn.setOnAction(e -> handleTrade(true));

        Button sellBtn = new Button("賣出");
        sellBtn.getStyleClass().addAll(Styles.DANGER, Styles.BUTTON_OUTLINED);
        sellBtn.setOnAction(e -> handleTrade(false));

        currentPriceLabel.getStyleClass().add(Styles.TITLE_3);
        infoLabel.getStyleClass().add(Styles.TEXT_BOLD);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button("✕");
        closeBtn.setPrefSize(35, 35);
        closeBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.DANGER, Styles.FLAT);
        closeBtn.setOnAction(e -> {
            Platform.exit();
            System.exit(0);
        });

        // 排版：開始 -> 股數欄 -> 買 -> 賣 -> 價格 -> 空白 -> 資訊 -> 關閉
        topBar.getChildren().addAll(startBtn, new Label("股數:"), sharesField, buyBtn, sellBtn, currentPriceLabel, spacer, infoLabel, closeBtn);

        VBox centerBox = new VBox(15);
        LineChart<Number, Number> lineChart = createPriceChart();
        VBox.setVgrow(lineChart, Priority.ALWAYS);
        VBox.setVgrow(historyTable, Priority.SOMETIMES);
        
        centerBox.getChildren().addAll(lineChart, historyTable);
        
        root.setTop(topBar);
        root.setCenter(centerBox);

        stage.initStyle(StageStyle.UNDECORATED);
        Scene scene = new Scene(root, 1100, 750);
        scene.setFill(null); 
        stage.setScene(scene);
        stage.show();
    }

    private void handleTrade(boolean isBuy) {
        if (currentPriceLabel.getText().contains("--")) return;

        try {
            // 從輸入框讀取股數，若為空或 0 則不執行
            String sharesText = sharesField.getText();
            if (sharesText.isEmpty()) return;
            int shares = Integer.parseInt(sharesText);
            if (shares <= 0) return;

            double price = Double.parseDouble(currentPriceLabel.getText().replace("當前市價: ", ""));
            String date = (historyData != null && dayIndex < historyData.size()) 
                          ? historyData.get(dayIndex).getDate() : "2024-01-01";
            
            double amount = price * shares;
            double commission = Math.max(20, Math.floor(amount * 0.001425)); 
            
            if (isBuy) {
                double totalCost = amount + commission;
                if (balance >= totalCost) {
                    balance -= totalCost;
                    inventory += shares;
                    observableRecords.add(new TradeRecord("STOCK001", date, "買入", price, shares, commission, 0.0, totalCost));
                } else {
                    showWarning("餘額不足！");
                }
            } else {
                if (inventory >= shares) {
                    double tax = Math.floor(amount * 0.003); 
                    double totalIncome = amount - commission - tax;
                    balance += totalIncome;
                    inventory -= shares;
                    observableRecords.add(new TradeRecord("STOCK001", date, "賣出", price, shares, commission, tax, totalIncome));
                } else {
                    showWarning("持股不足！");
                }
            }
            updateInfoLabel();
        } catch (NumberFormatException ex) {
            showWarning("股數格式錯誤");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void updateInfoLabel() {
        infoLabel.setText(String.format("餘額: $%,.0f | 持股: %d 股", balance, inventory));
    }

    private void showWarning(String msg) {
        // 為了讓使用者看到警告，簡單使用彈窗
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private LineChart<Number, Number> createPriceChart() {
        NumberAxis xAxis = new NumberAxis();
        NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("時間 (Ticks)");
        yAxis.setForceZeroInRange(false);
        
        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("即時股價走勢");
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        priceSeries.setName("股價");
        chart.getData().add(priceSeries);
        return chart;
    }

    private void setupTable() {
        TableColumn<TradeRecord, String> dateCol = new TableColumn<>("日期");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));
        
        TableColumn<TradeRecord, String> typeCol = new TableColumn<>("類型");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));

        TableColumn<TradeRecord, Double> priceCol = new TableColumn<>("成交價");
        priceCol.setCellValueFactory(new PropertyValueFactory<>("price"));

        TableColumn<TradeRecord, Integer> sharesCol = new TableColumn<>("股數");
        sharesCol.setCellValueFactory(new PropertyValueFactory<>("shares"));

        TableColumn<TradeRecord, Double> costCol = new TableColumn<>("淨收支");
        costCol.setCellValueFactory(new PropertyValueFactory<>("totalCost"));

        historyTable.getColumns().addAll(dateCol, typeCol, priceCol, sharesCol, costCol);
        historyTable.setItems(observableRecords);
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
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

    public static void startApp(String[] args) { launch(args); }
}