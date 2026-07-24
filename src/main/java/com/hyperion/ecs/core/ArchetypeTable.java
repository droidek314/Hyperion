package com.hyperion.ecs.core;

import com.hyperion.ecs.storage.Archetype;
import com.hyperion.ecs.storage.ComponentBitmask;
import com.hyperion.ecs.storage.MemoryLayout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Off-heap Struct-of-Arrays (SoA) storage for all entities sharing one component signature.
 *
 * <p>A single native {@link MemorySegment} backs the whole table, partitioned into contiguous,
 * 8-byte-aligned columns:
 *
 * <pre>
 *   [ entity handles : long[capacity] ] [ field_0 : T[capacity] ] [ field_1 : T[capacity] ] ...
 *   ^ base 0                            ^ columnBase[0]           ^ columnBase[1]
 * </pre>
 *
 * <p>Field columns are ordered by (componentId ascending, fieldIndex ascending). Element access
 * for field {@code f} of component {@code c} at row {@code r} is:
 * {@code columnBase[col] + (long) r * kind.byteSize()} — pure integer arithmetic, aligned.
 *
 * <p>Row insertion appends; removal is swap-with-last ({@code O(1)}), returning the entity that
 * was relocated so its slot metadata can be fixed up. Growth reallocates a larger segment and
 * copies the live prefix of each column. All of this is <b>structural</b> — the tick loop only
 * reads/writes existing rows and never triggers allocation here.
 *
 * @since Phase 2 (Perf-Core-Agent)
 */
final class ArchetypeTable implements Archetype, MemoryLayout {

    private static final long ENTITY_STRIDE = 8; // bytes per entity handle (long)

    private final Arena arena;
    private final NativeBitmask signature;
    private final int[] componentIds;            // sorted ascending
    private final FieldKind[] columnKind;        // per field-column
    private final int[] firstColumnOfComponent;  // componentId -> first field-column index, else -1

    private MemorySegment segment;
    private long[] columnBase;                    // per field-column byte offset (entity column is at 0)
    private long byteSize;
    private int capacity;
    private int count;

    ArchetypeTable(Arena arena, NativeBitmask signature, int[] componentIds,
                   ComponentRegistry registry, int initialCapacity) {
        this.arena = arena;
        this.signature = signature;
        this.componentIds = componentIds;

        this.firstColumnOfComponent = new int[ComponentBitmask.BIT_WIDTH];
        java.util.Arrays.fill(firstColumnOfComponent, -1);

        int columnCount = 0;
        for (int cid : componentIds) {
            firstColumnOfComponent[cid] = columnCount;
            columnCount += registry.byId(cid).fieldCount();
        }
        this.columnKind = new FieldKind[columnCount];
        int col = 0;
        for (int cid : componentIds) {
            RegisteredComponent rc = registry.byId(cid);
            for (int f = 0; f < rc.fieldCount(); f++) {
                columnKind[col++] = rc.fieldKinds[f];
            }
        }

        this.capacity = Math.max(1, initialCapacity);
        this.columnBase = new long[columnCount];
        allocate(this.capacity);
    }

    // ── Layout & allocation (structural) ─────────────────────────────────────────────────

    private static long align8(long x) {
        return (x + 7L) & ~7L;
    }

    /** Computes column offsets for a capacity and returns the total byte size. */
    private long computeLayout(int cap, long[] outBase) {
        long offset = align8((long) cap * ENTITY_STRIDE); // entity column occupies [0, offset)
        for (int c = 0; c < outBase.length; c++) {
            outBase[c] = offset;
            offset += align8((long) cap * columnKind[c].byteSize());
        }
        return offset;
    }

    private void allocate(int cap) {
        long[] newBase = new long[columnBase.length];
        long total = computeLayout(cap, newBase);
        this.segment = arena.allocate(total);
        this.columnBase = newBase;
        this.byteSize = total;
        this.capacity = cap;
    }

    private void growTo(int newCap) {
        MemorySegment oldSeg = segment;
        long[] oldBase = columnBase;
        int liveRows = count;

        long[] newBase = new long[columnBase.length];
        long total = computeLayout(newCap, newBase);
        MemorySegment newSeg = arena.allocate(total);

        // Copy live prefix of the entity column.
        MemorySegment.copy(oldSeg, 0, newSeg, 0, (long) liveRows * ENTITY_STRIDE);
        // Copy live prefix of each field column into its new (rescaled) base.
        for (int c = 0; c < newBase.length; c++) {
            MemorySegment.copy(oldSeg, oldBase[c], newSeg, newBase[c],
                    (long) liveRows * columnKind[c].byteSize());
        }

        this.segment = newSeg;
        this.columnBase = newBase;
        this.byteSize = total;
        this.capacity = newCap;
    }

    /** Ensures at least one free row, growing (×2) if full. Structural. */
    private void ensureOne() {
        if (count == capacity) {
            growTo(capacity + (capacity >> 1) + 1);
        }
    }

