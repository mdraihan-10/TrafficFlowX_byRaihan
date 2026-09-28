package com.trafficflowx.model;

/** Where a vehicle is in its journey through the intersection. */
public enum VehicleState {
    APPROACHING,   // driving towards the stop line
    WAITING,       // stopped (red light, accident, or vehicle ahead)
    CROSSING,      // inside the intersection (critical section)
    PASSED         // left the intersection; it can be removed
}