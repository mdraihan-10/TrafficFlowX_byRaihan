package com.trafficflowx.view;

import com.trafficflowx.model.Direction;
import com.trafficflowx.model.SignalState;
import javafx.scene.Group;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.EnumMap;
import java.util.Map;
import com.trafficflowx.model.IntersectionGeometry;
import com.trafficflowx.model.RoadStatus;
import javafx.geometry.Point2D;
/**
 * Draws the four-way intersection: roads, lane markings, stop lines and traffic lights.
 * It only DRAWS. It contains no traffic logic.
 *
 * THREAD RULE: every method here changes JavaFX nodes, so it must be called
 * on the JavaFX Application Thread (background threads must use Platform.runLater).
 *
 * The drawing is rebuilt whenever the window size changes, so the crossroad
 * always stays in the centre.
 */
public class IntersectionView extends Pane {

    // ---- Geometry (in pixels) ----
    public static final double LANE_WIDTH = IntersectionGeometry.LANE_WIDTH;
    public static final double ROAD_WIDTH = IntersectionGeometry.ROAD_WIDTH;
    private static final double STOP_LINE_OFFSET = IntersectionGeometry.STOP_LINE_OFFSET;
    private static final double LIGHT_WIDTH = 22;
    private static final double LIGHT_HEIGHT = 62;
    private static final double LIGHT_MARGIN = 8;

    // ---- Colours ----
    private static final Color ASPHALT = Color.web("#3b3f45");
    private static final Color JUNCTION = Color.web("#454a52");
    private static final Color CENTER_LINE = Color.web("#e0c341");
    private static final Color LABEL_COLOR = Color.web("#c9d1dc");
    private static final Color LIGHT_OFF = Color.web("#3a3a3a");

    // ---- Layers: later drawn on top of earlier ones ----
    private final Group roadLayer = new Group();
    private final Group lightLayer = new Group();
    private final Group vehicleLayer = new Group();   // vehicles are added here from Step 5
    private final Group incidentLayer = new Group();   // accidents and road blocks, drawn on top of vehicles
    private final IntersectionGeometry geometry = new IntersectionGeometry();
    private final Map<Direction, RoadStatus> roadStatuses = new EnumMap<>(Direction.class);
    private final Map<Direction, SignalState> signals = new EnumMap<>(Direction.class);

