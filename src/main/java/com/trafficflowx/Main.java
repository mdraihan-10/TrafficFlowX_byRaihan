package com.trafficflowx;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import com.trafficflowx.service.SimulationManager;

/**
 * Entry point of TrafficFlowX.
 * JavaFX calls start() on the JavaFX Application Thread after launch().
 */
public class Main extends Application {

    @Override
    public void start(Stage stage) throws IOException {
        URL fxml = Main.class.getResource("/com/trafficflowx/main-view.fxml");
        if (fxml == null) {
            throw new IOException("main-view.fxml not found. Check the resources folder path.");
        }

        Scene scene = new Scene(new FXMLLoader(fxml).load(), 1200, 750);

        URL css = Main.class.getResource("/com/trafficflowx/style.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }

        stage.setTitle("TrafficFlowX - Intelligent Traffic Intersection Simulator");
        stage.setScene(scene);
        stage.setMinWidth(1000);
        stage.setMinHeight(650);
        stage.show();
    }
    /** Called by JavaFX when the window closes. Makes sure no thread keeps running. */
    @Override
    public void stop() {
        SimulationManager.getInstance().shutdown();
    }

    public static void main(String[] args) {
        launch(args);
    }
}