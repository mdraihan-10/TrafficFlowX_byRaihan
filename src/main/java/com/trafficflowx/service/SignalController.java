package com.trafficflowx.service;

import com.trafficflowx.model.Direction;
import com.trafficflowx.model.SignalPhase;
import com.trafficflowx.model.SignalState;
import com.trafficflowx.model.TrafficLight;
import com.trafficflowx.model.TrafficSnapshot;
import com.trafficflowx.util.SimulationConfig;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Owns the four traffic lights and decides which phase is active.
 *
 * Normal cycle: NS_GREEN -> NS_YELLOW -> EW_GREEN -> EW_YELLOW -> repeat.
 * ADAPTIVE: each time an axis is about to turn green, its green time is chosen from the
 *           number of vehicles waiting on that axis (see greenSecondsFor()).
 * EMERGENCY: when a road reports an emergency vehicle, its axis is switched to green
 *           (through a yellow warning for the other axis) and held until the emergency
 *           vehicles have crossed.
 *
 * THREADS: the signal thread calls update(); the four road threads call
 * reportEmergency(); the JavaFX thread reads state. All of these methods are
 * 'synchronized' on this one object, so they never see half-finished changes.
 *
 * LOCK ORDER: while holding this lock, update() asks the traffic source for a snapshot,
 * which takes the Road locks. No code ever takes a Road lock first and then this lock,
 * so the two can never wait for each other (no deadlock).
 *
 * It contains no JavaFX code.
 */
public class SignalController {

    private final Map<Direction, TrafficLight> lights = new EnumMap<>(Direction.class);

    /** Highest emergency priority reported by each road (0 = none). Guarded by 'this'. */
    private final Map<Direction, Integer> emergencyReports = new EnumMap<>(Direction.class);

    /** Where the queue lengths come from. Set by SimulationManager. Guarded by 'this'. */
    private Supplier<TrafficSnapshot> trafficSource = () -> null;

    private SignalPhase phase = SignalPhase.NS_GREEN;
    private double elapsedInPhase = 0;
    private boolean active = false;
    private boolean adaptive = true;

    /** Length of the CURRENT normal green. Chosen when that green starts. */
    private double currentGreenSeconds = SimulationConfig.GREEN_SECONDS;

    /** The most recent green chosen for each axis (only shown on screen). */
    private double lastGreenNorthSouth = SimulationConfig.GREEN_SECONDS;
    private double lastGreenEastWest = SimulationConfig.GREEN_SECONDS;

    public SignalController() {
        for (Direction d : Direction.values()) {
            lights.put(d, new TrafficLight(d));   // all start RED
            emergencyReports.put(d, 0);
        }
    }

    /** Starts the cycle at NS_GREEN. */
    public synchronized void start() {
        phase = SignalPhase.NS_GREEN;
        elapsedInPhase = 0;
        active = true;
        clearReports();
        resetGreenTimes();   // no traffic data yet at the very first green: use the default
        applyPhase();
    }

