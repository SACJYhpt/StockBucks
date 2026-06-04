module com.stockbucks {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires java.net.http;
    requires atlantafx.base;
    requires transitive javafx.graphics;

    opens com.stockbucks.gui to javafx.graphics, javafx.base;
    opens com.stockbucks to javafx.base;

    exports com.stockbucks;
    exports com.stockbucks.gui;

    exports com.stockbucks.api;
    exports com.stockbucks.api.ai;
    exports com.stockbucks.api.config;
    exports com.stockbucks.api.mode;
    exports com.stockbucks.api.stock;
    exports com.stockbucks.api.debug;
}