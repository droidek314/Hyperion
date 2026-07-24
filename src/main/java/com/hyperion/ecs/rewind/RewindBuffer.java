package com.hyperion.ecs.rewind;

/**
 * Off-heap ring buffer of world snapshots enabling instantaneous frame rewind.
 *
 * <p>Backs {@link com.hyperion.ecs.World#enableRewindBuffer(int)} and
 * {@link com.hyperion.ecs.World#rewind(int)}. Each captured frame is a direct native block copy
 * ({@code MemorySegment.copyFrom()}) of the live archetype storage — an {@code O(N)} contiguous
 * memory write with no per-entity serialization and no heap allocation. Rewinding restores a
 * frame with the symmetric copy.
 *
 * <p>Owned by the {@code DevTools-Agent} (Phase 4). This is the contract only.
 *
 * <p><b>Immutable Signature</b> — governed by ADR-0006.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface RewindBuffer {

    /**
     * @return the ring-buffer depth in frames
     */
    int capacityFrames();

    /**
     * @return the number of frames currently retained (&le; {@link #capacityFrames()})
     */
    int retainedFrames();

    /**
     * Captures the current world state into the ring buffer, evicting the oldest frame if full.
     */
    void capture();

    /**
     * Restores world state from a retained frame via a direct native memory copy.
     *
     * @param framesAgo how many frames back to restore, in {@code [1, retainedFrames()]}
     */
    void restore(int framesAgo);
}
