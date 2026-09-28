package com.trafficflowx.util;

/**
 * Tunable numbers of the simulation, in one place.
 * Every value says where it is used, so nothing is a mystery number.
 */
public final class SimulationConfig {

    private SimulationConfig() {
    }

    /** Minimum empty space (px) kept between two vehicles in the same lane.
     *  Used by the car-following rule in SimulationController.moveLane(). */
    public static final double VEHICLE_GAP = 12;

    /** Largest time step (seconds) allowed for one animation frame.
     *  Prevents vehicles from jumping if the window freezes briefly. */
    public static final double MAX_FRAME_SECONDS = 0.05;

    /** The real gap between two spawns is the density's average interval multiplied by
     *  a random factor in this range, so traffic looks natural, not like a metronome.
     *  Used by VehicleGenerator.nextInterval(). */
    public static final double INTERVAL_MIN_FACTOR = 0.6;
    public static final double INTERVAL_MAX_FACTOR = 1.4;

    /** Chance (0 to 1) that a generated vehicle is an emergency vehicle.
     *  Used by VehicleGenerator.randomType(). */
    public static final double EMERGENCY_CHANCE = 0.03;
    /** How long one axis stays GREEN (seconds). Used by SignalController.durationOf().
     *  In Step 13 the adaptive algorithm will change this per cycle. */
    public static final double GREEN_SECONDS = 10;

    /** How long the YELLOW warning lasts (seconds). Used by SignalController.durationOf(). */
    public static final double YELLOW_SECONDS = 3;
    /** How long each background task sleeps between two simulation steps (milliseconds).
     *  Used by DirectionTask and SignalTask. */
    public static final long TICK_MILLIS = 10;

    /** How long reset() waits for each thread to finish (milliseconds).
     *  Used by SimulationManager.stopThreads(). */
    public static final long JOIN_TIMEOUT_MILLIS = 1000;
    /** How long a vehicle waits at the stop line for the junction to clear (milliseconds).
     *  One tick, so the road thread keeps animating its other vehicles.
     *  Used by DirectionTask.moveVehicles() -> Intersection.enter(). */
    public static final long ENTRY_WAIT_MILLIS = 10;

    /** UNSAFE MODE ONLY: artificial pause between "check" and "act" that makes the
     *  race condition easy to see. Used by Intersection.enterUnsafely(). */
    public static final long UNSAFE_DELAY_MILLIS = 5;
    /** Safety limit for ONE emergency green (seconds). Normally the green ends as soon as the
     *  emergency vehicles have crossed; this only stops a stuck vehicle from holding the
     *  intersection forever. Used by SignalController.serveEmergency(). */
    public static final double EMERGENCY_MAX_SECONDS = 25;
    /** Adaptive signals: the shortest and the longest green an axis can get (seconds).
     *  Used by SignalController.greenSecondsFor(). */
    public static final double MIN_GREEN_SECONDS = 5;
    public static final double MAX_GREEN_SECONDS = 30;

    /** Adaptive signals: extra green time for every vehicle waiting on the axis (seconds).
     *  Used by SignalController.greenSecondsFor(). */
    public static final double SECONDS_PER_WAITING_VEHICLE = 1.0;
    /** Where an accident or road block sits: this fraction of the way from the road entrance
     *  to the stop line. Used by IntersectionGeometry.blockPointDistance(). */
    public static final double BLOCK_POSITION_FRACTION = 0.55;

    /** Space (pixels) a stopped vehicle keeps between its front and the incident.
     *  Used by DirectionTask.moveVehicles() (Rule 4). */
    public static final double INCIDENT_CLEARANCE = 24;
    /** Extra distance (pixels) past the junction a vehicle must clear before releasing
     *  the junction lock. Gives converging traffic (turns into a shared exit lane) real
     *  separation, instead of the next vehicle being admitted the instant the junction
     *  box itself is clear. Used by DirectionTask.moveVehicles(). */
    public static final double EGRESS_CLEARANCE = 40;
}