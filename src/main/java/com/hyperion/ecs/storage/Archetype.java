package com.hyperion.ecs.storage;

/**
 * An archetype: the set of all entities sharing an identical component signature, stored
 * together in contiguous off-heap Struct-of-Arrays (SoA) columns.
 *
 * <p>Archetypes are the unit of storage and of parallel dispatch. Each archetype owns one SoA
 * column per component field; entity {@code row}s index in parallel across every column. The
 * parallel dispatcher (Phase 3) chunks an archetype's rows across
 * {@code Executors.newVirtualThreadPerTaskExecutor()}.
 *
 * <p>This interface is a read-oriented contract; mutation (add/remove entity, structural moves
 * between archetypes) is an implementation concern owned by the {@code Perf-Core-Agent}.
 *
 * <p><b>Immutable Signature</b> — governed by ADR-0002.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface Archetype {

    /**
     * @return the 256-bit component signature shared by every entity in this archetype
     */
    ComponentBitmask signature();

    /**
     * @return the number of entities (rows) currently stored in this archetype
     */
    int entityCount();

    /**
     * @param row a row index in {@code [0, entityCount())}
     * @return the packed entity handle stored at that row
     */
    long entityAt(int row);

    /**
     * @param componentId a component type id
     * @return {@code true} if this archetype's signature includes that component
     */
    boolean contains(int componentId);
}
