package com.hyperion.ecs.query;

import com.hyperion.ecs.Component;

/**
 * Zero-allocation per-entity callback over a single-component query.
 *
 * <p>The {@code entityId} is passed as a raw primitive {@code long} to avoid boxing. The
 * component argument is a mutable off-heap <em>view</em> supplied by the DX layer (Phase 3);
 * writing through it mutates the backing SoA buffer in place. See ADR-0005.
 *
 * @param <A> the component type
 * @since Phase 1 (Lead-Architect)
 */
@FunctionalInterface
public interface Each1<A extends Component> {

    /**
     * @param entityId the packed entity handle
     * @param a        the component view for this entity
     */
    void apply(long entityId, A a);
}
