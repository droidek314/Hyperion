package com.hyperion.ecs.query;

import com.hyperion.ecs.Component;

/**
 * Zero-allocation per-entity callback over a two-component query.
 *
 * <p>Backs the target ergonomics:
 * <pre>{@code
 * world.createSystem()
 *      .with(Position.class, Velocity.class)
 *      .each((long id, Position pos, Velocity vel) -> { ... });
 * }</pre>
 *
 * @param <A> the first component type
 * @param <B> the second component type
 * @since Phase 1 (Lead-Architect)
 */
@FunctionalInterface
public interface Each2<A extends Component, B extends Component> {

    /**
     * @param entityId the packed entity handle
     * @param a        the first component view
     * @param b        the second component view
     */
    void apply(long entityId, A a, B b);
}
