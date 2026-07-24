package com.hyperion.ecs.core;

import com.hyperion.ecs.storage.ComponentBitmask;

/**
 * Concrete {@link ComponentBitmask}: a 256-bit signature backed by four {@code long} words.
 *
 * <p>Matching is a bitwise superset test over the four words — no allocation, no branching per
 * component. Instances are built once at registration time; the {@link #matches(ComponentBitmask)}
 * call on the tick path only reads the pre-computed words.
 *
 * @since Phase 2 (Perf-Core-Agent)
 */
public final class NativeBitmask implements ComponentBitmask {

    private final long[] words = new long[WORD_COUNT];

    public NativeBitmask() {
    }

    private NativeBitmask(long[] src) {
        System.arraycopy(src, 0, words, 0, WORD_COUNT);
    }

    /** Sets the bit for the given component id. Setup-time mutation only. */
    public NativeBitmask set(int componentId) {
        words[componentId >>> 6] |= (1L << (componentId & 63));
        return this;
    }

    /** @return an immutable copy of this mask. */
    public NativeBitmask copy() {
        return new NativeBitmask(words);
    }

    @Override
    public boolean get(int componentId) {
        return (words[componentId >>> 6] & (1L << (componentId & 63))) != 0L;
    }

    @Override
    public long word(int wordIndex) {
        return words[wordIndex];
    }

    @Override
    public int cardinality() {
        return Long.bitCount(words[0]) + Long.bitCount(words[1])
                + Long.bitCount(words[2]) + Long.bitCount(words[3]);
    }

    /**
     * @return {@code true} if this mask is a superset of {@code required}, i.e.
     * {@code (this AND required) == required}. Zero-allocation; safe on the tick path.
     */
    @Override
    public boolean matches(ComponentBitmask required) {
        long r0 = required.word(0), r1 = required.word(1);
        long r2 = required.word(2), r3 = required.word(3);
        return (words[0] & r0) == r0
                && (words[1] & r1) == r1
                && (words[2] & r2) == r2
                && (words[3] & r3) == r3;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NativeBitmask other)) return false;
        return words[0] == other.words[0] && words[1] == other.words[1]
                && words[2] == other.words[2] && words[3] == other.words[3];
    }

    @Override
    public int hashCode() {
        long h = words[0] * 0x9E3779B97F4A7C15L
                ^ words[1] * 0xC2B2AE3D27D4EB4FL
                ^ words[2] * 0x165667B19E3779F9L
                ^ words[3] * 0xD6E8FEB86659FD93L;
        return (int) (h ^ (h >>> 32));
    }
}
