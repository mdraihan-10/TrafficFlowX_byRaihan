package com.trafficflowx.service;

import com.trafficflowx.util.SimulationConfig;

/**
 * The job of the traffic-signal thread: keep the light cycle moving.
 * Same loop shape as DirectionTask: sleep, honour pause, measure time, update.
 */
class SignalTask implements Runnable {

    private final SimulationManager manager;

    SignalTask(SimulationManager manager) {
        this.manager = manager;
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

                manager.getSignalController().update(dt);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // shutdown request: leave the loop
        }
    }
}