package com.hyperion.ecs;

import com.hyperion.ecs.query.QueryBuilder;

/**
 * The root ECS container: entity lifecycle, system registration, the zero-GC tick loop,
 * the entity relationship graph, and time-travel rewind.
 *
 * <p>A {@code World} is obtained from {@link Hyperion#createWorld()}. All methods on the tick
 * path ({@link #tick()}, query dispatch) must honour the <b>Zero Runtime Heap Allocation</b>
 * invariant — {@code $0$} bytes allocated per tick.
 *
 * <h2>Target usage</h2>
 * <pre>{@code
 * World world = Hyperion.createWorld();
 *
 * long entity = world.spawn()
 *     .add(new Position(0.0f, 0.0f))
 *     .add(new Velocity(1.0f, 1.0f))
 *     .id();
 *
 * world.createSystem()
 *      .with(Position.class, Velocity.class)
 *      .each((long id, Position pos, Velocity vel) -> { ... });
 *
 * while (running) {
 *     world.tick();
 * }
 * }</pre>
 *
 * <p><b>Immutable Signature</b> — governed by ADR-0001. Do not alter without an ADR revision.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface World {

    // ── Entity lifecycle ────────────────────────────────────────────────────────────────

    /**
     * Begins building a new entity.
     *
     * @return a fresh {@link EntityBuilder}
     */
    EntityBuilder spawn();

    /**
     * Tests whether a handle refers to a live entity. Detects stale handles by comparing the
     * handle's generation against the slot's current generation.
     *
     * @param entity a packed entity handle
     * @return {@code true} if the entity is alive and the generation matches
     */
    boolean isAlive(long entity);

    /**
     * Destroys an entity, freeing its off-heap slot and bumping the slot generation so that
     * outstanding handles become stale.
     *
     * @param entity a packed entity handle
     */
    void despawn(long entity);

    /**
     * @return the number of currently live entities
     */
    long entityCount();

    // ── System registration ─────────────────────────────────────────────────────────────

    /**
     * Begins registering a query-driven system.
     *
     * @return a fresh {@link QueryBuilder}
     */
    QueryBuilder createSystem();

    // ── Simulation loop ─────────────────────────────────────────────────────────────────

    /**
     * Advances the simulation by one frame: dispatches every enabled system over its matching
     * archetype chunks. <b>Must allocate zero heap bytes.</b>
     */
    void tick();

    // ── Relationship graph ──────────────────────────────────────────────────────────────

    /**
     * Creates a directed, typed edge {@code source --relation--> target}.
     *
     * @param source   the source entity handle
     * @param relation the relation type
     * @param target   the target entity handle
     * @param <R>      the relation type
     */
    <R extends Relation> void link(long source, Class<R> relation, long target);

    /**
     * Removes a directed, typed edge {@code source --relation--> target}, if present.
     *
     * @param source   the source entity handle
     * @param relation the relation type
     * @param target   the target entity handle
     * @param <R>      the relation type
     */
    <R extends Relation> void unlink(long source, Class<R> relation, long target);

    // ── Time-travel rewind ──────────────────────────────────────────────────────────────

    /**
     * Allocates an off-heap ring buffer capable of retaining the given number of past frames
     * for instantaneous rewind.
     *
     * @param frames the ring-buffer depth in frames (e.g. {@code 120})
     */
    void enableRewindBuffer(int frames);

    /**
     * Restores world state to a previously captured frame via a direct native memory copy.
     *
     * @param frame the number of frames to rewind (bounded by the ring-buffer depth)
     */
    void rewind(int frame);
}
