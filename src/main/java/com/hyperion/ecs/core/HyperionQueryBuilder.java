package com.hyperion.ecs.core;

import com.hyperion.ecs.Component;
import com.hyperion.ecs.query.Each1;
import com.hyperion.ecs.query.Each2;
import com.hyperion.ecs.query.Each3;
import com.hyperion.ecs.query.Each4;
import com.hyperion.ecs.query.Query;
import com.hyperion.ecs.query.QueryBuilder;

/**
 * Concrete {@link QueryBuilder}. Each {@code with(...)} overload captures the component classes
 * and returns the matching typed stage; the terminal {@code each(...)} builds the signature and
 * registers a compatibility (record) {@link RegisteredSystem} with the world.
 *
 * <p>The zero-GC primitive path is registered separately via
 * {@link HyperionWorld#createSystem(ChunkFunction, Class[])}; this builder serves the Phase-1
 * ergonomic API surface (ADR-0003 / ADR-0005).
 *
 * @since Phase 2 (Perf-Core-Agent)
 */
final class HyperionQueryBuilder implements QueryBuilder {

    private final HyperionWorld world;

    HyperionQueryBuilder(HyperionWorld world) {
        this.world = world;
    }

    @Override
    public <A extends Component> Stage1<A> with(Class<A> a) {
        return new StageImpl1<>(world, a);
    }

    @Override
    public <A extends Component, B extends Component> Stage2<A, B> with(Class<A> a, Class<B> b) {
        return new StageImpl2<>(world, a, b);
    }

    @Override
    public <A extends Component, B extends Component, C extends Component> Stage3<A, B, C> with(
            Class<A> a, Class<B> b, Class<C> c) {
        return new StageImpl3<>(world, a, b, c);
    }

    @Override
    public <A extends Component, B extends Component, C extends Component, D extends Component> Stage4<A, B, C, D> with(
            Class<A> a, Class<B> b, Class<C> c, Class<D> d) {
        return new StageImpl4<>(world, a, b, c, d);
    }

    // ── Shared stage state ───────────────────────────────────────────────────────────────

    private abstract static class BaseStage {
        final HyperionWorld world;
        final Class<? extends Component>[] included;
        Class<? extends Component>[] excluded;

        @SafeVarargs
        BaseStage(HyperionWorld world, Class<? extends Component>... included) {
            this.world = world;
            this.included = included;
        }

        final Query register(Object eachFn, int arity) {
            return world.registerEach(included, excluded, eachFn, arity);
        }
    }

    private static final class StageImpl1<A extends Component> extends BaseStage implements Stage1<A> {
        StageImpl1(HyperionWorld world, Class<A> a) {
            super(world, a);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Stage1<A> without(Class<? extends Component>... exclude) {
            this.excluded = exclude;
            return this;
        }

        @Override
        public Query each(Each1<A> fn) {
            return register(fn, 1);
        }
    }

    private static final class StageImpl2<A extends Component, B extends Component> extends BaseStage
            implements Stage2<A, B> {
        StageImpl2(HyperionWorld world, Class<A> a, Class<B> b) {
            super(world, a, b);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Stage2<A, B> without(Class<? extends Component>... exclude) {
            this.excluded = exclude;
            return this;
        }

        @Override
        public Query each(Each2<A, B> fn) {
            return register(fn, 2);
        }
    }

    private static final class StageImpl3<A extends Component, B extends Component, C extends Component>
            extends BaseStage implements Stage3<A, B, C> {
        StageImpl3(HyperionWorld world, Class<A> a, Class<B> b, Class<C> c) {
            super(world, a, b, c);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Stage3<A, B, C> without(Class<? extends Component>... exclude) {
            this.excluded = exclude;
            return this;
        }

        @Override
        public Query each(Each3<A, B, C> fn) {
            return register(fn, 3);
        }
    }

    private static final class StageImpl4<A extends Component, B extends Component,
            C extends Component, D extends Component> extends BaseStage implements Stage4<A, B, C, D> {
        StageImpl4(HyperionWorld world, Class<A> a, Class<B> b, Class<C> c, Class<D> d) {
            super(world, a, b, c, d);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Stage4<A, B, C, D> without(Class<? extends Component>... exclude) {
            this.excluded = exclude;
            return this;
        }

        @Override
        public Query each(Each4<A, B, C, D> fn) {
            return register(fn, 4);
        }
    }
}
