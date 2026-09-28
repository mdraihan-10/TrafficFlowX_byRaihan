package com.trafficflowx.model;

/**
 * One row of simulation history: a summary of a completed run.
 * id is -1 for a record not yet saved (SQLite assigns the real id on insert).
 */
public record SimulationRunRecord(
        int id,
        String runDate,
        long durationSeconds,
        String trafficDensity,
        int totalVehicles,
        int passedVehicles,
        double averageWaitingSeconds,
        double maxWaitingSeconds,
        int emergencyVehicles,
        int accidents,
        int roadBlocks,
        int junctionConflicts,
        boolean adaptiveSignals
) {
}