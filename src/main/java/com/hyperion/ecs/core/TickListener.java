package com.hyperion.ecs.core;

/**
 * A per-tick observation hook. Implemented by the DevTools telemetry sink and attached via
 * {@link HyperionWorld#setTickListener(TickListener)}. Keeping the hook as a tiny core-side
 * interface avoids a {@code core → devtools} dependency.
 *
 * <p>The callback runs on the simulation thread immediately after the tick body; implementations
 * must be cheap and allocation-free to honour the &lt; 2% telemetry-overhead guardrail.
 *
 * @since Phase 4 (DevTools-Agent)
 */
@FunctionalInterface
public interface TickListener {

    /**
     * @param world     the world that just ticked
     * @param tickNanos the measured wall-clock duration of the tick body
     */
    void onTick(HyperionWorld world, long tickNanos);
}
