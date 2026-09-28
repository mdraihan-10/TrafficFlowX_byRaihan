package com.trafficflowx.controller;

import com.trafficflowx.util.SceneNavigator;
import javafx.fxml.FXML;
import javafx.scene.control.Button;

/** Controller for the About screen (about-view.fxml). */
public class AboutController {

    @FXML private Button backButton;

    @FXML
    private void onBackClicked() {
        SceneNavigator.switchTo(backButton, "main-view.fxml");
    }
}