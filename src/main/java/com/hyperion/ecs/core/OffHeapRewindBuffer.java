package com.hyperion.ecs.core;

import com.hyperion.ecs.rewind.RewindBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Off-heap snapshot ring buffer for instantaneous frame rewind (Phase 4, DevTools-Agent).
 *
 * <p>Each captured frame is a <b>direct native block copy</b> ({@code MemorySegment.copy}) of the
 * live world state — the entity slot registry plus every archetype table's live rows — into a
 * pre-sized off-heap frame segment. No per-entity serialization, no boxing, and (in steady state)
 * <b>zero heap allocation</b> on {@link #capture()}, honouring the Copy-Free Snapshots guardrail and
 * Low-Level Requirement #5 ({@code O(N)} contiguous memory write).
 *
 * <p>Frame layout (one contiguous segment per ring slot):
 * <pre>
 *   [ header 32B: magic | tableCount | es.highWater | es.freeTop | es.liveCount ]
 *   [ int perTableRowCount[tableCount] ]
 *   [ entity-store live bytes : slots(highWater*16) + freeStack(freeTop*4) ]
 *   [ table0 live rows ][ table1 live rows ] ...        // each = [entity prefix][col prefixes]
 * </pre>
 *
 * <p>Restore reverses the copies into the <em>current</em> column bases (layout-correct even if a
 * table grew since capture, since tables only ever grow) and empties any archetype that did not yet
 * exist at the target frame. Entity existence, generations, and the free list are all reverted, so
 * stale handles and despawns time-travel correctly.
 *
 * @since Phase 4 (DevTools-Agent)
 */
public final class OffHeapRewindBuffer implements RewindBuffer, AutoCloseable {

    private static final int MAGIC = 0x48595257; // "HYRW"
    private static final long HEADER_BYTES = 32;
    private static final long OFF_MAGIC = 0, OFF_TC = 4, OFF_HW = 8, OFF_FT = 12, OFF_LIVE = 16;

    private final HyperionWorld world;
    private final Arena arena;
    private final int capacity;
    private final MemorySegment[] frames;

    private int head;      // next slot to write
    private int retained;  // valid frames (<= capacity)

    OffHeapRewindBuffer(HyperionWorld world, int capacityFrames) {
        if (capacityFrames < 1) {
            throw new IllegalArgumentException("Rewind buffer depth must be >= 1, got " + capacityFrames);
        }
        this.world = world;
        this.capacity = capacityFrames;
        this.arena = Arena.ofShared();
        this.frames = new MemorySegment[capacityFrames];
    }

    // ── Capture (per tick — zero heap allocation in steady state) ────────────────────────

    @Override
    public void capture() {
        int tc = world.tableCount();
        EntityStore es = world.entityStore();

        long total = HEADER_BYTES + (long) tc * Integer.BYTES + es.liveByteSize();
        for (int t = 0; t < tc; t++) {
            total += world.tableAt(t).liveByteSize();
        }

        MemorySegment f = ensureFrame(head, total);
        f.set(ValueLayout.JAVA_INT, OFF_MAGIC, MAGIC);
        f.set(ValueLayout.JAVA_INT, OFF_TC, tc);
        f.set(ValueLayout.JAVA_INT, OFF_HW, es.highWater());
        f.set(ValueLayout.JAVA_INT, OFF_FT, es.freeTop());
        f.set(ValueLayout.JAVA_INT, OFF_LIVE, (int) es.liveCount());

        long p = HEADER_BYTES;
        for (int t = 0; t < tc; t++) {
            f.set(ValueLayout.JAVA_INT, p, world.tableAt(t).entityCount());
            p += Integer.BYTES;
        }
        p += es.captureInto(f, p);
        for (int t = 0; t < tc; t++) {
            p += world.tableAt(t).captureInto(f, p);
        }

        head = (head + 1) % capacity;
        if (retained < capacity) {
            retained++;
        }
    }

    // ── Restore (occasional — small heap use acceptable, not the tick path) ──────────────

    @Override
    public void restore(int framesAgo) {
        if (framesAgo < 1 || framesAgo > retained) {
            throw new IllegalArgumentException(
                    "Cannot rewind " + framesAgo + " frames; retained = " + retained);
        }
        int slot = ((head - framesAgo) % capacity + capacity) % capacity;
        MemorySegment f = frames[slot];
        if (f.get(ValueLayout.JAVA_INT, OFF_MAGIC) != MAGIC) {
            throw new IllegalStateException("Corrupt rewind frame at slot " + slot);
        }
        int tc = f.get(ValueLayout.JAVA_INT, OFF_TC);
        int hw = f.get(ValueLayout.JAVA_INT, OFF_HW);
        int ft = f.get(ValueLayout.JAVA_INT, OFF_FT);
        int live = f.get(ValueLayout.JAVA_INT, OFF_LIVE);

        long p = HEADER_BYTES;
        int[] counts = new int[tc];
        for (int t = 0; t < tc; t++) {
            counts[t] = f.get(ValueLayout.JAVA_INT, p);
            p += Integer.BYTES;
        }
        p += world.entityStore().restoreFrom(f, p, hw, ft, live);
        for (int t = 0; t < tc; t++) {
            p += world.tableAt(t).restoreFrom(f, p, counts[t]);
        }
        // Archetypes created after the target frame did not exist then → empty them.
        for (int t = tc; t < world.tableCount(); t++) {
            world.tableAt(t).resetCount();
        }
    }

    private MemorySegment ensureFrame(int slot, long total) {
        MemorySegment f = frames[slot];
        if (f == null || f.byteSize() < total) {
            // Grow-only; world storage grows monotonically, so frames stabilise after warmup.
            f = arena.allocate(total);
            frames[slot] = f;
        }
        return f;
    }

    @Override
    public int capacityFrames() {
        return capacity;
    }

    @Override
    public int retainedFrames() {
        return retained;
    }

    @Override
    public void close() {
        arena.close();
    }
}
