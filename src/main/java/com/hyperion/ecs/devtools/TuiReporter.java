package com.hyperion.ecs.devtools;

/**
 * Renders a compact single-line text dashboard from a {@link Telemetry} sink — the "TUI" half of the
 * DevTools telemetry surface. Reads only volatile counters, so it never perturbs the tick thread.
 *
 * <pre>
 *   HYPERION | frame  1200 | ent 1,000,000 | arch 1 | tick 6.71ms (avg 6.68) | 149.7 FPS | rewind 120/120
 * </pre>
 *
 * @since Phase 4 (DevTools-Agent)
 */
public final class TuiReporter {

    private final Telemetry telemetry;

    public TuiReporter(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /** @return a one-line status string for the current telemetry sample. */
    public String render() {
        return String.format(
                "HYPERION | frame %6d | ent %,d | arch %d | tick %.2fms (avg %.2f) | %.1f FPS | rewind %d/%d",
                telemetry.frame(),
                telemetry.entities(),
                telemetry.archetypes(),
                telemetry.lastTickMillis(),
                telemetry.avgTickMillis(),
                telemetry.fps(),
                telemetry.rewindRetained(),
                telemetry.rewindCapacity());
    }

    /** Prints one dashboard line, carriage-returned for in-place TUI refresh. */
    public void printInPlace() {
        System.out.print("\r" + render());
        System.out.flush();
    }
}
