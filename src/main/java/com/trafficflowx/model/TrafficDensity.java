package com.trafficflowx.model;

/**
 * How busy the roads are.
 * meanSpawnIntervalSeconds is the AVERAGE time between two new vehicles on ONE road.
 * A SMALLER interval means MORE vehicles. VehicleGenerator reads this value.
 */
public enum TrafficDensity {
    LOW("Low", 6.0),      // about 10 vehicles per minute per road
    MEDIUM("Medium", 3.0),// about 20 vehicles per minute per road
    HIGH("High", 1.5);    // about 40 vehicles per minute per road

    private final String displayName;
    private final double meanSpawnIntervalSeconds;

    TrafficDensity(String displayName, double meanSpawnIntervalSeconds) {
        this.displayName = displayName;
        this.meanSpawnIntervalSeconds = meanSpawnIntervalSeconds;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getMeanSpawnIntervalSeconds() {
        return meanSpawnIntervalSeconds;
    }
}