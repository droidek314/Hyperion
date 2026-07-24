package com.hyperion.ecs.core;

import com.hyperion.ecs.query.Query;
import com.hyperion.ecs.storage.ComponentBitmask;

/**
 * A registered system: a required/excluded signature plus its callback. Implements the Phase-1
 * {@link Query} handle so callers can toggle or run it out of band.
 *
 * <p>Two execution kinds:
 * <ul>
 *   <li><b>Primitive</b> ({@link ChunkFunction}) — the zero-GC path driven by the tick loop.</li>
 *   <li><b>Compatibility</b> ({@code EachN} record callback) — a read-only, allocating adapter
 *       retained for the Phase-1 ergonomic API until Phase 3 replaces it with zero-cost views
 *       (ADR-0005).</li>
 * </ul>
 *
 * @since Phase 2 (Perf-Core-Agent)
 */
final class RegisteredSystem implements Query {

    private final HyperionWorld world;
    final NativeBitmask required;
    final NativeBitmask excluded;

    final boolean primitive;
    final ChunkFunction chunkFn;

    final Object eachFn;          // Each1..Each4 (compatibility path)
    final int arity;
    final RegisteredComponent[] eachComps;

    private boolean enabled = true;

    private RegisteredSystem(HyperionWorld world, NativeBitmask required, NativeBitmask excluded,
                             boolean primitive, ChunkFunction chunkFn,
                             Object eachFn, int arity, RegisteredComponent[] eachComps) {
        this.world = world;
        this.required = required;
        this.excluded = excluded;
        this.primitive = primitive;
        this.chunkFn = chunkFn;
        this.eachFn = eachFn;
        this.arity = arity;
        this.eachComps = eachComps;
    }

    static RegisteredSystem primitive(HyperionWorld world, NativeBitmask required, ChunkFunction fn) {
        return new RegisteredSystem(world, required, null, true, fn, null, 0, null);
    }

    static RegisteredSystem primitive(HyperionWorld world, NativeBitmask required,
                                      NativeBitmask excluded, ChunkFunction fn) {
        return new RegisteredSystem(world, required, excluded, true, fn, null, 0, null);
    }

    static RegisteredSystem each(HyperionWorld world, NativeBitmask required, NativeBitmask excluded,
                                 Object eachFn, int arity, RegisteredComponent[] comps) {
        return new RegisteredSystem(world, required, excluded, false, null, eachFn, arity, comps);
    }

    boolean enabledFlag() {
        return enabled;
    }

    // ── Query contract ───────────────────────────────────────────────────────────────────

    @Override
    public ComponentBitmask signature() {
        return required;
    }

    @Override
    public long matchCount() {
        return world.matchCount(this);
    }

    @Override
    public void run() {
        world.execute(this);
    }

    @Override
    public void enable() {
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
