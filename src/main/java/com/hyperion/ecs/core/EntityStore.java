package com.hyperion.ecs.core;

import com.hyperion.ecs.Entity;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Off-heap registry of entity slots. Each slot stores a generation counter and the entity's
 * current location (archetype index + row), enabling {@code O(1)} generational stale-handle
 * detection with no per-entity heap object.
 *
 * <p>Slot layout (16 bytes, 4-byte fields, padded):
 * <pre>
 *   +0  int generation
 *   +4  int archetypeIndex   (-1 = free/dead)
 *   +8  int row
 *   +12 (pad)
 * </pre>
 *
 * <p>Freed slot indices are recycled via an off-heap LIFO stack, and the slot's generation is
 * bumped on free so all outstanding handles to it become stale. Growth is structural.
 *
 * @since Phase 2 (Perf-Core-Agent)
 */
final class EntityStore {

    private static final long SLOT_BYTES = 16;
    private static final long GEN_OFF = 0;
    private static final long ARCH_OFF = 4;
    private static final long ROW_OFF = 8;

    private final Arena arena;
    private MemorySegment slots;
    private MemorySegment freeStack;
    private int capacity;
    private int highWater;   // number of slot indices ever handed out
    private int freeTop;     // number of recycled indices available
    private int liveCount;

    EntityStore(Arena arena, int initialCapacity) {
        this.arena = arena;
        this.capacity = Math.max(1, initialCapacity);
        this.slots = arena.allocate(this.capacity * SLOT_BYTES);
        this.freeStack = arena.allocate((long) this.capacity * Integer.BYTES);
    }

    private void grow() {
        int newCap = capacity + (capacity >> 1) + 1;
        MemorySegment newSlots = arena.allocate(newCap * SLOT_BYTES);
        MemorySegment.copy(slots, 0, newSlots, 0, (long) highWater * SLOT_BYTES);
        MemorySegment newFree = arena.allocate((long) newCap * Integer.BYTES);
        MemorySegment.copy(freeStack, 0, newFree, 0, (long) freeTop * Integer.BYTES);
        this.slots = newSlots;
        this.freeStack = newFree;
        this.capacity = newCap;
    }

    /** Allocates a fresh slot and returns a packed {@link Entity} handle. Structural. */
    long create() {
        int index;
        if (freeTop > 0) {
            index = freeStack.getAtIndex(ValueLayout.JAVA_INT, --freeTop);
        } else {
            if (highWater == capacity) {
                grow();
            }
            index = highWater++;
        }
        int gen = slots.get(ValueLayout.JAVA_INT, index * SLOT_BYTES + GEN_OFF);
        liveCount++;
        return Entity.of(index, gen);
    }

    /** Binds a slot's current location. Structural. */
    void bind(int index, int archetypeIndex, int row) {
        long base = index * SLOT_BYTES;
        slots.set(ValueLayout.JAVA_INT, base + ARCH_OFF, archetypeIndex);
        slots.set(ValueLayout.JAVA_INT, base + ROW_OFF, row);
    }

    /** Updates only the row of a slot (after a swap-remove relocation). Structural. */
    void setRow(int index, int row) {
        slots.set(ValueLayout.JAVA_INT, index * SLOT_BYTES + ROW_OFF, row);
    }

    /** Frees a slot, bumping its generation so outstanding handles go stale. Structural. */
    void free(int index) {
        long base = index * SLOT_BYTES;
        int gen = slots.get(ValueLayout.JAVA_INT, base + GEN_OFF);
        slots.set(ValueLayout.JAVA_INT, base + GEN_OFF, gen + 1);
        slots.set(ValueLayout.JAVA_INT, base + ARCH_OFF, -1);
        freeStack.setAtIndex(ValueLayout.JAVA_INT, freeTop++, index);
        liveCount--;
    }

    boolean isAlive(long handle) {
        int index = Entity.index(handle);
        if (index < 0 || index >= highWater) {
            return false;
        }
        long base = index * SLOT_BYTES;
        return slots.get(ValueLayout.JAVA_INT, base + GEN_OFF) == Entity.generation(handle)
                && slots.get(ValueLayout.JAVA_INT, base + ARCH_OFF) >= 0;
    }

    int archetypeIndex(int index) {
        return slots.get(ValueLayout.JAVA_INT, index * SLOT_BYTES + ARCH_OFF);
    }

    int row(int index) {
        return slots.get(ValueLayout.JAVA_INT, index * SLOT_BYTES + ROW_OFF);
    }

    long liveCount() {
        return liveCount;
    }

    // ── Snapshot / restore (Phase 4 — copy-free block transfers) ─────────────────────────

    int highWater() {
        return highWater;
    }

    int freeTop() {
        return freeTop;
    }

    /** @return live byte count: used slot region + used free-stack region. */
    long liveByteSize() {
        return (long) highWater * SLOT_BYTES + (long) freeTop * Integer.BYTES;
    }

    /** Block-copies the used slot + free-stack regions into {@code dst} at {@code off}. */
    long captureInto(MemorySegment dst, long off) {
        long slotBytes = (long) highWater * SLOT_BYTES;
        MemorySegment.copy(slots, 0, dst, off, slotBytes);
        MemorySegment.copy(freeStack, 0, dst, off + slotBytes, (long) freeTop * Integer.BYTES);
        return liveByteSize();
    }

    /** Restores slot metadata + counters from {@code src} at {@code off}. */
    long restoreFrom(MemorySegment src, long off, int hw, int ft, int live) {
        long slotBytes = (long) hw * SLOT_BYTES;
        MemorySegment.copy(src, off, slots, 0, slotBytes);
        MemorySegment.copy(src, off + slotBytes, freeStack, 0, (long) ft * Integer.BYTES);
        this.highWater = hw;
        this.freeTop = ft;
        this.liveCount = live;
        return slotBytes + (long) ft * Integer.BYTES;
    }
}
