package com.hyperion.ecs.core;

/**
 * The primitive, zero-allocation per-archetype execution unit — the "direct primitive method
 * call" target that the Phase-3 DX layer compiles its ergonomic {@code each(...)} DSL down to.
 *
 * <p>Invoked once per matching archetype chunk. The implementor loops rows {@code [0, count)}
 * over the supplied {@link ChunkView}, reading and writing component fields in place with
 * primitive typed accessors. No boxing, no {@code new}, no heap arrays — the whole call is
 * allocation-free and safe under the Strict Zero-GC Rule.
 *
 * @since Phase 2 (Perf-Core-Agent)
 */
@FunctionalInterface
public interface ChunkFunction {

    /**
     * Processes one archetype chunk.
     *
     * @param chunk a cursor over the matching archetype's rows (reused across chunks — do not retain)
     */
    void apply(ChunkView chunk);
}
