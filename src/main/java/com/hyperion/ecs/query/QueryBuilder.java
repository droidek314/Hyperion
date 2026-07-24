package com.hyperion.ecs.query;

import com.hyperion.ecs.Component;

/**
 * Fluent, type-safe DSL for declaring a query-driven system. Returned by
 * {@link com.hyperion.ecs.World#createSystem()}.
 *
 * <p>Each {@code with(...)} overload captures the component set at compile time and returns a
 * typed {@link Stage} whose terminal {@code each(...)} accepts a matching arity-{@code N}
 * callback. This yields fully generic, boxing-free lambdas while keeping system setup within
 * the DX-Agent's &le; 3-line budget:
 * <pre>{@code
 * world.createSystem()
 *      .with(Position.class, Velocity.class)          // Stage2<Position, Velocity>
 *      .each((long id, Position pos, Velocity vel) -> { ... });
 * }</pre>
 *
 * <p>The stages are a {@code sealed} hierarchy: the closed set of supported arities (1–4) is
 * part of the API contract. Higher arities require an ADR revision.
 *
 * <p><b>Immutable Signature</b> — governed by ADR-0003.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface QueryBuilder {

    /** Declares a single required component. */
    <A extends Component> Stage1<A> with(Class<A> a);

    /** Declares two required components. */
    <A extends Component, B extends Component> Stage2<A, B> with(Class<A> a, Class<B> b);

    /** Declares three required components. */
    <A extends Component, B extends Component, C extends Component> Stage3<A, B, C> with(
            Class<A> a, Class<B> b, Class<C> c);

    /** Declares four required components. */
    <A extends Component, B extends Component, C extends Component, D extends Component> Stage4<A, B, C, D> with(
            Class<A> a, Class<B> b, Class<C> c, Class<D> d);

    /**
     * Common contract shared by every typed query stage: the ability to further constrain the
     * match with excluded components before supplying a terminal callback.
     *
     * @param <S> the concrete self type, for fluent {@code without(...)} chaining
     */
    sealed interface Stage<S extends Stage<S>> permits Stage1, Stage2, Stage3, Stage4 {

        /**
         * Excludes entities possessing any of the given component types from the match set.
         *
         * @param exclude component types that must be absent
         * @return this stage, for chaining
         */
        @SuppressWarnings("unchecked")
        S without(Class<? extends Component>... exclude);
    }

    /** Typed stage for a single-component query. */
    non-sealed interface Stage1<A extends Component> extends Stage<Stage1<A>> {
        /**
         * Registers the terminal callback, producing an executable {@link Query}.
         *
         * @param fn the per-entity callback
         * @return the registered system handle
         */
        Query each(Each1<A> fn);
    }

    /** Typed stage for a two-component query. */
    non-sealed interface Stage2<A extends Component, B extends Component> extends Stage<Stage2<A, B>> {
        /**
         * Registers the terminal callback, producing an executable {@link Query}.
         *
         * @param fn the per-entity callback
         * @return the registered system handle
         */
        Query each(Each2<A, B> fn);
    }

    /** Typed stage for a three-component query. */
    non-sealed interface Stage3<A extends Component, B extends Component, C extends Component>
            extends Stage<Stage3<A, B, C>> {
        /**
         * Registers the terminal callback, producing an executable {@link Query}.
         *
         * @param fn the per-entity callback
         * @return the registered system handle
         */
        Query each(Each3<A, B, C> fn);
    }

    /** Typed stage for a four-component query. */
    non-sealed interface Stage4<A extends Component, B extends Component, C extends Component, D extends Component>
            extends Stage<Stage4<A, B, C, D>> {
        /**
         * Registers the terminal callback, producing an executable {@link Query}.
         *
         * @param fn the per-entity callback
         * @return the registered system handle
         */
        Query each(Each4<A, B, C, D> fn);
    }
}
