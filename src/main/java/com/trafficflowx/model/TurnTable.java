package com.trafficflowx.model;

/**
 * Where a vehicle ends up, based on where it entered and what it does.
 * Left-hand traffic (as drawn): a LEFT turn is the 'free' turn that never
 * crosses oncoming traffic; a RIGHT turn crosses the opposing straight lane.
 */
public final class TurnTable {

    private TurnTable() {
    }

    /** The arm (road) the vehicle leaves by. */
    public static Direction exitArm(Direction entry, Movement movement) {
        return switch (movement) {
            case STRAIGHT -> entry.opposite();
            case LEFT -> switch (entry) {
                case NORTH -> Direction.EAST;
                case SOUTH -> Direction.WEST;
                case EAST -> Direction.SOUTH;
                case WEST -> Direction.NORTH;
            };
            case RIGHT -> switch (entry) {
                case NORTH -> Direction.WEST;
                case SOUTH -> Direction.EAST;
                case EAST -> Direction.NORTH;
                case WEST -> Direction.SOUTH;
            };
        };
    }

    /**
     * The Direction whose normal (straight-through) motion pattern matches this
     * vehicle's travel AFTER the turn: moving away from the centre, ending at
     * the exit arm's edge. Used by VehicleRenderer to draw the outbound leg,
     * and by IntersectionGeometry to know when the vehicle has left the screen.
     */
    public static Direction travelDirectionAfterTurn(Direction entry, Movement movement) {
        return exitArm(entry, movement).opposite();
    }
}