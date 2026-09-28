package com.trafficflowx.controller;

import com.trafficflowx.util.SceneNavigator;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;

/**
 * Controller for the welcome screen (main-view.fxml).
 * It only reacts to button clicks and changes screens.
 */
public class MainController {

    @FXML
    private Button startButton;

    @FXML
    private void onStartClicked() {
        SceneNavigator.switchTo(startButton, "simulation-view.fxml");
    }

    @FXML
    private void onAboutClicked() {
        SceneNavigator.switchTo(startButton, "about-view.fxml");
    }

    @FXML
    private void onExitClicked() {
        Platform.exit();
    }
}