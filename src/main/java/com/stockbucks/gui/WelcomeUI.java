package com.stockbucks.gui;

import atlantafx.base.theme.Styles;
import com.stockbucks.SaveData;
import com.stockbucks.SaveManager;
import com.stockbucks.User;
import com.stockbucks.api.mode.MarketMode;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import java.util.ArrayList;
import java.util.List;

public class WelcomeUI {

    private final java.util.Map<String, MarketMode> saveModeCache = new java.util.HashMap<>();
    private final Stage stage;
    private final StackPane rootContainer;
    private VBox mainMenuRoot;

    public WelcomeUI(Stage stage) {
        this.stage = stage;
        this.rootContainer = new StackPane();
        initUI(); 
    }

    private void initUI() {
        ImageView backgroundView = new ImageView();
        try {
            String imagePath = getClass().getResource("stonks-meme.gif").toExternalForm();
            backgroundView.setImage(new Image(imagePath));
        } catch (Exception e) {
            System.out.println("暫時找不到 GIF 背景圖，將使用純色備用背景。");
        }

        backgroundView.fitWidthProperty().bind(stage.widthProperty());
        backgroundView.fitHeightProperty().bind(stage.heightProperty());
        backgroundView.setPreserveRatio(false); 

        this.mainMenuRoot = createMainMenu();

        rootContainer.setStyle("-fx-background-color: #1c2128;"); 
        rootContainer.getChildren().addAll(backgroundView, mainMenuRoot);
    }

    // 顯示歡迎主選單
    public void show() {
        javafx.application.Application.setUserAgentStylesheet(new atlantafx.base.theme.PrimerDark().getUserAgentStylesheet());
        Scene scene = new Scene(rootContainer, 600, 450);
        stage.setScene(scene);
        stage.setTitle("StockBucks 模擬交易系統");
        stage.show();
        Platform.runLater(() -> {
            rootContainer.requestFocus();
        });
    }

    // 建立主選單畫面
    private VBox createMainMenu() {
        VBox root = new VBox(25);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(50));
        root.setStyle("-fx-background-color: rgba(28, 33, 40, 0.75);"); // 半透明遮罩，方便看清文字

        Label titleLabel = new Label("StockBucks");
        titleLabel.getStyleClass().add(Styles.TITLE_1);
        titleLabel.setStyle("-fx-text-fill: #adbac7; -fx-font-size: 36px; -fx-font-weight: bold;");

        Label subTitle = new Label("擬真股票交易與策略模擬工具");
        subTitle.getStyleClass().add(Styles.TEXT_MUTED);

        // ===== 點擊「開啟新市場」時跳出三選一對話框 =====
        Button newMarketBtn = new Button("開啟新市場");
        newMarketBtn.getStyleClass().addAll(Styles.LARGE, Styles.ACCENT);
        newMarketBtn.setPrefWidth(250);
        newMarketBtn.setOnAction(e -> {
            // 1. 建立對話框選項清單
            List<MarketMode> choices = new ArrayList<>();
            choices.add(MarketMode.REALTIME);
            choices.add(MarketMode.HISTORY);
            choices.add(MarketMode.AI_RANDOM);

            // 2. 初始化 ChoiceDialog，預設選中歷史高速模擬
            ChoiceDialog<MarketMode> dialog = new ChoiceDialog<>(MarketMode.HISTORY, choices);
            dialog.setTitle("選擇市場運行模式");
            dialog.setHeaderText("請選擇您要在 StockBucks 體驗的環境：");
            dialog.setContentText("運行模式：");
            
            // 讓對話框的外觀完美融入你的暗色系風格
            dialog.getDialogPane().setStyle("-fx-background-color: #1c2128;");

            // 3. 監聽點擊結果，當使用者按下確認時才建立新戰局
            dialog.showAndWait().ifPresent(chosenMode -> {
                SaveData newData = new SaveData(new User(), 0, "全新模擬", chosenMode);
                enterMainApp(newData);
            });
        }); 

        Button loadArchiveBtn = new Button("開啟舊模擬檔案");
        loadArchiveBtn.getStyleClass().addAll(Styles.LARGE, Styles.SUCCESS, Styles.BUTTON_OUTLINED);
        loadArchiveBtn.setPrefWidth(250);
        loadArchiveBtn.setOnAction(e -> switchToArchiveList());

