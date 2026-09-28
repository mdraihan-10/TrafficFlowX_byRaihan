package com.trafficflowx.service;

import com.trafficflowx.model.Direction;
import com.trafficflowx.model.Intersection;
import com.trafficflowx.model.IntersectionGeometry;
import com.trafficflowx.model.Road;
import com.trafficflowx.model.RoadStatus;
import com.trafficflowx.model.TrafficDensity;
import com.trafficflowx.model.Vehicle;
import com.trafficflowx.model.VehicleType;
import com.trafficflowx.util.SimulationConfig;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.trafficflowx.model.Movement;
import com.trafficflowx.model.SimulationRunRecord;
import com.trafficflowx.util.DatabaseManager;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The single entry point the GUI uses to control the simulation.
 * It owns the roads, the signals, the counters and two thread pools:
 *
 *   simulationPool   - fixed pool: 4 road tasks + 1 signal task (endless loops).
 *                      Created on every start(), shut down on every reset().
 *   analysisExecutor - one thread that runs short Callables (traffic snapshots).
 *                      Lives as long as the application.
 *
 * It contains NO JavaFX code, and it lives outside the controllers, so the
 * simulation keeps running while the user switches screens.
 *
 * Lifecycle:  IDLE --start()--> RUNNING <--pause()/resume()--> PAUSED
 *             any state --reset()--> IDLE
 */
public class SimulationManager {

    public enum State { IDLE, RUNNING, PAUSED }

    private static final VehicleType[] EMERGENCY_TYPES =
            {VehicleType.AMBULANCE, VehicleType.FIRE_TRUCK, VehicleType.POLICE_CAR};

    // ---- Singleton: one manager for the whole application ----
    private static final class Holder {
        private static final SimulationManager INSTANCE = new SimulationManager();
    }

    public static SimulationManager getInstance() {
        return Holder.INSTANCE;
    }

    // ---- Shared objects ----
    private final Map<Direction, Road> roads = new EnumMap<>(Direction.class);   // filled once, then only read
    private final SignalController signalController = new SignalController();
    private final StatisticsManager statistics = new StatisticsManager();
    private final PauseControl pauseControl = new PauseControl();
    private final IntersectionGeometry geometry = new IntersectionGeometry();
    private final Intersection intersection;

    // ---- Thread pools ----
    // An ExecutorService cannot be restarted after shutdown, so the simulation
    // pool is created again in every start().
    private ExecutorService simulationPool;   // guarded by 'this'
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor(namedFactory("Analysis"));
    // One thread for all database I/O (saving and, from HistoryController, reading runs
    // that were just saved). Blocking JDBC calls never run on the JavaFX or simulation threads.
    private final ExecutorService historyExecutor = Executors.newSingleThreadExecutor(namedFactory("History"));

    private static final DateTimeFormatter RUN_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ---- volatile: written by one thread, read by others ----
    private volatile State state = State.IDLE;
    private volatile TrafficDensity density = TrafficDensity.MEDIUM;
    private volatile boolean signalsIgnored = false;// demo switch: vehicles ignore the lights
    private volatile long startTimeMillis;
    private volatile Consumer<String> statusListener = message -> { };

    private SimulationManager() {
        this.intersection = new Intersection(this::announce);
        for (Direction d : Direction.values()) {
            roads.put(d, new Road(d));
        }
        // The signal controller counts the queues through the same class that the GUI uses.
        // Here we call it directly (not through the executor), because the decision must be
        // made at exactly the moment the green starts.
        signalController.setTrafficSource(() -> new TrafficSnapshotTask(roads).call());
        // Creating the table here (not lazily) means the first save never has to wait for it.
        try {
            DatabaseManager.init();
        } catch (RuntimeException e) {
            e.printStackTrace();   // history is a nice-to-have; a DB problem must not block the app
        }
    }
    // Creating the table here (not lazily) means the first save never has to wait for it.

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Creates a pool of 5 worker threads and gives each one a long-running task. */
    public synchronized void start() {
        if (state != State.IDLE) {
            throw new IllegalStateException("The simulation is already running.");
        }
        statistics.reset();
        startTimeMillis = System.currentTimeMillis();
        intersection.reset();
        pauseControl.reset();
        for (Road road : roads.values()) {
            road.clear();
            road.setStatus(RoadStatus.OPEN);
        }
        signalController.start();

        // The pool must have AT LEAST as many threads as there are endless tasks.
        // With fewer, the extra tasks would wait in the queue forever and never start.
        int taskCount = Direction.values().length + 1;   // 4 roads + 1 signal
        simulationPool = Executors.newFixedThreadPool(taskCount, namedFactory("Sim"));

        state = State.RUNNING;
        for (Direction d : Direction.values()) {
            launch("Traffic-" + d, new DirectionTask(d, this));
        }
        launch("Signal-Controller", new SignalTask(this));
    }


