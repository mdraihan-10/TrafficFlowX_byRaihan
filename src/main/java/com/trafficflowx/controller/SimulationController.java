package com.trafficflowx.controller;

import com.trafficflowx.model.Direction;
import com.trafficflowx.model.TrafficDensity;
import com.trafficflowx.service.SignalController;
import com.trafficflowx.service.SimulationManager;
import com.trafficflowx.service.StatisticsManager;
import com.trafficflowx.util.SceneNavigator;
import com.trafficflowx.view.IntersectionView;
import com.trafficflowx.view.VehicleRenderer;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import com.trafficflowx.model.Intersection;
import com.trafficflowx.model.SignalState;
import javafx.scene.control.CheckBox;
import com.trafficflowx.model.TrafficSnapshot;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import com.trafficflowx.model.SignalPhase;
import java.util.List;
import java.util.stream.Collectors;
import javafx.animation.FadeTransition;
import javafx.util.Duration;


/**
 * Controller for the main simulation screen (simulation-view.fxml).
 * GUI ONLY: it forwards button clicks to SimulationManager and draws what
 * the manager reports. All simulation work happens on background threads.
 */
public class SimulationController {

    private final SimulationManager manager = SimulationManager.getInstance();

    // ---- Intersection area ----
    @FXML private StackPane intersectionPane;
    private IntersectionView intersectionView;
    private VehicleRenderer renderer;
    // ---- Asynchronous traffic snapshot (Callable + Future) ----
    private static final long SNAPSHOT_INTERVAL_NANOS = 500_000_000L;   // twice per second
    private Future<TrafficSnapshot> pendingSnapshot;
    private long lastSnapshotRequestNanos;

    // ---- Top bar ----
    @FXML private Button backButton;
    @FXML private Label emergencyBanner;
    private FadeTransition emergencyPulse;
    @FXML private Label signalLabel;

    // ---- Selectors ----
    @FXML private ComboBox<String> densityBox;
    @FXML private ComboBox<String> directionBox;
    @FXML private CheckBox ignoreSignalsCheck;
    @FXML private CheckBox unsafeModeCheck;
    @FXML private CheckBox adaptiveCheck;
    // ---- Statistics labels ----
    @FXML private Label totalVehiclesLabel;
    @FXML private Label passedVehiclesLabel;
    @FXML private Label waitingVehiclesLabel;
    @FXML private Label emergencyVehiclesLabel;
    @FXML private Label avgWaitLabel;
    @FXML private Label maxWaitLabel;
    @FXML private Label accidentsLabel;
    @FXML private Label roadBlocksLabel;
    @FXML private Label densityLabel;
    @FXML private Label conflictsLabel;
    @FXML private Label queuesLabel;
    @FXML private Label greenTimesLabel;

    // ---- Status bar ----
    @FXML private Label statusLabel;

