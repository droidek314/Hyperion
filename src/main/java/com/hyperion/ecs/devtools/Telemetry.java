package com.hyperion.ecs.devtools;

import com.hyperion.ecs.core.HyperionWorld;
import com.hyperion.ecs.core.OffHeapRewindBuffer;
import com.hyperion.ecs.core.TickListener;

/**
 * Lightweight, allocation-free telemetry sink for the simulation loop (Phase 4, DevTools-Agent).
 *
 * <p>The per-tick hook ({@link #record}) does only a handful of primitive field reads/writes and no
 * allocation, so it stays comfortably under the <b>&lt; 2% overhead</b> guardrail. Fields are
 * {@code volatile} so a telemetry consumer (TUI or WebSocket server) on another thread observes
 * fresh values without locking. Serialization ({@link #toJson()}) happens off the tick thread and is
 * the only place that allocates.
 *
 * @since Phase 4 (DevTools-Agent)
 */
public final class Telemetry implements TickListener {

    private volatile long frame;
    private volatile long lastTickNanos;
    private volatile double emaTickNanos;   // exponential moving average, smoothing factor 1/16
    private volatile long entities;
    private volatile int archetypes;
    private volatile int rewindRetained;
    private volatile int rewindCapacity;

    /**
     * Records one completed tick. Called from {@code HyperionWorld.tick()} with the measured
     * duration; performs no allocation.
     *
     * @param world       the world (cheap primitive counters are read)
     * @param tickNanos   the wall-clock duration of the tick body
     */
    @Override
    public void onTick(HyperionWorld world, long tickNanos) {
        record(world, tickNanos);
    }

    public void record(HyperionWorld world, long tickNanos) {
        frame++;
        lastTickNanos = tickNanos;
        double ema = emaTickNanos;
        emaTickNanos = (ema == 0) ? tickNanos : ema + (tickNanos - ema) / 16.0;
        entities = world.entityCount();
        archetypes = world.archetypeCount();
        OffHeapRewindBuffer rb = world.rewindBuffer();
        if (rb != null) {
            rewindRetained = rb.retainedFrames();
            rewindCapacity = rb.capacityFrames();
        }
    }

    public long frame() {
        return frame;
    }

    public double lastTickMillis() {
        return lastTickNanos / 1_000_000.0;
    }

    public double avgTickMillis() {
        return emaTickNanos / 1_000_000.0;
    }

    public double fps() {
        double ms = emaTickNanos / 1_000_000.0;
        return ms > 0 ? 1000.0 / ms : 0.0;
    }

    public long entities() {
        return entities;
    }

    public int archetypes() {
        return archetypes;
    }

    public int rewindRetained() {
        return rewindRetained;
    }

    public int rewindCapacity() {
        return rewindCapacity;
    }

    /** Serializes the current sample to a compact JSON object (off the tick thread). */
    public String toJson() {
        return "{\"frame\":" + frame
                + ",\"entities\":" + entities
                + ",\"archetypes\":" + archetypes
                + ",\"tickMs\":" + round3(lastTickMillis())
                + ",\"avgTickMs\":" + round3(avgTickMillis())
                + ",\"fps\":" + round1(fps())
                + ",\"rewind\":{\"retained\":" + rewindRetained + ",\"capacity\":" + rewindCapacity + "}}";
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
