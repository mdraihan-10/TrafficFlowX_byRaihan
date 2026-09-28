package com.trafficflowx.service;

import com.trafficflowx.model.Direction;
import com.trafficflowx.model.Movement;
import com.trafficflowx.model.TrafficDensity;
import com.trafficflowx.model.Vehicle;
import com.trafficflowx.model.VehicleType;
import com.trafficflowx.util.SimulationConfig;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides WHEN and WHAT to spawn on ONE road: its vehicle type and its movement
 * (straight, left or right). Each road thread owns its own generator, so no
 * locking is needed here.
 */
public class VehicleGenerator {

    private static final VehicleType[] EMERGENCY_TYPES =
            {VehicleType.AMBULANCE, VehicleType.FIRE_TRUCK, VehicleType.POLICE_CAR};

    private final Direction direction;
    private double countdownSeconds;

    public VehicleGenerator(Direction direction, TrafficDensity density) {
        this.direction = direction;
        this.countdownSeconds = nextInterval(density) * ThreadLocalRandom.current().nextDouble();
    }

    public Optional<Vehicle> update(double dt, TrafficDensity density, boolean entryClear) {
        countdownSeconds -= dt;
        if (countdownSeconds > 0) {
            return Optional.empty();
        }
        if (!entryClear) {
            countdownSeconds = 0;
            return Optional.empty();
        }
        countdownSeconds = nextInterval(density);
        VehicleType type = randomType();
        // Emergency vehicles always go straight, to keep priority handling simple.
        Movement movement = type.isEmergency() ? Movement.STRAIGHT : randomMovement();
        return Optional.of(Vehicle.create(type, direction, movement));
    }

    private double nextInterval(TrafficDensity density) {
        double factor = ThreadLocalRandom.current()
                .nextDouble(SimulationConfig.INTERVAL_MIN_FACTOR, SimulationConfig.INTERVAL_MAX_FACTOR);
        return density.getMeanSpawnIntervalSeconds() * factor;
    }

    private VehicleType randomType() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() < SimulationConfig.EMERGENCY_CHANCE) {
            return EMERGENCY_TYPES[random.nextInt(EMERGENCY_TYPES.length)];
        }
        double r = random.nextDouble();
        if (r < 0.65) {
            return VehicleType.CAR;
        } else if (r < 0.85) {
            return VehicleType.MOTORCYCLE;
        }
        return VehicleType.BUS;
    }

    /** 60% straight, 20% left (the 'free' turn), 20% right (crosses oncoming traffic). */
    private Movement randomMovement() {
        double r = ThreadLocalRandom.current().nextDouble();
        if (r < 0.60) {
            return Movement.STRAIGHT;
        } else if (r < 0.80) {
            return Movement.LEFT;
        }
        return Movement.RIGHT;
    }
}