package com.trafficflowx.model;
import com.trafficflowx.util.SimulationConfig;

/**
 * Dimensions of the intersection, in a form that ANY thread can read.
 * The drawing (IntersectionView) belongs to the JavaFX thread, so background
 * threads must not ask it for sizes. Instead the controller copies the size here
 * whenever the window changes.
 *
 * All distances are measured from the entry edge of a road along the direction
 * of travel, the same measure Vehicle.getDistance() uses.
 */
public class IntersectionGeometry {

    public static final double LANE_WIDTH = 60;
    public static final double ROAD_WIDTH = LANE_WIDTH * 2;   // two lanes per road
    public static final double STOP_LINE_OFFSET = 4;

    // volatile: written by the JavaFX thread, read by the road threads.
    private volatile double width;
    private volatile double height;

    public void setSize(double width, double height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative: " + width + " x " + height);
        }
        this.width = width;
        this.height = height;
    }

    /** False until the screen has been laid out and has a real size. */
    public boolean isReady() {
        return width > 0 && height > 0;
    }

    /** Length of the road: height for North/South, width for East/West. */
    public double pathLength(Direction direction) {
        return direction.isNorthSouthAxis() ? height : width;
    }

    /** Where the FRONT of a vehicle must stop when its light is not green. */
    public double stopLineDistance(Direction direction) {
        return pathLength(direction) / 2 - ROAD_WIDTH / 2 - STOP_LINE_OFFSET;
    }

    /** The shared junction square begins here. */
    public double junctionStartDistance(Direction direction) {
        return pathLength(direction) / 2 - ROAD_WIDTH / 2;
    }

    /** The junction ends here. */
    public double junctionEndDistance(Direction direction) {
        return pathLength(direction) / 2 + ROAD_WIDTH / 2;
    }
    /** Where an accident or road block sits on this road. */
    public double blockPointDistance(Direction direction) {
        return stopLineDistance(direction) * SimulationConfig.BLOCK_POSITION_FRACTION;
    }
    /**
     * The 'distance' value (same units as Vehicle.getDistance()) at which a vehicle
     * that entered from 'entry' and will leave via 'exitDirection' has fully left
     * the screen. exitDirection can be either the exit arm or its opposite (the
     * post-turn travel direction) — both give the same result, since pathLength
     * only depends on which AXIS a direction is on, not which of its two directions.
     */
    public double exitDistance(Direction entry, Direction exitDirection) {
        return junctionEndDistance(entry) + (pathLength(exitDirection) / 2 - ROAD_WIDTH / 2);
    }
}