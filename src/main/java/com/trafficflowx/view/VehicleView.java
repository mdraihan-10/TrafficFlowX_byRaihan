package com.trafficflowx.view;

import com.trafficflowx.model.Direction;
import com.trafficflowx.model.Vehicle;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeType;

/**
 * The picture of ONE vehicle, drawn facing right (+x). VehicleRenderer moves and
 * rotates it every frame via placeCenterAt()/setFacingDegrees(), because a
 * turning vehicle's facing angle changes as it crosses the junction.
 * Only the JavaFX Application Thread may touch this object.
 */
public class VehicleView extends Group {

    private final Vehicle vehicle;
    private final double length;
    private final double width;

    public VehicleView(Vehicle vehicle) {
        this.vehicle = vehicle;
        this.length = vehicle.getType().getLength();
        this.width = vehicle.getType().getWidth();

        Rectangle body = new Rectangle(0, 0, length, width);
        body.setArcWidth(8);
        body.setArcHeight(8);
        body.setFill(Color.web(vehicle.getType().getColorHex()));
        body.setStroke(vehicle.isEmergency() ? Color.web("#e53935") : Color.web("#1b1b1b"));
        body.setStrokeWidth(vehicle.isEmergency() ? 2 : 1);
        body.setStrokeType(StrokeType.INSIDE);

        Rectangle windshield = new Rectangle(length * 0.68, 2, length * 0.18, width - 4);
        windshield.setFill(Color.web("#000000", 0.45));

        getChildren().addAll(body, windshield);

        if (vehicle.isEmergency()) {
            Circle siren = new Circle(length * 0.4, width / 2, 3.5, Color.web("#ff1744"));
            getChildren().add(siren);
        }
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    /** Puts the CENTRE of the vehicle at (x, y). */
    public void placeCenterAt(double x, double y) {
        setLayoutX(x - length / 2);
        setLayoutY(y - width / 2);
    }

    /** Rotates to face the given angle (JavaFX degrees, clockwise, 0 = facing right). */
    public void setFacingDegrees(double degrees) {
        setRotate(degrees);
    }

    /** JavaFX rotation is clockwise in degrees. 0 = facing right. */
    public static double rotationFor(Direction from) {
        return switch (from) {
            case NORTH -> 90;    // travelling down
            case SOUTH -> 270;   // travelling up
            case WEST -> 0;      // travelling right
            case EAST -> 180;    // travelling left
        };
    }
}