package com.trafficflowx.controller;

import com.trafficflowx.model.Direction;
import com.trafficflowx.service.SimulationManager;
import com.trafficflowx.service.StatisticsManager;
import com.trafficflowx.util.SceneNavigator;
import javafx.animation.AnimationTimer;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;

import java.util.List;

/**
 * Controller for the statistics screen (statistics-view.fxml).
 * READS ONLY from SimulationManager; it starts nothing and stops nothing, so
 * viewing this screen never affects the running simulation.
 */
public class StatisticsController {

    private final SimulationManager manager = SimulationManager.getInstance();

    @FXML private Button backButton;
    @FXML private Button historyButton;

    @FXML private BarChart<String, Number> vehiclesByDirectionChart;
    @FXML private BarChart<String, Number> waitingTimeChart;

    @FXML private Label summaryLabel;
    @FXML private ListView<String> emergencyEventsList;
    @FXML private ListView<String> accidentHistoryList;

    /** Refreshes the numbers a few times a second. A chart doesn't need 60 fps. */
    private long lastRefreshNanos = 0;
    private static final long REFRESH_INTERVAL_NANOS = 400_000_000L;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long nowNanos) {
            if (nowNanos - lastRefreshNanos >= REFRESH_INTERVAL_NANOS) {
                lastRefreshNanos = nowNanos;
                refresh();
            }
        }
    };

    @FXML
    private void initialize() {
        vehiclesByDirectionChart.setLegendVisible(false);
        waitingTimeChart.setLegendVisible(false);
        refresh();
        timer.start();
    }

    private void refresh() {
        StatisticsManager stats = manager.getStatistics();

        summaryLabel.setText(String.format(
                "Total: %d   Passed: %d   Emergency: %d   Avg wait: %.1fs   Max wait: %.1fs   Accidents: %d   Road blocks: %d",
                stats.getTotalVehicles(), stats.getPassedVehicles(), stats.getEmergencyVehicles(),
                stats.getAverageWaitingSeconds(), stats.getMaxWaitingSeconds(),
                stats.getAccidentCount(), stats.getRoadBlockCount()));

        fillVehiclesChart(stats);
        fillWaitingChart(stats);
        fillHistory(stats);
    }

    private void fillVehiclesChart(StatisticsManager stats) {
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (Direction d : Direction.values()) {
            series.getData().add(new XYChart.Data<>(d.name(), stats.getVehiclesByDirection(d)));
        }
        vehiclesByDirectionChart.getData().setAll(series);
    }

    private void fillWaitingChart(StatisticsManager stats) {
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (Direction d : Direction.values()) {
            series.getData().add(new XYChart.Data<>(d.name(), stats.getAverageWaitingSeconds(d)));
        }
        waitingTimeChart.getData().setAll(series);
    }

    private void fillHistory(StatisticsManager stats) {
        setListOrPlaceholder(accidentHistoryList, stats.getIncidentHistory(),
                "(no accidents or road blocks yet)");
        setListOrPlaceholder(emergencyEventsList, stats.getEmergencyHistory(),
                "(no emergency events yet)");
    }

    /** Newest entries first, so the most recent event is always visible without scrolling. */
    private void setListOrPlaceholder(ListView<String> list, List<String> lines, String placeholder) {
        if (lines.isEmpty()) {
            list.setItems(FXCollections.observableArrayList(placeholder));
            return;
        }
        ObservableList<String> newestFirst = FXCollections.observableArrayList(lines);
        java.util.Collections.reverse(newestFirst);
        list.setItems(newestFirst);
    }

    @FXML
    private void onBackClicked() {
        timer.stop();
        SceneNavigator.switchTo(backButton, "simulation-view.fxml");
    }
    @FXML
    private void onHistoryClicked() {
        timer.stop();
        SceneNavigator.switchTo(backButton, "history-view.fxml");
    }
}