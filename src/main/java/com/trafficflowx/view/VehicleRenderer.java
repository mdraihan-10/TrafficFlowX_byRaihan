package com.trafficflowx.view;

import com.trafficflowx.model.Direction;
import com.trafficflowx.model.IntersectionGeometry;
import com.trafficflowx.model.TurnTable;
import com.trafficflowx.model.Vehicle;
import javafx.geometry.Point2D;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the vehicle pictures in sync with the vehicle objects.
 *
 * A vehicle's path has three parts, all driven by ONE number, Vehicle.getDistance():
 *   1. Approach - before the junction, straight, in the entry lane.
 *   2. Junction  - a straight line for STRAIGHT vehicles, or a curve for turns.
 *   3. Egress    - after the junction, straight, in the exit lane, moving away
 *                  from the centre.
 *
 * For a curve, position is paced by ARC LENGTH (not by the entry road's raw
 * distance units), so the vehicle's on-screen speed matches its real speed all
 * the way through the turn instead of speeding up/slowing down and snapping at
 * the boundary.
 * FX-thread only.
 */
public class VehicleRenderer {

    private static final int CURVE_SAMPLES = 20;

    private final IntersectionView view;
    private final Map<Vehicle, VehicleView> nodes = new HashMap<>();

    public VehicleRenderer(IntersectionView view) {
        this.view = view;
    }

    public void sync(Collection<Vehicle> current) {
        Set<Vehicle> alive = new HashSet<>(current);

        Iterator<Map.Entry<Vehicle, VehicleView>> it = nodes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Vehicle, VehicleView> entry = it.next();
            if (!alive.contains(entry.getKey())) {
                view.getVehicleLayer().getChildren().remove(entry.getValue());
                it.remove();
            }
        }

        for (Vehicle vehicle : current) {
            VehicleView node = nodes.get(vehicle);
            if (node == null) {
                node = new VehicleView(vehicle);
                nodes.put(vehicle, node);
                view.getVehicleLayer().getChildren().add(node);
            }
            place(node);
        }
    }

    public void clear() {
        view.getVehicleLayer().getChildren().clear();
        nodes.clear();
    }

    private void place(VehicleView node) {
        Vehicle v = node.getVehicle();
        IntersectionGeometry geometry = view.getGeometry();
        Direction entry = v.getDirection();
        Direction exitTravel = TurnTable.travelDirectionAfterTurn(entry, v.getMovement());

        double distance = v.getDistance();
        double enterAt = geometry.junctionStartDistance(entry);

        Point2D position;
        double angle;

        if (distance <= enterAt) {
            // Approach: still on the entry road, in the entry lane.
            position = view.pointOnRoad(entry, distance);
            angle = VehicleView.rotationFor(entry);

        } else if (entry.isNorthSouthAxis() == exitTravel.isNorthSouthAxis()) {
            // Straight through: entry and exit lie on the same line, so distance
            // already maps 1:1 to screen distance. No curve needed.
            double leaveAt = geometry.junctionEndDistance(entry);
            if (distance >= leaveAt) {
                double outDistance = geometry.junctionEndDistance(exitTravel) + (distance - leaveAt);
                position = view.pointOnRoad(exitTravel, outDistance);
                angle = VehicleView.rotationFor(exitTravel);
            } else {
                Point2D pEnter = view.pointOnRoad(entry, enterAt);
                Point2D pExit = view.pointOnRoad(exitTravel, geometry.junctionEndDistance(exitTravel));
                double t = (distance - enterAt) / (leaveAt - enterAt);
                position = lerp(pEnter, pExit, t);
                angle = VehicleView.rotationFor(entry);
            }

        } else {
            // Turning: curve through the corner, paced by real arc length so the
            // vehicle's on-screen speed stays constant through the whole turn.
            Point2D pEnter = view.pointOnRoad(entry, enterAt);
            Point2D pExit = view.pointOnRoad(exitTravel, geometry.junctionEndDistance(exitTravel));
            Point2D nsPoint = entry.isNorthSouthAxis() ? pEnter : pExit;
            Point2D ewPoint = entry.isNorthSouthAxis() ? pExit : pEnter;
            Point2D control = new Point2D(nsPoint.getX(), ewPoint.getY());

            double[] lengths = cumulativeLengths(pEnter, control, pExit);
            double curveLength = lengths[CURVE_SAMPLES];
            double travelledInCurve = distance - enterAt;

            if (travelledInCurve >= curveLength) {
                double outDistance = geometry.junctionEndDistance(exitTravel) + (travelledInCurve - curveLength);
                position = view.pointOnRoad(exitTravel, outDistance);
                angle = VehicleView.rotationFor(exitTravel);
            } else {
                double t = tForArcLength(lengths, travelledInCurve);
                position = quadraticBezier(pEnter, control, pExit, t);
                double entryAngle = VehicleView.rotationFor(entry);
                double exitAngle = VehicleView.rotationFor(exitTravel);
                angle = entryAngle + shortestDelta(entryAngle, exitAngle) * t;
            }
        }

        node.placeCenterAt(position.getX(), position.getY());
        node.setFacingDegrees(angle);
    }

    // ------------------------------------------------------------------
    // Arc-length helpers
    // ------------------------------------------------------------------

    /** Cumulative screen-pixel length along the curve at CURVE_SAMPLES evenly-spaced points. */
    private double[] cumulativeLengths(Point2D p0, Point2D control, Point2D p2) {
        double[] lengths = new double[CURVE_SAMPLES + 1];
        Point2D prev = p0;
        double total = 0;
        for (int i = 1; i <= CURVE_SAMPLES; i++) {
            double t = (double) i / CURVE_SAMPLES;
            Point2D point = quadraticBezier(p0, control, p2, t);
            total += point.distance(prev);
            lengths[i] = total;
            prev = point;
        }
        return lengths;
    }

    /** Finds the curve parameter t whose arc length equals s (interpolating between samples). */
    private double tForArcLength(double[] lengths, double s) {
        double total = lengths[CURVE_SAMPLES];
        if (total <= 0) {
            return 0;
        }
        s = Math.max(0, Math.min(total, s));
        for (int i = 1; i <= CURVE_SAMPLES; i++) {
            if (lengths[i] >= s) {
                double segStart = lengths[i - 1];
                double segEnd = lengths[i];
                double localT = segEnd > segStart ? (s - segStart) / (segEnd - segStart) : 0;
                return ((i - 1) + localT) / CURVE_SAMPLES;
            }
        }
        return 1.0;
    }

    private Point2D lerp(Point2D a, Point2D b, double t) {
        return a.add(b.subtract(a).multiply(t));
    }

    private Point2D quadraticBezier(Point2D p0, Point2D control, Point2D p2, double t) {
        double u = 1 - t;
        double x = u * u * p0.getX() + 2 * u * t * control.getX() + t * t * p2.getX();
        double y = u * u * p0.getY() + 2 * u * t * control.getY() + t * t * p2.getY();
        return new Point2D(x, y);
    }

    /** Shortest signed angle (degrees) from 'from' to 'to', in the range (-180, 180]. */
    private double shortestDelta(double from, double to) {
        return ((to - from + 540) % 360) - 180;
    }
}