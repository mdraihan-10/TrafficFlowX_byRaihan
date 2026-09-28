package com.trafficflowx.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A read-only picture of the traffic at one moment: how many vehicles are on each
 * road, how many of them are waiting, and which roads are blocked. It is the RESULT
 * produced by one thread and read by others, so it is immutable.
 */
public record TrafficSnapshot(Map<Direction, Integer> vehiclesByDirection,
                              Map<Direction, Integer> waitingByDirection,
                              Set<Direction> blockedRoads) {

    /** Makes private, unmodifiable copies, so the caller's collections can never change our data. */
    public TrafficSnapshot {
        EnumMap<Direction, Integer> vehicles = new EnumMap<>(Direction.class);
        EnumMap<Direction, Integer> waiting = new EnumMap<>(Direction.class);
        for (Direction d : Direction.values()) {
            vehicles.put(d, vehiclesByDirection.getOrDefault(d, 0));
            waiting.put(d, waitingByDirection.getOrDefault(d, 0));
        }
        EnumSet<Direction> blocked = EnumSet.noneOf(Direction.class);
        blocked.addAll(blockedRoads);

        vehiclesByDirection = Collections.unmodifiableMap(vehicles);
        waitingByDirection = Collections.unmodifiableMap(waiting);
        blockedRoads = Collections.unmodifiableSet(blocked);
    }

    public int vehicles(Direction direction) {
        return vehiclesByDirection.get(direction);
    }

    public int waiting(Direction direction) {
        return waitingByDirection.get(direction);
    }

    public boolean isBlocked(Direction direction) {
        return blockedRoads.contains(direction);
    }

    public int totalWaiting() {
        int total = 0;
        for (int count : waitingByDirection.values()) {
            total += count;
        }
        return total;
    }

    /** The road with the most waiting vehicles, or empty if nobody is waiting. */
    public Optional<Direction> busiestRoad() {
        Direction best = null;
        int bestCount = 0;
        for (Direction d : Direction.values()) {
            if (waiting(d) > bestCount) {
                best = d;
                bestCount = waiting(d);
            }
        }
        return Optional.ofNullable(best);
    }
}