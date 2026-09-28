package com.trafficflowx.model;

/**
 * Ambulance, Fire Truck or Police Car.
 * It IS-A Vehicle (inheritance) but is treated specially by the traffic controller.
 * When several emergency vehicles arrive together, the higher priority goes first.
 */
public class EmergencyVehicle extends Vehicle {

    private final int priority;
    private volatile boolean sirenOn = true;

    /** Use Vehicle.create(...) instead of calling this directly. */
    protected EmergencyVehicle(VehicleType type, Direction direction, Movement movement) {
        super(type, direction, movement);
        if (!type.isEmergency()) {
            throw new IllegalArgumentException(type + " is not an emergency vehicle type");
        }
        this.priority = switch (type) {
            case AMBULANCE  -> 3;
            case FIRE_TRUCK -> 2;
            case POLICE_CAR -> 1;
            default -> throw new IllegalArgumentException("Unexpected emergency type: " + type);
        };
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public boolean isEmergency() {
        return true;
    }

    public boolean isSirenOn()            { return sirenOn; }
    public void setSirenOn(boolean on)    { this.sirenOn = on; }

    @Override
    public String toString() {
        return "🚨 " + super.toString() + " (priority " + priority + ")";
    }
}