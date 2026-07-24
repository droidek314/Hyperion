package com.hyperion.ecs.dsl;

import com.hyperion.ecs.Entity;
import com.hyperion.ecs.Relation;
import com.hyperion.ecs.core.HyperionWorld;

import java.util.function.LongConsumer;

/**
 * Ergonomic graph relationship query syntax (Phase 3).
 *
 * <pre>{@code
 * world.link(hunter, Targeting.class, prey);
 *
 * Relations.of(world, hunter)
 *          .targets(Targeting.class, target -> attack(hunter, target));
 * }</pre>
 *
 * <p>Iterates the live targets linked from a source entity via a relation type, decoding the
 * composite edge index maintained by the engine. Entity handles are delivered as raw primitive
 * {@code long}s (no boxing at the call boundary). This is a query-path convenience, not part of
 * the zero-GC tick loop.
 *
 * @since Phase 3 (DX-Agent)
 */
public final class Relations {

    private final HyperionWorld world;
    private final long source;

    private Relations(HyperionWorld world, long source) {
        this.world = world;
        this.source = source;
    }

    /**
     * @param world  the world
     * @param source the source entity handle
     * @return a relationship query rooted at {@code source}
     */
    public static Relations of(HyperionWorld world, long source) {
        return new Relations(world, source);
    }

    /**
     * Visits each live target linked from the source via {@code relation}.
     *
     * @param relation the relation type
     * @param action   invoked with each live target handle
     * @return this, for chaining across relation types
     */
    public Relations targets(Class<? extends Relation> relation, LongConsumer action) {
        world.related(source, relation, action);
        return this;
    }

    /**
     * @param relation the relation type
     * @return the number of live targets linked via {@code relation}
     */
    public int count(Class<? extends Relation> relation) {
        int[] n = {0};
        world.related(source, relation, t -> n[0]++);
        return n[0];
    }

    /**
     * @param relation the relation type
     * @return the first live target, or {@link Entity#NULL} if none
     */
    public long first(Class<? extends Relation> relation) {
        long[] found = {Entity.NULL};
        world.related(source, relation, t -> {
            if (found[0] == Entity.NULL) {
                found[0] = t;
            }
        });
        return found[0];
    }
}
