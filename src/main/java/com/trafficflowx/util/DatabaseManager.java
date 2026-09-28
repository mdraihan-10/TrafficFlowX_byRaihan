package com.trafficflowx.util;

import com.trafficflowx.model.SimulationRunRecord;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Saves and loads simulation run history using SQLite.
 *
 * A new JDBC connection is opened per call. SQLite connections are cheap, and this
 * avoids keeping one connection object alive (and threaded) for the whole application.
 *
 * Every method here does BLOCKING file I/O, so callers must run them off the
 * JavaFX Application Thread. SimulationManager and HistoryController both use a
 * dedicated background thread for this.
 */
public final class DatabaseManager {

    private static final String DB_DIRECTORY = System.getProperty("user.home") + File.separator + ".trafficflowx";
    private static final String DB_PATH = DB_DIRECTORY + File.separator + "history.db";
    private static final String JDBC_URL = "jdbc:sqlite:" + DB_PATH;

    private static volatile boolean initialized = false;

    private DatabaseManager() {
    }

    /** Creates the database folder, file and table if they don't exist yet. Safe to call more than once. */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        new File(DB_DIRECTORY).mkdirs();
        try (Connection connection = DriverManager.getConnection(JDBC_URL);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS simulation_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        run_date TEXT NOT NULL,
                        duration_seconds INTEGER NOT NULL,
                        traffic_density TEXT NOT NULL,
                        total_vehicles INTEGER NOT NULL,
                        passed_vehicles INTEGER NOT NULL,
                        average_waiting_seconds REAL NOT NULL,
                        max_waiting_seconds REAL NOT NULL,
                        emergency_vehicles INTEGER NOT NULL,
                        accidents INTEGER NOT NULL,
                        road_blocks INTEGER NOT NULL,
                        junction_conflicts INTEGER NOT NULL,
                        adaptive_signals INTEGER NOT NULL
                    )
                    """);
            initialized = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not initialize the history database: " + e.getMessage(), e);
        }
    }

    /** Inserts one completed run. */
    public static void insertRun(SimulationRunRecord record) {
        String sql = """
                INSERT INTO simulation_runs
                    (run_date, duration_seconds, traffic_density, total_vehicles, passed_vehicles,
                     average_waiting_seconds, max_waiting_seconds, emergency_vehicles, accidents,
                     road_blocks, junction_conflicts, adaptive_signals)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = DriverManager.getConnection(JDBC_URL);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.runDate());
            statement.setLong(2, record.durationSeconds());
            statement.setString(3, record.trafficDensity());
            statement.setInt(4, record.totalVehicles());
            statement.setInt(5, record.passedVehicles());
            statement.setDouble(6, record.averageWaitingSeconds());
            statement.setDouble(7, record.maxWaitingSeconds());
            statement.setInt(8, record.emergencyVehicles());
            statement.setInt(9, record.accidents());
            statement.setInt(10, record.roadBlocks());
            statement.setInt(11, record.junctionConflicts());
            statement.setInt(12, record.adaptiveSignals() ? 1 : 0);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not save the run to history: " + e.getMessage(), e);
        }
    }

    /** Every saved run, most recent first. */
    public static List<SimulationRunRecord> getAllRuns() {
        String sql = "SELECT * FROM simulation_runs ORDER BY id DESC";
        List<SimulationRunRecord> runs = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(JDBC_URL);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                runs.add(new SimulationRunRecord(
                        rs.getInt("id"),
                        rs.getString("run_date"),
                        rs.getLong("duration_seconds"),
                        rs.getString("traffic_density"),
                        rs.getInt("total_vehicles"),
                        rs.getInt("passed_vehicles"),
                        rs.getDouble("average_waiting_seconds"),
                        rs.getDouble("max_waiting_seconds"),
                        rs.getInt("emergency_vehicles"),
                        rs.getInt("accidents"),
                        rs.getInt("road_blocks"),
                        rs.getInt("junction_conflicts"),
                        rs.getInt("adaptive_signals") == 1
                ));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not load history: " + e.getMessage(), e);
        }
        return runs;
    }

    /** Deletes every saved run. Used by the "Clear History" button. */
    public static void clearAll() {
        try (Connection connection = DriverManager.getConnection(JDBC_URL);
             Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM simulation_runs");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not clear history: " + e.getMessage(), e);
        }
    }
}