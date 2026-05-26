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
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainApp extends Application {
    private SaveData initialSaveData = null;
    private User user;
    private TradingEngine tradingEngine;
    private CsvLoading csvLoader = new CsvLoading();
    private PriceSimulator simulator = new PriceSimulator();
    
    // UI 控制元件（下單交易視窗用）
    private TableView<TradeRecord> historyTable = new TableView<>();
    private ObservableList<TradeRecord> observableRecords = FXCollections.observableArrayList();
    private Label currentPriceLabel = new Label("當前市價: --");
    private Label infoLabel = new Label();
    private TextField sharesField = new TextField("1000");
    private LineChart<Number, Number> lineChart;
    private XYChart.Series<Number, Number> priceSeries = new XYChart.Series<>();
    
    // 模擬核心數據
    private int tickCount = 0;
    private List<StockData> historyData = new ArrayList<>();
    private int dayIndex = 0;
    private Timeline timeline;

    //  側邊欄核心元件
    private BorderPane mainLayout;
    private StackPane contentArea;
    private VBox sidebar;
    private boolean isSidebarExpanded = true;

    // 獨立出來的四個子畫面 Container
    private StackPane marketView;
    private BorderPane tradeView;
    private VBox assetView;
    private VBox orderListView;

    // 定義選單 Enum
    private enum MenuType {
        MARKET("📈  市場行情", "📈"),
        TRADE("💰  下單交易", "💰"),
        ASSET("📇  帳務總覽", "📇"),
        ORDERS("📜  委託清單", "📜");

        final String fullText;
        final String icon;
        MenuType(String fullText, String icon) {
            this.fullText = fullText;
            this.icon = icon;
        }
    }

    public void setInitialSaveData(SaveData data) {
        this.initialSaveData = data;
    }

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        
        // 1. 初始化資料
        if (initialSaveData != null) {
            this.user = initialSaveData.getUser();
            this.dayIndex = initialSaveData.getDayIndex();
        } else {
            this.user = new User();
            this.dayIndex = 0;
        }

        List<String> globalCanlendar = csvLoader.loadGlobalCanlendar("TestDataTSMC");
        this.tradingEngine = new TradingEngine(globalCanlendar);

        csvLoader.streamStockData("TestDataTSMC", data -> historyData.add(data));
        
        if (this.user.getTradeHistory() != null) {
            List<TradeRecord> pastRecords = this.user.getTradeHistory();
            for (int i = pastRecords.size() - 1; i >= 0; i--) {
                observableRecords.add(pastRecords.get(i));
            }
        }
        
        setupTable();
        updateInfoLabel();

        // 2. 核心架構：外層主佈局
        mainLayout = new BorderPane();
        contentArea = new StackPane();
        contentArea.setPadding(new Insets(15));
        contentArea.setStyle("-fx-background-color: #22272e;");

        // 3. 預先建立好四個子畫面
        initAllViews();

        // 4. 建立側邊欄並裝載到 Left
        sidebar = createSidebar();
        mainLayout.setLeft(sidebar);
        mainLayout.setCenter(contentArea);

        // 預設進入第一個畫面
        switchView(MenuType.MARKET);

        stage.setScene(new Scene(mainLayout, 1300, 850));
        stage.setTitle("StockBucks 模擬交易系統");
        stage.show();
    }

    /**
     * 初始化四大主要功能畫面
     */
    private void initAllViews() {
        // 【1. 市場行情】仿國泰方格行情佈局
        marketView = new StackPane();
        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(15);
        grid.setAlignment(Pos.CENTER);
        
        // 快捷建立測試用卡片（未來可依據不同 stockId 動態建構）
        String[] stocks = {"元大台灣50", "富邦科技 0052", "國泰台灣科技", "台積電 2330"};
        int col = 0, row = 0;
        for (String name : stocks) {
            VBox card = new VBox(10);
            card.setStyle("-fx-background-color: #2d333b; -fx-border-color: #444; -fx-border-radius: 8; -fx-background-radius: 8;");
            card.setPadding(new Insets(20));
            card.setPrefSize(220, 150);
            card.setAlignment(Pos.CENTER);
            
            Label lblName = new Label(name);
            lblName.getStyleClass().add(Styles.TITLE_4);
            Label lblPrice = new Label("#100.65"); // 假資料示意
            lblPrice.setStyle("-fx-text-fill: #ea4335; -fx-font-size: 24px; -fx-font-weight: bold;");
            
            card.getChildren().addAll(lblName, lblPrice);
            // 💡 點擊卡片後直接切換到「下單交易」
            card.setOnMouseClicked(e -> switchView(MenuType.TRADE));
            
            grid.add(card, col, row);
            col++; if (col > 1) { col = 0; row++; }
        }
        marketView.getChildren().add(grid);

        // 【2. 下單交易】把原本的主程式畫面完整塞在這裡
        tradeView = new BorderPane();
        
        HBox topBar = new HBox(15);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(5, 0, 15, 0));

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

        topBar.getChildren().addAll(startBtn, new Label("股數:"), sharesField, buyBtn, sellBtn, saveBtn, currentPriceLabel, spacer, infoLabel);

        lineChart = createPriceChart();
        tradeView.setTop(topBar);
        tradeView.setCenter(lineChart);

        // 【3. 帳務總覽】
        assetView = new VBox(20);
        assetView.setPadding(new Insets(20));
        Label assetTitle = new Label("💰 我的帳務資產總覽");
        assetTitle.getStyleClass().add(Styles.TITLE_2);
        // 這邊可以塞資產圓餅圖或是庫存餘額明細表格
        assetView.getChildren().addAll(assetTitle, new Separator());

        // 【4. 委託清單】把歷史交易表格放在獨立畫面
        orderListView = new VBox(15);
        orderListView.setPadding(new Insets(10));
        Label orderTitle = new Label("📜 歷史成交與委託明細");
        orderTitle.getStyleClass().add(Styles.TITLE_2);
        VBox.setVgrow(historyTable, Priority.ALWAYS);
        orderListView.getChildren().addAll(orderTitle, historyTable);
    }

    /**
     *  伸縮側邊欄
     */
    private VBox createSidebar() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(15, 10, 15, 10));
        box.setPrefWidth(220); // 初始展開寬度
        box.setStyle("-fx-background-color: #1c2128; -fx-border-color: #30363d; -fx-border-width: 0 1 0 0;");

        // 頂部漢堡縮放按鈕
        Button toggleBtn = new Button("☰");
        toggleBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.FLAT);

        // 建立功能按鈕
        List<Button> menuButtons = new ArrayList<>();
        for (MenuType menu : MenuType.values()) {
            Button btn = new Button(menu.fullText);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setAlignment(Pos.BASELINE_LEFT);
            btn.getStyleClass().add(Styles.FLAT);
            btn.setPadding(new Insets(10, 15, 10, 15));
            btn.setOnAction(e -> switchView(menu));
            menuButtons.add(btn);
        }

        // 側邊欄收合動態控制
        toggleBtn.setOnAction(e -> {
            if (isSidebarExpanded) {
                box.setPrefWidth(65);
                for (int i = 0; i < menuButtons.size(); i++) {
                    menuButtons.get(i).setText(MenuType.values()[i].icon);
                }
                isSidebarExpanded = false;
            } else {
                box.setPrefWidth(220);
                for (int i = 0; i < menuButtons.size(); i++) {
                    menuButtons.get(i).setText(MenuType.values()[i].fullText);
                }
                isSidebarExpanded = true;
            }
        });

        box.getChildren().add(toggleBtn);
        box.getChildren().add(new Separator());
        box.getChildren().addAll(menuButtons);
        return box;
    }

    /**
     * 動態切換中央工作區畫面的核心方法
     */
    private void switchView(MenuType menu) {
        contentArea.getChildren().clear();
        updateInfoLabel(); // 每當切換畫面，同步更新最新的資產狀態數值
        
        switch (menu) {
            case MARKET:
                contentArea.getChildren().add(marketView);
                break;
            case TRADE:
                contentArea.getChildren().add(tradeView);
                break;
            case ASSET:
                contentArea.getChildren().add(assetView);
                // 可以在這裡呼叫方法重新渲染資產清單
                break;
            case ORDERS:
                contentArea.getChildren().add(orderListView);
                break;
        }
    }

    // ===== 模擬功能邏輯 =====
    
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
        if (!dir.exists()) dir.mkdir();
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

        historyTable.getColumns().clear();
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