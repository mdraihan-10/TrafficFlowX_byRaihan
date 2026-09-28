package com.trafficflowx.model;

/**
 * One traffic light, guarding one approach road.
 *
 * 'state' is volatile: the signal thread writes it and the vehicle threads
 * read it, so readers must always see the newest colour.
 */
public class TrafficLight {

    private final Direction direction;
    private volatile SignalState state = SignalState.RED;

    public TrafficLight(Direction direction) {
        if (direction == null) {
            throw new IllegalArgumentException("Traffic light direction must not be null");
        }
        this.direction = direction;
    }

    public Direction getDirection() {
        return direction;
    }

    public SignalState getState() {
        return state;
    }

    public void setState(SignalState state) {
        if (state == null) {
            throw new IllegalArgumentException("Signal state must not be null");
        }
        this.state = state;
    }

    public boolean isRed() {
        return state == SignalState.RED;
    }

    public boolean isGreen() {
        return state == SignalState.GREEN;
    }

    @Override
    public String toString() {
        return direction + " light: " + state;
    }
}