    /** Stops the cycle and turns every light RED. */
    public synchronized void stop() {
        active = false;
        phase = SignalPhase.NS_GREEN;
        elapsedInPhase = 0;
        clearReports();
        resetGreenTimes();
        for (TrafficLight light : lights.values()) {
            light.setState(SignalState.RED);
        }
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /** Where the controller gets the queue lengths from. */
    public synchronized void setTrafficSource(Supplier<TrafficSnapshot> source) {
        if (source == null) {
            throw new IllegalArgumentException("Traffic source must not be null");
        }
        this.trafficSource = source;
    }

    /** true = green time depends on the queue; false = every green lasts GREEN_SECONDS. */
    public synchronized void setAdaptive(boolean adaptive) {
        this.adaptive = adaptive;
    }

    public synchronized boolean isAdaptive() {
        return adaptive;
    }

    // ------------------------------------------------------------------
    // Emergency reports (called by the road threads)
    // ------------------------------------------------------------------

    /**
     * A road tells the controller the priority of the most important emergency
     * vehicle that has not yet cleared the junction (0 = there is none).
     */
    public synchronized void reportEmergency(Direction direction, int priority) {
        if (direction == null) {
            throw new IllegalArgumentException("Direction must not be null");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("Priority cannot be negative: " + priority);
        }
        emergencyReports.put(direction, priority);
    }

    /** Roads that currently have an emergency vehicle (for the banner on screen). */
    public synchronized List<Direction> getEmergencyDirections() {
        List<Direction> result = new ArrayList<>();
        for (Direction d : Direction.values()) {
            if (emergencyReports.get(d) > 0) {
                result.add(d);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Time steps (called by the signal thread)
    // ------------------------------------------------------------------

    /**
     * Moves time forward by dt seconds.
     *
     * @return true if the phase changed
     */
    public synchronized boolean update(double dt) {
        if (!active) {
            return false;
        }
        elapsedInPhase += dt;
        SignalPhase before = phase;

        if (phase.isEmergency()) {
            serveEmergency();
        } else {
            reactToEmergencyRequest();
            runNormalTimer();
        }

        if (phase != before) {
            applyPhase();
            return true;
        }
        return false;
    }

    /** During an emergency green: hold it until the emergency vehicles have crossed. */
    private void serveEmergency() {
        boolean stillNeeded = axisHasEmergency(phase.isNorthSouthPhase());
        if (stillNeeded && elapsedInPhase < SimulationConfig.EMERGENCY_MAX_SECONDS) {
            return;   // keep this axis green
        }
        // Done (or the safety limit ran out): NS_EMERGENCY -> NS_YELLOW, EW_EMERGENCY -> EW_YELLOW.
        // From there the ordinary cycle continues, so the normal cycle resumes by itself.
        moveTo(phase.next());
    }

    /** In a normal phase: is there an emergency request, and what must change now? */
    private void reactToEmergencyRequest() {
        Boolean wanted = wantedEmergencyAxis();
        if (wanted == null) {
            return;
        }
        boolean wantNorthSouth = wanted;

        if (phase.isNorthSouthPhase() == wantNorthSouth) {
            // The right axis already has green or yellow. The crossing axis is red,
            // so switching this axis to (emergency) green is safe.
            moveTo(SignalPhase.emergencyFor(wantNorthSouth));
        } else if (phase.isGreen()) {
            // The crossing axis has GREEN: give it the normal yellow warning right now.
            // When that yellow ends, runNormalTimer() hands the green to the emergency axis.
            moveTo(phase.next());
        }
        // else: the crossing axis is already YELLOW, so we just wait for it to end.
    }

    /**
     * Normal timing. After a YELLOW, an emergency request takes the next green;
     * otherwise the next normal phase starts, and if it is a green, its length is chosen now.
     */
    private void runNormalTimer() {
        while (!phase.isEmergency() && elapsedInPhase >= durationOf(phase)) {
            elapsedInPhase -= durationOf(phase);
            Boolean wanted = phase.isYellow() ? wantedEmergencyAxis() : null;
            if (wanted != null) {
                phase = SignalPhase.emergencyFor(wanted);
                elapsedInPhase = 0;
            } else {
                phase = phase.next();
                if (phase.isGreen()) {
                    currentGreenSeconds = chooseGreenSeconds(phase.isNorthSouthPhase());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Adaptive timing
    // ------------------------------------------------------------------

    /**
     * THE ADAPTIVE RULE: more waiting vehicles -> longer green.
     * Start from MIN_GREEN, add SECONDS_PER_WAITING_VEHICLE per waiting vehicle,
     * and never go above MAX_GREEN.  (0 waiting = 5 s, 5 = 10 s, 10 = 15 s, 25+ = 30 s.)
     */
    public static double greenSecondsFor(int waitingVehicles) {
        double seconds = SimulationConfig.MIN_GREEN_SECONDS
                + SimulationConfig.SECONDS_PER_WAITING_VEHICLE * Math.max(0, waitingVehicles);
        return Math.min(SimulationConfig.MAX_GREEN_SECONDS, seconds);
    }

    /** Called when an axis is about to turn green. Decides how long that green lasts. */
    private double chooseGreenSeconds(boolean northSouth) {
        double seconds = SimulationConfig.GREEN_SECONDS;   // fixed mode, or no data
        if (adaptive) {
            TrafficSnapshot snapshot = trafficSource.get();
            if (snapshot != null) {
                seconds = greenSecondsFor(waitingOnAxis(snapshot, northSouth));
            }
        }
        if (northSouth) {
            lastGreenNorthSouth = seconds;
        } else {
            lastGreenEastWest = seconds;
        }
        return seconds;
    }

    private static int waitingOnAxis(TrafficSnapshot snapshot, boolean northSouth) {
        int total = 0;
        for (Direction d : Direction.values()) {
            if (d.isNorthSouthAxis() == northSouth) {
                total += snapshot.waiting(d);
            }
        }
        return total;
    }

    private void resetGreenTimes() {
        currentGreenSeconds = SimulationConfig.GREEN_SECONDS;
        lastGreenNorthSouth = SimulationConfig.GREEN_SECONDS;
        lastGreenEastWest = SimulationConfig.GREEN_SECONDS;
    }

    // ------------------------------------------------------------------
    // Emergency helpers
    // ------------------------------------------------------------------

    /**
     * Which axis should get an emergency green? true = North/South, false = East/West,
     * null = nobody. The higher priority wins; on a tie the axis that is already
     * in charge keeps it, which causes the least disruption.
     */
    private Boolean wantedEmergencyAxis() {
        int northSouth = highestPriority(true);
        int eastWest = highestPriority(false);
        if (northSouth == 0 && eastWest == 0) {
            return null;
        }
        if (northSouth > eastWest) {
            return Boolean.TRUE;
        }
        if (eastWest > northSouth) {
            return Boolean.FALSE;
        }
        return phase.isNorthSouthPhase();
    }

    private int highestPriority(boolean northSouth) {
        int highest = 0;
        for (Direction d : Direction.values()) {
            if (d.isNorthSouthAxis() == northSouth) {
                highest = Math.max(highest, emergencyReports.get(d));
            }
        }
        return highest;
    }

    private boolean axisHasEmergency(boolean northSouth) {
        return highestPriority(northSouth) > 0;
    }

    private void moveTo(SignalPhase newPhase) {
        phase = newPhase;
        elapsedInPhase = 0;
    }

    /** How long a phase lasts. */
    private double durationOf(SignalPhase p) {
        if (p.isEmergency()) {
            return SimulationConfig.EMERGENCY_MAX_SECONDS;   // only a safety limit
        }
        return p.isGreen() ? currentGreenSeconds : SimulationConfig.YELLOW_SECONDS;
    }

    private void clearReports() {
        for (Direction d : Direction.values()) {
            emergencyReports.put(d, 0);
        }
    }

    /** Copies the colours of the current phase into the four lights. */
    private void applyPhase() {
        for (Direction d : Direction.values()) {
            lights.get(d).setState(phase.stateFor(d));
        }
    }

    // ---- Read-only access for the rest of the program ----
    public TrafficLight getLight(Direction direction) {
        return lights.get(direction);
    }

    public synchronized SignalPhase getPhase() {
        return phase;
    }

    public synchronized boolean isActive() {
        return active;
    }

    public synchronized double getSecondsRemaining() {
        return Math.max(0, durationOf(phase) - elapsedInPhase);
    }

    public synchronized double getLastGreenNorthSouth() {
        return lastGreenNorthSouth;
    }

    public synchronized double getLastGreenEastWest() {
        return lastGreenEastWest;
    }
}