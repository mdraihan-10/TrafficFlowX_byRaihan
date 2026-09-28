package com.trafficflowx.model;

/**
 * The four approach roads of the intersection.
 * A vehicle's Direction is the road it ARRIVES from
 * (NORTH means it starts at the top and travels south).
 */
public enum Direction {
    NORTH, SOUTH, EAST, WEST;

    /** The road on the other side of the intersection. */
    public Direction opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }

    /** North and South can be green together, so they form one "axis". */
    public boolean isNorthSouthAxis() {
        return this == NORTH || this == SOUTH;
    }
}