package com.hyperion.ecs;

/**
 * Marker contract implemented by every component <em>data</em> definition.
 *
 * <p>Components are always immutable {@code record} types carrying only primitive-friendly
 * fields ({@code float}, {@code int}, {@code long}, {@code boolean}). They contain no behaviour.
 * The storage engine (Phase 2, {@code Perf-Core-Agent}) reflects over the record's canonical
 * component descriptor to derive an off-heap Struct-of-Arrays (SoA) layout.
 *
 * <p>Example (user-space):
 * <pre>{@code
 * public record Position(float x, float y) implements Component {}
 * public record Velocity(float dx, float dy) implements Component {}
 * }</pre>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Implementors <b>must</b> be {@code record} types.</li>
 *   <li>Implementors <b>must not</b> declare reference-typed fields (no {@code String},
 *       arrays, or nested objects) — this preserves the flat, allocation-free SoA layout.</li>
 *   <li>Implementors <b>must not</b> declare instance methods with side effects.</li>
 * </ul>
 *
 * <p><b>Immutable Signature</b> — governed by ADR-0001. Do not alter without an ADR revision.
 *
 * @since Phase 1 (Lead-Architect)
 */
public interface Component {
}
