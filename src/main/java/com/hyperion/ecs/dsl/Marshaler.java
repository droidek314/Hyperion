package com.hyperion.ecs.dsl;

import com.hyperion.ecs.core.ChunkView;

/**
 * A generated, direct-field marshaler for one system's component set. Bridges the reused flyweight
 * <em>view</em> objects and the off-heap SoA columns with <b>zero-cost, zero-allocation</b> bytecode
 * synthesized at registration time via {@link java.lang.classfile} (JDK 25).
 *
 * <p>The concrete implementation is a runtime-generated hidden class whose {@code load}/{@code store}
 * bodies are straight-line {@code getfield}/{@code putfield} against the component classes and
 * {@code invokevirtual} against {@link ChunkView}'s primitive accessors — no reflection, no
 * {@code VarHandle}, no boxing. See {@link MarshalerFactory} and ADR-0007/0008.
 *
 * @since Phase 3 (DX-Agent); codegen backend added in the JDK-25 migration
 */
public interface Marshaler {

    /**
     * Copies every field of every component from the SoA row into the reused view objects.
     *
     * @param views the per-component reused view instances (index = component order)
     * @param chunk the bound chunk cursor
     * @param row   the local row index within the chunk
     */
    void load(Object[] views, ChunkView chunk, int row);

    /**
     * Copies every field of every component from the reused view objects back into the SoA row.
     *
     * @param views the per-component reused view instances (index = component order)
     * @param chunk the bound chunk cursor
     * @param row   the local row index within the chunk
     */
    void store(Object[] views, ChunkView chunk, int row);
}
