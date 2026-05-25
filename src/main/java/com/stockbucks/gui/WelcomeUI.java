package com.stockbucks.gui;

import atlantafx.base.theme.Styles;
import com.stockbucks.SaveData;
import com.stockbucks.SaveManager;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import java.util.List;

public class WelcomeUI {

    private final Stage stage;
    private final VBox mainMenuRoot;

    public WelcomeUI(Stage stage) {
        this.stage = stage;
        this.mainMenuRoot = createMainMenu();
    }

    /**
     * 顯示歡迎主選單
     */
    public void show() {
        Scene scene = new Scene(mainMenuRoot, 600, 450);
        stage.setScene(scene);
        stage.setTitle("StockBucks 模擬交易系統");
        stage.show();
    }

    /**
     * 建立主選單畫面
     */
    private VBox createMainMenu() {
        VBox root = new VBox(25);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(50));
        root.setStyle("-fx-background-color: #1c2128;"); // 保持與 MainApp 一致的暗色調

        Label titleLabel = new Label("StockBucks");
        titleLabel.getStyleClass().add(Styles.TITLE_1);
        titleLabel.setStyle("-fx-text-fill: #adbac7; -fx-font-size: 36px; -fx-font-weight: bold;");

        Label subTitle = new Label("擬真股票交易與策略模擬工具");
        subTitle.getStyleClass().add(Styles.TEXT_MUTED);

        Button newMarketBtn = new Button("開啟新市場");
        newMarketBtn.getStyleClass().addAll(Styles.LARGE, Styles.ACCENT);
        newMarketBtn.setPrefWidth(250);
        newMarketBtn.setOnAction(e -> enterMainApp(null)); // 傳入 null 代表全新開始

        Button loadArchiveBtn = new Button("開啟舊模擬檔案");
        loadArchiveBtn.getStyleClass().addAll(Styles.LARGE, Styles.SUCCESS, Styles.BUTTON_OUTLINED);
        loadArchiveBtn.setPrefWidth(250);
        loadArchiveBtn.setOnAction(e -> switchToArchiveList());

        root.getChildren().addAll(titleLabel, subTitle, newMarketBtn, loadArchiveBtn);
        return root;
    }

    /**
     * 切換到內建的存檔選取清單畫面
     */
    private void switchToArchiveList() {
        VBox archiveRoot = new VBox(15);
        archiveRoot.setAlignment(Pos.CENTER);
        archiveRoot.setPadding(new Insets(30));
        archiveRoot.setStyle("-fx-background-color: #1c2128;");

        Label headerLabel = new Label("請選擇要載入的模擬檔案");
        headerLabel.getStyleClass().add(Styles.TITLE_3);
        headerLabel.setStyle("-fx-text-fill: #adbac7;");

        // 讀取本地 saves/ 目錄下的所有 .dat 檔案
        List<String> saveFiles = SaveManager.getSaveFiles();
        ListView<String> archiveListView = new ListView<>(FXCollections.observableArrayList(saveFiles));
        archiveListView.setPrefHeight(200);
        archiveListView.setMaxWidth(400);

        // 如果沒有任何存檔的提示
        if (saveFiles.isEmpty()) {
            archiveListView.setPlaceholder(new Label("目前沒有任何模擬存檔"));
        }

        // 按鈕列
        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);

        Button backBtn = new Button("返回主選單");
        backBtn.getStyleClass().add(Styles.FLAT);
        backBtn.setOnAction(e -> stage.getScene().setRoot(mainMenuRoot));

        Button confirmLoadBtn = new Button("載入檔案");
        confirmLoadBtn.getStyleClass().add(Styles.SUCCESS);
        confirmLoadBtn.disableProperty().bind(archiveListView.getSelectionModel().selectedItemProperty().isNull());
        
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

        btnBox.getChildren().addAll(backBtn, confirmLoadBtn);
        archiveRoot.getChildren().addAll(headerLabel, archiveListView, btnBox);
        
        // 直接替換當前場景的 Root 節點，視覺流暢不閃爍
        stage.getScene().setRoot(archiveRoot);
    }

    /**
     * 跳轉進入主模擬程式
     */
    private void enterMainApp(SaveData loadedData) {
        MainApp mainApp = new MainApp();
        // 將載入的數據（或 null）注入 MainApp
        mainApp.setInitialSaveData(loadedData); 
        try {
            mainApp.start(stage);
        } catch (Exception e) {
            e.printStackTrace();
            showErrorAlert("啟動失敗", "主模擬介面啟動時發生錯誤。");
        }
    }

    private void showErrorAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}