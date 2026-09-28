package com.trafficflowx.service;

import com.trafficflowx.model.Direction;
import com.trafficflowx.model.RoadStatus;
import com.trafficflowx.model.Vehicle;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters and history updated by several threads at the same time (the four road
 * threads call recordSpawn/recordPassed; the GUI thread only reads).
 * AtomicInteger/AtomicLong.incrementAndGet()/addAndGet() are single indivisible
 * steps; a plain 'total++' would lose counts when two threads increment together
 * (a race condition, the same idea as Step 4's vehicle ID counter).
 */
public class StatisticsManager {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AtomicInteger totalVehicles = new AtomicInteger();
    private final AtomicInteger passedVehicles = new AtomicInteger();
    private final AtomicInteger emergencyVehicles = new AtomicInteger();
    private final AtomicInteger accidents = new AtomicInteger();
    private final AtomicInteger roadBlocks = new AtomicInteger();

    /** Sum and count of the FINAL waiting time of every vehicle that has passed.
     *  average = waitingTimeSumMillis / passedVehicles (guarded against divide-by-zero). */
    private final AtomicLong waitingTimeSumMillis = new AtomicLong();
    private final AtomicLong maxWaitingTimeMillis = new AtomicLong();

    /** Per-direction totals, for the Step 16 charts. One counter per road: no shared state
     *  between roads, so no extra locking is needed beyond the atomics themselves. */
    private final Map<Direction, AtomicInteger> vehiclesByDirection = new EnumMap<>(Direction.class);
    private final Map<Direction, AtomicLong> waitingSumByDirection = new EnumMap<>(Direction.class);
    private final Map<Direction, AtomicInteger> passedByDirection = new EnumMap<>(Direction.class);

    /** Lines like "14:05:32  Accident on NORTH". Guarded by itself. */
    private final List<String> incidentHistory = new ArrayList<>();
    /** Lines like "14:05:32  🚨 Ambulance detected on NORTH". Guarded by itself. */
    private final List<String> emergencyHistory = new ArrayList<>();

    public StatisticsManager() {
        for (Direction d : Direction.values()) {
            vehiclesByDirection.put(d, new AtomicInteger());
            waitingSumByDirection.put(d, new AtomicLong());
            passedByDirection.put(d, new AtomicInteger());
        }
    }

    public void recordSpawn(Vehicle vehicle) {
        totalVehicles.incrementAndGet();
        vehiclesByDirection.get(vehicle.getDirection()).incrementAndGet();
        if (vehicle.isEmergency()) {
            emergencyVehicles.incrementAndGet();
        }
    }

    /** Called once per vehicle, exactly when it leaves, so its waiting time is final. */
    public void recordPassed(Vehicle vehicle) {
        passedVehicles.incrementAndGet();
        passedByDirection.get(vehicle.getDirection()).incrementAndGet();

        long waited = vehicle.getWaitingTimeMillis();
        waitingTimeSumMillis.addAndGet(waited);
        waitingSumByDirection.get(vehicle.getDirection()).addAndGet(waited);
        maxWaitingTimeMillis.updateAndGet(current -> Math.max(current, waited));
    }

    /** An accident or road block has started on a road. */
    public void recordIncident(RoadStatus type, Direction road) {
        switch (type) {
            case ACCIDENT -> accidents.incrementAndGet();
            case ROAD_BLOCK -> roadBlocks.incrementAndGet();
            default -> throw new IllegalArgumentException("Not an incident type: " + type);
        }
        log(type.getDisplayName() + " on " + road);
    }

    public void recordIncidentCleared(RoadStatus type, Direction road) {
        log(type.getDisplayName() + " cleared on " + road);

    }
    public void recordEmergencyEvent(String description) {
        synchronized (emergencyHistory) {
            emergencyHistory.add(LocalTime.now().format(TIME) + "  " + description);
        }
    }

    /** A copy of the emergency log, safe to read while other threads add to it. */
    public List<String> getEmergencyHistory() {
        synchronized (emergencyHistory) {
            return new ArrayList<>(emergencyHistory);
        }
    }

    private void log(String text) {
        synchronized (incidentHistory) {
            incidentHistory.add(LocalTime.now().format(TIME) + "  " + text);
        }
    }

    /** A copy of the history, safe to read while other threads add to it. */
    public List<String> getIncidentHistory() {
        synchronized (incidentHistory) {
            return new ArrayList<>(incidentHistory);
        }
    }

    public void reset() {
        totalVehicles.set(0);
        passedVehicles.set(0);
        emergencyVehicles.set(0);
        accidents.set(0);
        roadBlocks.set(0);
        waitingTimeSumMillis.set(0);
        maxWaitingTimeMillis.set(0);
        for (Direction d : Direction.values()) {
            vehiclesByDirection.get(d).set(0);
            waitingSumByDirection.get(d).set(0);
            passedByDirection.get(d).set(0);
        }
        synchronized (incidentHistory) {
            incidentHistory.clear();
        }
        synchronized (emergencyHistory) {
            emergencyHistory.clear();
        }
    }

    public int getTotalVehicles()     { return totalVehicles.get(); }
    public int getPassedVehicles()    { return passedVehicles.get(); }
    public int getEmergencyVehicles() { return emergencyVehicles.get(); }
    public int getAccidentCount()     { return accidents.get(); }
    public int getRoadBlockCount()    { return roadBlocks.get(); }

    public double getAverageWaitingSeconds() {
        int count = passedVehicles.get();
        return count == 0 ? 0.0 : (waitingTimeSumMillis.get() / 1000.0) / count;
    }

    public double getMaxWaitingSeconds() {
        return maxWaitingTimeMillis.get() / 1000.0;
    }

    public int getVehiclesByDirection(Direction direction) {
        return vehiclesByDirection.get(direction).get();
    }

    /** Average waiting time of vehicles that passed FROM this direction, in seconds. */
    public double getAverageWaitingSeconds(Direction direction) {
        int count = passedByDirection.get(direction).get();
        return count == 0 ? 0.0 : (waitingSumByDirection.get(direction).get() / 1000.0) / count;
    }

}