    public synchronized void pause() {
        if (state != State.RUNNING) {
            throw new IllegalStateException("Nothing to pause: the simulation is not running.");
        }
        pauseControl.pause();
        state = State.PAUSED;
    }

    public synchronized void resume() {
        if (state != State.PAUSED) {
            throw new IllegalStateException("The simulation is not paused.");
        }
        pauseControl.resume();
        state = State.RUNNING;
    }

    /** Stops all tasks and returns everything to the initial state. Safe to call repeatedly. */
    /** Stops all tasks and returns everything to the initial state. Safe to call repeatedly. */
    public synchronized void reset() {
        if (state != State.IDLE) {
            stopSimulationPool();
        }
        // Save BEFORE clearing the statistics, and only if the run actually had traffic,
        // so an accidental Reset on an empty simulation doesn't clutter the history.
        if (statistics.getTotalVehicles() > 0) {
            saveRunToHistory();
        }
        signalController.stop();
        for (Road road : roads.values()) {
            road.clear();
            road.setStatus(RoadStatus.OPEN);
        }
        statistics.reset();
        intersection.reset();
        pauseControl.reset();
        state = State.IDLE;
    }

    /** Builds a summary of the just-finished run and saves it on the history thread. */
    private void saveRunToHistory() {
        long durationSeconds = Math.max(0, (System.currentTimeMillis() - startTimeMillis) / 1000);
        SimulationRunRecord record = new SimulationRunRecord(
                -1,
                LocalDateTime.now().format(RUN_DATE_FORMAT),
                durationSeconds,
                density.getDisplayName(),
                statistics.getTotalVehicles(),
                statistics.getPassedVehicles(),
                statistics.getAverageWaitingSeconds(),
                statistics.getMaxWaitingSeconds(),
                statistics.getEmergencyVehicles(),
                statistics.getAccidentCount(),
                statistics.getRoadBlockCount(),
                intersection.getConflictCount(),
                signalController.isAdaptive());

        historyExecutor.execute(() -> {
            try {
                DatabaseManager.insertRun(record);
            } catch (RuntimeException e) {
                announce("Could not save run to history: " + e.getMessage());
            }
        });
    }

    /**
     * Called when the application closes: stop the simulation, then close the
     * analysis executor. After this the manager cannot be used again.
     */
    /**
     * Called when the application closes: stop the simulation, then close the
     * analysis and history executors. After this the manager cannot be used again.
     */
    public void shutdown() {
        reset();
        shutdownPolitely(analysisExecutor);
        shutdownPolitely(historyExecutor);
    }

    /** shutdown() = accept no new tasks, let running ones finish; then wait, with a timeout. */
    private void shutdownPolitely(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SimulationConfig.JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * shutdownNow() interrupts every worker (our tasks end when they see
     * InterruptedException). awaitTermination() then waits until every worker has
     * really finished. Tasks never ask for this manager's lock, so waiting here
     * cannot deadlock.
     */
    private void stopSimulationPool() {
        ExecutorService pool = simulationPool;
        simulationPool = null;
        if (pool == null) {
            return;
        }
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(SimulationConfig.JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                announce("Warning: some simulation tasks did not stop in time.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Hands a task to the pool. RuntimeExceptions are reported, never swallowed silently. */
    private void launch(String taskName, Runnable task) {
        simulationPool.execute(() -> {
            // Pool threads are generic, so give the thread the task's name (useful when debugging).
            Thread.currentThread().setName(taskName);
            try {
                task.run();
            } catch (RuntimeException error) {
                error.printStackTrace();
                announce("ERROR in " + taskName + ": " + error);
            }
        });
    }

    /** Names the pool's threads and makes them daemon threads (they never keep the JVM alive). */
    private ThreadFactory namedFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, error) -> {
                error.printStackTrace();
                announce("ERROR: thread " + t.getName() + " stopped: " + error);
            });
            return thread;
        };
    }

    // ------------------------------------------------------------------
    // Actions from the GUI
    // ------------------------------------------------------------------

    /**
     * @return true if the emergency vehicle entered, false if the road entrance is full
     * @throws IllegalStateException if the simulation has not been started
     */
    public boolean spawnEmergency(Direction direction) {
        if (direction == null) {
            throw new IllegalArgumentException("Direction must not be null");
        }
        if (state == State.IDLE) {
            throw new IllegalStateException("Start the simulation first.");
        }
        VehicleType type = EMERGENCY_TYPES[ThreadLocalRandom.current().nextInt(EMERGENCY_TYPES.length)];
        Vehicle vehicle = Vehicle.create(type, direction, Movement.STRAIGHT);
        if (!roads.get(direction).addIfEntryClear(vehicle)) {
            return false;
        }
        statistics.recordSpawn(vehicle);
        String message = "🚨 " + type.getDisplayName() + " manually spawned on " + direction;
        statistics.recordEmergencyEvent(message);
        announce(message);
        return true;
    }

