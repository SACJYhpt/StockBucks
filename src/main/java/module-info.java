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

    exports com.stockbucks.ai;
    exports com.stockbucks.ai.assistant;
    exports com.stockbucks.ai.client;
    exports com.stockbucks.ai.mode;
    exports com.stockbucks.ai.model;
    exports com.stockbucks.ai.data;
}