    public IntersectionView() {
        for (Direction d : Direction.values()) {
            roadStatuses.put(d, RoadStatus.OPEN);
        }
        getChildren().addAll(roadLayer, lightLayer, vehicleLayer, incidentLayer);
        // Clip: anything drawn outside the pane (like vehicles leaving) is hidden.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        // Redraw when the size changes.
        widthProperty().addListener((obs, oldValue, newValue) -> redrawAll());
        heightProperty().addListener((obs, oldValue, newValue) -> redrawAll());
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Changes the colour shown by one traffic light. Call on the FX thread. */
    public void setSignal(Direction direction, SignalState state) {
        if (signals.get(direction) == state) {
            return;
        }
        signals.put(direction, state);
        drawLights();
    }

    /** The layer where vehicles will be placed in later steps. */
    public Group getVehicleLayer() {
        return vehicleLayer;
    }
    public IntersectionGeometry getGeometry() {
        return geometry;
    }

    public double centerX() {
        return getWidth() / 2;
    }

    public double centerY() {
        return getHeight() / 2;
    }
    // ------------------------------------------------------------------
    // Positions along a road, measured as 'distance from the entry edge'.
    // This is the same measure that Vehicle.getDistance() uses.
    // ------------------------------------------------------------------


    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    private void redrawAll() {
        geometry.setSize(getWidth(), getHeight());
        drawRoads();
        drawLights();
        drawIncidents();
    }

    private void drawRoads() {
        roadLayer.getChildren().clear();

        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        double cx = w / 2;
        double cy = h / 2;
        double half = ROAD_WIDTH / 2;

        // Two long rectangles that cross each other.
        Rectangle vertical = new Rectangle(cx - half, 0, ROAD_WIDTH, h);
        Rectangle horizontal = new Rectangle(0, cy - half, w, ROAD_WIDTH);
        vertical.setFill(roadGradient(true));
        horizontal.setFill(roadGradient(false));

        // The shared middle square (this becomes the critical section later).
        Rectangle junction = new Rectangle(cx - half, cy - half, ROAD_WIDTH, ROAD_WIDTH);
        junction.setFill(JUNCTION);

        roadLayer.getChildren().addAll(vertical, horizontal, junction);

        // Dashed centre lines on each arm (they stop before the junction).
        roadLayer.getChildren().addAll(
                centerLine(cx, 0, cx, cy - half),
                centerLine(cx, cy + half, cx, h),
                centerLine(0, cy, cx - half, cy),
                centerLine(cx + half, cy, w, cy));

        // Stop lines, only across the lane that enters the junction (left-hand traffic).
        roadLayer.getChildren().addAll(
                stopLine(cx, cy - half - STOP_LINE_OFFSET, cx + half, cy - half - STOP_LINE_OFFSET),   // NORTH
                stopLine(cx - half, cy + half + STOP_LINE_OFFSET, cx, cy + half + STOP_LINE_OFFSET),   // SOUTH
                stopLine(cx + half + STOP_LINE_OFFSET, cy, cx + half + STOP_LINE_OFFSET, cy + half),   // EAST
                stopLine(cx - half - STOP_LINE_OFFSET, cy - half, cx - half - STOP_LINE_OFFSET, cy)); // WEST

        // Road names.
        roadLayer.getChildren().addAll(
                label("NORTH", cx - 24, 24),
                label("SOUTH", cx - 24, h - 12),
                label("WEST", 12, cy - half - 10),
                label("EAST", w - 52, cy - half - 10));
    }

    private void drawLights() {
        lightLayer.getChildren().clear();

        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        double cx = w / 2;
        double cy = h / 2;
        double half = ROAD_WIDTH / 2;

        // One light in each corner, next to the road it controls.
        drawLight(Direction.NORTH, cx + half + LIGHT_MARGIN, cy - half - LIGHT_MARGIN - LIGHT_HEIGHT);
        drawLight(Direction.SOUTH, cx - half - LIGHT_MARGIN - LIGHT_WIDTH, cy + half + LIGHT_MARGIN);
        drawLight(Direction.EAST, cx + half + LIGHT_MARGIN, cy + half + LIGHT_MARGIN);
        drawLight(Direction.WEST, cx - half - LIGHT_MARGIN - LIGHT_WIDTH, cy - half - LIGHT_MARGIN - LIGHT_HEIGHT);
    }

    /** Draws one light box at (x, y): black housing plus three circles (red, yellow, green). */
    private void drawLight(Direction direction, double x, double y) {
        SignalState current = signals.get(direction);

        Rectangle housing = new Rectangle(x, y, LIGHT_WIDTH, LIGHT_HEIGHT);
        housing.setArcWidth(8);
        housing.setArcHeight(8);
        housing.setFill(Color.web("#111111"));
        housing.setStroke(Color.web("#555555"));

        double centerX = x + LIGHT_WIDTH / 2;
        Circle red = bulb(centerX, y + 11, current == SignalState.RED, Color.web("#e53935"));
        Circle yellow = bulb(centerX, y + 31, current == SignalState.YELLOW, Color.web("#fdd835"));
        Circle green = bulb(centerX, y + 51, current == SignalState.GREEN, Color.web("#43a047"));

        lightLayer.getChildren().addAll(housing, red, yellow, green);
    }

    /** Adds a soft glow around a lit bulb, making the active colour easier to read at a glance. */
    private void addGlow(Circle bulbShape, boolean lit, Color color) {
        if (lit) {
            bulbShape.setEffect(new javafx.scene.effect.DropShadow(10, color));
        }
    }
    // ------------------------------------------------------------------
    // Accidents and road blocks
    // ------------------------------------------------------------------

    /** Shows or hides the incident on one road. Call on the FX thread. */
    public void setRoadStatus(Direction direction, RoadStatus status) {
        if (roadStatuses.get(direction) == status) {
            return;   // nothing changed: do not redraw
        }
        roadStatuses.put(direction, status);
        drawIncidents();
    }

    /**
     * Converts (road, distance from the entry edge) into a point on the screen.
     * Left-hand traffic, matching the stop lines drawn above.
     */
    public Point2D pointOnRoad(Direction direction, double distance) {
        double w = getWidth();
        double h = getHeight();
        double cx = centerX();
        double cy = centerY();
        double lane = LANE_WIDTH / 2;   // centre of a lane

        return switch (direction) {
            case NORTH -> new Point2D(cx + lane, distance);        // top -> bottom
            case SOUTH -> new Point2D(cx - lane, h - distance);    // bottom -> top
            case WEST -> new Point2D(distance, cy - lane);         // left -> right
            case EAST -> new Point2D(w - distance, cy + lane);     // right -> left
        };
    }

    private void drawIncidents() {
        incidentLayer.getChildren().clear();
        if (!geometry.isReady()) {
            return;
        }
        for (Direction d : Direction.values()) {
            RoadStatus status = roadStatuses.get(d);
            if (status == RoadStatus.ROAD_BLOCK) {
                drawRoadBlock(d);
            } else if (status == RoadStatus.ACCIDENT) {
                drawAccident(d);
            }
        }
    }

    /** An orange barrier across the approach lane. */
    private void drawRoadBlock(Direction direction) {
        Point2D p = pointOnRoad(direction, geometry.blockPointDistance(direction));
        double length = LANE_WIDTH - 8;   // across the lane
        double thickness = 14;            // along the direction of travel

        Rectangle barrier = direction.isNorthSouthAxis()
                ? new Rectangle(p.getX() - length / 2, p.getY() - thickness / 2, length, thickness)
                : new Rectangle(p.getX() - thickness / 2, p.getY() - length / 2, thickness, length);
        barrier.setArcWidth(4);
        barrier.setArcHeight(4);
        barrier.setFill(Color.web("#e67e22"));
        barrier.setStroke(Color.web("#7a3e00"));
        barrier.setStrokeWidth(2);

        incidentLayer.getChildren().addAll(barrier, incidentLabel(direction, p, "🚧 ROAD BLOCK"));
    }

    /** Two crashed vehicles lying across the lane. */
    private void drawAccident(Direction direction) {
        Point2D p = pointOnRoad(direction, geometry.blockPointDistance(direction));
        double length = 34;
        double width = 18;

        Rectangle first = new Rectangle(p.getX() - length / 2 - 4, p.getY() - width / 2 - 3, length, width);
        first.setRotate(28);
        first.setFill(Color.web("#8d99a6"));

        Rectangle second = new Rectangle(p.getX() - length / 2 + 4, p.getY() - width / 2 + 4, length, width);
        second.setRotate(-24);
        second.setFill(Color.web("#b5545a"));

        for (Rectangle wreck : new Rectangle[]{first, second}) {
            wreck.setArcWidth(6);
            wreck.setArcHeight(6);
            wreck.setStroke(Color.web("#1b1b1b"));
            wreck.setStrokeWidth(1.5);
        }
        incidentLayer.getChildren().addAll(first, second, incidentLabel(direction, p, "💥 ACCIDENT"));
    }

    /** A text label placed on the grass next to the road, so it never covers the lane. */
    private Text incidentLabel(Direction direction, Point2D p, String message) {
        Text text = new Text(message);
        text.setFont(Font.font("System", FontWeight.BOLD, 13));
        text.setFill(Color.web("#ffb74d"));

        double width = text.getLayoutBounds().getWidth();
        double half = ROAD_WIDTH / 2;
        double gap = 10;
        switch (direction) {
            case NORTH -> { text.setX(centerX() + half + gap);         text.setY(p.getY() + 5); }
            case SOUTH -> { text.setX(centerX() - half - gap - width); text.setY(p.getY() + 5); }
            case WEST -> { text.setX(p.getX() - width / 2);            text.setY(centerY() - half - gap); }
            case EAST -> { text.setX(p.getX() - width / 2);            text.setY(centerY() + half + gap + 12); }
        }
        return text;
    }

    // ------------------------------------------------------------------
    // Small drawing helpers
    // ------------------------------------------------------------------

    private Circle bulb(double x, double y, boolean lit, Color litColor) {
        Circle circle = new Circle(x, y, 8, lit ? litColor : LIGHT_OFF);
        addGlow(circle, lit, litColor);
        return circle;
    }

    private Line centerLine(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.setStroke(CENTER_LINE);
        line.setStrokeWidth(3);
        line.getStrokeDashArray().addAll(16.0, 12.0);   // 16 px dash, 12 px gap
        return line;
    }

    private Line stopLine(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.setStroke(Color.WHITE);
        line.setStrokeWidth(4);
        return line;
    }

    private Text label(String text, double x, double y) {
        Text t = new Text(x, y, text);
        t.setFont(Font.font("System", FontWeight.BOLD, 14));
        t.setFill(LABEL_COLOR);
        return t;
    }
    /** A subtle gradient across the road width, giving a slight 3D feel. */
    private javafx.scene.paint.LinearGradient roadGradient(boolean vertical) {
        return new javafx.scene.paint.LinearGradient(
                0, 0, vertical ? 1 : 0, vertical ? 0 : 1, true,
                javafx.scene.paint.CycleMethod.NO_CYCLE,
                new javafx.scene.paint.Stop(0, Color.web("#33373d")),
                new javafx.scene.paint.Stop(0.5, Color.web("#3f4349")),
                new javafx.scene.paint.Stop(1, Color.web("#33373d")));
    }
}