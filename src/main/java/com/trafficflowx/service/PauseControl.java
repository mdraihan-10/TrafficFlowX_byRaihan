package com.trafficflowx.service;

/**
 * Lets the GUI pause and resume all background threads.
 *
 * A paused thread calls wait() and SLEEPS until notifyAll() wakes it, so it uses
 * no CPU while paused. The 'while' loop is required: a thread can wake up when
 * paused is still true (for example a spurious wake-up), so it must re-check.
 *
 * sleep() vs wait():
 *   sleep(ms) - pause for a fixed time and KEEP holding any lock.
 *   wait()    - pause until another thread calls notifyAll(); it RELEASES the lock
 *               while waiting, so other threads can change 'paused' in the meantime.
 */
public class PauseControl {

    private boolean paused;   // guarded by 'this'

    public synchronized void pause() {
        paused = true;
    }

    public synchronized void resume() {
        paused = false;
        notifyAll();   // wake every waiting thread
    }

    /** Same as resume(), used when the simulation is reset. */
    public synchronized void reset() {
        resume();
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /**
     * Returns immediately if not paused. Otherwise blocks until resume().
     *
     * @return true if the caller had to wait (so it should restart its time measurement)
     * @throws InterruptedException if the thread is interrupted while waiting (shutdown request)
     */
    public synchronized boolean awaitIfPaused() throws InterruptedException {
        boolean waited = false;
        while (paused) {
            waited = true;
            wait();
        }
        return waited;
    }
}