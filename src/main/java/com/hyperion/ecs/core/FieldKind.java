package com.hyperion.ecs.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Maps a supported primitive record-field type to its off-heap footprint and native accessors.
 *
 * <p>Component records decompose into one SoA column per field; this enum is the bridge between a
 * Java primitive and the {@link ValueLayout} used to read/write it in a {@link MemorySegment}.
 *
 * <p>The {@code readBoxed}/{@code writeBoxed} helpers are used only on the <b>structural</b> paths
 * (spawn, and the Phase-3 compatibility record path) — never inside the gated zero-GC tick loop,
 * which uses the primitive typed accessors on {@link ChunkView} instead.
 *
 * @since Phase 2 (Perf-Core-Agent)
 */
public enum FieldKind {
    FLOAT(4),
    DOUBLE(8),
    INT(4),
    LONG(8),
    SHORT(2),
    CHAR(2),
    BYTE(1),
    BOOLEAN(1);

    private final int byteSize;

    FieldKind(int byteSize) {
        this.byteSize = byteSize;
    }

    public int byteSize() {
        return byteSize;
    }

    /**
     * Resolves the {@code FieldKind} for a primitive field type.
     *
     * @param type a primitive {@code Class} (e.g. {@code float.class})
     * @return the matching kind
     * @throws IllegalArgumentException if the type is not a supported primitive
     */
    static FieldKind of(Class<?> type) {
        if (type == float.class) return FLOAT;
        if (type == double.class) return DOUBLE;
        if (type == int.class) return INT;
        if (type == long.class) return LONG;
        if (type == short.class) return SHORT;
        if (type == char.class) return CHAR;
        if (type == byte.class) return BYTE;
        if (type == boolean.class) return BOOLEAN;
        throw new IllegalArgumentException(
                "Unsupported component field type '" + type.getName()
                        + "'. Components must contain only primitive fields (see Component contract).");
    }

    // ── Structural (setup) accessors — boxing permitted, never on the tick path ──────────

    /** Writes a boxed primitive value into the segment. Spawn / compatibility path only. */
    void writeBoxed(MemorySegment seg, long offset, Object value) {
        switch (this) {
            case FLOAT   -> seg.set(ValueLayout.JAVA_FLOAT, offset, (Float) value);
            case DOUBLE  -> seg.set(ValueLayout.JAVA_DOUBLE, offset, (Double) value);
            case INT     -> seg.set(ValueLayout.JAVA_INT, offset, (Integer) value);
            case LONG    -> seg.set(ValueLayout.JAVA_LONG, offset, (Long) value);
            case SHORT   -> seg.set(ValueLayout.JAVA_SHORT, offset, (Short) value);
            case CHAR    -> seg.set(ValueLayout.JAVA_CHAR, offset, (Character) value);
            case BYTE    -> seg.set(ValueLayout.JAVA_BYTE, offset, (Byte) value);
            case BOOLEAN -> seg.set(ValueLayout.JAVA_BYTE, offset, (byte) (((Boolean) value) ? 1 : 0));
        }
    }

    /** @return the boxed zero/default value for this kind. Setup path only. */
    Object zero() {
        return switch (this) {
            case FLOAT   -> 0f;
            case DOUBLE  -> 0d;
            case INT     -> 0;
            case LONG    -> 0L;
            case SHORT   -> (short) 0;
            case CHAR    -> (char) 0;
            case BYTE    -> (byte) 0;
            case BOOLEAN -> Boolean.FALSE;
        };
    }

    /** Reads a boxed primitive value from the segment. Compatibility (record) path only. */
    Object readBoxed(MemorySegment seg, long offset) {
        return switch (this) {
            case FLOAT   -> seg.get(ValueLayout.JAVA_FLOAT, offset);
            case DOUBLE  -> seg.get(ValueLayout.JAVA_DOUBLE, offset);
            case INT     -> seg.get(ValueLayout.JAVA_INT, offset);
            case LONG    -> seg.get(ValueLayout.JAVA_LONG, offset);
            case SHORT   -> seg.get(ValueLayout.JAVA_SHORT, offset);
            case CHAR    -> seg.get(ValueLayout.JAVA_CHAR, offset);
            case BYTE    -> seg.get(ValueLayout.JAVA_BYTE, offset);
            case BOOLEAN -> seg.get(ValueLayout.JAVA_BYTE, offset) != 0;
        };
    }
}
