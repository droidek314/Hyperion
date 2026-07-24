package com.hyperion.ecs.query;

import com.hyperion.ecs.Component;

/**
 * Zero-allocation per-entity callback over a four-component query.
 *
 * @param <A> the first component type
 * @param <B> the second component type
 * @param <C> the third component type
 * @param <D> the fourth component type
 * @since Phase 1 (Lead-Architect)
 */
@FunctionalInterface
public interface Each4<A extends Component, B extends Component, C extends Component, D extends Component> {

    /**
     * @param entityId the packed entity handle
     * @param a        the first component view
     * @param b        the second component view
     * @param c        the third component view
     * @param d        the fourth component view
     */
    void apply(long entityId, A a, B b, C c, D d);
}
