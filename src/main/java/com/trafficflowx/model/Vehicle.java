package com.trafficflowx.model;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A normal vehicle (car, bus, motorcycle).
 * EmergencyVehicle extends this class.
 *
 * Thread notes:
 *  - The final fields never change, so any thread can read them safely.
 *  - state, distance and waitingTimeMillis are 'volatile': the thread that drives
 *    this vehicle writes them, and other threads (JavaFX, statistics) always see
 *    the latest value. Only ONE thread updates a given vehicle, so volatile is enough.
 *  - The ID counter is an AtomicInteger because several direction threads will
 *    create vehicles at the same time. A plain int++ would be a race condition.
 */
public class Vehicle {

    private static final AtomicInteger ID_COUNTER = new AtomicInteger(0);

    private final int id;
    private final VehicleType type;
    private final Direction direction;
    private final Movement movement;
    private final double speed;               // pixels per second
    private final long arrivalTimeMillis;     // when the vehicle was created

    private volatile long waitingTimeMillis;  // total time spent stopped
    private volatile VehicleState state = VehicleState.APPROACHING;
    private volatile double distance;

    /** Use Vehicle.create(...) instead of calling this directly. */
    protected Vehicle(VehicleType type, Direction direction, Movement movement) {
        if (type == null) {
            throw new IllegalArgumentException("Vehicle type must not be null");
        }
        if (direction == null) {
            throw new IllegalArgumentException("Vehicle direction must not be null");
        }
        if (movement == null) {
            throw new IllegalArgumentException("Vehicle movement must not be null");
        }
        this.id = ID_COUNTER.incrementAndGet();
        this.type = type;
        this.direction = direction;
        this.movement = movement;
        this.speed = type.getBaseSpeed();
        this.arrivalTimeMillis = System.currentTimeMillis();
        this.distance = -type.getLength();   // start just outside the screen
    }

    /**
     * Factory method: returns an EmergencyVehicle for ambulance / fire truck /
     * police car, and a normal Vehicle for everything else.
     */
    public static Vehicle create(VehicleType type, Direction direction, Movement movement) {
        if (type == null) {
            throw new IllegalArgumentException("Vehicle type must not be null");
        }
        return type.isEmergency()
                ? new EmergencyVehicle(type, direction, movement)
                : new Vehicle(type, direction, movement);
    }

    /** Called by the thread that drives this vehicle while it is stopped. */
    public void addWaitingTime(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("Waiting time cannot be negative: " + millis);
        }
        waitingTimeMillis += millis;
    }

    /** Moves the vehicle forward. Called once per tick by the driving thread. */
    public void advance(double pixels) {
        if (pixels < 0) {
            throw new IllegalArgumentException("Cannot move backwards: " + pixels);
        }
        distance += pixels;
    }

    /** Normal vehicles have no priority. EmergencyVehicle overrides this (polymorphism). */
    public int getPriority() {
        return 0;
    }

    public boolean isEmergency() {
        return false;
    }

    /** The road this vehicle leaves by, derived from where it entered and its movement. */
    public Direction getExitDirection() {
        return TurnTable.exitArm(direction, movement);
    }

    // ---- Getters ----
    public int getId()                     { return id; }
    public VehicleType getType()           { return type; }
    public Direction getDirection()        { return direction; }
    public Movement getMovement()          { return movement; }
    public double getSpeed()               { return speed; }
    public long getArrivalTimeMillis()     { return arrivalTimeMillis; }
    public long getWaitingTimeMillis()     { return waitingTimeMillis; }
    public double getWaitingTimeSeconds()  { return waitingTimeMillis / 1000.0; }
    public VehicleState getState()         { return state; }
    public double getDistance()            { return distance; }

    public void setState(VehicleState state) {
        if (state == null) {
            throw new IllegalArgumentException("Vehicle state must not be null");
        }
        this.state = state;
    }

    @Override
    public String toString() {
        String turn = movement == Movement.STRAIGHT ? "" : " " + movement;
        return String.format("#%d %s from %s%s [%s, waited %.1fs]",
                id, type.getDisplayName(), direction, turn, state, getWaitingTimeSeconds());
    }
}