    /**
     * Asks the analysis thread to count the queues. Returns immediately with a
     * Future (a receipt); the caller checks isDone() and then calls get().
     *
     * @throws java.util.concurrent.RejectedExecutionException if the application is closing
     */
    public Future<com.trafficflowx.model.TrafficSnapshot> requestSnapshot() {
        return analysisExecutor.submit(new TrafficSnapshotTask(roads));
    }

    public void setDensity(TrafficDensity density) {
        if (density == null) {
            throw new IllegalArgumentException("Density must not be null");
        }
        this.density = density;
    }

    public void setSignalsIgnored(boolean ignored) {
        this.signalsIgnored = ignored;
    }

    /** The GUI registers here to receive status messages (they arrive on a background thread!). */
    public void setStatusListener(Consumer<String> listener) {
        this.statusListener = (listener == null) ? message -> { } : listener;
    }

    // ------------------------------------------------------------------
    // Accidents and road blocks
    // ------------------------------------------------------------------

    public void simulateAccident(Direction direction) { startIncident(direction, RoadStatus.ACCIDENT); }
    public void blockRoad(Direction direction)        { startIncident(direction, RoadStatus.ROAD_BLOCK); }
    public void clearAccident(Direction direction)    { endIncident(direction, RoadStatus.ACCIDENT); }
    public void clearRoadBlock(Direction direction)   { endIncident(direction, RoadStatus.ROAD_BLOCK); }

    /**
     * 'synchronized' because "is the road free? then block it" is a check-then-act
     * sequence that must not be interleaved with another call.
     *
     * @throws IllegalStateException if the simulation is not running or the road already has an incident
     */
    private synchronized void startIncident(Direction direction, RoadStatus type) {
        if (direction == null) {
            throw new IllegalArgumentException("Direction must not be null");
        }
        if (state == State.IDLE) {
            throw new IllegalStateException("Start the simulation first.");
        }
        Road road = roads.get(direction);
        if (road.isBlocked()) {
            throw new IllegalStateException("Road " + direction + " already has an active "
                    + road.getStatus().getDisplayName().toLowerCase() + ". Clear it first.");
        }
        road.setStatus(type);
        statistics.recordIncident(type, direction);
        announce(type == RoadStatus.ACCIDENT
                ? "💥 Accident on " + direction + ": the road is blocked."
                : "🚧 Road block on " + direction + ": vehicles cannot pass.");
    }

    /** @throws IllegalStateException if the road has no incident of that type */
    private synchronized void endIncident(Direction direction, RoadStatus expected) {
        if (direction == null) {
            throw new IllegalArgumentException("Direction must not be null");
        }
        Road road = roads.get(direction);
        if (road.getStatus() != expected) {
            throw new IllegalStateException("There is no "
                    + expected.getDisplayName().toLowerCase() + " on " + direction + ".");
        }
        road.setStatus(RoadStatus.OPEN);
        statistics.recordIncidentCleared(expected, direction);
        announce(expected.getDisplayName() + " on " + direction + " cleared. Traffic resumes.");
    }

    // ------------------------------------------------------------------
    // Read access for the GUI and the tasks
    // ------------------------------------------------------------------

    public State getState()                        { return state; }
    public TrafficDensity getDensity()             { return density; }
    public boolean areSignalsIgnored()             { return signalsIgnored; }
    public SignalController getSignalController()  { return signalController; }
    public StatisticsManager getStatistics()       { return statistics; }
    public IntersectionGeometry getGeometry()      { return geometry; }
    public Intersection getIntersection()          { return intersection; }

    public RoadStatus getRoadStatus(Direction direction) {
        return roads.get(direction).getStatus();
    }

    /** A copy of every vehicle on every road. */
    public List<Vehicle> getAllVehicles() {
        List<Vehicle> all = new ArrayList<>();
        for (Road road : roads.values()) {
            all.addAll(road.snapshot());
        }
        return all;
    }

    public int getWaitingCount() {
        int total = 0;
        for (Road road : roads.values()) {
            total += road.waitingCount();
        }
        return total;
    }

    // ---- Package-private: only the tasks in this package use these ----
    Road getRoad(Direction direction)    { return roads.get(direction); }
    PauseControl getPauseControl()       { return pauseControl; }

    void announce(String message) {
        statusListener.accept(message);
    }
}