    // ── Row lifecycle (structural) ───────────────────────────────────────────────────────

    /** Appends a row for {@code entity} and returns its row index. */
    int addRow(long entity) {
        ensureOne();
        int row = count++;
        segment.set(ValueLayout.JAVA_LONG, (long) row * ENTITY_STRIDE, entity);
        return row;
    }

    /**
     * Removes {@code row} by swapping the last row into it.
     *
     * @return the entity handle that was relocated into {@code row}, or {@link com.hyperion.ecs.Entity#NULL}
     * if the removed row was already the last row
     */
    long removeRowSwap(int row) {
        int last = count - 1;
        long moved = com.hyperion.ecs.Entity.NULL;
        if (row != last) {
            // Move every field of the last row into the vacated row, then the entity handle.
            for (int c = 0; c < columnKind.length; c++) {
                int size = columnKind[c].byteSize();
                MemorySegment.copy(segment, columnBase[c] + (long) last * size,
                        segment, columnBase[c] + (long) row * size, size);
            }
            moved = segment.get(ValueLayout.JAVA_LONG, (long) last * ENTITY_STRIDE);
            segment.set(ValueLayout.JAVA_LONG, (long) row * ENTITY_STRIDE, moved);
        }
        count--;
        return moved;
    }

    // ── Snapshot / restore (Phase 4 — copy-free block transfers) ─────────────────────────

    /** @return live byte count for the current rows: entity column prefix + each field-column prefix. */
    long liveByteSize() {
        return liveByteSizeFor(count);
    }

    private long liveByteSizeFor(int rows) {
        long b = (long) rows * ENTITY_STRIDE;
        for (int c = 0; c < columnKind.length; c++) {
            b += (long) rows * columnKind[c].byteSize();
        }
        return b;
    }

    /**
     * Block-copies the live rows into {@code dst} starting at {@code off}, packed as
     * [entity prefix][col0 prefix][col1 prefix]…. Pure {@code MemorySegment.copy} (native).
     *
     * @return the number of bytes written
     */
    long captureInto(MemorySegment dst, long off) {
        long p = off;
        long n = (long) count * ENTITY_STRIDE;
        MemorySegment.copy(segment, 0, dst, p, n);
        p += n;
        for (int c = 0; c < columnKind.length; c++) {
            n = (long) count * columnKind[c].byteSize();
            MemorySegment.copy(segment, columnBase[c], dst, p, n);
            p += n;
        }
        return p - off;
    }

    /**
     * Restores {@code rows} live rows from {@code src} at {@code off} into the current column bases
     * (which may differ from capture time if the table grew — restore is layout-correct regardless).
     *
     * @return the number of bytes read
     */
    long restoreFrom(MemorySegment src, long off, int rows) {
        if (rows > capacity) {
            growTo(rows);
        }
        long p = off;
        long n = (long) rows * ENTITY_STRIDE;
        MemorySegment.copy(src, p, segment, 0, n);
        p += n;
        for (int c = 0; c < columnKind.length; c++) {
            n = (long) rows * columnKind[c].byteSize();
            MemorySegment.copy(src, p, segment, columnBase[c], n);
            p += n;
        }
        this.count = rows;
        return p - off;
    }

    /** Empties this table (used when rewinding to before it existed). */
    void resetCount() {
        this.count = 0;
    }

    // ── Hot-path accessors (zero-allocation) ─────────────────────────────────────────────

    /** Byte offset of field {@code f} of component {@code cid} at {@code row}. Pure arithmetic. */
    long fieldOffset(int cid, int f, int row) {
        int col = firstColumnOfComponent[cid] + f;
        return columnBase[col] + (long) row * columnKind[col].byteSize();
    }

    MemorySegment segment() {
        return segment;
    }

    int count() {
        return count;
    }

    int[] componentIds() {
        return componentIds;
    }

    NativeBitmask nativeSignature() {
        return signature;
    }

    // ── Archetype contract ───────────────────────────────────────────────────────────────

    @Override
    public ComponentBitmask signature() {
        return signature;
    }

    @Override
    public int entityCount() {
        return count;
    }

    @Override
    public long entityAt(int row) {
        return segment.get(ValueLayout.JAVA_LONG, (long) row * ENTITY_STRIDE);
    }

    @Override
    public boolean contains(int componentId) {
        return firstColumnOfComponent[componentId] >= 0;
    }

    // ── MemoryLayout contract ────────────────────────────────────────────────────────────

    @Override
    public long capacityInEntities() {
        return capacity;
    }

    @Override
    public long columnBaseOffset(int componentId, int fieldIndex) {
        return columnBase[firstColumnOfComponent[componentId] + fieldIndex];
    }

    @Override
    public long fieldStride(int componentId, int fieldIndex) {
        return columnKind[firstColumnOfComponent[componentId] + fieldIndex].byteSize();
    }

    @Override
    public long byteSize() {
        return byteSize;
    }
}
