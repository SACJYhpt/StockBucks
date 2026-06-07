package com.stockbucks.gui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.Styles;

import com.stockbucks.*;
import com.stockbucks.api.AIHub;

import javafx.application.Application;
import javafx.application.Platform;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.geometry.Rectangle2D;

import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Screen;
import javafx.stage.StageStyle;

import javafx.animation.TranslateTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.FadeTransition;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.text.NumberFormat;

import javafx.util.Duration;
import java.util.ArrayList;
import java.util.List;

public class MainApp extends Application {
    private SaveData initialSaveData = null;
    private String currentSelectedStockId = "2330";
    private User user;
    private Timeline marketSearchDebounce = null;
    private Timeline tradeSearchDebounce = null;
    private TradingEngine tradingEngine;
    private CsvLoading csvLoader = new CsvLoading();
    private PriceSimulator simulator = new PriceSimulator();
    
    // UI 控制元件（下單交易視窗用）
    private TableView<TradeRecord> historyTable = new TableView<>();
    private TableView<Order> activeOrderTable = new TableView<>();
    private ObservableList<TradeRecord> observableRecords = FXCollections.observableArrayList();
    private ObservableList<Order> observableOrders = FXCollections.observableArrayList();
    private Label currentPriceLabel = new Label("當前市價: --");
    private Label infoLabel = new Label();
    private TextField priceField;
    private TextField sharesField = new TextField("1000");
    private LineChart<Number, Number> lineChart;
    private XYChart.Series<Number, Number> priceSeries = new XYChart.Series<>();
    private XYChart.Series<Number, Number> limitUpSeries;
    private XYChart.Series<Number, Number> limitDownSeries;
    private XYChart.Series<Number, Number> yesterdayCloseSeries;

    private HBox backtestControlBar;  // 包含時空選擇與速控的容器
    private DatePicker dateSelector;  // 月曆時空選擇器
    private Slider speedSlider;       // 速度控制條
    private Label speedLabel;       // 用於顯示當前模擬速度的標籤
    
    // 模擬核心數據
    private int tickCount = 0;
    private List<StockData> historyData = new ArrayList<>();
    private int dayIndex = 0;
    private double currentPrice;
    private com.stockbucks.api.mode.MarketMode marketMode = com.stockbucks.api.mode.MarketMode.HISTORY;
    private Timeline timeline;
    private static final double BASE_INTERVAL_MS = 1000.0;

    //  側邊欄核心元件
    private BorderPane mainLayout;
    private StackPane contentArea;
    private VBox sidebar;
    private boolean isSidebarExpanded = true;
    private Stage stage;

    private AIHub aiHub = new AIHub(); // 初始化 AI 入口

    // 獨立出來的四個子畫面 Container
    private StackPane marketView;
    private BorderPane tradeView;
    private VBox assetView;
    private VBox orderListView;
    private VBox activeOrderLayout;
    private VBox emptyPlaceholder;

    // 最愛清單資料結構：Key 是分頁名稱，Value 是該分頁裡的股票列表
    private java.util.LinkedHashMap<String, ObservableList<String>> watchlistData = new java.util.LinkedHashMap<>();
    private TabPane marketTabPane = new TabPane();
    private TextField marketSearchField = new TextField();
    private TextField stockSearchField;

    // 定義選單 Enum
    private enum MenuType {
        MARKET("📈  市場行情", "📈"),
        TRADE("💰  下單交易", "💰"),
        ASSET("📇  帳務總覽", "📇"),
        ORDERS("📜  委託清單", "📜"),
        EXIT("🚪 返回主選單", "🚪");

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
        this.stage = stage;
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        
        // 1. 初始化資料
        if (initialSaveData != null) {
            this.user = initialSaveData.getUser();
            this.dayIndex = initialSaveData.getDayIndex();
            this.marketMode = initialSaveData.getMarketMode();
            //讀取存檔：還原自選股分頁與字卡
            if (initialSaveData.getSerializableWatchlist() != null) {
                watchlistData.clear(); // 清空預設值
                java.util.LinkedHashMap<String, ArrayList<String>> loadedWatchlist = initialSaveData.getSerializableWatchlist();
                for (String key : loadedWatchlist.keySet()) {
                    // 將 ArrayList 轉回 JavaFX 專用的 ObservableList
                    watchlistData.put(key, FXCollections.observableArrayList(loadedWatchlist.get(key)));
                }
            }
        } else {
            this.user = new User();
            this.dayIndex = 0;
            this.marketMode = com.stockbucks.api.mode.MarketMode.HISTORY;
        }

        //如果是全新開局，或是舊存檔完全沒有自選股資料，才初始化預設的「全部最愛」
        if (!watchlistData.containsKey("全部最愛")) {
            watchlistData.put("全部最愛", FXCollections.observableArrayList());
            watchlistData.get("全部最愛").addAll("0050", "2330");
        }

        List<String> globalCalendar = csvLoader.loadGlobalCanlendar("0050");
        this.tradingEngine = new TradingEngine(globalCalendar);

        if (this.historyData != null) {
            this.historyData.clear(); // 保持乾淨，開局不盲目塞入任何個股歷史
        }
        
        if (this.user.getTradeHistory() != null) {
            List<TradeRecord> pastRecords = this.user.getTradeHistory();
            for (int i = pastRecords.size() - 1; i >= 0; i--) {
                observableRecords.add(pastRecords.get(i));
            }
        }
        
        setupTable();
        setupActiveOrderTable();
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

        stage.setScene(new Scene(mainLayout, 900, 500));
        stage.setTitle("StockBucks 模擬交易系統");
        stage.show();
    }

