package com.hyperion.ecs;

/**
 * Marker contract implemented by every relationship <em>type</em> in the entity graph.
 *
 * <p>Relations are directed, typed edges between two entities — e.g. {@code Targeting},
 * {@code ChildOf}, {@code Owns}. A relation type carries no per-edge payload by itself; the
 * edge is identified by a composite 64-bit key (see {@link com.hyperion.ecs.storage.EdgeKey}).
 *
 * <p>Example (user-space):
 * <pre>{@code
 * public record Targeting() implements Relation {}
 *
 * world.link(sourceEntity, Targeting.class, targetEntity);
 * }</pre>
 *
 * <p><b>Immutable Signature</b> — governed by ADR-0004.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface Relation {
}
