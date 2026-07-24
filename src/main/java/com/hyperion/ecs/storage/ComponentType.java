package com.hyperion.ecs.storage;

import com.hyperion.ecs.Component;

/**
 * Canonical descriptor for a registered component type — the bridge between a user-space
 * {@link Component} record and its off-heap SoA representation.
 *
 * <p>Assigned once, at registration time, by the storage engine. The {@code id} is the bit
 * index used within a {@link ComponentBitmask}. {@code byteSize} and {@code alignment}
 * describe the packed footprint of one component instance within a per-field SoA column.
 *
 * <p>This is a pure signature record (no logic), owned by the Lead-Architect.
 *
 * @param id        stable component id and bitmask bit index, in {@code [0, ComponentBitmask.BIT_WIDTH)}
 * @param type      the user-space component record class
 * @param byteSize  packed size, in bytes, of one component instance
 * @param alignment required byte alignment for the component's off-heap columns
 * @param fieldCount number of primitive fields (SoA columns) the component decomposes into
 * @since Phase 1 (Lead-Architect)
 */
public record ComponentType(
        int id,
        Class<? extends Component> type,
        int byteSize,
        int alignment,
        int fieldCount
) {
}
