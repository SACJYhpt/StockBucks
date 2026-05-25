package com.stockbucks;

import com.stockbucks.gui.WelcomeUI;
import javafx.application.Application;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage primaryStage) {
        System.out.println("正在啟動 StockBucks 系統...");
        // 實例化歡迎介面並呈現
        WelcomeUI welcomeUI = new WelcomeUI(primaryStage);
        welcomeUI.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}