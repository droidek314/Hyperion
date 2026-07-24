package com.hyperion.ecs.query;

import com.hyperion.ecs.storage.ComponentBitmask;

/**
 * A registered, executable system handle — the result of completing a {@link QueryBuilder}
 * chain with a terminal {@code each(...)} callback.
 *
 * <p>The engine matches archetypes against this query in {@code O(1)} via a bitwise {@code AND}
 * of the query's {@link #signature()} against each archetype's component bitmask. Execution
 * iterates matching archetype chunks and invokes the registered callback per entity row —
 * with <b>zero heap allocation</b>.
 *
 * <p>The handle is retained so a system can be toggled or run outside the main
 * {@link com.hyperion.ecs.World#tick() tick} loop (e.g. by tooling).
 *
 * <p><b>Immutable Signature</b> — governed by ADR-0003.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface Query {

    /**
     * @return the 256-bit component signature this query requires
     */
    ComponentBitmask signature();

    /**
     * @return the number of entities currently matching this query across all archetypes
     */
    long matchCount();

    /**
     * Executes the query's callback once over all matching entities, out of band from
     * {@link com.hyperion.ecs.World#tick()}.
     */
    void run();

    /** Enables this system so it participates in {@link com.hyperion.ecs.World#tick()}. */
    void enable();

    /** Disables this system so it is skipped during {@link com.hyperion.ecs.World#tick()}. */
    void disable();

    /**
     * @return {@code true} if this system runs during {@link com.hyperion.ecs.World#tick()}
     */
    boolean isEnabled();
}
