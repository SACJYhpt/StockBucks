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
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.animation.FadeTransition;
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
    private TableView<Order> activeOrderTable = new TableView<>();
    private ObservableList<TradeRecord> observableRecords = FXCollections.observableArrayList();
    private ObservableList<Order> observableOrders = FXCollections.observableArrayList();
    private Label currentPriceLabel = new Label("當前市價: --");
    private Label infoLabel = new Label();
    private TextField sharesField = new TextField("1000");
    private LineChart<Number, Number> lineChart;
    private XYChart.Series<Number, Number> priceSeries = new XYChart.Series<>();
    private XYChart.Series<Number, Number> limitUpSeries;
    private XYChart.Series<Number, Number> limitDownSeries;

    private HBox backtestControlBar;  // 包含時空選擇與速控的容器
    private DatePicker dateSelector;  // 月曆時空選擇器
    private Slider speedSlider;       // 速度控制條
    private Label speedLabel;       // 用於顯示當前模擬速度的標籤
    
    // 模擬核心數據
    private int tickCount = 0;
    private List<StockData> historyData = new ArrayList<>();
    private int dayIndex = 0;
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
            watchlistData.get("全部最愛").addAll("元大台灣50", "富邦科技 0052", "國泰台灣科技", "台積電 2330");
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

        stage.setScene(new Scene(mainLayout, 1300, 850));
        stage.setTitle("StockBucks 模擬交易系統");
        stage.show();
    }

    /**
     * 初始化四大主要功能畫面
     */
    private void initAllViews() {
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
        TextField stockSearchField = new TextField();
        stockSearchField.setPromptText("🔍 搜尋股票、名稱或代碼...");
        stockSearchField.setPrefWidth(400);
        stockSearchField.getStyleClass().add(Styles.ROUNDED); // 讓邊角變圓

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

        sharesField.setPrefWidth(100);
        Button buyBtn = new Button("買入");
        buyBtn.getStyleClass().addAll(Styles.SUCCESS, Styles.BUTTON_OUTLINED);
        buyBtn.setOnAction(e -> handleTrade(true));

        Button sellBtn = new Button("賣出");
        sellBtn.getStyleClass().addAll(Styles.DANGER, Styles.BUTTON_OUTLINED);
        sellBtn.setOnAction(e -> handleTrade(false));

        tradeControlPanel.getChildren().addAll(new Label("交易股數:"), sharesField, buyBtn, sellBtn, new Separator(javafx.geometry.Orientation.VERTICAL), infoLabel);
        
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
            
            System.out.println("玩家在介面上選了日期: " + selectedDate);
            
            /* 💡 留給未來的提示：
               等確定 StockData 的格式後，把這段註解解開即可：
               
            if (historyData != null && !historyData.isEmpty()) {
                for (int i = 0; i < historyData.size(); i++) {
                    // 根據未來的格式去比對日期
                    // this.dayIndex = i;
                    // break;
                }
            }
            */
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
            final String currentStockId = "TestDataTSMC";

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
                        aiChatArea.setScrollTop(Double.MAX_VALUE); // 自動滾動到最底部
                        
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
        activeOrderLayout.getChildren().add(emptyPlaceholder);
        activeOrderTab.setContent(activeOrderLayout);

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
        
        priceSeries = new XYChart.Series<>();
        priceSeries.setName("股價");

        chart.getData().add(limitUpSeries);
        chart.getData().add(limitDownSeries);
        chart.getData().add(priceSeries);

        limitUpSeries.getNode().setStyle("-fx-stroke: #FF3B30; -fx-stroke-dash-array: 5 5; -fx-stroke-width: 1.5px;");
        limitDownSeries.getNode().setStyle("-fx-stroke: #34C759; -fx-stroke-dash-array: 5 5; -fx-stroke-width: 1.5px;");

        return chart;
    }

    private void handleTrade(boolean isBuy) {
        if (currentPriceLabel.getText().contains("--")) return;
        try {
            int shares = Integer.parseInt(sharesField.getText());
            String priceStr = currentPriceLabel.getText().replace("當前市價: ", "");
            if (priceStr.contains("(")) {
                priceStr = priceStr.split("\\(")[0].trim();
            }
            double price = Double.parseDouble(priceStr);
            String date = (historyData != null && dayIndex < historyData.size()) ? historyData.get(dayIndex).getDate() : "2024-01-01";

            tradingEngine.trading(user, "TestDataTSMC", date, tickCount, shares, price, isBuy);

            updateInfoLabel();

            if (activeOrderTable != null) {
                observableOrders.clear();
                for (int i = tradingEngine.getPendingOrders().size() - 1; i >= 0; i--) {
                    observableOrders.add(tradingEngine.getPendingOrders().get(i));
                }
            }
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
            toastLabel.setStyle("-fx-background-color: #FF3B30; -fx-text-fill: white; -fx-padding: 10 20; -fx-background-radius: 20; -fx-font-size: 14px; -fx-font-weight: bold;");
        }
        else if (type.equals("SELL")) {
            toastLabel.setStyle("-fx-background-color: #34C759; -fx-text-fill: white; -fx-padding: 10 20; -fx-background-radius: 20; -fx-font-size: 14px; -fx-font-weight: bold;");
        }
        else {
            toastLabel.setStyle("-fx-background-color: #8E8E93; -fx-text-fill: white; -fx-padding: 10 20; -fx-background-radius: 20; -fx-font-size: 14px;");
        }

        VBox root = new VBox(toastLabel);
        root.setStyle("-fx-background-color: transparent;"); // 容器本身必須完全透明
        root.setAlignment(Pos.CENTER);

        Scene scene = new Scene(root);
        scene.setFill(Color.TRANSPARENT); // 畫布也必須透明
        toastStage.setScene(scene);

        // 🎯 讓通知視窗出現在螢幕正中央（偏上方的位置）
        toastStage.setX(javafx.stage.Screen.getPrimary().getVisualBounds().getWidth() / 2 - 150);
        toastStage.setY(120); // 距離螢幕頂端 120 像素

        toastStage.show();

        FadeTransition fade = new FadeTransition(Duration.millis(500), root);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setDelay(Duration.seconds(2.0));
        fade.setOnFinished(event -> toastStage.close());
        fade.play();
    }

    private void updateInfoLabel() {
        infoLabel.setText(String.format("可用現金: $%,.0f | 庫存: %d 股", 
            user.getCash(), user.getStockQuantity("TestDataTSMC")));
    }

    @SuppressWarnings("unchecked")
    private void setupTable() {
        TableColumn<TradeRecord, String> dateCol = new TableColumn<>("日期");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));
        TableColumn<TradeRecord, String> timeCol = new TableColumn<>("時間");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("time"));
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
        historyTable.getColumns().addAll(dateCol, timeCol, typeCol, priceCol, sharesCol, costCol);
        historyTable.setItems(observableRecords);
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    @SuppressWarnings("unchecked")
    private void setupActiveOrderTable() {
        TableColumn <Order, String> dateCol = new TableColumn<>("委託日期");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));
        TableColumn<Order, String> timeCol = new TableColumn<>("時間");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("time"));
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
        activeOrderTable.getColumns().addAll(dateCol, timeCol, typeCol, priceCol, sharesCol, statusCol);
        activeOrderTable.setItems(observableOrders);
        activeOrderTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void handleStartSimulation() {
        if (historyData == null || dayIndex >= historyData.size()) return;

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
        
        simulator.generateDayPath(today, yesterdayClose);
        
        priceSeries.getData().clear();
        tickCount = 0;

        if (timeline != null) timeline.stop();

        timeline = new Timeline(new KeyFrame(Duration.millis(300), e -> {
            double currentPrice = simulator.getNextPrice();
            if (currentPrice != -1) {
                int currentTime = tickCount;
                currentTime += 9*60;
                String currentTimeStr = String.format("%02d:%02d", currentTime / 60, currentTime % 60);

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

                tradingEngine.onPriceUpdate("TestDataTSMC", currentPrice, tickCount++);

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

        marketTabPane.getTabs().clear();

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
            int col = 0, row = 0;
            
            for (String stockName : stocksInTab) {
                // 建立股票字卡 (仿國泰方格佈局)
                AnchorPane cardContainer = new AnchorPane();
                
                VBox card = new VBox(10);
                card.setStyle("-fx-background-color: #2d333b; -fx-border-color: #444; -fx-border-radius: 8; -fx-background-radius: 8;");
                card.setPadding(new Insets(20));
                card.setPrefSize(220, 150);
                card.setAlignment(Pos.CENTER);

                Label lblName = new Label(stockName);
                lblName.getStyleClass().add(Styles.TITLE_4);
                
                // 模擬隨機價格與漲跌幅
                double mockPrice = 50 + Math.random() * 500;
                double mockPercent = -5 + Math.random() * 10;
                
                Label lblPrice = new Label(String.format("$%.2f", mockPrice));
                lblPrice.setStyle(mockPercent >= 0 
                    ? "-fx-text-fill: #ea4335; -fx-font-size: 24px; -fx-font-weight: bold;" 
                    : "-fx-text-fill: #34a853; -fx-font-size: 24px; -fx-font-weight: bold;");

                Label lblPercent = new Label(String.format("%s%.2f%%", mockPercent >= 0 ? "+" : "", mockPercent));
                lblPercent.setStyle(mockPercent >= 0 ? "-fx-text-fill: #ea4335;" : "-fx-text-fill: #34a853;");

                card.getChildren().addAll(lblName, lblPrice, lblPercent);
                
                // 點擊字卡本體切換到交易頁面
                card.setOnMouseClicked(e -> switchView(MenuType.TRADE));

                // 2.刪除按鈕
                Button deleteBtn = new Button();
                deleteBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.FLAT, Styles.DANGER);
                deleteBtn.setStyle("-fx-font-size: 10px; -fx-padding: 2 6 2 6;"); // 縮小按鈕尺寸
                
                // 用 JavaFX 原生線條畫出一個簡單的小 X
                javafx.scene.shape.SVGPath xIcon = new javafx.scene.shape.SVGPath();
                xIcon.setContent("M18 6L6 18M6 6l12 12");
                xIcon.setStroke(javafx.scene.paint.Color.web("#a59d9d"));
                xIcon.setStrokeWidth(1.5);
                deleteBtn.setGraphic(xIcon);

                // 3.實作刪除按鈕的點擊邏輯
                deleteBtn.setOnAction(e -> {
                    // 防止點擊刪除按鈕時，同時觸發 VBox 的切換頁面事件（阻斷事件冒泡）
                    e.consume(); 

                    if (tabName.equals("全部最愛")) {
                        // A 邏輯：在全部最愛刪除 -> 從「所有群組」中徹底拔除該個股
                        for (String key : watchlistData.keySet()) {
                            watchlistData.get(key).remove(stockName);
                        }
                    } else {
                        // B 邏輯：在特定群組刪除 -> 僅移出該分頁，保留在全部最愛中
                        watchlistData.get(tabName).remove(stockName);
                    }

                    // 重新重整分頁 UI，讓被刪除的字卡平滑消失
                    refreshWatchlistTabs();
                });

                // 4. 將字卡本體與刪除按鈕裝進 Container 中，並設定 X 按鈕靠右上角定位
                AnchorPane.setTopAnchor(card, 0.0);
                AnchorPane.setLeftAnchor(card, 0.0);
                AnchorPane.setRightAnchor(card, 0.0);
                AnchorPane.setBottomAnchor(card, 0.0);

                AnchorPane.setTopAnchor(deleteBtn, 5.0);
                AnchorPane.setRightAnchor(deleteBtn, 5.0);

                cardContainer.getChildren().addAll(card, deleteBtn);

                // 5. 將原本塞進 grid 的物件換成新包裝好的 container
                grid.add(cardContainer, col, row);
                
                col++; 
                if (col > 3) { 
                    col = 0; 
                    row++; 
                }
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

        // 監聽分頁切換事件：當點到「＋」號時，觸發新增分頁對話框
        marketTabPane.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, newTab) -> {
            if (newTab != null && newTab.getText().equals("＋")) {
                Platform.runLater(() -> handleAddNewWatchlistPage());
            }
        });
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
        assetView.getChildren().clear(); // 清空舊畫面

        // 1. 標題
        Label assetTitle = new Label("📊 我的帳務資產總覽");
        assetTitle.getStyleClass().add(Styles.TITLE_2);
        assetView.getChildren().addAll(assetTitle, new Separator());

        // 建立雙分頁滑動面板
        TabPane assetTabPane = new TabPane();
        assetTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); // 禁用關閉按鈕
        VBox.setVgrow(assetTabPane, Priority.ALWAYS);

        //第一分頁：未實現資產
        Tab unrealizedTab = new Tab("📈 未實現資產");
        VBox unrealizedContent = new VBox(15);
        unrealizedContent.setPadding(new Insets(15, 0, 0, 0));
        
        // 取得當前模擬的股票最新價格
        double currentPrice = 0.0;
        try {
            currentPrice = Double.parseDouble(currentPriceLabel.getText().replace("當前市價: ", ""));
        } catch (Exception e) {
            currentPrice = (historyData != null && !historyData.isEmpty()) ? historyData.get(dayIndex).getClose() : 600.0;
        }

        // 呼叫 User 內的新方法，精準計算出所有庫存股票的總市值
        double totalStockValue = user.getTotalPresentValue("TestDataTSMC", currentPrice);
        
        // 總資產 = 股票總市值
        double totalAsset = totalStockValue;

        //總原始購買成本
        double totalStockCost = 0.0;
        java.util.HashMap<String, StockHoldings> holdings = user.getHolding();
        if (holdings != null && !holdings.isEmpty()) {
            for (String stockID : holdings.keySet()) {
                totalStockCost += user.getOneTotalCost(stockID);
            }
        }

        double totalReturn = totalAsset - totalStockCost; // 報酬金額
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

        //歷史總報酬: +$ 45,000.00 (+7.50%) 或 -$ 12,000.00 (-2.30%)
        String sign = totalReturn >= 0 ? "+" : ""; // 賺錢補上加號
        Label lblTotalReturn = new Label(String.format("預估未實現損益: %s$ %,.2f (%s %.2f%%)", 
                sign, totalReturn, sign, returnRate));

        //顏色分流，如果想改成美股反過來，可以把底下的顏色代碼互換
        if (totalReturn > 0) {
            lblTotalReturn.setStyle("-fx-text-fill: #da3633; -fx-font-size: 13px; -fx-font-weight: bold;"); // 鮮明紅
        } else if (totalReturn < 0) {
            lblTotalReturn.setStyle("-fx-text-fill: #2da44e; -fx-font-size: 13px; -fx-font-weight: bold;"); // 活潑綠
        } else {
            lblTotalReturn.setStyle("-fx-text-fill: #adbac7; -fx-font-size: 13px;"); // 平盤灰
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

        // --- 建立右側的詳細比例數據清單 ---
        VBox legendList = new VBox(12);
        legendList.setAlignment(Pos.CENTER_LEFT);

        Label lblListTitle = new Label("📊 各資產配置比例");
        lblListTitle.getStyleClass().addAll(Styles.TEXT_BOLD, Styles.TITLE_4);
        legendList.getChildren().add(lblListTitle);

        //迴圈遍歷 user.getHoldings()，動態將每檔擁有的股票塞入圖表與明細
        if (holdings != null && !holdings.isEmpty()) {
            for (String stockID : holdings.keySet()) {
                // 計算這檔股票的當前價值
                double eachStockValue = 0;
                if (stockID.equals("TestDataTSMC")) {
                    eachStockValue = user.getOnePresentValue(stockID, currentPrice);
                } else {
                    eachStockValue = user.getOneTotalCost(stockID);
                }

                if (eachStockValue > 0) {
                    // 塞入圓餅圖數據
                    pieChart.getData().add(new javafx.scene.chart.PieChart.Data(stockID, eachStockValue));
                    
                    // 計算此個股佔總資產的百分比
                    double stockPercent = totalAsset > 0 ? (eachStockValue / totalAsset) * 100 : 0;
                    int shares = user.getStockQuantity(stockID);
                    double eachStockCost = user.getOneTotalCost(stockID);

                    Label lblStockData = new Label(String.format("🟢 庫存股票 [%s]: %.1f%% (%d 股 | 市值 $ %,.2f | 成本 $ %,.2f)", 
                            stockID, stockPercent, shares, eachStockValue, eachStockCost));
                    lblStockData.setStyle("-fx-font-size: 14px; -fx-text-fill: #57ab5a;");
                    legendList.getChildren().add(lblStockData);
                }
            }
        }

        // C. 防呆：如果完全沒資產
        if (totalAsset == 0) {
            pieChart.getData().add(new javafx.scene.chart.PieChart.Data("無庫存股票", 1));
            legendList.getChildren().add(new Label("⚠️ 目前帳戶內沒有任何股票庫存。"));
        }

        // 將圖表與數據面板並排組合
        chartSection.getChildren().addAll(pieChart, legendList);
        unrealizedContent.getChildren().add(chartSection);
        unrealizedTab.setContent(unrealizedContent);

        //第二分頁：已實現損益
        Tab realizedTab = new Tab("💰 已實現損益");
        VBox realizedContent = new VBox(15);
        realizedContent.setPadding(new Insets(15, 10, 10, 10));

        // 取得使用者已經平倉（賣出）結算後的累積已實現利潤
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

        // 根據正負損益進行強烈的顏色流動（紅賺綠賠）
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

        //最後將兩個分頁加入資產總覽主畫面
        assetTabPane.getTabs().addAll(unrealizedTab, realizedTab);
        assetView.getChildren().add(assetTabPane);
    }

    public static void startApp(String[] args) {
        launch(args);
    }
}