package com.trafficflowx.model;

/**
 * All vehicle kinds in the simulation.
 * Speed is in pixels per second; length and width are in pixels
 * (a lane is 60 px wide, so every vehicle fits inside one lane).
 */
public enum VehicleType {
    //          label          speed  length width emergency colour
    CAR        ("Car",          90,   36,    20,   false,     "#4fa3e0"),
    BUS        ("Bus",          65,   62,    26,   false,     "#e0913a"),
    MOTORCYCLE ("Motorcycle",  110,   24,    12,   false,     "#9b59b6"),
    AMBULANCE  ("Ambulance",   130,   44,    22,   true,      "#ffffff"),
    FIRE_TRUCK ("Fire Truck",  110,   58,    24,   true,      "#d63031"),
    POLICE_CAR ("Police Car",  125,   38,    20,   true,      "#1e3a8a");

    private final String displayName;
    private final double baseSpeed;
    private final double length;
    private final double width;
    private final boolean emergency;
    private final String colorHex;

    VehicleType(String displayName, double baseSpeed, double length, double width,
                boolean emergency, String colorHex) {
        this.displayName = displayName;
        this.baseSpeed = baseSpeed;
        this.length = length;
        this.width = width;
        this.emergency = emergency;
        this.colorHex = colorHex;
    }

    public String getDisplayName() { return displayName; }
    public double getBaseSpeed()   { return baseSpeed; }
    public double getLength()      { return length; }
    public double getWidth()       { return width; }
    public boolean isEmergency()   { return emergency; }
    public String getColorHex()    { return colorHex; }
}