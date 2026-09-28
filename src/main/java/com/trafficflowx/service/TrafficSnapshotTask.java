package com.trafficflowx.service;

import com.trafficflowx.model.Direction;
import com.trafficflowx.model.Road;
import com.trafficflowx.model.TrafficSnapshot;
import com.trafficflowx.model.Vehicle;
import com.trafficflowx.model.VehicleState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * A Callable: a job that RETURNS a result (a Runnable cannot).
 * It only reads from the roads through Road.snapshot(), which is synchronized,
 * so it is safe to run while the road threads keep moving vehicles.
 */
class TrafficSnapshotTask implements Callable<TrafficSnapshot> {

    private final Map<Direction, Road> roads;   // never modified after start-up, so safe to read

    TrafficSnapshotTask(Map<Direction, Road> roads) {
        this.roads = roads;
    }

    @Override
    public TrafficSnapshot call() {
        Map<Direction, Integer> vehicles = new EnumMap<>(Direction.class);
        Map<Direction, Integer> waiting = new EnumMap<>(Direction.class);
        Set<Direction> blocked = EnumSet.noneOf(Direction.class);

        for (Map.Entry<Direction, Road> entry : roads.entrySet()) {
            List<Vehicle> onRoad = entry.getValue().snapshot();
            int waitingCount = 0;
            for (Vehicle v : onRoad) {
                if (v.getState() == VehicleState.WAITING) {
                    waitingCount++;
                }
            }
            vehicles.put(entry.getKey(), onRoad.size());
            waiting.put(entry.getKey(), waitingCount);
            if (entry.getValue().isBlocked()) {
                blocked.add(entry.getKey());
            }
        }
        return new TrafficSnapshot(vehicles, waiting, blocked);
    }
}