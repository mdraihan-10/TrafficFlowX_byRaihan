package com.trafficflowx.service;

import com.trafficflowx.model.Direction;
import com.trafficflowx.model.Intersection;
import com.trafficflowx.model.IntersectionGeometry;
import com.trafficflowx.model.Road;
import com.trafficflowx.model.Vehicle;
import com.trafficflowx.model.VehicleState;
import com.trafficflowx.util.SimulationConfig;

import java.util.Optional;

/**
 * The job of ONE road thread: generate vehicles, move them, and report emergency
 * vehicles to the signal controller. There are four of these, one per direction,
 * all running at the same time.
 */
class DirectionTask implements Runnable {

    private final Direction direction;
    private final SimulationManager manager;
    private final Road road;
    private final VehicleGenerator generator;

    /** The last emergency priority we told the signal controller about (0 = none). */
    private int lastReportedPriority = 0;

    DirectionTask(Direction direction, SimulationManager manager) {
        this.direction = direction;
        this.manager = manager;
        this.road = manager.getRoad(direction);
        this.generator = new VehicleGenerator(direction, manager.getDensity());
    }

    @Override
    public void run() {
        long last = System.nanoTime();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(SimulationConfig.TICK_MILLIS);

                boolean wasPaused = manager.getPauseControl().awaitIfPaused();
                long now = System.nanoTime();
                if (wasPaused) {
                    last = now;
                    continue;
                }
                double dt = Math.min((now - last) / 1_000_000_000.0, SimulationConfig.MAX_FRAME_SECONDS);
                last = now;

                if (manager.getGeometry().isReady()) {
                    spawn(dt);
                    moveVehicles(dt);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void spawn(double dt) {
        Optional<Vehicle> created = generator.update(dt, manager.getDensity(), road.isEntryClear());
        if (created.isEmpty()) {
            return;
        }
        Vehicle vehicle = created.get();
        if (road.addIfEntryClear(vehicle)) {
            manager.getStatistics().recordSpawn(vehicle);
        }
    }

    /**
     * Moves every vehicle of this road, front vehicle first.
     * Each rule can only REDUCE how far a vehicle may move this tick:
     *   Rule 1 - car following: keep VEHICLE_GAP behind the vehicle ahead.
     *   Rule 2 - traffic light: do not pass the stop line unless the light is GREEN.
     *   Rule 4 - incident: on a blocked road, stop in front of the accident / road block
     *            unless the vehicle is already past it.
     *   Rule 3 - junction access: to cross the stop line the vehicle must get
     *            permission from the Intersection (mutual exclusion).
     *
     * All four rules only depend on the vehicle's ENTRY road, so a turning vehicle
     * follows the same rules as a straight one right up to the stop line. Only
     * where the vehicle is considered to have left the screen depends on its
     * actual exit road (it may have turned).
     */
    private void moveVehicles(double dt) throws InterruptedException {
        IntersectionGeometry geometry = manager.getGeometry();
        Intersection intersection = manager.getIntersection();

        boolean green = manager.areSignalsIgnored()
                || manager.getSignalController().getLight(direction).isGreen();
        double stopLine = geometry.stopLineDistance(direction);
        double junctionEnd = geometry.junctionEndDistance(direction);

        boolean blocked = road.isBlocked();
        double blockPoint = geometry.blockPointDistance(direction);
        double blockStop = blockPoint - SimulationConfig.INCIDENT_CLEARANCE;

        Vehicle leader = null;
        Vehicle topEmergency = null;
        boolean stuckEmergency = false;

        for (Vehicle v : road.snapshot()) {
            double half = v.getType().getLength() / 2;
            double front = v.getDistance() + half;
            double rear = v.getDistance() - half;
            double wanted = v.getSpeed() * dt;
            double allowed = wanted;
            boolean permit = v.getState() == VehicleState.CROSSING;

            // Rule 1: keep the gap to the vehicle ahead.
            if (leader != null) {
                double freeSpace = leader.getDistance() - leader.getType().getLength() / 2
                        - half - v.getDistance() - SimulationConfig.VEHICLE_GAP;
                allowed = Math.max(0, Math.min(allowed, freeSpace));
            }

            // Rule 2: RED or YELLOW -> stop at the line, unless already past it.
            if (!green && front <= stopLine + 0.01) {
                allowed = Math.min(allowed, Math.max(0, stopLine - front));
            }

            // Rule 4: blocked road -> stop in front of the incident, unless already past it.
            if (blocked && front <= blockPoint) {
                allowed = Math.min(allowed, Math.max(0, blockStop - front));
            }

            // Rule 3: about to cross the stop line without permission? Ask the intersection.
            if (green && !permit && rear < junctionEnd && front + allowed > stopLine) {
                if (intersection.enter(v, SimulationConfig.ENTRY_WAIT_MILLIS)) {
                    permit = true;
                    v.setState(VehicleState.CROSSING);
                } else {
                    allowed = Math.min(allowed, Math.max(0, stopLine - front));
                }
            }

            v.advance(allowed);

            // Rear has left the junction (plus a clearance buffer): give the permission back.
            if (permit && v.getDistance() - half >= junctionEnd + SimulationConfig.EGRESS_CLEARANCE) {
                intersection.exit(v);
                permit = false;
            }

            boolean stopped = allowed < wanted * 0.1;
            if (permit) {
                v.setState(VehicleState.CROSSING);
            } else if (stopped && v.getDistance() > 0) {
                v.setState(VehicleState.WAITING);
                v.addWaitingTime(Math.round(dt * 1000));
            } else {
                v.setState(VehicleState.APPROACHING);
            }

            // Left the screen? Measured along the vehicle's OWN exit road, since it may have turned.
            double exitDistance = geometry.exitDistance(direction, v.getExitDirection());
            if (v.getDistance() > exitDistance + half) {
                if (permit) {
                    intersection.exit(v);
                }
                v.setState(VehicleState.PASSED);
                road.remove(v);
                manager.getStatistics().recordPassed(v);
            } else {
                leader = v;
                if (v.isEmergency() && v.getDistance() - half < junctionEnd) {
                    boolean stuckBehindIncident = blocked && v.getDistance() + half <= blockPoint;
                    if (stuckBehindIncident) {
                        stuckEmergency = true;
                    } else if (topEmergency == null || v.getPriority() > topEmergency.getPriority()) {
                        topEmergency = v;
                    }
                }
            }
        }

        reportEmergency(topEmergency, stuckEmergency);
    }

    private void reportEmergency(Vehicle top, boolean stuckBehindIncident) {
        int priority = (top == null) ? 0 : top.getPriority();
        if (priority == lastReportedPriority) {
            return;
        }
        manager.getSignalController().reportEmergency(direction, priority);

        if (priority > 0 && lastReportedPriority == 0) {
            String message = "🚨 " + top.getType().getDisplayName() + " detected on " + direction;
            manager.getStatistics().recordEmergencyEvent(message);
            manager.announce(message);
        } else if (priority == 0) {
            String message = stuckBehindIncident
                    ? "Emergency vehicle on " + direction + " stuck behind an incident: priority released."
                    : "Emergency vehicle from " + direction + " crossed. Normal signals resumed.";
            manager.getStatistics().recordEmergencyEvent(message);
            manager.announce(message);
        }
        lastReportedPriority = priority;
    }
}