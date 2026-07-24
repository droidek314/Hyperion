package com.hyperion.ecs;

/**
 * Public entry point and factory for the Hyperion ECS.
 *
 * <p>This is the only bootstrap seam exposed to user code:
 * <pre>{@code
 * World world = Hyperion.createWorld();
 * }</pre>
 *
 * <p><b>Status:</b> as of Phase 2 ({@code Perf-Core-Agent}) the factory returns the concrete
 * off-heap engine ({@code com.hyperion.ecs.core.HyperionWorld}) — archetype SoA storage, the
 * 256-bit bitmask query index, and the zero-GC tick loop. The public signatures are unchanged
 * from the Phase-1 contract (ADR-0001).
 *
 * @since Phase 1 (Lead-Architect); implementation wired in Phase 2 (Perf-Core-Agent)
 */
public final class Hyperion {

    private Hyperion() {
        // Non-instantiable factory holder.
    }

    /**
     * Creates a world with {@link WorldConfig#DEFAULT default} configuration.
     *
     * @return a new {@link World}
     */
    public static World createWorld() {
        return createWorld(WorldConfig.DEFAULT);
    }

    /**
     * Creates a world with explicit configuration.
     *
     * @param config the world configuration
     * @return a new {@link World}
     */
    public static World createWorld(WorldConfig config) {
        return new com.hyperion.ecs.core.HyperionWorld(config);
    }
}
