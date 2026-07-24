package com.hyperion.ecs.storage;

/**
 * Off-heap memory-layout contract for a single archetype's Struct-of-Arrays storage.
 *
 * <p>Defines the addressing arithmetic used to locate a component field for a given entity row
 * within the archetype's backing native buffer (a {@code java.lang.foreign.MemorySegment},
 * introduced concretely in Phase 2). All offsets are byte offsets from the segment base; all
 * accessors are pure integer arithmetic and allocate nothing.
 *
 * <p>SoA addressing for field {@code f} of component {@code c} at row {@code r}:
 * <pre>
 *   address = columnBaseOffset(c, f) + (long) r * fieldStride(c, f)
 * </pre>
 *
 * <p>This interface intentionally references offsets as primitive {@code long}/{@code int} and
 * does <em>not</em> expose {@code MemorySegment} in its signatures. This keeps the addressing
 * contract decoupled from the storage backend (a {@code MemorySegment} today; conceivably another
 * native buffer abstraction later) — the binding to {@code MemorySegment} is a Phase-2
 * implementation detail. (On the original JDK-21 baseline this also avoided the FFM preview flag;
 * under the JDK-25 baseline FFM is final, so decoupling is now the sole rationale.) See ADR-0002.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface MemoryLayout {

    /**
     * @return total capacity, in entities, of the columns described by this layout
     */
    long capacityInEntities();

    /**
     * @param componentId a component type id present in this archetype
     * @param fieldIndex  a field index in {@code [0, fieldCount)} of that component
     * @return the byte offset of the field's SoA column base within the backing segment
     */
    long columnBaseOffset(int componentId, int fieldIndex);

    /**
     * @param componentId a component type id present in this archetype
     * @param fieldIndex  a field index in {@code [0, fieldCount)} of that component
     * @return the per-row stride, in bytes, of the field's SoA column
     */
    long fieldStride(int componentId, int fieldIndex);

    /**
     * @return the total byte size of the backing native segment for this layout
     */
    long byteSize();
}
