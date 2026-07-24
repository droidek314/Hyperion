package com.hyperion.ecs.storage;

/**
 * Canonical encoding contract for the composite 64-bit relationship edge key.
 *
 * <p>Per the Low-Level Technical Requirements, an edge is indexed by a composite key packing the
 * relation type id and the target entity index:
 *
 * <pre>
 *  bit 63                             bit 32 bit 31                              bit 0
 *  ┌───────────────────────────────────────┬───────────────────────────────────────┐
 *  │       RELATION TYPE ID (32 bits)       │      TARGET ENTITY INDEX (32 bits)     │
 *  └───────────────────────────────────────┴───────────────────────────────────────┘
 *  key = (RelationTypeID << 32) | TargetEntityIndex
 * </pre>
 *
 * <p>The static codec methods are the single source of truth for edge-key packing and are pure
 * and allocation-free. This is a memory-layout contract owned by the Lead-Architect.
 *
 * <p><b>Immutable Signature</b> — governed by ADR-0004.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface EdgeKey {

    /** Bit mask for the low 32 bits (target entity index). */
    long TARGET_MASK = 0xFFFF_FFFFL;

    /**
     * Packs a relation type id and target entity index into a 64-bit edge key.
     *
     * @param relationTypeId    the relation type id (high 32 bits)
     * @param targetEntityIndex the target entity slot index (low 32 bits)
     * @return the packed edge key
     */
    static long of(int relationTypeId, int targetEntityIndex) {
        return (((long) relationTypeId) << 32) | (targetEntityIndex & TARGET_MASK);
    }

    /**
     * @param edgeKey a packed edge key
     * @return the relation type id (high 32 bits)
     */
    static int relationTypeId(long edgeKey) {
        return (int) (edgeKey >>> 32);
    }

    /**
     * @param edgeKey a packed edge key
     * @return the target entity slot index (low 32 bits)
     */
    static int targetEntityIndex(long edgeKey) {
        return (int) edgeKey;
    }
}
