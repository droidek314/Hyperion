package com.hyperion.ecs;

/**
 * Immutable construction parameters for a {@link World}, consumed by
 * {@link Hyperion#createWorld(WorldConfig)}.
 *
 * <p>These values size the off-heap arenas up-front so that the steady-state simulation never
 * needs to grow a buffer during {@link World#tick()}.
 *
 * @param initialEntityCapacity number of entity slots to pre-allocate off-heap
 * @param maxComponentTypes     upper bound on distinct component types (bounded by the
 *                              256-bit archetype bitmask, see
 *                              {@link com.hyperion.ecs.storage.ComponentBitmask#BIT_WIDTH})
 * @param rewindFrames          ring-buffer depth in frames; {@code 0} disables rewind
 * @since Phase 1 (Lead-Architect)
 */
public record WorldConfig(
        long initialEntityCapacity,
        int maxComponentTypes,
        int rewindFrames
) {

    /** Default configuration targeting the 1,000,000-entity throughput invariant. */
    public static final WorldConfig DEFAULT = new WorldConfig(1_000_000L, 256, 0);
}
