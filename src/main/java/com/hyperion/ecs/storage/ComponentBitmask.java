package com.hyperion.ecs.storage;

/**
 * A fixed-width 256-bit component signature, used for {@code O(1)} archetype matching.
 *
 * <p>Every archetype and every {@link com.hyperion.ecs.query.Query} carries one of these. A
 * query matches an archetype iff {@code (archetype AND query) == query} — a bitwise {@code AND}
 * over four 64-bit words. The 256-bit width bounds the number of distinct component types at
 * {@value #BIT_WIDTH}.
 *
 * <p>Implementations must be backed by four primitive {@code long} words (or an equivalent
 * off-heap layout) and perform matching without allocation.
 *
 * <p><b>Immutable Signature</b> — governed by ADR-0002.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface ComponentBitmask {

    /** Total width of the signature in bits. */
    int BIT_WIDTH = 256;

    /** Number of 64-bit words backing the signature. */
    int WORD_COUNT = BIT_WIDTH / Long.SIZE;

    /**
     * @param componentId a component type id in {@code [0, BIT_WIDTH)}
     * @return {@code true} if the bit for that component is set
     */
    boolean get(int componentId);

    /**
     * Tests whether this signature contains all bits of {@code required} (superset test).
     *
     * @param required the required signature (typically a query signature)
     * @return {@code true} if {@code (this AND required) == required}
     */
    boolean matches(ComponentBitmask required);

    /**
     * @param wordIndex a word index in {@code [0, WORD_COUNT)}
     * @return the raw 64-bit word at that index
     */
    long word(int wordIndex);

    /**
     * @return the number of set bits (population count) across all words
     */
    int cardinality();
}
