package com.trafficflowx.model;

/** What is happening on a road's approach lane. */
public enum RoadStatus {
    OPEN("Open"),
    ACCIDENT("Accident"),
    ROAD_BLOCK("Road block");

    private final String displayName;

    RoadStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}