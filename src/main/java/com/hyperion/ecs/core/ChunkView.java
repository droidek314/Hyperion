package com.hyperion.ecs.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A reusable, zero-allocation cursor over one archetype's SoA columns, passed to a
 * {@link ChunkFunction}. A single instance is re-pointed at each matching archetype during a
 * {@code tick()} — it never allocates and must not be retained beyond the callback.
 *
 * <p>Fields are addressed by {@code (componentId, fieldIndex, row)}: the global component id
 * (assigned at registration) and the field's declaration order within its record. All accessors
 * resolve to a single {@link MemorySegment} get/set over precomputed offsets.
 *
 * <p>Example (primitive movement system over Position{x,y}=cid0, Velocity{dx,dy}=cid1):
 * <pre>{@code
 * chunk -> {
 *     int n = chunk.count();
 *     for (int r = 0; r < n; r++) {
 *         float x  = chunk.getFloat(POSITION, 0, r);
 *         float y  = chunk.getFloat(POSITION, 1, r);
 *         float dx = chunk.getFloat(VELOCITY, 0, r);
 *         float dy = chunk.getFloat(VELOCITY, 1, r);
 *         chunk.setFloat(POSITION, 0, r, x + dx);
 *         chunk.setFloat(POSITION, 1, r, y + dy);
 *     }
 * }
 * }</pre>
 *
 * @since Phase 2 (Perf-Core-Agent)
 */
public final class ChunkView {

    private ArchetypeTable table;
    private MemorySegment segment;
    private int count;
    private int rowOffset;

    /** Re-points this cursor at a whole table. Called by the engine between chunks; not user-facing. */
    void bind(ArchetypeTable table) {
        this.table = table;
        this.segment = table.segment();
        this.rowOffset = 0;
        this.count = table.count();
    }

    /**
     * Re-points this cursor at a sub-range {@code [startRow, startRow + length)} of a table — used
     * by the parallel dispatcher to hand each virtual thread a disjoint chunk. The callback still
     * sees local rows {@code [0, length)}; addressing is transparently offset. Not user-facing.
     */
    void bindWindow(ArchetypeTable table, int startRow, int length) {
        this.table = table;
        this.segment = table.segment();
        this.rowOffset = startRow;
        this.count = length;
    }

    /** @return number of entity rows in the current chunk. */
    public int count() {
        return count;
    }

    /** @return the packed entity handle at {@code row}. */
    public long entity(int row) {
        return table.entityAt(rowOffset + row);
    }

    // ── Primitive typed accessors (zero-allocation) ──────────────────────────────────────

    public float getFloat(int componentId, int fieldIndex, int row) {
        return segment.get(ValueLayout.JAVA_FLOAT, table.fieldOffset(componentId, fieldIndex, rowOffset + row));
    }

    public void setFloat(int componentId, int fieldIndex, int row, float value) {
        segment.set(ValueLayout.JAVA_FLOAT, table.fieldOffset(componentId, fieldIndex, rowOffset + row), value);
    }

    public int getInt(int componentId, int fieldIndex, int row) {
        return segment.get(ValueLayout.JAVA_INT, table.fieldOffset(componentId, fieldIndex, rowOffset + row));
    }

    public void setInt(int componentId, int fieldIndex, int row, int value) {
        segment.set(ValueLayout.JAVA_INT, table.fieldOffset(componentId, fieldIndex, rowOffset + row), value);
    }

    public long getLong(int componentId, int fieldIndex, int row) {
        return segment.get(ValueLayout.JAVA_LONG, table.fieldOffset(componentId, fieldIndex, rowOffset + row));
    }

    public void setLong(int componentId, int fieldIndex, int row, long value) {
        segment.set(ValueLayout.JAVA_LONG, table.fieldOffset(componentId, fieldIndex, rowOffset + row), value);
    }

    public double getDouble(int componentId, int fieldIndex, int row) {
        return segment.get(ValueLayout.JAVA_DOUBLE, table.fieldOffset(componentId, fieldIndex, rowOffset + row));
    }

    public void setDouble(int componentId, int fieldIndex, int row, double value) {
        segment.set(ValueLayout.JAVA_DOUBLE, table.fieldOffset(componentId, fieldIndex, rowOffset + row), value);
    }
}
