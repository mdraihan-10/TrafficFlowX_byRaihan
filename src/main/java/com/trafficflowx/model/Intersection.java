package com.trafficflowx.model;

import com.trafficflowx.util.SimulationConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The junction square: THE shared resource of the whole project.
 * Four road threads want to enter it; vehicles of crossing roads must never be
 * inside together. The time between enter() and exit() is the CRITICAL SECTION.
 *
 * SAFE mode:   enter() is 'synchronized' (one monitor = this object). Check and
 *              admit happen under the same lock, so no other thread can slip in between.
 * UNSAFE mode: the same check is done WITHOUT a lock, with a small pause between
 *              "check" and "act". Two threads can both see "free" and both enter.
 *              That is a race condition. Used only for the classroom demo.
 */
public class Intersection {

    public enum Mode { SAFE, UNSAFE }

    /** A refused vehicle counts as "waiting" until 250 ms after its last refusal. */
    private static final long WAITING_EXPIRY_NANOS = 250_000_000L;

    /** Vehicles currently inside. Concurrent set: even the UNSAFE path can never corrupt it. */
    private final Set<Vehicle> inside = ConcurrentHashMap.newKeySet();

    /** Refused vehicles -> time of their last refusal. Guarded by 'this'. Used for fairness. */
    private final Map<Vehicle, Long> waiting = new HashMap<>();

    /** The 'referee' only COUNTS conflicts. It is not part of the protection. */
    private final Object refereeLock = new Object();
    private final AtomicInteger conflictCount = new AtomicInteger();

    private final Consumer<String> alertSink;
    private volatile Mode mode = Mode.SAFE;

    public Intersection(Consumer<String> alertSink) {
        if (alertSink == null) {
            throw new IllegalArgumentException("alertSink must not be null");
        }
        this.alertSink = alertSink;
    }

    // ------------------------------------------------------------------
    // Entering
    // ------------------------------------------------------------------

    /**
     * Asks for permission to enter the junction.
     *
     * @param timeoutMillis SAFE mode: the longest time to wait for the junction to clear
     * @return true if the vehicle may enter now; false if it must keep waiting
     * @throws InterruptedException if the thread is interrupted while waiting (shutdown request)
     */
    public boolean enter(Vehicle vehicle, long timeoutMillis) throws InterruptedException {
        if (vehicle == null) {
            throw new IllegalArgumentException("Vehicle must not be null");
        }
        return (mode == Mode.SAFE) ? enterSafely(vehicle, timeoutMillis) : enterUnsafely(vehicle);
    }

    private synchronized boolean enterSafely(Vehicle vehicle, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;

        // Guarded wait: ALWAYS a 'while' loop, never 'if'. After wake-up the condition
        // may be false again (another thread got in first), so we must re-check.
        while (!canEnter(vehicle)) {
            waiting.put(vehicle, System.nanoTime());
            long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMillis <= 0) {
                return false;                 // timed out: the caller tries again next tick
            }
            wait(remainingMillis);            // releases the lock while waiting
        }
        waiting.remove(vehicle);
        admit(vehicle);
        return true;
    }

    private boolean enterUnsafely(Vehicle vehicle) throws InterruptedException {
        // Step 1: CHECK, with no lock.
        if (hasConflictingOccupant(vehicle)) {
            return false;
        }
        // The window between check and act. Real races have a much smaller window;
        // this pause only makes it visible in a demo.
        Thread.sleep(SimulationConfig.UNSAFE_DELAY_MILLIS);

        // Step 2: ACT, with no lock. Another thread may have entered during the pause.
        admit(vehicle);
        return true;
    }

    /** Puts the vehicle inside, and counts a conflict if crossing traffic is already there. */
    private void admit(Vehicle vehicle) {
        synchronized (refereeLock) {
            boolean conflict = hasConflictingOccupant(vehicle);
            inside.add(vehicle);
            if (conflict) {
                conflictCount.incrementAndGet();
                alertSink.accept("⚠ CONFLICT: " + vehicle.getType().getDisplayName() + " from "
                        + vehicle.getDirection() + " entered while crossing traffic was inside the junction!");
            }
        }
    }

    // ------------------------------------------------------------------
    // Leaving
    // ------------------------------------------------------------------

    /** Called when the vehicle's rear has left the junction. Wakes every waiting thread. */
    public synchronized void exit(Vehicle vehicle) {
        inside.remove(vehicle);
        notifyAll();   // the junction state changed: waiting threads should re-check
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    /**
     * THE conflict rule.
     * - Different axis (NS vs EW): always conflicting. They're never green together
     *   anyway; this is a safety net for the guard.
     * - Same road: not a junction conflict (their order is handled by the follow rule).
     * - Same axis, opposite roads (e.g. NORTH vs SOUTH): only conflict if one of them
     *   is turning RIGHT. A right turn crosses the opposing straight-through lane; a
     *   LEFT turn (the 'free' turn in left-hand traffic) and two STRAIGHT vehicles
     *   pass side by side without crossing.
     */
    private static boolean conflicts(Vehicle a, Vehicle b) {
        Direction da = a.getDirection();
        Direction db = b.getDirection();
        if (da.isNorthSouthAxis() != db.isNorthSouthAxis()) {
            return true;
        }
        if (da == db) {
            return false;
        }
        return a.getMovement() == Movement.RIGHT || b.getMovement() == Movement.RIGHT;
    }

    /** May this vehicle enter now? Must be called while holding the lock. */
    private boolean canEnter(Vehicle vehicle) {
        int insideCount = 0;
        for (Vehicle other : inside) {
            if (conflicts(vehicle, other)) {
                return false;              // crossing traffic is inside
            }
            insideCount++;
        }
        if (insideCount == 0 || vehicle.isEmergency()) {
            return true;                   // empty junction, or an emergency vehicle (no fairness rule)
        }

        // Same-axis traffic is inside. Let this vehicle in only if nobody from the
        // crossing axis is waiting; otherwise that side would starve.
        long now = System.nanoTime();
        waiting.values().removeIf(lastRefusal -> now - lastRefusal > WAITING_EXPIRY_NANOS);
        for (Vehicle other : waiting.keySet()) {
            if (other != vehicle && conflicts(vehicle, other)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasConflictingOccupant(Vehicle vehicle) {
        for (Vehicle other : inside) {
            if (other != vehicle && conflicts(vehicle, other)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Settings and read access
    // ------------------------------------------------------------------

    public void setMode(Mode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("Mode must not be null");
        }
        this.mode = mode;
    }

    public Mode getMode()          { return mode; }
    public int getConflictCount()  { return conflictCount.get(); }

    /** Empties the junction and zeroes the conflict counter. */
    public synchronized void reset() {
        inside.clear();
        waiting.clear();
        conflictCount.set(0);
        notifyAll();
    }
}