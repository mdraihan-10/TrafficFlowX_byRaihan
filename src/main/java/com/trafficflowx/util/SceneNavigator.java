package com.trafficflowx.util;

import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;

import java.io.IOException;
import java.net.URL;

/**
 * Helper for switching between screens.
 * It loads an FXML file and puts it into the SAME Scene, so the window size
 * and the CSS stylesheet are kept.
 */
public final class SceneNavigator {

    private static final String BASE_PATH = "/com/trafficflowx/";

    private SceneNavigator() {
        // Utility class: no objects should be created.
    }

    /**
     * @param source   any node on the current screen (for example the clicked button)
     * @param fxmlFile file name only, for example "simulation-view.fxml"
     */
    public static void switchTo(Node source, String fxmlFile) {
        try {
            URL url = SceneNavigator.class.getResource(BASE_PATH + fxmlFile);
            if (url == null) {
                throw new IOException("FXML file not found: " + fxmlFile);
            }
            Parent root = new FXMLLoader(url).load();
            source.getScene().setRoot(root);
        } catch (IOException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Could not open screen '" + fxmlFile + "':\n" + e.getMessage());
            alert.setHeaderText("Navigation error");
            alert.showAndWait();
        }
    }
}