    /**
     * 初始化四大主要功能畫面
     */
    private void initAllViews() {
        if (this.marketMode == com.stockbucks.api.mode.MarketMode.REALTIME) {
            aiHub.setMarketMode(com.stockbucks.api.mode.MarketMode.REALTIME);
        } else {
            aiHub.setMarketMode(com.stockbucks.api.mode.MarketMode.HISTORY);
        }

        // 【1. 市場行情】
        marketView = new StackPane();
        VBox marketLayout = new VBox(15);
        marketLayout.setPadding(new Insets(10));

        // ===== (A) 頂部搜尋與加入最愛功能列 =====
        HBox marketTopBar = new HBox(10);
        marketTopBar.setAlignment(Pos.CENTER_LEFT);

        marketSearchField.setPromptText("🔍 輸入公司名稱或代碼... (例如: 台積電 或 2330)");
        marketSearchField.setPrefWidth(400);
        marketSearchField.getStyleClass().add(Styles.ROUNDED);

        //建立搜尋推薦選單
        ContextMenu searchPopup = new ContextMenu();
        searchPopup.setStyle("-fx-background-color: #2d333b; -fx-border-color: #444;");

        //監聽打字事件，動態匹配全市場股票
        marketSearchField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (marketSearchDebounce != null) {
                marketSearchDebounce.stop();
            }

            marketSearchDebounce = new Timeline(new KeyFrame(Duration.millis(300), e -> {
                String query = newValue.trim().toLowerCase();
                if (query.isEmpty()) {
                    searchPopup.hide();
                    return;
                }

                searchPopup.getItems().clear();
                List<com.stockbucks.api.stock.StockProfile> allStocks = aiHub.fetchAllListedStocks();
                
                if (allStocks != null) {
                    int limit = 0;
                    for (com.stockbucks.api.stock.StockProfile p : allStocks) {
                        String id = p.getStockId();
                        
                        //如果 getStockName() 依然報錯，請記得在這一行改呼叫 p.getCompanyName()
                        String name = p.getStockName(); 
                        
                        if (id == null || name == null) continue;

                        // 支援同時用「代號」或「中文名稱」進行模糊搜尋
                        if (id.toLowerCase().contains(query) || name.toLowerCase().contains(query)) {
                            MenuItem item = new MenuItem(id + "  " + name);
                            item.setStyle("-fx-text-fill: #adbac7; -fx-font-size: 14px;");
                            
                            // 當選中了某一筆推薦（例如選中 2330 台積電）
                            item.setOnAction(evt -> {
                                //核心修正：將輸入框自動修正成「純代號」，確保加入最愛後的字卡永遠讀得到資料！
                                marketSearchField.setText(id); 
                                searchPopup.hide();
                                
                                // 連動主介面的看盤跑線邏輯
                                if (this.stockSearchField != null) {
                                    this.stockSearchField.setText(id);
                                    this.currentSelectedStockId = id;
                                    handleStartSimulation();
                                }
                            });
                            
                            searchPopup.getItems().add(item);
                            limit++;
                            if (limit >= 5) break; // 最多顯示 5 筆
                        }
                    }
                }

                // 展開推薦選單
                if (!searchPopup.getItems().isEmpty()) {
                    if (!searchPopup.isShowing()) {
                        searchPopup.show(marketSearchField, javafx.geometry.Side.BOTTOM, 0, 0);
                    }
                } else {
                    searchPopup.hide();
                }
            }));
            marketSearchDebounce.play();
        });

        // 當輸入框失去焦點，自動隱藏選單
        marketSearchField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) searchPopup.hide();
        });

        Button addToFavoriteBtn = new Button("⭐ 加入最愛");
        addToFavoriteBtn.getStyleClass().add(Styles.ACCENT);
        
        marketTopBar.getChildren().addAll(marketSearchField, addToFavoriteBtn);

        // ===== (B) 中央多功能分頁系統 (TabPane) =====
        marketTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); // 預設不能關閉
        
        // 重新渲染所有分頁的按鈕
        refreshWatchlistTabs();

        // 綁定「加入最愛」的按鈕邏輯
        addToFavoriteBtn.setOnAction(e -> {
            String stockInput = marketSearchField.getText().trim();
            if (stockInput.isEmpty()) {
                showWarning("請輸入公司名稱或代碼！");
                return;
            }

            // 獲取當前使用者切換到哪一個分頁
            Tab currentTab = marketTabPane.getSelectionModel().getSelectedItem();
            if (currentTab == null) return;
            String currentTabName = currentTab.getText();

            // 1. 無論在哪個分頁加，都必須同步放進「全部最愛」
            ObservableList<String> allList = watchlistData.get("全部最愛");
            if (!allList.contains(stockInput)) {
                allList.add(stockInput);
            }

            // 2. 如果目前不是在第一頁，也要放進當前的自訂分頁中
            if (!currentTabName.equals("全部最愛")) {
                ObservableList<String> subList = watchlistData.get(currentTabName);
                if (subList != null && !subList.contains(stockInput)) {
                    subList.add(stockInput);
                }
            }

            marketSearchField.clear();
            refreshWatchlistTabs(); // 刷新 UI 畫面，字卡立刻跳出來
        });

        VBox.setVgrow(marketTabPane, Priority.ALWAYS);
        marketLayout.getChildren().addAll(marketTopBar, marketTabPane);
        marketView.getChildren().add(marketLayout);

        // 【2. 下單交易】把原本的主程式畫面完整塞在這裡
        tradeView = new BorderPane();
        tradeView.setPadding(new Insets(10));

        // --- (A) 頂部搜尋與控制列 ---
        HBox topSearchBar = new HBox(15);
        topSearchBar.setAlignment(Pos.CENTER_LEFT);
        topSearchBar.setPadding(new Insets(0, 0, 15, 0));

        // 搜尋輸入框
        if (this.stockSearchField == null) {
            this.stockSearchField = new TextField();
        }
        stockSearchField.setPromptText("🔍 搜尋股票、名稱或代碼...");
        stockSearchField.setPrefWidth(400);
        stockSearchField.getStyleClass().add(Styles.ROUNDED); // 讓邊角變圓

        ContextMenu tradeSearchPopup = new ContextMenu();
        tradeSearchPopup.setStyle("-fx-background-color: #2d333b; -fx-border-color: #444;");
        stockSearchField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (tradeSearchDebounce != null) {
                tradeSearchDebounce.stop();
            }

            tradeSearchDebounce = new Timeline(new KeyFrame(Duration.millis(300), e -> {
                String query = newValue.trim().toLowerCase();
                if (query.isEmpty()) { tradeSearchPopup.hide(); return; }
                tradeSearchPopup.getItems().clear();
                List<com.stockbucks.api.stock.StockProfile> allStocks = aiHub.fetchAllListedStocks();
                if (allStocks != null) {
                    int limit = 0;
                    for (com.stockbucks.api.stock.StockProfile p : allStocks) {
                        String id = p.getStockId();
                        String name = p.getStockName(); // 若報錯請改 getCompanyName()
                        if (id == null || name == null) continue;
                        if (id.toLowerCase().contains(query) || name.toLowerCase().contains(query)) {
                            MenuItem item = new MenuItem(id + "  " + name);
                            item.setStyle("-fx-text-fill: #adbac7; -fx-font-size: 14px;");
                            item.setOnAction(evt -> {
                                stockSearchField.setText(id); // 自動導正為純代號
                                if (marketSearchField != null) marketSearchField.setText(id); // 同步給自選頁
                                tradeSearchPopup.hide();
                                this.currentSelectedStockId = id;
                                handleStartSimulation(); // 導正後立刻連動更換線圖與啟動模擬！
                            });
                            tradeSearchPopup.getItems().add(item);
                            if (++limit >= 5) break;
                        }
                    }
                }
                if (!tradeSearchPopup.getItems().isEmpty() && !tradeSearchPopup.isShowing()) {
                    tradeSearchPopup.show(stockSearchField, javafx.geometry.Side.BOTTOM, 0, 0);
                } else if (tradeSearchPopup.getItems().isEmpty()) {
                    tradeSearchPopup.hide();
                }
            }));
            tradeSearchDebounce.play();
        });
        stockSearchField.focusedProperty().addListener((obs, oldVal, newVal) -> { if (!newVal) tradeSearchPopup.hide(); });

        Button startBtn = new Button("開始模擬");
        startBtn.getStyleClass().add(Styles.ACCENT);
        startBtn.setOnAction(e -> handleStartSimulation());

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        topSearchBar.getChildren().addAll(stockSearchField, startBtn, topSpacer, currentPriceLabel);

        // --- (B) 中間圖表與交易控制區 ---
        VBox centerArea = new VBox(10);
        lineChart = createPriceChart(100.0, 110.0 ,90.0);
        VBox.setVgrow(lineChart, Priority.ALWAYS);

        // 交易控制面板 (放在圖表下方)
        HBox tradeControlPanel = new HBox(15);
        tradeControlPanel.setAlignment(Pos.CENTER_LEFT);
        tradeControlPanel.setPadding(new Insets(10));
        tradeControlPanel.setStyle("-fx-background-color: #2d333b; -fx-background-radius: 8;");

        // 讓原本就有的 currentPriceLabel 變成可以點擊帶入價格
        if (currentPriceLabel != null) {
            currentPriceLabel.setCursor(javafx.scene.Cursor.HAND);
            currentPriceLabel.setOnMouseClicked(event -> {
                if (currentPrice > 0) {
                    priceField.setText(String.format("%.2f", currentPrice));
                    showNotification("💡 已將價格帶入為當前市價：" + String.format("%.2f", currentPrice), "INFO");
                }
            });
        }

        // 價格控制欄位：上下分層按鈕 (左側/右側按鈕各切上下兩半。上：1 Tick / 下：10 Tick)
        priceField = new TextField();
        priceField.setPromptText("限價");
        priceField.setPrefWidth(85);
        priceField.setStyle("-fx-background-color: #22272e; -fx-text-fill: #adbac7; -fx-alignment: CENTER; -fx-font-weight: bold; -fx-border-color: #444c56; -fx-border-radius: 4;");

        // 讓輸入文字在「滑鼠點擊外面/失去焦點」時，自動四捨五入修正為合法 Tick
        priceField.focusedProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue) { // 代表玩家輸入完點擊其他地方，失去焦點了
                String text = priceField.getText().trim();
                if (text.isEmpty()) return;
                try {
                    double inputPrice = Double.parseDouble(text);
                    if (inputPrice <= 0) return;

                    double tickSize = simulator.getTickChangeSize(inputPrice);

                    double validatedPrice = Math.round(inputPrice / tickSize) * tickSize;

                    double newTickSize = simulator.getTickChangeSize(validatedPrice);
                    if (tickSize != newTickSize) {
                        validatedPrice = Math.round(inputPrice / newTickSize) * newTickSize;
                    }

                    priceField.setText(String.format("%.2f", validatedPrice));
                }
                catch (NumberFormatException ex) {
                    if (currentPrice > 0) {
                        priceField.setText(String.format("%.2f", currentPrice));
                    }
                }
            }
        });

        // 建立價格【減少】的上下複合按鈕 (上: 減1 Tick, 下: 減10 Tick)
        Button btnPriceMinus1 = new Button("◀");
        Button btnPriceMinus10 = new Button("⇇");
        btnPriceMinus1.setStyle("-fx-background-color: #444c56; -fx-text-fill: #adbac7; -fx-cursor: hand; -fx-font-size: 10px; -fx-padding: 2 6;");
        btnPriceMinus10.setStyle("-fx-background-color: #353c45; -fx-text-fill: #8b949e; -fx-cursor: hand; -fx-font-size: 10px; -fx-padding: 2 6;");
        btnPriceMinus1.setPrefWidth(28);
        btnPriceMinus10.setPrefWidth(28);

        btnPriceMinus1.setOnAction(e -> {
            try {
                double currentVal = Double.parseDouble(priceField.getText());
                double tickSize = simulator.getTickChangeSize(currentVal);
                priceField.setText(String.format("%.2f", Math.max(0, currentVal - tickSize)));
            } catch (NumberFormatException ex) { priceField.setText(String.format("%.2f", currentPrice)); }
        });

        btnPriceMinus10.setOnAction(e -> {
            try {
                double currentVal = Double.parseDouble(priceField.getText());
                for (int i = 0; i < 10; i++) {
                    currentVal -= simulator.getTickChangeSize(currentVal);
                }
                priceField.setText(String.format("%.2f", Math.max(0, currentVal)));
            } catch (NumberFormatException ex) { priceField.setText(String.format("%.2f", currentPrice)); }
        });
        VBox priceMinusBox = new VBox(2, btnPriceMinus1, btnPriceMinus10);

        // 建立價格【增加】的上下複合按鈕 (上: 加1 Tick, 下: 加10 Tick)
        Button btnPricePlus1 = new Button("▶");
        Button btnPricePlus10 = new Button("⇉");
        btnPricePlus1.setStyle("-fx-background-color: #444c56; -fx-text-fill: #adbac7; -fx-cursor: hand; -fx-font-size: 10px; -fx-padding: 2 6;");
        btnPricePlus10.setStyle("-fx-background-color: #353c45; -fx-text-fill: #8b949e; -fx-cursor: hand; -fx-font-size: 10px; -fx-padding: 2 6;");
        btnPricePlus1.setPrefWidth(28); btnPricePlus10.setPrefWidth(28);
        
        btnPricePlus1.setOnAction(e -> {
            try {
                double currentVal = Double.parseDouble(priceField.getText());
                double tickSize = simulator.getTickChangeSize(currentVal);
                priceField.setText(String.format("%.2f", currentVal + tickSize));
            } catch (NumberFormatException ex) { priceField.setText(String.format("%.2f", currentPrice)); }
        });

        btnPricePlus10.setOnAction(e -> {
            try {
                double currentVal = Double.parseDouble(priceField.getText());
                for (int i = 0; i < 10; i++) {
                    currentVal += simulator.getTickChangeSize(currentVal);
                }
                priceField.setText(String.format("%.2f", currentVal));
            } catch (NumberFormatException ex) { priceField.setText(String.format("%.2f", currentPrice)); }
        });
        VBox pricePlusBox = new VBox(2, btnPricePlus1, btnPricePlus10);

        // 打包 ◀ [價格欄] ▶ 
        HBox priceTickBox = new HBox(3, priceMinusBox, priceField, pricePlusBox);
        priceTickBox.setAlignment(Pos.CENTER_LEFT);

        sharesField.setPrefWidth(80);
        sharesField.setStyle("-fx-background-color: #22272e; -fx-text-fill: #adbac7; -fx-alignment: CENTER; -fx-border-color: #444c56; -fx-border-radius: 4;");

        // 建立股數【減少】的上下複合按鈕 (上: -1 股, 下: -1000 股)
        Button btnSharesMinus1 = new Button("-1");
        Button btnSharesMinus1000 = new Button("-1k");
        btnSharesMinus1.setStyle("-fx-background-color: #444c56; -fx-text-fill: #adbac7; -fx-cursor: hand; -fx-font-size: 9px; -fx-font-weight: bold; -fx-padding: 2 4;");
        btnSharesMinus1000.setStyle("-fx-background-color: #353c45; -fx-text-fill: #8b949e; -fx-cursor: hand; -fx-font-size: 9px; -fx-font-weight: bold; -fx-padding: 2 4;");
        btnSharesMinus1.setPrefWidth(32); btnSharesMinus1000.setPrefWidth(32);
        
        btnSharesMinus1.setOnAction(e -> {
            try {
                int currentShares = Integer.parseInt(sharesField.getText().trim());
                int newShares = currentShares - 1;
                sharesField.setText(String.valueOf(Math.max(0, newShares))); // 🎯 低於零變回 0
            } catch (NumberFormatException ex) { sharesField.setText("0"); }
        });

        btnSharesMinus1000.setOnAction(e -> {
            try {
                int currentShares = Integer.parseInt(sharesField.getText().trim());
                int newShares = currentShares - 1000;
                sharesField.setText(String.valueOf(Math.max(0, newShares))); // 🎯 低於零變回 0
            } catch (NumberFormatException ex) { sharesField.setText("0"); }
        });
        VBox sharesMinusBox = new VBox(2, btnSharesMinus1, btnSharesMinus1000);

        // 建立股數【增加】的上下複合按鈕 (上: +1 股, 下: +1000 股)
        Button btnSharesPlus1 = new Button("+1");
        Button btnSharesPlus1000 = new Button("+1k");
        btnSharesPlus1.setStyle("-fx-background-color: #444c56; -fx-text-fill: #adbac7; -fx-cursor: hand; -fx-font-size: 9px; -fx-font-weight: bold; -fx-padding: 2 4;");
        btnSharesPlus1000.setStyle("-fx-background-color: #353c45; -fx-text-fill: #8b949e; -fx-cursor: hand; -fx-font-size: 9px; -fx-font-weight: bold; -fx-padding: 2 4;");
        btnSharesPlus1.setPrefWidth(32); btnSharesPlus1000.setPrefWidth(32);
        
        btnSharesPlus1.setOnAction(e -> {
            try {
                int currentShares = Integer.parseInt(sharesField.getText().trim());
                int newShares = currentShares + 1;
                sharesField.setText(String.valueOf(Math.max(0, newShares)));
            } catch (NumberFormatException ex) { sharesField.setText("1000"); }
        });

        btnSharesPlus1000.setOnAction(e -> {
            try {
                int currentShares = Integer.parseInt(sharesField.getText().trim());
                int newShares = currentShares + 1000;
                sharesField.setText(String.valueOf(Math.max(0, newShares)));
            } catch (NumberFormatException ex) { sharesField.setText("1000"); }
        });

        VBox sharesPlusBox = new VBox(2, btnSharesPlus1, btnSharesPlus1000);

        // 將 ◀ [股數欄] ▶ 橫向打包成一組物件
        HBox sharesTickBox = new HBox(3, sharesMinusBox, sharesField, sharesPlusBox);
        sharesTickBox.setAlignment(Pos.CENTER_LEFT);

        Button buyBtn = new Button("買入");
        buyBtn.getStyleClass().addAll(Styles.SUCCESS, Styles.BUTTON_OUTLINED);
        buyBtn.setOnAction(e -> handleTrade(true));

        Button sellBtn = new Button("賣出");
        sellBtn.getStyleClass().addAll(Styles.DANGER, Styles.BUTTON_OUTLINED);
        sellBtn.setOnAction(e -> handleTrade(false));

        tradeControlPanel.getChildren().addAll(
            new Label("目標價格"), priceTickBox, 
            new Label("交易股數:"), sharesTickBox, 
            buyBtn, sellBtn, 
            new Separator(javafx.geometry.Orientation.VERTICAL), 
            infoLabel
        );
        
        backtestControlBar = new HBox(15);
        backtestControlBar.setAlignment(Pos.CENTER_LEFT);
        backtestControlBar.setPadding(new Insets(10));
        backtestControlBar.setStyle("-fx-background-color: #22272e; -fx-background-radius: 6px; -fx-border-color: #30363d; -fx-border-width: 1;");

        //時空選擇器
        Label lblDate = new Label("📅 時空選擇:");
        dateSelector = new DatePicker();
        dateSelector.setPrefWidth(150);

        dateSelector.setOnAction(e -> {
            java.time.LocalDate selectedDate = dateSelector.getValue();
            if (selectedDate == null) return;
            
            String targetDateStr = selectedDate.toString(); 
            System.out.println("玩家在介面上選了日期: " + targetDateStr);
            
            String currentStockId = this.currentSelectedStockId;

            // 💡 【核心優化】：如果快取大表已經有資料且代號相符，直接在記憶體搜尋，0 毫秒極速反應，徹底告別卡頓通知！
            if (this.historyData != null && !this.historyData.isEmpty()) {
                boolean dateFound = false;
                int foundIndex = -1;

                for (int i = 0; i < this.historyData.size(); i++) {
                    com.stockbucks.StockData data = this.historyData.get(i);
                    if (data == null || data.getDate() == null) continue;

                    String cleanCsvDate = data.getDate().replace("/", "-").trim();
                    if (cleanCsvDate.equals(targetDateStr)) {
                        foundIndex = i;
                        dateFound = true;
                        break;
                    }
                }

                if (dateFound) {
                    this.dayIndex = finalIndexUpdate(foundIndex, targetDateStr);
                    return; // 記憶體中找到了，直接結束，不需要開 Thread 去網路下載！
                }
            }

            // 如果記憶體找不到（例如初次載入或換股票），才退化成 Thread 去遠端下載
            currentPriceLabel.setText("⏳ 正在調閱時空大表中...");
            new Thread(() -> {
                try {
                    List<?> loadedData = aiHub.loadSimulationHistory(currentStockId, null);
                    if (loadedData != null && !loadedData.isEmpty() && loadedData.get(0) instanceof com.stockbucks.StockData) {
                        java.util.List<com.stockbucks.StockData> tempHistory = new java.util.ArrayList<>();
                        for (Object obj : loadedData) {
                            if (obj instanceof com.stockbucks.StockData) {
                                tempHistory.add((com.stockbucks.StockData) obj);
                            }
                        }
                        this.historyData = tempHistory; 
                    }

                    boolean dateFound = false;
                    int foundIndex = -1;

                    if (this.historyData != null && !this.historyData.isEmpty()) {
                        for (int i = 0; i < this.historyData.size(); i++) {
                            com.stockbucks.StockData data = this.historyData.get(i);
                            if (data == null || data.getDate() == null) continue;

                            String cleanCsvDate = data.getDate().replace("/", "-").trim();
                            if (cleanCsvDate.equals(targetDateStr)) {
                                foundIndex = i;
                                dateFound = true;
                                break;
                            }
                        }
                    }

                    final boolean finalDateFound = dateFound;
                    final int finalIndex = foundIndex;
                    
                    Platform.runLater(() -> {
                        if (finalDateFound) {
                            this.dayIndex = finalIndex;
                            if (timeline != null) timeline.stop(); 

                            com.stockbucks.StockData selectedDayData = this.historyData.get(this.dayIndex);
                            currentPriceLabel.setText(String.format("已切換至 %s (等待開盤)", targetDateStr));
                            priceSeries.getData().clear(); 
                            tickCount = 0;

                            if (infoLabel != null) {
                                infoLabel.setText(String.format("歷史模式 | 開盤預估: %.2f", selectedDayData.getOpen()));
                            }
                            
                            refreshAssetView();
                            updateInfoLabel();
                            showNotification("AIHub 行事曆匹配成功：已跳轉至 " + targetDateStr, "INFO");
                        } else {
                            showWarning("AIHub 查無數據：" + targetDateStr + " 該股未開盤或處於市場休市日！");
                            resetDatePickerToValid();
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> showWarning("連線至 AIHub 獲取歷史資料失敗！"));
                }
            }).start();
        });

        //速度控制條
        Label lblSpeed = new Label("⚡ 模擬速度:");
        speedSlider = new Slider(1, 10, 1); // 1x ~ 10x
        speedSlider.setShowTickMarks(true);
        speedSlider.setMajorTickUnit(3);
        speedLabel = new Label("1x (一般)");
        
        speedSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int speed = newVal.intValue();
            speedLabel.setText(speed + "x");
            updateTimelineSpeed(speed);
        });

        backtestControlBar.getChildren().addAll(lblDate, dateSelector, new Separator(javafx.geometry.Orientation.VERTICAL), lblSpeed, speedSlider, speedLabel);

        //動態顯示核心：根據從 WelcomeUI 傳進來的 marketMode 決定生死
        if (this.marketMode == com.stockbucks.api.mode.MarketMode.HISTORY) {
            backtestControlBar.setVisible(true);
            backtestControlBar.setManaged(true);
        } else {
            backtestControlBar.setVisible(false);
            backtestControlBar.setManaged(false);
        }

        centerArea.getChildren().addAll(backtestControlBar,lineChart, tradeControlPanel);

        // --- (C) 右側 AI 問答區塊 ---
        VBox aiSection = new VBox(15);
        aiSection.setPrefWidth(300);
        aiSection.setPadding(new Insets(0, 0, 0, 15));
        aiSection.setStyle("-fx-border-color: #30363d; -fx-border-width: 0 0 0 1;"); // 左邊界線

        HBox aiHeader = new HBox(8);
        aiHeader.setAlignment(Pos.CENTER_LEFT);
        Label aiTitle = new Label("AI 投資助理");
        aiTitle.getStyleClass().add(Styles.TITLE_3);

        // 小綠燈/小紅燈動態狀態
        Label aiStatusDot = new Label(aiHub.isAiReady() ? "● Ready" : "● Offline");
        aiStatusDot.setStyle(aiHub.isAiReady() ? "-fx-text-fill: #2da44e; -fx-font-size: 11px;" : "-fx-text-fill: #da3633; -fx-font-size: 11px;");
        aiHeader.getChildren().addAll(aiTitle, aiStatusDot);

        TextArea aiChatArea = new TextArea();
        aiChatArea.setEditable(false);
        aiChatArea.setWrapText(true);
        aiChatArea.setPromptText("AI 分析建議將顯示於此...");
        VBox.setVgrow(aiChatArea, Priority.ALWAYS);

        TextField aiInputField = new TextField();
        aiInputField.setPromptText("提出財經問題...");
        
        Button askBtn = new Button("發送");
        askBtn.getStyleClass().add(Styles.ACCENT);
        askBtn.setMaxWidth(Double.MAX_VALUE);

        Runnable sendAiMessageAction = () -> {
            String question = aiInputField.getText().trim();
            if (question.isEmpty()) return;

            // 1. 將玩家問題打印到畫面上，並清空輸入框
            aiChatArea.appendText("【我】: " + question + "\n\n");
            aiInputField.clear();

            // 2. 停用輸入元件，避免玩家在 AI 回應前連續轟炸
            aiInputField.setDisable(true);
            askBtn.setDisable(true);
            aiChatArea.appendText("【AI 助理】: ⚡ 正在通靈並調閱當前財務與 K 線數據中...\n");
 
            double currentPrice = 0.0;
            try {
                String priceStr = currentPriceLabel.getText().replace("當前市價: ", "");
                if (priceStr.contains("(")) {
                    priceStr = priceStr.split("\\(")[0].trim();
                }
                currentPrice = Double.parseDouble(priceStr);
            } catch (Exception e) {
                // 如果模擬還沒點開始，先抓 historyData 當前或第一筆的收盤價作為預設值
                currentPrice = (historyData != null && !historyData.isEmpty() && dayIndex < historyData.size()) 
                        ? historyData.get(dayIndex).getClose() : 600.0;
            }

            final double finalPrice = currentPrice;
            String stockIdFromUi = stockSearchField.getText().trim();
            if (stockIdFromUi.isEmpty() && marketSearchField != null) {
                stockIdFromUi = marketSearchField.getText().trim();
            }
            final String currentStockId = stockIdFromUi.isEmpty() ? "2330" : stockIdFromUi;

            // 4. 開啟多執行緒非同步呼叫，防止 AI 網路延遲導致主畫面 K 線、時脈卡死崩潰
            new Thread(() -> {
                try {
                    // 呼叫新版 AIHub 提供的完整數據對話方法
                    String aiResponse = aiHub.answerQuestion(
                            user, 
                            tradingEngine, 
                            historyData, 
                            currentStockId, 
                            finalPrice, 
                            question
                    );

                    // 5. 拿到結果後，回到 JavaFX 主執行緒更新 UI
                    Platform.runLater(() -> {
                        // 移除「正在通靈中...」的提示，換成真正的答案
                        String currentText = aiChatArea.getText();
                        if (currentText.contains("【AI 助理】: ⚡ 正在通靈並調閱當前財務與 K 線數據中...")) {
                            currentText = currentText.replace("【AI 助理】: ⚡ 正在通靈並調閱當前財務與 K 線數據中...", "");
                            aiChatArea.setText(currentText);
                        }
                        
                        aiChatArea.appendText("【AI 助理】:\n" + aiResponse + "\n\n");
                        Platform.runLater(() -> aiChatArea.setScrollTop(Double.MAX_VALUE));
                        
                        // 恢復元件可用性
                        aiInputField.setDisable(false);
                        askBtn.setDisable(false);
                        aiInputField.requestFocus();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        aiChatArea.appendText("【系統錯誤】: ❌ 呼叫 AI 失敗。原因: " + ex.getMessage() + "\n\n");
                        aiInputField.setDisable(false);
                        askBtn.setDisable(false);
                    });
                }
            }).start();
        };

        // 綁定事件：不論點擊按鈕或在輸入框內敲 Enter，皆能完美送出
        askBtn.setOnAction(e -> sendAiMessageAction.run());
        aiInputField.setOnAction(e -> sendAiMessageAction.run());

        aiSection.getChildren().addAll(aiHeader, aiChatArea, aiInputField, askBtn);

        // --- 組合 ---
        tradeView.setTop(topSearchBar);
        tradeView.setCenter(centerArea);
        tradeView.setRight(aiSection);

        // 【3. 帳務總覽】
        assetView = new VBox(20);
        assetView.setPadding(new Insets(20));
        Label assetTitle = new Label("💰 我的帳務資產總覽");
        assetTitle.getStyleClass().add(Styles.TITLE_2);
        // 這邊可以塞資產圓餅圖或是庫存餘額明細表格
        assetView.getChildren().addAll(assetTitle, new Separator());

        // 【4. 委託清單】把歷史交易表格放在獨立畫面
        orderListView = new VBox(15);
        orderListView.setPadding(new Insets(15));
        Label orderTitle = new Label("📜 歷史成交與委託明細");
        orderTitle.getStyleClass().add(Styles.TITLE_2);
        orderListView.getChildren().addAll(orderTitle, new Separator());

        // 建立獨立的 TabPane
        TabPane orderTabPane = new TabPane();
        orderTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); // 禁用關閉功能
        VBox.setVgrow(orderTabPane, Priority.ALWAYS);

        // ===== 分頁一：現存委託 (未成交) =====
        Tab activeOrderTab = new Tab("⏳ 現存委託");
        activeOrderLayout = new VBox(10);
        activeOrderLayout.setPadding(new Insets(10));
        
        // 目前因為還沒做委託邏輯，先塞一個漂亮的 Placeholder 提示字卡
        emptyPlaceholder = new VBox(10);
        emptyPlaceholder.setAlignment(Pos.CENTER);
        emptyPlaceholder.setPadding(new Insets(50));
        Label lblEmptyIcon = new Label("💤");
        lblEmptyIcon.setStyle("-fx-font-size: 40px;");
        Label lblEmptyText = new Label("目前沒有進行中的委託單");
        lblEmptyText.setStyle("-fx-text-fill: #8b949e; -fx-font-size: 14px;");
        emptyPlaceholder.getChildren().addAll(lblEmptyIcon, lblEmptyText);
        
        // 未來做委託功能時，可以把下面 activeOrderTable 顯示出來，暫時先放提示
        VBox.setVgrow(activeOrderTable, Priority.ALWAYS);
        activeOrderLayout.getChildren().addAll(emptyPlaceholder, activeOrderTable);
        activeOrderTab.setContent(activeOrderLayout);

        refreshActiveOrderVisibility();

        // ===== 分頁二：歷史成交 (已成交) =====
        Tab historyOrderTab = new Tab("✅ 歷史成交");
        VBox historyOrderLayout = new VBox(10);
        historyOrderLayout.setPadding(new Insets(10));
        
        // 確保你原本就寫好的交易歷史表格能夠在這邊完美延伸拉滿
        VBox.setVgrow(historyTable, Priority.ALWAYS);
        historyOrderLayout.getChildren().add(historyTable);
        historyOrderTab.setContent(historyOrderLayout);

        // 將兩個固定分頁塞入 TabPane
        orderTabPane.getTabs().addAll(activeOrderTab, historyOrderTab);

        // 將 TabPane 塞入主畫面 Container
        orderListView.getChildren().add(orderTabPane);
    }

    private void updateTimelineSpeed(int speedMultiplier) {
        if (timeline == null) return;
        timeline.setRate(speedMultiplier);
    }

    private void refreshActiveOrderVisibility() {
        if (observableOrders == null || emptyPlaceholder == null || activeOrderTable == null) return;
        
        if (observableOrders.isEmpty()) {
            // 沒有委託單：顯示 💤 提示，隱藏表格
            emptyPlaceholder.setVisible(true);
            emptyPlaceholder.setManaged(true);
            activeOrderTable.setVisible(false);
            activeOrderTable.setManaged(false);
        } else {
            // 有委託單：隱藏 💤 提示，顯示表格
            emptyPlaceholder.setVisible(false);
            emptyPlaceholder.setManaged(false);
            activeOrderTable.setVisible(true);
            activeOrderTable.setManaged(true);
        }
    }

    // 輔助方法 A：極速同步更新 UI 指針
    private int finalIndexUpdate(int foundIndex, String targetDateStr) {
        this.dayIndex = foundIndex;
        if (timeline != null) timeline.stop();
        com.stockbucks.StockData selectedDayData = this.historyData.get(this.dayIndex);
        currentPriceLabel.setText(String.format("已切換至 %s (等待開盤)", targetDateStr));
        priceSeries.getData().clear();
        tickCount = 0;
        if (infoLabel != null) {
            infoLabel.setText(String.format("歷史模式 | 開盤預估: %.2f", selectedDayData.getOpen()));
        }
        refreshAssetView();
        updateInfoLabel();
        return foundIndex;
    }

    // 輔助方法 B：失敗時自動回彈日曆
    private void resetDatePickerToValid() {
        if (this.historyData != null && dayIndex < this.historyData.size()) {
            try {
                String currentDateStr = this.historyData.get(dayIndex).getDate().replace("/", "-");
                dateSelector.setValue(java.time.LocalDate.parse(currentDateStr));
            } catch (Exception ex) {}
        }
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
        Button toggleBtn = new Button();
        toggleBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.FLAT);
        javafx.scene.shape.SVGPath hamburgerIcon = new javafx.scene.shape.SVGPath();

        hamburgerIcon.setContent("M3 5h18M3 12h18M3 19h18"); 
        hamburgerIcon.setStroke(javafx.scene.paint.Color.web("#adbac7"));
        hamburgerIcon.setStrokeWidth(2);
        hamburgerIcon.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);

        toggleBtn.setGraphic(hamburgerIcon);

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

        //儲存檔案按鈕
        Button sideSaveBtn = new Button("💾  儲存模擬進度");
        sideSaveBtn.setMaxWidth(Double.MAX_VALUE);
        sideSaveBtn.setAlignment(Pos.BASELINE_LEFT);
        sideSaveBtn.getStyleClass().addAll(Styles.ACCENT, Styles.FLAT);
        sideSaveBtn.setPadding(new Insets(10, 15, 10, 15));
        sideSaveBtn.setOnAction(e -> handleSaveGame()); // 觸發原本寫好的 handleSaveGame()

        // 側邊欄收合動態控制
        toggleBtn.setOnAction(e -> {
            if (isSidebarExpanded) {
                box.setPrefWidth(65);
                for (int i = 0; i < menuButtons.size(); i++) {
                    menuButtons.get(i).setText(MenuType.values()[i].icon);
                }
                sideSaveBtn.setText("💾");
                isSidebarExpanded = false;
            } else {
                box.setPrefWidth(220);
                for (int i = 0; i < menuButtons.size(); i++) {
                    menuButtons.get(i).setText(MenuType.values()[i].fullText);
                }
                sideSaveBtn.setText("💾  儲存模擬進度");
                isSidebarExpanded = true;
            }
        });

        box.getChildren().add(toggleBtn);
        box.getChildren().add(new Separator());
        
        for (int i = 0; i < menuButtons.size(); i++) {
            Button btn = menuButtons.get(i);
            MenuType type = MenuType.values()[i];
            
            if (type == MenuType.EXIT) {
                // 建立一個彈簧元件，把退出按鈕頂到側欄最底端
                javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
                VBox.setVgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
                box.getChildren().add(spacer);
                
                // 加一條分割線
                box.getChildren().add(new Separator());
                box.getChildren().add(sideSaveBtn);
                box.getChildren().add(new Separator());
                box.getChildren().add(menuButtons.get(i)); 

                break;
            }
            
            box.getChildren().add(btn);
        }

        return box;
    }

    /**
     * 動態切換中央工作區畫面的核心方法
     */
    private void switchView(MenuType menu) {
        if (menu == MenuType.EXIT) {
            // 1. 安全機制：退回前先強制把正在跑的 K 線計時器（Timeline）停掉
            if (this.timeline != null) {
                this.timeline.stop();
            }

            if (mainLayout != null) {
                mainLayout.requestFocus();
            }

            Platform.runLater(() -> {
                com.stockbucks.gui.WelcomeUI welcomeUI = new com.stockbucks.gui.WelcomeUI(this.stage);
                welcomeUI.show();
            });
            
            return; // 🔍 極其重要：直接結束方法，阻止後續的 contentArea 清空與切換
        }

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
                refreshAssetView();
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
            java.util.LinkedHashMap<String, ArrayList<String>> saveWatchlist = new java.util.LinkedHashMap<>();
            for (String key : watchlistData.keySet()) {
                saveWatchlist.put(key, new ArrayList<>(watchlistData.get(key)));
            }
            SaveData data = new SaveData(this.user, this.dayIndex, fileName, this.marketMode, saveWatchlist);
            oos.writeObject(data);
        } catch (IOException e) {
            e.printStackTrace();
            showWarning("存檔寫入硬碟時發生錯誤！");
        }
    }

    private LineChart<Number, Number> createPriceChart(double yesterdayClose, double limitUp, double limitDown) {
        NumberAxis xAxis = new NumberAxis(0, 270, 30);
        NumberAxis yAxis = new NumberAxis(limitDown-yesterdayClose*0.01, limitUp*0.01, (limitUp - limitDown)/10);
        xAxis.setLabel("時間 (Ticks)");
        xAxis.setForceZeroInRange(false);
        xAxis.setAutoRanging(false);
        yAxis.setSide(Side.RIGHT); 
        yAxis.setForceZeroInRange(false);
        yAxis.setAutoRanging(false);
        
        LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("即時價格走勢");
        chart.setCreateSymbols(false); 
        chart.setAnimated(false);

        limitUpSeries = new XYChart.Series<>();
        limitUpSeries.setName("漲停價");

        limitDownSeries = new XYChart.Series<>();
        limitDownSeries.setName("跌停價");

        yesterdayCloseSeries = new XYChart.Series<>();
        yesterdayCloseSeries.setName("昨收價");
        
        priceSeries = new XYChart.Series<>();
        priceSeries.setName("股價");

        chart.getData().add(limitUpSeries);
        chart.getData().add(limitDownSeries);
        chart.getData().add(yesterdayCloseSeries);
        chart.getData().add(priceSeries);

        limitUpSeries.getNode().setStyle("-fx-stroke: #FF3B30; -fx-stroke-dash-array: 5 5; -fx-stroke-width: 1.5px;");
        limitDownSeries.getNode().setStyle("-fx-stroke: #34C759; -fx-stroke-dash-array: 5 5; -fx-stroke-width: 1.5px;");
        yesterdayCloseSeries.getNode().setStyle("-fx-stroke: #8E8E93; -fx-stroke-dash-array: 5 5; -fx-stroke-width: 1.5px;");

        return chart;
    }

    private void handleTrade(boolean isBuy) {
        if ((currentPriceLabel.getText().contains("--"))|| currentPriceLabel.getText().contains("讀取中")) return;
        try {
            String currentStockId = "2330"; // 預設值
            if (stockSearchField != null && !stockSearchField.getText().trim().isEmpty()) {
                currentStockId = stockSearchField.getText().trim();
            } else if (marketSearchField != null && !marketSearchField.getText().trim().isEmpty()) {
                currentStockId = marketSearchField.getText().trim();
            }

            int shares = Integer.parseInt(sharesField.getText().trim());
            if (shares <= 0) {
                showWarning("交易股數必須大於 0！");
                return;
            }

            double price = 0.0;

            // 確認限價輸入是否有填寫數字

            if (priceField != null && !priceField.getText().trim().isEmpty()) {
                try {
                    price = Double.parseDouble(priceField.getText().trim());
                }
                catch (NumberFormatException e) {
                    showWarning("請輸入正確的限價數字格式");
                    return;
                }
            }
            else {
                // 限價是空的就以市價買入
                //安全解析字串：同時相容「當前市價」與「即時市價」的文本結構
                String priceStr = currentPriceLabel.getText()
                        .replace("當前市價: ", "")
                        .replace("即時市價: ", "")
                        .trim();
                
                if (priceStr.contains("(")) {
                    priceStr = priceStr.split("\\(")[0].trim();
                }
                price = Double.parseDouble(priceStr);

                // 自動幫玩家把抓到的市價填回輸入框，優化體驗
                if (priceField != null) {
                    priceField.setText(String.format("%.2f", price));
                }
            }

            if (price <= 0) {
                showWarning("交易價格必須大於 0！");
                return;
            }

            // 4. 動態獲取當前日期
            String date = (historyData != null && dayIndex < historyData.size()) 
                    ? historyData.get(dayIndex).getDate() 
                    : java.time.LocalDate.now().toString(); // 即時模式下用今天日期

            //把寫死的 "TestDataTSMC" 換成動態的 currentStockId
            tradingEngine.trading(user, currentStockId, date, tickCount, shares, price, isBuy);

            // 6. 更新畫面資產/餘額標籤
            updateInfoLabel();
            refreshAssetView();

            // 7. 重新載入未成交/委託中的單據表格
            if (activeOrderTable != null) {
                observableOrders.clear();
                //配合tradingEngine 實際的未成交單方法 (getPendingOrders)
                java.util.List<?> pending = tradingEngine.getPendingOrders();
                if (pending != null) {
                    for (int i = pending.size() - 1; i >= 0; i--) {
                        // 確保轉型安全
                        if (pending.get(i) instanceof com.stockbucks.Order) {
                            observableOrders.add((com.stockbucks.Order) pending.get(i));
                        }
                    }
                }
            }
            
            //sharesField.clear();
        } catch (NumberFormatException ex) {
            showWarning("請輸入正確的數字格式");
        } catch (Exception ex) {
            ex.printStackTrace();
            showWarning("交易執行失敗");
        }
    }

    // 一個漂亮的浮動通知
    private void showNotification(String message, String type) {
        Stage toastStage = new Stage();
        toastStage.initStyle(StageStyle.TRANSPARENT);

        Label toastLabel = new Label(message);

        if (type.equals("BUY")) {
            toastLabel.setStyle("-fx-background-color: rgba(255, 59, 48, 0.95); -fx-text-fill: white; -fx-padding: 10 20; -fx-background-radius: 20; -fx-font-size: 14px; -fx-font-weight: bold;");
        }
        else if (type.equals("SELL")) {
            toastLabel.setStyle("-fx-background-color: rgba(52, 199, 89, 0.95); white; -fx-padding: 10 20; -fx-background-radius: 20; -fx-font-size: 14px; -fx-font-weight: bold;");
        }
        else {
            toastLabel.setStyle("-fx-background-color: rgba(23, 26, 33, 0.95); -fx-text-fill: white; -fx-padding: 10 20; -fx-background-radius: 20; -fx-font-size: 14px;");
        }

        VBox root = new VBox(toastLabel);
        root.setStyle("-fx-background-color: transparent;"); // 容器本身完全透明
        root.setAlignment(Pos.CENTER);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT); // 畫布也必須透明
        toastStage.setScene(scene);

        Rectangle2D visualBounds = Screen.getPrimary().getVisualBounds();
        double windowWigth = 320;
        double windowHeight = 60;

        double startX = visualBounds.getWidth() - windowWigth - 20;
        double startY = visualBounds.getHeight() - windowHeight - 20;

        toastStage.setX(startX);
        toastStage.setY(startY);
        toastStage.show();

        root.setTranslateY(50);
        TranslateTransition slideIn = new TranslateTransition(Duration.millis(400), root);
        slideIn.setToY(0);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(300), root);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);

        FadeTransition fadeOut = new FadeTransition(Duration.millis(500), root);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setDelay(Duration.seconds(3.0)); // 在畫面上停留 3 秒
        fadeOut.setOnFinished(event -> toastStage.close()); // 播完後關閉視窗

        ParallelTransition appearance = new ParallelTransition(slideIn, fadeIn);
        appearance.play();
        
        appearance.setOnFinished(e -> fadeOut.play());
    }

    private void updateInfoLabel() {
        if (infoLabel != null && user != null) {
            infoLabel.setText(String.format("可用現金: $%,.0f | 庫存: %d 股", 
                    user.getCash(), 
                    user.getStockQuantity(currentSelectedStockId))); 
        }
    }

    @SuppressWarnings("unchecked")
    private void setupTable() {
        TableColumn<TradeRecord, String> dateCol = new TableColumn<>("日期");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));
        TableColumn<TradeRecord, String> timeCol = new TableColumn<>("時間");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("time"));
        TableColumn<TradeRecord, String> stockIDCol = new TableColumn<>("股票代號");
        stockIDCol.setCellValueFactory(new PropertyValueFactory<>("stockID"));
        TableColumn<TradeRecord, String> typeCol = new TableColumn<>("類型");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeCol.setCellFactory(column -> new TableCell<TradeRecord, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                }
                else {
                    setText(item);
                    if (item.contains("買入")) {
                        setStyle("-fx-text-fill: #FF3B30; -fx-font-weight: bold;"); // 買入亮紅
                    } else if (item.contains("賣出")) {
                        setStyle("-fx-text-fill: #34C759; -fx-font-weight: bold;"); // 賣出亮綠
                    } else {
                        setStyle("");
                    }
                }
            }
        });
        TableColumn<TradeRecord, Double> priceCol = new TableColumn<>("成交價");
        priceCol.setCellValueFactory(new PropertyValueFactory<>("price"));
        TableColumn<TradeRecord, Integer> sharesCol = new TableColumn<>("股數");
        sharesCol.setCellValueFactory(new PropertyValueFactory<>("shares"));
        TableColumn<TradeRecord, Double> costCol = new TableColumn<>("結算金額");
        costCol.setCellValueFactory(new PropertyValueFactory<>("totalCost"));

        historyTable.getColumns().clear();
        historyTable.getColumns().addAll(dateCol, timeCol, stockIDCol, typeCol, priceCol, sharesCol, costCol);
        historyTable.setItems(observableRecords);
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    @SuppressWarnings("unchecked")
    private void setupActiveOrderTable() {
        TableColumn <Order, String> dateCol = new TableColumn<>("委託日期");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));
        TableColumn<Order, String> timeCol = new TableColumn<>("時間");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("time"));
        TableColumn<Order, String> stockIDCol = new TableColumn<>("股票代號");
        stockIDCol.setCellValueFactory(new PropertyValueFactory<>("stockID"));
        TableColumn<Order, Boolean> typeCol = new TableColumn<>("類型");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("buy"));
        typeCol.setCellFactory(column -> new TableCell<Order, Boolean>() {
            @Override
            protected void updateItem(Boolean isBuy, boolean empty) {
                super.updateItem(isBuy, empty);
                if (empty || isBuy == null) {
                    setText(null);
                    setStyle("");
                }
                else {
                    if (isBuy) {
                        setText("買入");
                        setStyle("-fx-text-fill: #FF3B30; -fx-font-weight: bold;");
                    }
                    else {
                        setText("賣出");
                        setStyle("-fx-text-fill: #34C759; -fx-font-weight: bold;");
                    }
                }
            }
        });
        TableColumn<Order, Double> priceCol = new TableColumn<>("委託限價");
        priceCol.setCellValueFactory(new PropertyValueFactory<>("limitPrice"));
        TableColumn<Order, Integer> sharesCol = new TableColumn<>("委託股數");
        sharesCol.setCellValueFactory(new PropertyValueFactory<>("shares"));
        TableColumn<Order, Order.OrderStatus> statusCol = new TableColumn<>("狀態");
        statusCol.setCellValueFactory(new PropertyValueFactory<>("status"));
        statusCol.setCellFactory(column -> new TableCell<Order, Order.OrderStatus>() {
            @Override
            protected void updateItem(Order.OrderStatus status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setText(null);
                    setStyle("");
                }
                else {
                    setText(status.toString());
                    if (status == Order.OrderStatus.FILLED) {
                        setStyle("-fx-text-fill: #007AFF; -fx-font-weight: bold;");
                    }
                    else if (status == Order.OrderStatus.PENDING) {
                        setStyle("-fx-text-fill: #FF9500; -fx-font-weight: bold;");
                    }
                    else if (status == Order.OrderStatus.FAILED) {
                        setStyle("-fx-text-fill: #ff0d00; -fx-font-weight: bold;");
                    }
                    else {
                        setStyle("-fx-text-fill: #8E8E93;");
                    }
                }
            }
        });

        activeOrderTable.getColumns().clear();
        activeOrderTable.getColumns().addAll(dateCol, timeCol, stockIDCol, typeCol, priceCol, sharesCol, statusCol);
        activeOrderTable.setItems(observableOrders);
        activeOrderTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void handleStartSimulation() {
        String inputId = stockSearchField.getText().trim();
        if (!inputId.isEmpty() && !inputId.equals(this.currentSelectedStockId)) {
            this.currentSelectedStockId = inputId;
            this.historyData = null; // 倒空舊股票的大表，讓時脈跑動時會去抓新股票的資料
            this.dayIndex = 0;
        }

        // 如果定時器正在跑，先無條件關閉它，防止多個執行緒重疊衝突
        if (timeline != null) timeline.stop();

        // ====================================================================
        // 🟢 【分流 A】真實即時模式：只抓最新的一筆靜態資料，不跑計時器模擬
        // ====================================================================
        if (this.marketMode == com.stockbucks.api.mode.MarketMode.REALTIME) {
            final String finalStockId = this.currentSelectedStockId;
            new Thread(() -> {
                try {
                    com.stockbucks.api.stock.StockQuote quote = aiHub.fetchStockQuote(finalStockId);
                    if (quote != null) {
                        double price = quote.getLastPrice();
                        double open = quote.getOpenPrice();
                        
                        // 直接把價格餵給即時交易撮合引擎
                        tradingEngine.onPriceUpdate(finalStockId, price, 0);

                        Platform.runLater(() -> {
                            currentPriceLabel.setText(String.format("即時市價: %.2f (真實即時)", price));
                            
                            // 清空舊線圖，直接在圖表畫一條最新的靜態線（或者單一亮點）
                            priceSeries.getData().clear();
                            priceSeries.getData().add(new XYChart.Data<>(0, open > 0 ? open : price));
                            priceSeries.getData().add(new XYChart.Data<>(270, price));
                            
                            if (priceSeries.getNode() != null) {
                                priceSeries.getNode().setStyle("-fx-stroke: #ea4335; -fx-stroke-width: 2px;");
                            }
                            
                            // 隱藏或清空回測專用的漲跌停虛線
                            if (limitUpSeries != null) limitUpSeries.getData().clear();
                            if (limitDownSeries != null) limitDownSeries.getData().clear();
                            if (yesterdayCloseSeries != null) yesterdayCloseSeries.getData().clear();
                            refreshAssetView();
                            updateInfoLabel();
                        });
                    }
                } catch (Exception ex) {
                    Platform.runLater(() -> currentPriceLabel.setText("即時報價載入失敗"));
                }
            }).start();
            
            return; // 🛑 核心細節：即時模式到此結束，直接 return，絕對不去碰下方的歷史模擬！
        }

        // ====================================================================
        // 🔵 【分流 B】歷史回測模式：原本的動態 Timeline 跑線邏輯
        // ====================================================================
        
        // 2. 自動檢查與加載歷史模擬資料
        if (historyData == null || historyData.isEmpty()) {
            try {
                List<?> loadedData = aiHub.loadSimulationHistory(currentSelectedStockId, null);
                if (loadedData != null && !loadedData.isEmpty() && loadedData.get(0) instanceof com.stockbucks.StockData) {
                    this.historyData = (List<com.stockbucks.StockData>) loadedData;
                }
            } catch (Exception ex) {
                showWarning("歷史資料載入失敗，無法啟動模擬！");
                return;
            }
        }

        if (historyData == null || dayIndex >= historyData.size()) {
            showWarning("暫無可用的回測歷史數據！");
            return;
        }

        StockData today = historyData.get(dayIndex);
        double yesterdayClose = (dayIndex == 0) ? today.getOpen() : historyData.get(dayIndex - 1).getClose();
        
        double limitUp = simulator.calculateLimitPrice(yesterdayClose, true);
        double limitDown = simulator.calculateLimitPrice(yesterdayClose, false);

        if (lineChart != null) {
            NumberAxis yAxis = (NumberAxis) lineChart.getYAxis();
            yAxis.setLowerBound(limitDown - yesterdayClose*0.01);
            yAxis.setUpperBound(limitUp + yesterdayClose*0.01);
            yAxis.setTickUnit((limitUp - limitDown)/10);
        }
        if (limitUpSeries != null) {
            limitUpSeries.getData().clear();
            limitUpSeries.getData().add(new XYChart.Data<>(0, limitUp));
            limitUpSeries.getData().add(new XYChart.Data<>(270, limitUp));
            if (limitUpSeries.getNode() != null) { // 紅色虛線
                limitUpSeries.getNode().setStyle("-fx-stroke: #FF3B30; -fx-stroke-dash-array: 5 5; -fx-stroke-width: 1.5px;");
            }
        }
        if (limitDownSeries != null) {
            limitDownSeries.getData().clear();
            limitDownSeries.getData().add(new XYChart.Data<>(0, limitDown));
            limitDownSeries.getData().add(new XYChart.Data<>(270, limitDown));
            if (limitDownSeries.getNode() != null) { // 綠色虛線
                limitDownSeries.getNode().setStyle("-fx-stroke: #34C759; -fx-stroke-dash-array: 5 5; -fx-stroke-width: 1.5px;");
            }
        }
        if (yesterdayCloseSeries != null) {
            yesterdayCloseSeries.getData().clear();
            yesterdayCloseSeries.getData().add(new XYChart.Data<>(0, yesterdayClose));
            yesterdayCloseSeries.getData().add(new XYChart.Data<>(270, yesterdayClose));
            if (yesterdayCloseSeries.getNode() != null) { //灰色虛線
                yesterdayCloseSeries.getNode().setStyle("-fx-stroke: #8E8E93; -fx-stroke-dash-array: 5 5; -fx-stroke-width: 1.5px;");
            }
        }
        
        simulator.generateDayPath(today, yesterdayClose);
        
        priceSeries.getData().clear();
        tickCount = 0;

        if (timeline != null) timeline.stop();

        timeline = new Timeline(new KeyFrame(Duration.millis(300), e -> {
            currentPrice = simulator.getNextPrice();
            if (currentPrice != -1) {
                int currentTime = tickCount;
                currentTime += 9*60;
                String currentTimeStr = String.format("%02d:%02d", currentTime / 60, currentTime % 60);

                String simDate = historyData.get(dayIndex).getDate().replace("/", "-");
                currentPriceLabel.setText("當前市價: " + String.format("%.2f(%s)", currentPrice, currentTimeStr));
                priceSeries.getData().add(new XYChart.Data<>(tickCount, currentPrice));
                if (priceSeries.getNode() != null) {
                    if (currentPrice > yesterdayClose * 1.005) {
                        priceSeries.getNode().setStyle("-fx-stroke: #FF3B30; -fx-stroke-width: 2px;");
                    } else if (currentPrice < yesterdayClose * 0.995) {
                        priceSeries.getNode().setStyle("-fx-stroke: #34C759; -fx-stroke-width: 2px;");
                    } else {
                        priceSeries.getNode().setStyle("-fx-stroke: #8E8E93; -fx-stroke-width: 2px;");
                    }
                }

                tradingEngine.onPriceUpdate(this.currentSelectedStockId, currentPrice, tickCount++);
                refreshAssetView();

                List <TradeRecord> records = tradingEngine.getDailyRecords();
                observableRecords.clear();
                for (int i = records.size() - 1; i >= 0; i--) {
                    observableRecords.add(records.get(i));
                }

                if (activeOrderTable != null) {
                    observableOrders.clear();

                    List <Order> uiOrderList = tradingEngine.getAllOrders();

                    for (int i = uiOrderList.size() - 1; i >= 0; i--) {
                        observableOrders.add(uiOrderList.get(i));
                    }
                    
                    activeOrderLayout.getChildren().clear();

                    if (observableOrders.isEmpty()) {
                        activeOrderLayout.getChildren().add(emptyPlaceholder);
                    }
                    else {
                        VBox.setVgrow(activeOrderTable, Priority.ALWAYS);
                        activeOrderLayout.getChildren().add(activeOrderTable);
                    }
                }

                updateInfoLabel();

                String msg;
                while ((msg = tradingEngine.getReturnMsg()) != null) {
                    String type = "INFO";
                    if (msg.contains("證交稅")) {
                        type = "SELL";
                    }
                    else if (msg.contains("成交價")) {
                        type = "BUY";
                    }
                    showNotification(msg, type);
                }
            }
            else {
                timeline.stop();
                dayIndex++;
                tickCount = 0;
            }
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        if (speedSlider != null) {
            int currentSpeed = (int) speedSlider.getValue();
            timeline.setRate(currentSpeed);
        }
        timeline.play();
    }

    private void refreshWatchlistTabs() {
        // 記錄當前使用者選中的分頁名稱，避免重整後焦點跑掉
        String selectedTabName = marketTabPane.getSelectionModel().getSelectedItem() != null 
                ? marketTabPane.getSelectionModel().getSelectedItem().getText() : "全部最愛";

        marketTabPane.getSelectionModel().clearSelection();
        marketTabPane.getTabs().clear();

        final String currentSimDate = (this.historyData != null && this.dayIndex < this.historyData.size()) 
            ? this.historyData.get(this.dayIndex).getDate().replace("/", "-").trim() 
            : java.time.LocalDate.now().toString();

        // 1. 根據資料結構中的每一個 Key，建立對應的 Tab
        for (String tabName : watchlistData.keySet()) {
            Tab tab = new Tab(tabName);
            
            // 建立這個分頁專屬的卡片網格佈局
            ScrollPane scrollPane = new ScrollPane();
            scrollPane.setFitToWidth(true);
            scrollPane.setStyle("-fx-background: #22272e; -fx-background-color: #22272e;");

            GridPane grid = new GridPane();
            grid.setHgap(15);
            grid.setVgap(15);
            grid.setPadding(new Insets(15));

            ObservableList<String> stocksInTab = watchlistData.get(tabName);
            final int[] cardCount = {0};
            
            for (String stockInput : stocksInTab) {
                String targetStockId = stockInput.trim();
                // 建立股票字卡
                AnchorPane cardContainer = new AnchorPane();
                
                VBox card = new VBox(10);
                card.setStyle("-fx-background-color: #2d333b; -fx-border-color: #444; -fx-border-radius: 8; -fx-background-radius: 8;");
                card.setPadding(new Insets(20));
                card.setPrefSize(220, 150);
                card.setAlignment(Pos.CENTER);

                Label lblName = new Label(stockInput + " 讀取中...");
                lblName.getStyleClass().add(Styles.TITLE_4);
                
                Label lblPrice = new Label("$ --.--");
                lblPrice.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #adbac7;");

                Label lblPercent = new Label("0.00%");
                lblPercent.setStyle("-fx-text-fill: #8b949e;");

                card.getChildren().addAll(lblName, lblPrice, lblPercent);

                // 2.刪除按鈕
                Button deleteBtn = new Button();
                deleteBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.FLAT, Styles.DANGER);
                deleteBtn.setStyle("-fx-font-size: 10px; -fx-padding: 2 6 2 6;"); 
                
                javafx.scene.shape.SVGPath xIcon = new javafx.scene.shape.SVGPath();
                xIcon.setContent("M18 6L6 18M6 6l12 12");
                xIcon.setStroke(javafx.scene.paint.Color.web("#a59d9d"));
                xIcon.setStrokeWidth(1.5);
                deleteBtn.setGraphic(xIcon);

                deleteBtn.setOnAction(e -> {
                    e.consume(); 
                    if (tabName.equals("全部最愛")) {
                        for (String key : watchlistData.keySet()) {
                            watchlistData.get(key).remove(stockInput);
                        }
                    } else {
                        watchlistData.get(tabName).remove(stockInput);
                    }
                    refreshWatchlistTabs();
                });

                // 4. 將字卡本體與刪除按鈕裝進 Container 中
                AnchorPane.setTopAnchor(card, 0.0);
                AnchorPane.setLeftAnchor(card, 0.0);
                AnchorPane.setRightAnchor(card, 0.0);
                AnchorPane.setBottomAnchor(card, 0.0);
                AnchorPane.setTopAnchor(deleteBtn, 5.0);
                AnchorPane.setRightAnchor(deleteBtn, 5.0);

                // ⚠️ 微調點 2：必須先組合完 container 的內容物，才能加進 grid 裡！
                cardContainer.getChildren().addAll(card, deleteBtn);

                // ✨ 組合完畢後，在這裡把完整的 cardContainer 提早加入 grid 卡位
                int currentCount = cardCount[0];
                int col = currentCount % 4;
                int row = currentCount / 4;
                grid.add(cardContainer, col, row);
                cardCount[0]++; 

                new Thread(() -> {
                    try {
                        double realPrice = 0.0;
                        double changePercent = 0.0;
                        String detectedName = targetStockId; // 預設為代號
                        boolean isCompanyExistInThisEra = true; // 標記該公司在該時代是否存在

                        // 雙重保險第一層：從全市場清單中，用 2330 匹配出中文名稱
                        List<com.stockbucks.api.stock.StockProfile> profiles = aiHub.fetchAllListedStocks();
                        if (profiles != null) {
                            for (com.stockbucks.api.stock.StockProfile p : profiles) {
                                if (p.getStockId() != null && p.getStockId().equals(targetStockId)) {
                                    detectedName = p.getStockName(); 
                                    break;
                                }
                            }
                        }

                        if (this.marketMode == com.stockbucks.api.mode.MarketMode.REALTIME) {
                            com.stockbucks.api.stock.StockQuote quote = aiHub.fetchStockQuote(targetStockId);
                            if (quote != null) {
                                realPrice = quote.getLastPrice(); 
                                
                                // 雙重保險第二層：如果第一層沒撈到名字，且 quote 裡有名字，就用 quote 的
                                if (detectedName.equals(targetStockId) && quote.getStockName() != null && !quote.getStockName().isBlank()) {
                                    detectedName = quote.getStockName();
                                }
                                
                                double open = quote.getOpenPrice();
                                if (open > 0) {
                                    changePercent = ((realPrice - open) / open) * 100;
                                }
                            }
                        } else {
                            //歷史回測模式
                            List<?> hist = aiHub.loadSimulationHistory(targetStockId, null);
                            if (hist != null && !hist.isEmpty()) {
                                int targetIndex = -1;

                                // 1. 尋找精確匹配當前穿梭日期（currentSimDate）的 K 線索引
                                for (int i = 0; i < hist.size(); i++) {
                                    Object dataRow = hist.get(i);
                                    if (dataRow instanceof com.stockbucks.StockData) {
                                        String histDate = ((com.stockbucks.StockData) dataRow).getDate().replace("/", "-").trim();
                                        if (histDate.equals(currentSimDate)) {
                                            targetIndex = i;
                                            break;
                                        }
                                    }
                                }

                                // 🛡️ 2. 時空防禦檢查：如果比最早的歷史還要早，代表這家公司「在此時代尚未上市」
                                Object firstRow = hist.get(0);
                                if (firstRow instanceof com.stockbucks.StockData) {
                                    String firstAvailDate = ((com.stockbucks.StockData) firstRow).getDate().replace("/", "-").trim();
                                    if (targetIndex == -1 && currentSimDate.compareTo(firstAvailDate) < 0) {
                                        isCompanyExistInThisEra = false; 
                                    }
                                }

                                // 3. 如果已上市但碰上休市日，則往前尋找最近一個交易日
                                if (isCompanyExistInThisEra && targetIndex == -1) {
                                    for (int i = hist.size() - 1; i >= 0; i--) {
                                        Object dataRow = hist.get(i);
                                        if (dataRow instanceof com.stockbucks.StockData) {
                                            String histDate = ((com.stockbucks.StockData) dataRow).getDate().replace("/", "-").trim();
                                            if (histDate.compareTo(currentSimDate) <= 0) {
                                                targetIndex = i;
                                                break;
                                            }
                                        }
                                    }
                                }

                                // 4. 撈出數據並計算相較於前一天的漲跌幅
                                if (targetIndex >= 0) {
                                    com.stockbucks.StockData currentDay = (com.stockbucks.StockData) hist.get(targetIndex);
                                    realPrice = currentDay.getClose();
                                    
                                    if (targetIndex > 0) {
                                        Object prevRow = hist.get(targetIndex - 1);
                                        if (prevRow instanceof com.stockbucks.StockData) {
                                            double prevClose = ((com.stockbucks.StockData) prevRow).getClose();
                                            if (prevClose > 0) {
                                                changePercent = ((realPrice - prevClose) / prevClose) * 100;
                                            }
                                        }
                                    }
                                } else {
                                    isCompanyExistInThisEra = false; 
                                }
                            } else {
                                isCompanyExistInThisEra = false; 
                            }
                        }

                        // 傳遞給 UI 執行緒刷新
                        final double finalPrice = realPrice;
                        final double finalPercent = changePercent;
                        final boolean finalShowCard = isCompanyExistInThisEra; 
                        
                        final String finalTitle = detectedName.equals(targetStockId) 
                                ? targetStockId 
                                : detectedName + " (" + targetStockId + ")";
                        
                        Platform.runLater(() -> {
                            //如果這家公司在當前時空不存在，直接中斷不顯示
                            if (!finalShowCard) {
                                cardContainer.setVisible(false);
                                cardContainer.setManaged(false);
                                return; 
                            }

                            cardContainer.setVisible(true);
                            cardContainer.setManaged(true);

                            lblName.setText(finalTitle);
                            lblPrice.setText(String.format("$%,.2f", finalPrice));
                            lblPercent.setText(String.format("%s%.2f%%", finalPercent >= 0 ? "+" : "", finalPercent));
                            
                            if (finalPercent > 0) {
                                lblPrice.setStyle("-fx-text-fill: #ea4335; -fx-font-size: 24px; -fx-font-weight: bold;");
                                lblPercent.setStyle("-fx-text-fill: #ea4335;");
                            } else if (finalPercent < 0) {
                                lblPrice.setStyle("-fx-text-fill: #34a853; -fx-font-size: 24px; -fx-font-weight: bold;");
                                lblPercent.setStyle("-fx-text-fill: #34a853;");
                            } else {
                                lblPrice.setStyle("-fx-text-fill: #adbac7; -fx-font-size: 24px; -fx-font-weight: bold;");
                                lblPercent.setStyle("-fx-text-fill: #adbac7;");
                            }
                        });

                    } catch (Exception ex) {
                        Platform.runLater(() -> lblName.setText(stockInput + " (載入失敗)"));
                    }
                }).start();
                
                // 點擊字卡本體切換到交易頁面
                card.setOnMouseClicked(e -> {
                    if (this.stockSearchField != null) {
                        this.stockSearchField.setText(stockInput);
                        handleStartSimulation(); // 自動幫玩家觸發看盤與跑線邏輯！
                    }
                    switchView(MenuType.TRADE);
                });
            }

            scrollPane.setContent(grid);
            tab.setContent(scrollPane);
            marketTabPane.getTabs().add(tab);
            
            // 恢復原本選取的分頁焦點
            if (tabName.equals(selectedTabName)) {
                marketTabPane.getSelectionModel().select(tab);
            }
        }

        // 2. 建立最後一頁：特殊「+」號按鈕分頁
        Tab addTab = new Tab("＋");
        marketTabPane.getTabs().add(addTab);

        marketTabPane.getSelectionModel().selectedItemProperty().removeListener(this::watchlistTabChangeListener);
        marketTabPane.getSelectionModel().selectedItemProperty().addListener(this::watchlistTabChangeListener);

        // 監聽分頁切換事件：當點到「＋」號時，觸發新增分頁對話框
        for (Tab t : marketTabPane.getTabs()) {
            if (t.getText().equals(selectedTabName)) {
                marketTabPane.getSelectionModel().select(t);
                break;
            }
        }
    }

    private void watchlistTabChangeListener(javafx.beans.value.ObservableValue<? extends Tab> observable, Tab oldTab, Tab newTab) {
        if (newTab != null && "＋".equals(newTab.getText())) {
            Platform.runLater(() -> handleAddNewWatchlistPage());
        }
    }

    /**
     * 處理使用者點擊「＋」號新增並命名新分頁
     */
    private void handleAddNewWatchlistPage() {
        TextInputDialog dialog = new TextInputDialog("行動自選" + watchlistData.size());
        dialog.setTitle("新增自選股分頁");
        dialog.setHeaderText("建立專屬的股票投資清單");
        dialog.setContentText("請輸入新分頁的名稱:");
        dialog.getDialogPane().setStyle("-fx-background-color: #1c2128;");

        dialog.showAndWait().ifPresent(pageName -> {
            String trimmedName = pageName.trim();
            if (trimmedName.isEmpty() || trimmedName.equals("＋")) {
                showWarning("分頁名稱無效！");
                refreshWatchlistTabs(); // 跳回原分頁
                return;
            }
            if (watchlistData.containsKey(trimmedName)) {
                showWarning("分頁名稱已存在！");
                refreshWatchlistTabs();
                return;
            }

            // 新增空的分頁清單到資料結構中
            watchlistData.put(trimmedName, FXCollections.observableArrayList());
            
            // 重新渲染，並將焦點自動選中新建立的分頁
            marketTabPane.getSelectionModel().clearSelection();
            refreshWatchlistTabs();
            
            // 找到剛建好的分頁並選中它
            for (Tab t : marketTabPane.getTabs()) {
                if (t.getText().equals(trimmedName)) {
                    marketTabPane.getSelectionModel().select(t);
                    break;
                }
            }
        });
        
        // 如果使用者點取消，重整以確保焦點不會卡在「＋」上面
        if (!dialog.isShowing() && marketTabPane.getSelectionModel().getSelectedItem().getText().equals("＋")) {
            marketTabPane.getSelectionModel().select(0);
        }
    }

    private void showWarning(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    /**
     * ✨ 動態對接 User 數據，渲染仿國泰/富邦風格的圓餅圖資產面板
     */
    private void refreshAssetView() {
        if (assetView == null || user == null) return;

        assetView.getChildren().clear(); // 清空舊畫面

        // 1. 標題
        Label assetTitle = new Label("📊 我的帳務資產總覽");
        assetTitle.getStyleClass().add(Styles.TITLE_2);
        assetView.getChildren().addAll(assetTitle, new Separator());

        // 建立雙分頁滑動面板
        TabPane assetTabPane = new TabPane();
        assetTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); // 禁用關閉按鈕
        VBox.setVgrow(assetTabPane, Priority.ALWAYS);

        // 第一分頁：未實現資產
        Tab unrealizedTab = new Tab("📈 未實現資產");
        VBox unrealizedContent = new VBox(15);
        unrealizedContent.setPadding(new Insets(15, 0, 0, 0));
        
        // 取得當前模擬的股票最新價格
        String currentStockId = "2330";
        if (stockSearchField != null && !stockSearchField.getText().trim().isEmpty()) {
            currentStockId = stockSearchField.getText().trim();
        } else if (marketSearchField != null && !marketSearchField.getText().trim().isEmpty()) {
            currentStockId = marketSearchField.getText().trim();
        }

        // 安全獲取「當前市價」（回測模式下優先採用歷史關聯價）
        double currentPrice = 600.0; // 預設安全底線
        if (historyData != null && !historyData.isEmpty()) {
            // 防止指標越界安全保護
            int safeIndex = Math.min(Math.max(0, this.dayIndex), historyData.size() - 1);
            currentPrice = historyData.get(safeIndex).getClose(); // 優先以回測紀錄的收盤價為基準
        }

        try {
            if (currentPriceLabel != null) {
                String labelText = currentPriceLabel.getText();
                if (labelText.contains("當前市價:") || labelText.contains("即時市價:")) {
                    String cleanPrice = labelText.replace("當前市價:", "").replace("即時市價:", "").trim();
                    if (cleanPrice.contains("(")) {
                        cleanPrice = cleanPrice.split("\\(")[0].trim();
                    }
                    currentPrice = Double.parseDouble(cleanPrice);
                }
            }
        } catch (Exception e) {
            // 如果從 UI 標籤抽取失敗，默默沿用上方對齊 historyData 的安全價格，絕不中斷
        }

        // 獲取當前模擬的精準時空日期 (格式如 "2010-09-14")
        String currentSimDate = java.time.LocalDate.now().toString();
        if (historyData != null && !historyData.isEmpty()) {
            int safeIndex = Math.min(Math.max(0, this.dayIndex), historyData.size() - 1);
            currentSimDate = historyData.get(safeIndex).getDate().replace("/", "-").trim();
        }

        // 呼叫 User 內的新方法，精準計算出所有庫存股票的總市值
        double totalStockValue = 0.0;
        // 總原始購買成本
        double totalStockCost = 0.0;
        java.util.HashMap<String, StockHoldings> holdings = user.getHolding();

        java.util.List<javafx.scene.chart.PieChart.Data> validPieData = new java.util.ArrayList<>();
        VBox legendList = new VBox(12);
        legendList.setAlignment(Pos.CENTER_LEFT);

        Label lblListTitle = new Label("📊 各持股配置比例 (" + currentSimDate + ")");
        lblListTitle.getStyleClass().addAll(Styles.TEXT_BOLD, Styles.TITLE_4);
        legendList.getChildren().add(lblListTitle);

        if (holdings != null && !holdings.isEmpty()) {
            for (String stockID : holdings.keySet()) {
                // 1. 現場計算該股票在該時空下的股數
                int sharesAtTime = 0;
                double costForBuy = 0.0;
                int totalSharesBought = 0;

                // 安全防空檢查
                if (user.getTradeHistory() != null) {
                    for (com.stockbucks.TradeRecord record : user.getTradeHistory()) {
                        if (record == null || record.getDate() == null) continue;
                        String cleanRecordDate = record.getDate().replace("/", "-").trim();
                        // 只有小於等於當前穿梭日期的交易才算數
                        if (cleanRecordDate.compareTo(currentSimDate) <= 0 && record.getStockID().equals(stockID)) {
                            if ("買入".equals(record.getType())) {
                                sharesAtTime += record.getShares();
                                costForBuy += record.getTotalCost();
                                totalSharesBought += record.getShares();
                            } else if ("賣出".equals(record.getType())) {
                                sharesAtTime -= record.getShares();
                            }
                        }
                    }
                }

                // 如果在該時空下根本還沒買，或者已清空持股，直接隱形
                if (sharesAtTime <= 0) continue;

                // 2. 現場計算該持股的歷史均價與成本
                double averagePrice = totalSharesBought > 0 ? (costForBuy / totalSharesBought) : 0.0;
                double eachStockCost = averagePrice * sharesAtTime;
                
                // 3. 計算市值：如果是當前跑線股票用動態價，其餘股票先用歷史成本保底
                double eachStockValue = 0.0;
                if (stockID.equals(currentStockId)) {
                    eachStockValue = sharesAtTime * currentPrice;
                } else {
                    eachStockValue = eachStockCost; 
                }

                totalStockValue += eachStockValue;
                totalStockCost += eachStockCost;

                validPieData.add(new javafx.scene.chart.PieChart.Data(stockID, eachStockValue));

                Label lblStockData = new Label(String.format("🟢 庫存股票 [%s]: (%d 股 | 市值 $ %,.2f | 成本 $ %,.2f)", 
                        stockID, sharesAtTime, eachStockValue, eachStockCost));
                lblStockData.setStyle("-fx-font-size: 14px; -fx-text-fill: #57ab5a;");
                legendList.getChildren().add(lblStockData);
            }
        }

        // 💡 遵照需求：總資產即為庫存持股總現值，排除可用現金
        double totalAsset = totalStockValue;
        double totalReturn = totalAsset - totalStockCost; // 報酬金額 (總市值 - 總成本)
        double returnRate = totalStockCost > 0 ? (totalReturn / totalStockCost) * 100 : 0.0; // 報酬率

        // 3. 【資產總現值面板】 (仿手機 App 頂部字卡樣式)
        VBox totalAssetPanel = new VBox(8);
        totalAssetPanel.setPadding(new Insets(15));
        totalAssetPanel.setStyle("-fx-background-color: #1c2128; -fx-background-radius: 8; -fx-border-color: #30363d; -fx-border-radius: 8;");
        
        Label lblPanelTitle = new Label("庫存總現值 (TWD)");
        lblPanelTitle.setStyle("-fx-text-fill: #8b949e; -fx-font-size: 14px;");
        
        Label lblTotalAssetValue = new Label(String.format("$ %,.2f", totalAsset));
        lblTotalAssetValue.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #adbac7;");
        
        Label lblCashAndStock = new Label(String.format("原始投資總成本: $ %,.2f", totalStockCost));
        lblCashAndStock.setStyle("-fx-text-fill: #58a6ff; -fx-font-size: 13px;");

        // 歷史總報酬
        String sign = totalReturn >= 0 ? "+" : ""; 
        Label lblTotalReturn = new Label(String.format("預估未實現損益: %s$ %,.2f (%s %.2f%%)", 
                sign, totalReturn, sign, returnRate));

        // 顏色分流 (賺紅賠綠)
        if (totalReturn > 0) {
            lblTotalReturn.setStyle("-fx-text-fill: #da3633; -fx-font-size: 13px; -fx-font-weight: bold;"); 
        } else if (totalReturn < 0) {
            lblTotalReturn.setStyle("-fx-text-fill: #2da44e; -fx-font-size: 13px; -fx-font-weight: bold;"); 
        } else {
            lblTotalReturn.setStyle("-fx-text-fill: #adbac7; -fx-font-size: 13px;"); 
        }
        
        totalAssetPanel.getChildren().addAll(lblPanelTitle, lblTotalAssetValue, lblCashAndStock, lblTotalReturn);
        unrealizedContent.getChildren().add(totalAssetPanel);

        // 4. 【左右並排區】區域：左邊放圓餅圖，右邊放比例明細
        HBox chartSection = new HBox(40);
        chartSection.setAlignment(Pos.CENTER_LEFT);
        chartSection.setPadding(new Insets(15, 10, 10, 10));

        // --- 建立 JavaFX PieChart ---
        javafx.scene.chart.PieChart pieChart = new javafx.scene.chart.PieChart();
        pieChart.setLabelsVisible(false); // 關閉圖表外圍文字，改看右邊清單
        pieChart.setLegendVisible(false); // 關閉下方預設圖例
        pieChart.setPrefSize(260, 260);

        // 迴圈遍歷，動態將每檔擁有的持股股票塞入圖表與明細（排除現金）
        if (!validPieData.isEmpty()) {
            for (javafx.scene.chart.PieChart.Data pData : validPieData) { pieChart.getData().add(pData); }
        } else {
            pieChart.getData().add(new javafx.scene.chart.PieChart.Data("無庫存股票", 1));
            legendList.getChildren().add(new Label("⚠️ 在 " + currentSimDate + "，您在這個時空內沒有任何股票庫存。"));
        }

        // 將圖表與數據面板並排組合
        chartSection.getChildren().addAll(pieChart, legendList);
        unrealizedContent.getChildren().add(chartSection);
        unrealizedTab.setContent(unrealizedContent);

        // 第二分頁：已實現損益
        Tab realizedTab = new Tab("💰 已實現損益");
        VBox realizedContent = new VBox(15);
        realizedContent.setPadding(new Insets(15, 10, 10, 10));

        // 「截至當前時空為止」已經平倉的總損益
        double realizedProfit = user.getRealizedProfit();

        // 建立已實現損益字卡面板
        VBox realizedPanel = new VBox(12);
        realizedPanel.setPadding(new Insets(20));
        realizedPanel.setStyle("-fx-background-color: #1c2128; -fx-background-radius: 8; -fx-border-color: #30363d; -fx-border-radius: 8;");
        realizedPanel.setAlignment(Pos.CENTER_LEFT);

        Label lblRealizedTitle = new Label("累積已實現損益 (TWD)");
        lblRealizedTitle.setStyle("-fx-text-fill: #8b949e; -fx-font-size: 14px;");

        String realizedSign = realizedProfit >= 0 ? "+" : "";
        Label lblRealizedValue = new Label(String.format("%s$ %,.2f", realizedSign, realizedProfit));
        lblRealizedValue.setStyle("-fx-font-size: 32px; -fx-font-weight: bold;");

        if (realizedProfit > 0) {
            lblRealizedValue.setStyle(lblRealizedValue.getStyle() + " -fx-text-fill: #da3633;");
        } else if (realizedProfit < 0) {
            lblRealizedValue.setStyle(lblRealizedValue.getStyle() + " -fx-text-fill: #2da44e;");
        } else {
            lblRealizedValue.setStyle(lblRealizedValue.getStyle() + " -fx-text-fill: #adbac7;");
        }

        Label lblRealizedDesc = new Label("※ 此數值為您在本次模擬交易中，已賣出股票平倉結算後的「實際淨賺賠總額」。");
        lblRealizedDesc.setStyle("-fx-text-fill: #8b949e; -fx-font-size: 12px; -fx-font-style: italic;");

        realizedPanel.getChildren().addAll(lblRealizedTitle, lblRealizedValue, lblRealizedDesc);
        realizedContent.getChildren().add(realizedPanel);
        realizedTab.setContent(realizedContent);

        // 最後將兩個分頁加入資產總覽主畫面
        assetTabPane.getTabs().addAll(unrealizedTab, realizedTab);
        assetView.getChildren().add(assetTabPane);
    }

    public static void startApp(String[] args) {
        launch(args);
    }
}