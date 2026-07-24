package com.hyperion.ecs;

/**
 * Fluent, single-entity spawn contract returned by {@link World#spawn()}.
 *
 * <p>Enables the target developer experience:
 * <pre>{@code
 * long entity = world.spawn()
 *     .add(new Position(0.0f, 0.0f))
 *     .add(new Velocity(1.0f, 1.0f))
 *     .id();
 * }</pre>
 *
 * <p><b>Ergonomics note (DX-Agent, Phase 3):</b> the {@code add(...)} chain declares the
 * entity's component set. The concrete builder must resolve the final archetype and perform a
 * single off-heap slot allocation on {@link #id()} — intermediate {@code add} calls stage the
 * component set without per-call reallocation.
 *
 * <p><b>Immutable Signature</b> — governed by ADR-0001.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface EntityBuilder {

    /**
     * Stages a component instance for the entity being built.
     *
     * @param component the immutable component record instance
     * @param <T>       the component type
     * @return this builder, for chaining
     */
    <T extends Component> EntityBuilder add(T component);

    /**
     * Removes a previously staged component type from the entity being built.
     *
     * @param type the component class to unstage
     * @param <T>  the component type
     * @return this builder, for chaining
     */
    <T extends Component> EntityBuilder remove(Class<T> type);

    /**
     * Materialises the entity and returns its packed 64-bit handle.
     *
     * @return the newly spawned {@link Entity} handle
     */
    long id();
}