    /** Runs about 60 times per second on the JavaFX Application Thread. It only READS and DRAWS. */
    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long nowNanos) {
            refreshScreen(nowNanos);
        }
    };

    /** Called automatically by JavaFX after the FXML is loaded. */
    @FXML
    private void initialize() {
        intersectionView = new IntersectionView();
        renderer = new VehicleRenderer(intersectionView);
        intersectionPane.getChildren().add(intersectionView);

        // Background threads need the road size, but must not touch the view,
        // so we copy the size into IntersectionGeometry whenever it changes.
        intersectionView.widthProperty().addListener((obs, oldValue, newValue) -> updateGeometry());
        intersectionView.heightProperty().addListener((obs, oldValue, newValue) -> updateGeometry());
        emergencyPulse = new FadeTransition(Duration.seconds(0.6), emergencyBanner);
        emergencyPulse.setFromValue(1.0);
        emergencyPulse.setToValue(0.55);
        emergencyPulse.setCycleCount(FadeTransition.INDEFINITE);
        emergencyPulse.setAutoReverse(true);

        densityBox.setItems(FXCollections.observableArrayList("LOW", "MEDIUM", "HIGH"));
        densityBox.setValue(manager.getDensity().name());
        directionBox.setItems(FXCollections.observableArrayList("NORTH", "SOUTH", "EAST", "WEST"));
        directionBox.setValue("NORTH");
        ignoreSignalsCheck.setSelected(manager.areSignalsIgnored());
        unsafeModeCheck.setSelected(manager.getIntersection().getMode() == Intersection.Mode.UNSAFE);
        adaptiveCheck.setSelected(manager.getSignalController().isAdaptive());

        // Messages arrive on a BACKGROUND thread, so hop to the JavaFX thread first.
        manager.setStatusListener(message -> Platform.runLater(() -> setStatus(message)));

        setStatus(manager.getState() == SimulationManager.State.IDLE
                ? "Ready. Press Start Simulation."
                : "Simulation is " + manager.getState() + ".");
        timer.start();
    }

    private void updateGeometry() {
        manager.getGeometry().setSize(intersectionView.getWidth(), intersectionView.getHeight());
    }

    // ------------------------------------------------------------------
    // One frame: read the state, draw it
    // ------------------------------------------------------------------

    private void refreshScreen(long nowNanos) {
        renderer.sync(manager.getAllVehicles());
        syncLights();
        syncIncidents();
        updateSignalLabel();
        updateEmergencyBanner();
        updateStatisticsLabels();
        pollTrafficSnapshot(nowNanos);
    }
    /**
     * Asks the analysis thread for a snapshot and picks up the result later.
     * isDone() never blocks, so the JavaFX thread is never made to wait.
     */
    private void pollTrafficSnapshot(long nowNanos) {
        if (pendingSnapshot == null) {
            if (nowNanos - lastSnapshotRequestNanos >= SNAPSHOT_INTERVAL_NANOS) {
                lastSnapshotRequestNanos = nowNanos;
                try {
                    pendingSnapshot = manager.requestSnapshot();
                } catch (RejectedExecutionException e) {
                    setStatus("Traffic analysis is not available: " + e.getMessage());
                }
            }
            return;
        }
        if (!pendingSnapshot.isDone()) {
            return;   // still being calculated: check again next frame
        }
        try {
            showQueues(pendingSnapshot.get());   // already done, so get() returns immediately
        } catch (ExecutionException e) {
            setStatus("Traffic snapshot failed: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pendingSnapshot = null;
    }

    private void showQueues(TrafficSnapshot snapshot) {
        queuesLabel.setText(String.format("%d / %d / %d / %d",
                snapshot.waiting(Direction.NORTH),
                snapshot.waiting(Direction.SOUTH),
                snapshot.waiting(Direction.EAST),
                snapshot.waiting(Direction.WEST)));
    }

    private void syncLights() {
        for (Direction d : Direction.values()) {
            // With the lights ignored (demo), show all four as YELLOW = "out of service".
            SignalState shown = manager.areSignalsIgnored()
                    ? SignalState.YELLOW
                    : manager.getSignalController().getLight(d).getState();
            intersectionView.setSignal(d, shown);
        }
    }
    /** Copies each road's status (model) onto the drawing (view). The view ignores unchanged values. */
    private void syncIncidents() {
        for (Direction d : Direction.values()) {
            intersectionView.setRoadStatus(d, manager.getRoadStatus(d));
        }
    }

    private void updateSignalLabel() {
        SignalController signals = manager.getSignalController();
        if (!signals.isActive()) {
            signalLabel.setText("Signals: off");
            return;
        }
        SignalPhase phase = signals.getPhase();
        String paused = manager.getState() == SimulationManager.State.PAUSED ? " [paused]" : "";
        if (phase.isEmergency()) {
            // An emergency green has no fixed length: it lasts until the vehicle has crossed.
            signalLabel.setText("Signals: " + phase.getDisplayName() + " (held)" + paused);
        } else {
            signalLabel.setText(String.format("Signals: %s (%.0fs)%s",
                    phase.getDisplayName(),
                    Math.ceil(signals.getSecondsRemaining()),
                    paused));
        }
    }
    /** Shows the red banner while any road has an emergency vehicle that has not yet crossed. */
    /** Shows or hides the pulsing red banner while any road has an emergency vehicle. */
    private void updateEmergencyBanner() {
        List<Direction> approaching = manager.getSignalController().getEmergencyDirections();
        boolean show = !approaching.isEmpty();

        if (show) {
            String names = approaching.stream().map(Direction::name).collect(Collectors.joining(", "));
            String text = "🚨 EMERGENCY VEHICLE APPROACHING — " + names;
            if (!text.equals(emergencyBanner.getText())) {
                emergencyBanner.setText(text);
            }
        }
        if (show != emergencyBanner.isVisible()) {
            emergencyBanner.setVisible(show);
            emergencyBanner.setManaged(show);
            if (show) {
                emergencyPulse.playFromStart();
            } else {
                emergencyPulse.stop();
                emergencyBanner.setOpacity(1.0);
            }
        }
    }

    private void updateStatisticsLabels() {
        StatisticsManager stats = manager.getStatistics();
        totalVehiclesLabel.setText(String.valueOf(stats.getTotalVehicles()));
        passedVehiclesLabel.setText(String.valueOf(stats.getPassedVehicles()));
        waitingVehiclesLabel.setText(String.valueOf(manager.getWaitingCount()));
        emergencyVehiclesLabel.setText(String.valueOf(stats.getEmergencyVehicles()));
        densityLabel.setText(manager.getDensity().getDisplayName());
        int conflicts = manager.getIntersection().getConflictCount();
        conflictsLabel.setText(String.valueOf(conflicts));
        conflictsLabel.setStyle(conflicts > 0 ? "-fx-text-fill: #ff6b6b;" : "");
        SignalController signals = manager.getSignalController();
        greenTimesLabel.setText(String.format("%.0f s / %.0f s",
                signals.getLastGreenNorthSouth(), signals.getLastGreenEastWest()));
        accidentsLabel.setText(String.valueOf(stats.getAccidentCount()));
        roadBlocksLabel.setText(String.valueOf(stats.getRoadBlockCount()));
        avgWaitLabel.setText(String.format("%.1f sec", stats.getAverageWaitingSeconds()));
        maxWaitLabel.setText(String.format("%.1f sec", stats.getMaxWaitingSeconds()));
    }

    // ---------- Simulation buttons ----------
    @FXML
    private void onStartClicked() {
        try {
            manager.start();
            setStatus("Simulation started (4 road tasks + 1 signal task).");
        } catch (IllegalStateException e) {
            setStatus(e.getMessage());
        }
    }

    @FXML
    private void onPauseClicked() {
        try {
            manager.pause();
            setStatus("Simulation paused.");
        } catch (IllegalStateException e) {
            setStatus(e.getMessage());
        }
    }

    @FXML
    private void onResumeClicked() {
        try {
            manager.resume();
            setStatus("Simulation resumed.");
        } catch (IllegalStateException e) {
            setStatus(e.getMessage());
        }
    }

    @FXML
    private void onResetClicked() {
        manager.reset();
        renderer.clear();
        setStatus("Simulation reset.");
    }

    // ---------- Event buttons ----------
    @FXML
    private void onEmergencyClicked() {
        Direction direction = Direction.valueOf(directionBox.getValue());
        try {
            if (!manager.spawnEmergency(direction)) {
                setStatus("Road " + direction + " is full right now. Try again in a moment.");
            }
        } catch (IllegalStateException e) {
            setStatus(e.getMessage());
        }
    }

    @FXML
    private void onAccidentClicked() {
        Direction direction = selectedDirection();
        perform(() -> manager.simulateAccident(direction));
    }

    @FXML
    private void onClearAccidentClicked() {
        Direction direction = selectedDirection();
        perform(() -> manager.clearAccident(direction));
    }

    @FXML
    private void onRoadBlockClicked() {
        Direction direction = selectedDirection();
        perform(() -> manager.blockRoad(direction));
    }

    @FXML
    private void onClearRoadBlockClicked() {
        Direction direction = selectedDirection();
        perform(() -> manager.clearRoadBlock(direction));
    }

    @FXML
    private void onDensityChanged() {
        TrafficDensity density = TrafficDensity.valueOf(densityBox.getValue());
        manager.setDensity(density);
        setStatus("Traffic density set to " + density.getDisplayName());
    }
    @FXML
    private void onIgnoreSignalsChanged() {
        boolean ignored = ignoreSignalsCheck.isSelected();
        manager.setSignalsIgnored(ignored);
        setStatus(ignored
                ? "Traffic lights ignored: only the intersection guard controls the junction."
                : "Traffic lights are in control again.");
    }

    @FXML
    private void onSafetyModeChanged() {
        boolean unsafe = unsafeModeCheck.isSelected();
        manager.getIntersection().setMode(unsafe ? Intersection.Mode.UNSAFE : Intersection.Mode.SAFE);
        setStatus(unsafe
                ? "UNSAFE mode: no synchronization. Crossing vehicles may collide in the junction."
                : "SAFE mode: the junction is synchronized.");
    }
    @FXML
    private void onAdaptiveChanged() {
        boolean adaptive = adaptiveCheck.isSelected();
        manager.getSignalController().setAdaptive(adaptive);
        setStatus(adaptive
                ? "Adaptive signals ON: the green time depends on the queue length."
                : "Fixed signals: every green lasts the same time.");
    }

    // ---------- Navigation ----------
    @FXML
    private void onStatisticsClicked() {
        // The simulation keeps running in the background while we look at the statistics.
        leaveScreen();
        SceneNavigator.switchTo(backButton, "statistics-view.fxml");
    }

    @FXML
    private void onBackClicked() {
        // Going back to the welcome screen ends the simulation.
        leaveScreen();
        manager.reset();
        SceneNavigator.switchTo(backButton, "main-view.fxml");
    }

    private void leaveScreen() {
        timer.stop();
        manager.setStatusListener(null);
        if (pendingSnapshot != null) {
            pendingSnapshot.cancel(true);   // nobody will read the result any more
            pendingSnapshot = null;
        }
    }

    // ---------- Helpers ----------
    private void setStatus(String message) {
        statusLabel.setText(message);
    }
    private Direction selectedDirection() {
        return Direction.valueOf(directionBox.getValue());
    }

    /** Runs a manager action and shows its error message (if any) in the status bar. */
    private void perform(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException e) {
            setStatus(e.getMessage());
        }
    }



}