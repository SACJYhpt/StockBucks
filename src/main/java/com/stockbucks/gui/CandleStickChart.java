package com.stockbucks.gui;

import javafx.collections.FXCollections;
import javafx.scene.chart.Axis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/**
 * 簡易自定義 K 線圖元件
 */
public class CandleStickChart extends XYChart<Number, Number> {

    public CandleStickChart(Axis<Number> xAxis, Axis<Number> yAxis) {
        super(xAxis, yAxis);
        setData(FXCollections.observableArrayList()); 
        setAnimated(false); // 關閉動畫以增加即時模擬的效能
    }

    @Override
    protected void layoutPlotChildren() {
        for (int seriesIndex = 0; seriesIndex < getData().size(); seriesIndex++) {
            Series<Number, Number> series = getData().get(seriesIndex);
            for (Data<Number, Number> data : series.getData()) {
                
                // 這裡我們約定：data.getExtraValue() 會傳入一個 double[] 陣列
                // 包含 [open, close, high, low]
                if (data.getExtraValue() instanceof double[]) {
                    double[] ohlc = (double[]) data.getExtraValue();
                    double open = ohlc[0];
                    double close = ohlc[1];
                    double high = ohlc[2];
                    double low = ohlc[3];

                    double x = getXAxis().getDisplayPosition(data.getXValue());
                    double yOpen = getYAxis().getDisplayPosition(open);
                    double yClose = getYAxis().getDisplayPosition(close);
                    double yHigh = getYAxis().getDisplayPosition(high);
                    double yLow = getYAxis().getDisplayPosition(low);

                    // 1. 繪製影線 (High to Low)
                    Line wick = (Line) data.getNode().lookup(".candle-wick");
                    if (wick == null) {
                        wick = new Line();
                        wick.getStyleClass().add("candle-wick");
                        getPlotChildren().add(wick);
                    }
                    wick.setStartX(x); wick.setEndX(x);
                    wick.setStartY(yHigh); wick.setEndY(yLow);
                    wick.setStroke(close >= open ? Color.web("#ef5350") : Color.web("#26a69a"));

                    // 2. 繪製蠟燭實體 (Open to Close)
                    Rectangle body = (Rectangle) data.getNode().lookup(".candle-body");
                    if (body == null) {
                        body = new Rectangle();
                        body.getStyleClass().add("candle-body");
                        getPlotChildren().add(body);
                    }
                    double height = Math.abs(yClose - yOpen);
                    body.setX(x - 5); // 蠟燭寬度為 10
                    body.setY(Math.min(yOpen, yClose));
                    body.setWidth(10);
                    body.setHeight(Math.max(height, 1)); // 確保平盤時仍有一條線
                    body.setFill(close >= open ? Color.web("#ef5350") : Color.web("#26a69a"));
                }
            }
        }
    }

    @Override protected void dataItemAdded(Series<Number, Number> series, int itemIndex, Data<Number, Number> item) {
        item.setNode(new Region()); // 建立一個空節點作為容器
        getPlotChildren().add(item.getNode());
    }
    @Override protected void dataItemRemoved(Data<Number, Number> item, Series<Number, Number> series) { getPlotChildren().remove(item.getNode()); }
    @Override protected void dataItemChanged(Data<Number, Number> item) {}
    @Override protected void seriesAdded(Series<Number, Number> series, int seriesIndex) {}
    @Override protected void seriesRemoved(Series<Number, Number> series) {}
}