package com.stockbucks.gui;

import com.stockbucks.StockData;
import com.stockbucks.TradeRecord;
import com.stockbucks.CsvLoading;
import com.stockbucks.PriceSimulator;
import com.stockbucks.User;
import com.stockbucks.TradingEngine;

import java.util.List;
import java.util.ArrayList;
import javafx.util.Duration;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;

public class MainController {

    @FXML
    private TableView <TradeRecord> historyTable;

    @FXML
    private Label currentPriceLabel;

    private ObservableList <TradeRecord> observableRecords = FXCollections.observableArrayList();
    private CsvLoading csvLoader = new CsvLoading();
    private PriceSimulator simulator = new PriceSimulator();

    private User user;
    private TradingEngine tradingEngine;

    private List <StockData> historyData = new ArrayList<>();
    private int dayIndex = 0;
    private Timeline timeline;

    public void initBackend(User user, TradingEngine tradingEngine) {
        this.user = user;
        this.tradingEngine = tradingEngine;
        this.historyData.clear();
        csvLoader.streamStockData("TestDataTSMC", data -> {
            historyData.add(data);
        });
    }

    @FXML
    private void handleTestAdd() {
        // addRecordToUI(new TradeRecord("2330", "2026/05/04", "買入", 805.0, 1000, 1147, 0, 806147.0));
    }

    @FXML
    public void initialize() {
        historyTable.setItems(observableRecords);
        TableColumn <TradeRecord, String> dateCol = new TableColumn<>("交易日期");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));

        TableColumn <TradeRecord, String> idCol = new TableColumn<>("股票代號");
        idCol.setCellValueFactory(new PropertyValueFactory<>("stockID"));

        TableColumn <TradeRecord, String> typeCol = new TableColumn<>("操作");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));

        TableColumn <TradeRecord, Double> priceCol = new TableColumn<>("成交價格");
        priceCol.setCellValueFactory(new PropertyValueFactory<>("price"));

        TableColumn <TradeRecord, Integer> sharesCol = new TableColumn<>("成交股數");
        sharesCol.setCellValueFactory(new PropertyValueFactory<>("shares"));

        TableColumn <TradeRecord, Double> commissionCol = new TableColumn<>("手續費");
        commissionCol.setCellValueFactory(new PropertyValueFactory<>("commission"));

        TableColumn <TradeRecord, Double> taxCol = new TableColumn<>("證交稅");
        taxCol.setCellValueFactory(new PropertyValueFactory<>("tax"));

        TableColumn <TradeRecord, Double> totalCostCol = new TableColumn<>("成交額");
        totalCostCol.setCellValueFactory(new PropertyValueFactory<>("totalCost"));

        historyTable.getColumns().add(dateCol);
        historyTable.getColumns().add(idCol);
        historyTable.getColumns().add(typeCol);
        historyTable.getColumns().add(priceCol);
        historyTable.getColumns().add(sharesCol);
        historyTable.getColumns().add(commissionCol);
        historyTable.getColumns().add(taxCol);
        historyTable.getColumns().add(totalCostCol);

        historyTable.setItems(observableRecords);
    }

    public void addRecordToUI(TradeRecord newRecord) {
        observableRecords.add(newRecord);
        historyTable.scrollTo(newRecord);
    }

    @FXML
    private void handleStartSimulation() {
        if (historyData == null || dayIndex >= historyData.size()) {
            return;
        }

        StockData today = historyData.get(dayIndex);
        double yesterdayClose = (dayIndex == 0) ? today.getOpen() : historyData.get(dayIndex-1).getClose();

        simulator.generateDayPath(today, yesterdayClose);

        if (timeline != null) {
            timeline.stop();
        }

        timeline = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            double currentPrice = simulator.getNextPrice();
            if (currentPrice != -1) {
                currentPriceLabel.setText("當前市價: "+String.format("%.2f", currentPrice));
            }
            else {
                timeline.stop();
                dayIndex++;
            }
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }
}