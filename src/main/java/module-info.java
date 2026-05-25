module com.stockbucks {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires atlantafx.base;

    opens com.stockbucks.gui to javafx.graphics, javafx.base;

    exports com.stockbucks;
    opens com.stockbucks to javafx.base; // 讓 TableView 能讀取你的實體類

    exports com.stockbucks.gui;
}