        root.getChildren().addAll(titleLabel, subTitle, newMarketBtn, loadArchiveBtn);
        return root;
    }

    // 切換到內建的存檔選取清單畫面
    private void switchToArchiveList() {
        saveModeCache.clear();
        VBox archiveRoot = new VBox(15);
        archiveRoot.setAlignment(Pos.CENTER);
        archiveRoot.setPadding(new Insets(30));
        archiveRoot.setStyle("-fx-background-color: rgba(28, 33, 40, 0.85);");

        Label headerLabel = new Label("請選擇要載入的模擬檔案");
        headerLabel.getStyleClass().add(Styles.TITLE_3);
        headerLabel.setStyle("-fx-text-fill: #adbac7;");

        List<String> saveFiles = SaveManager.getSaveFiles();
        ListView<String> archiveListView = new ListView<>(FXCollections.observableArrayList(saveFiles));
        archiveListView.setPrefHeight(200);
        archiveListView.setMaxWidth(450);

        if (saveFiles.isEmpty()) {
            archiveListView.setPlaceholder(new Label("目前沒有任何模擬存檔"));
        }

        archiveListView.setCellFactory(param -> new ListCell<String>() {
            @Override
            protected void updateItem(String fileName, boolean empty) {
                super.updateItem(fileName, empty);
                if (empty || fileName == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    HBox cellLayout = new HBox(10);
                    cellLayout.setAlignment(Pos.CENTER_LEFT);

                    // 顯示存檔名稱
                    String displayName = fileName.replace(".dat", "").replace(".ser", "");
                    Label nameLabel = new Label(displayName);
                    nameLabel.setStyle("-fx-text-fill: #bec0c1; -fx-font-weight: bold;");

                    Label modeBadge = new Label("未知的模式");
                    
                    //快取黑魔法：如果記憶體有，直接拿；沒有，才讀硬碟並記錄
                    MarketMode mode = saveModeCache.get(fileName);
                    if (mode == null) {
                        SaveData temp = SaveManager.loadGame(fileName);
                        if (temp != null) {
                            mode = temp.getMarketMode();
                            saveModeCache.put(fileName, mode); // 塞入快取
                        }
                    }

                    // 根據模式渲染不同顏色的 GitHub 風格徽章 (Badge)
                    if (mode != null) {
                        switch (mode) {
                            case HISTORY:
                                modeBadge.setText("⏳ 歷史回測模式");
                                modeBadge.setStyle("-fx-background-color: #d29922; -fx-text-fill: #1c2128; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-size: 11px; -fx-font-weight: bold;");
                                break;
                            case REALTIME:
                                modeBadge.setText("🌐 真實即時模式");
                                modeBadge.setStyle("-fx-background-color: #388bfd; -fx-text-fill: white; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-size: 11px; -fx-font-weight: bold;");
                                break;
                            case AI_RANDOM:
                                modeBadge.setText("🤖 AI模擬盤模式");
                                modeBadge.setStyle("-fx-background-color: #56d364; -fx-text-fill: #1c2128; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-size: 11px; -fx-font-weight: bold;");
                                break;
                        }
                    } else {
                        modeBadge.setStyle("-fx-background-color: #484f58; -fx-text-fill: #8b949e; -fx-padding: 2 8; -fx-background-radius: 4; -fx-font-size: 11px;");
                    }

                    // 自動推開間距的彈簧
                    javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
                    HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

                    cellLayout.getChildren().addAll(nameLabel, spacer, modeBadge);
                    setGraphic(cellLayout);
                }
            }
        });

        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);

        Button backBtn = new Button("返回主選單");
        backBtn.getStyleClass().add(Styles.FLAT);
        backBtn.setOnAction(e -> {
            if (rootContainer.getChildren().size() > 1) {
                rootContainer.getChildren().set(1, mainMenuRoot);
            }
        });

        Button confirmLoadBtn = new Button("載入檔案");
        confirmLoadBtn.getStyleClass().add(Styles.SUCCESS);
        confirmLoadBtn.disableProperty().bind(archiveListView.getSelectionModel().selectedItemProperty().isNull());

        Button deleteBtn = new Button("刪除檔案");
        deleteBtn.getStyleClass().addAll(Styles.DANGER, Styles.BUTTON_OUTLINED);
        deleteBtn.disableProperty().bind(archiveListView.getSelectionModel().selectedItemProperty().isNull());
        
        confirmLoadBtn.setOnAction(e -> {
            String selectedFile = archiveListView.getSelectionModel().getSelectedItem();
            if (selectedFile != null) {
                SaveData loadedData = SaveManager.loadGame(selectedFile);
                if (loadedData != null) {
                    enterMainApp(loadedData);
                } else {
                    showErrorAlert("損壞的存檔", "該存檔檔案可能已損壞，無法載入。");
                }
            }
        });

        deleteBtn.setOnAction(e -> {
            String selectedFile = archiveListView.getSelectionModel().getSelectedItem();
            if (selectedFile != null && SaveManager.deleteGame(selectedFile)) {
                archiveListView.getItems().remove(selectedFile);
                saveModeCache.remove(selectedFile);
            }
        });

        btnBox.getChildren().addAll(backBtn, confirmLoadBtn, deleteBtn);
        archiveRoot.getChildren().addAll(headerLabel, archiveListView, btnBox);
        
        if (rootContainer.getChildren().size() > 1) {
            rootContainer.getChildren().set(1, archiveRoot);
        } else {
            rootContainer.getChildren().add(archiveRoot);
        }
    }

    private void enterMainApp(SaveData loadedData) {
        VBox loadingRoot = new VBox(20);
        loadingRoot.setAlignment(Pos.CENTER);
        loadingRoot.setPadding(new Insets(50));
        // 帶點神秘感的深色背景
        loadingRoot.setStyle("-fx-background-color: #1c2128;"); 

        // 1. 載入狀態提示字
        Label lblStatus = new Label("🤖 正在初始化 AI 投資助理...");
        lblStatus.getStyleClass().add(Styles.TITLE_3);
        lblStatus.setStyle("-fx-text-fill: #9ba9b9;");

        // 2. 絢麗的進度條 (使用 AtlantaFX 的圓角或進度條風格)
        ProgressBar progressBar = new ProgressBar(0.0);
        progressBar.setPrefWidth(350);
        progressBar.getStyleClass().add(Styles.SMALL); // 纖細精緻的進度條

        // 3. 隨機跳動的細部進度提示 (讓畫面看起來真的有在運算)
        Label lblSubStatus = new Label("正在建立安全連線...");
        lblSubStatus.getStyleClass().add(Styles.TEXT_MUTED);
        lblSubStatus.setStyle("-fx-font-size: 12px;");

        loadingRoot.getChildren().addAll(lblStatus, progressBar, lblSubStatus);

        // 4. 切換核心：把原本的主選單或存檔畫面拔掉，塞入載入畫面
        rootContainer.getChildren().remove(1); // 移除當前上層畫面
        rootContainer.getChildren().add(loadingRoot);

        // 5. 使用 Timeline 模擬動態載入進度與文字切換
        javafx.animation.Timeline loadingTimeline = new javafx.animation.Timeline();
        
        // 模擬 0.5 秒時的變化
        javafx.animation.KeyFrame kf1 = new javafx.animation.KeyFrame(javafx.util.Duration.millis(500), e -> {
            progressBar.setProgress(0.3);
            lblStatus.setText("📊 正在解析歷史市場數據...");
            lblSubStatus.setText("正在載入 CSV 股價矩陣與權重...");
        });

        // 模擬 1.2 秒時的變化
        javafx.animation.KeyFrame kf2 = new javafx.animation.KeyFrame(javafx.util.Duration.millis(1200), e -> {
            progressBar.setProgress(0.7);
            lblStatus.setText("⚡ 正在配置時脈引擎...");
            lblSubStatus.setText("動態變速拉桿對接中 (1x - 10x)...");
        });

        // 模擬 1.8 秒時的變化
        javafx.animation.KeyFrame kf3 = new javafx.animation.KeyFrame(javafx.util.Duration.millis(1800), e -> {
            progressBar.setProgress(0.95);
            lblStatus.setText("🚀 準備就緒！");
            lblSubStatus.setText("正在渲染終端交易介面...");
        });

        // 2.3 秒時，切換進入 MainApp
        javafx.animation.KeyFrame kfEnd = new javafx.animation.KeyFrame(javafx.util.Duration.millis(2300), e -> {
            MainApp mainApp = new MainApp();
            mainApp.setInitialSaveData(loadedData); 
            try {
                mainApp.start(stage); //真正啟動主程式
            } catch (Exception ex) {
                ex.printStackTrace();
                showErrorAlert("啟動失敗", "主模擬介面啟動時發生錯誤。");
            }
        });

        loadingTimeline.getKeyFrames().addAll(kf1, kf2, kf3, kfEnd);
        loadingTimeline.play();
    }

    private void showErrorAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}