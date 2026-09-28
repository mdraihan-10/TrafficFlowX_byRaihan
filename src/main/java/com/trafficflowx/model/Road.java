package com.trafficflowx.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One approach road and the vehicles on it. Index 0 is the FRONT vehicle.
 *
 * WHY synchronized: the road's own thread adds and removes vehicles, while the
 * JavaFX thread also reads the list (drawing) and can add an emergency vehicle
 * (button click). Two threads touching an ArrayList at once can corrupt it, so
 * every method that touches the list uses the SAME lock: this Road object.
 */
public class Road {

    private final Direction direction;
    private final List<Vehicle> vehicles = new ArrayList<>();// guarded by 'this'
    /**
     * OPEN, ACCIDENT or ROAD_BLOCK. 'volatile': the JavaFX thread's button click writes it,
     * this road's thread reads it every tick, and volatile guarantees the road thread
     * sees the new value at once.
     */
    private volatile RoadStatus status = RoadStatus.OPEN;
    public Road(Direction direction) {
        if (direction == null) {
            throw new IllegalArgumentException("Road direction must not be null");
        }
        this.direction = direction;
    }

    public Direction getDirection() {
        return direction;
    }
    public RoadStatus getStatus() {
        return status;
    }

    public void setStatus(RoadStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("Road status must not be null");
        }
        this.status = status;
    }

    /** True while an accident or a road block is on this road. */
    public boolean isBlocked() {
        return status != RoadStatus.OPEN;
    }
    /**
     * Adds a vehicle only if the entrance has room. Checking and adding happen
     * under ONE lock, so two threads cannot both pass the check and then both add.
     *
     * @return true if the vehicle was added
     */
    public synchronized boolean addIfEntryClear(Vehicle vehicle) {
        if (vehicle == null) {
            throw new IllegalArgumentException("Vehicle must not be null");
        }
        if (vehicle.getDirection() != direction) {
            throw new IllegalArgumentException(
                    "Vehicle from " + vehicle.getDirection() + " cannot join road " + direction);
        }
        if (!entryClear()) {
            return false;
        }
        vehicles.add(vehicle);
        return true;
    }

    public synchronized boolean isEntryClear() {
        return entryClear();
    }

    public synchronized void remove(Vehicle vehicle) {
        vehicles.remove(vehicle);
    }

    /** A copy of the list, safe to loop over while other threads keep working. */
    public synchronized List<Vehicle> snapshot() {
        return new ArrayList<>(vehicles);
    }

    public synchronized int waitingCount() {
        int count = 0;
        for (Vehicle v : vehicles) {
            if (v.getState() == VehicleState.WAITING) {
                count++;
            }
        }
        return count;
    }

    public synchronized void clear() {
        vehicles.clear();
    }

    /** The entrance is clear when the last vehicle's rear is already on screen. */
    private boolean entryClear() {
        if (vehicles.isEmpty()) {
            return true;
        }
        Vehicle last = vehicles.get(vehicles.size() - 1);
        return last.getDistance() - last.getType().getLength() / 2 > 0;
    }
}