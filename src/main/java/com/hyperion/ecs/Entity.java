package com.hyperion.ecs;

/**
 * Canonical encoding contract for the 64-bit primitive entity handle.
 *
 * <p>Per the Core Invariants, an entity is <b>not</b> an object — it is a raw {@code long}.
 * The handle packs an index and a generation counter so that stale handles referencing a
 * recycled slot can be detected in {@code O(1)} without a heap indirection:
 *
 * <pre>
 *  bit 63                             bit 32 bit 31                              bit 0
 *  ┌───────────────────────────────────────┬───────────────────────────────────────┐
 *  │            INDEX (32 bits)             │          GENERATION (32 bits)          │
 *  └───────────────────────────────────────┴───────────────────────────────────────┘
 *          high 32 bits = slot index               low 32 bits = generation
 * </pre>
 *
 * <p>The static codec methods below are the <b>single source of truth</b> for handle
 * packing across all agents and phases. They are pure, branch-light, and allocation-free
 * ({@code $0$} bytes). They constitute a memory-layout contract owned by the Lead-Architect —
 * not simulation logic — and therefore live here rather than in a Phase 2 implementation.
 *
 * <p><b>Immutable Signature</b> — governed by ADR-0002. Do not alter without an ADR revision.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface Entity {

    /** Number of bits used to encode the slot index (high word). */
    int INDEX_BITS = 32;

    /** Number of bits used to encode the generation counter (low word). */
    int GENERATION_BITS = 32;

    /** Maximum addressable slot index ({@code 2^32 - 1}). */
    long MAX_INDEX = 0xFFFF_FFFFL;

    /** Maximum generation value before wrap-around ({@code 2^32 - 1}). */
    long MAX_GENERATION = 0xFFFF_FFFFL;

    /**
     * Reserved sentinel representing "no entity". Never returned by {@link World#spawn()}.
     * Encoded as all-ones so that a zeroed off-heap slot ({@code 0L}) is a distinct, valid handle.
     */
    long NULL = -1L;

    /**
     * Packs a slot index and generation counter into a single 64-bit handle.
     *
     * @param index      the slot index (high 32 bits)
     * @param generation the generation counter (low 32 bits)
     * @return the packed entity handle
     */
    static long of(int index, int generation) {
        return (((long) index) << GENERATION_BITS) | (generation & MAX_GENERATION);
    }

    /**
     * Extracts the slot index (high 32 bits) from a handle.
     *
     * @param handle the packed entity handle
     * @return the slot index
     */
    static int index(long handle) {
        return (int) (handle >>> GENERATION_BITS);
    }

    /**
     * Extracts the generation counter (low 32 bits) from a handle.
     *
     * @param handle the packed entity handle
     * @return the generation counter
     */
    static int generation(long handle) {
        return (int) handle;
    }

    /**
     * @param handle any 64-bit value
     * @return {@code true} if the handle is the reserved {@link #NULL} sentinel
     */
    static boolean isNull(long handle) {
        return handle == NULL;
    }
}
