package com.trafficflowx.controller;

import com.trafficflowx.model.SimulationRunRecord;
import com.trafficflowx.util.DatabaseManager;
import com.trafficflowx.util.SceneNavigator;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller for the history screen (history-view.fxml).
 * Shows every completed simulation run, loaded from SQLite. Reading the file is
 * blocking JDBC I/O, so it always happens on a background thread ('loader'),
 * never on the JavaFX Application Thread. Results come back through
 * Platform.runLater(), the same pattern used for status messages elsewhere.
 */
public class HistoryController {

    @FXML private Button backButton;
    @FXML private Button clearButton;
    @FXML private Label statusLabel;
    @FXML private TableView<SimulationRunRecord> historyTable;

    @FXML private TableColumn<SimulationRunRecord, String> dateColumn;
    @FXML private TableColumn<SimulationRunRecord, String> durationColumn;
    @FXML private TableColumn<SimulationRunRecord, String> densityColumn;
    @FXML private TableColumn<SimulationRunRecord, String> totalColumn;
    @FXML private TableColumn<SimulationRunRecord, String> passedColumn;
    @FXML private TableColumn<SimulationRunRecord, String> avgWaitColumn;
    @FXML private TableColumn<SimulationRunRecord, String> maxWaitColumn;
    @FXML private TableColumn<SimulationRunRecord, String> emergencyColumn;
    @FXML private TableColumn<SimulationRunRecord, String> accidentsColumn;
    @FXML private TableColumn<SimulationRunRecord, String> roadBlocksColumn;
    @FXML private TableColumn<SimulationRunRecord, String> conflictsColumn;
    @FXML private TableColumn<SimulationRunRecord, String> adaptiveColumn;

    // One background thread for this screen's own database reads, shut down when
    // the screen is left, so it never outlives the controller.
    private final ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "History-Loader");
        thread.setDaemon(true);
        return thread;
    });

    @FXML
    private void initialize() {
        dateColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().runDate()));
        durationColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().durationSeconds() + " s"));
        densityColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().trafficDensity()));
        totalColumn.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().totalVehicles())));
        passedColumn.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().passedVehicles())));
        avgWaitColumn.setCellValueFactory(cell -> new SimpleStringProperty(String.format("%.1f s", cell.getValue().averageWaitingSeconds())));
        maxWaitColumn.setCellValueFactory(cell -> new SimpleStringProperty(String.format("%.1f s", cell.getValue().maxWaitingSeconds())));
        emergencyColumn.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().emergencyVehicles())));
        accidentsColumn.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().accidents())));
        roadBlocksColumn.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().roadBlocks())));
        conflictsColumn.setCellValueFactory(cell -> new SimpleStringProperty(String.valueOf(cell.getValue().junctionConflicts())));
        adaptiveColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().adaptiveSignals() ? "Yes" : "No"));

        loadHistory();
    }

    private void loadHistory() {
        statusLabel.setText("Loading history...");
        loader.execute(() -> {
            try {
                List<SimulationRunRecord> runs = DatabaseManager.getAllRuns();
                Platform.runLater(() -> {
                    historyTable.setItems(FXCollections.observableArrayList(runs));
                    statusLabel.setText(runs.isEmpty()
                            ? "No completed runs yet. Start a simulation and press Reset to save one."
                            : runs.size() + " run(s) recorded.");
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> statusLabel.setText("Could not load history: " + e.getMessage()));
            }
        });
    }

    @FXML
    private void onClearClicked() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete all saved simulation history? This cannot be undone.", ButtonType.YES, ButtonType.NO);
        confirm.setHeaderText("Clear History");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.YES) {
            return;
        }
        statusLabel.setText("Clearing history...");
        loader.execute(() -> {
            try {
                DatabaseManager.clearAll();
                Platform.runLater(this::loadHistory);
            } catch (RuntimeException e) {
                Platform.runLater(() -> statusLabel.setText("Could not clear history: " + e.getMessage()));
            }
        });
    }

    @FXML
    private void onBackClicked() {
        loader.shutdownNow();
        SceneNavigator.switchTo(backButton, "statistics-view.fxml");
    }
}