# Architecture Decision Records — Hyperion ECS

> Owner: **Lead-Architect Agent**. Phase 1 establishes the immutable API contracts. Per the
> Lead-Architect guardrails, **signatures cannot change without a revising ADR** recorded here.
> Each ADR names the files it governs; downstream agents (Perf-Core, DX, DevTools, QA-Bench)
> build against these and may not silently alter them.

| ADR | Title | Status | Governs |
|-----|-------|--------|---------|
| 0001 | Public façade: `World` / `Hyperion` / builder DX | Accepted | `Hyperion`, `World`, `EntityBuilder`, `Component`, `WorldConfig` |
| 0002 | 64-bit entity handles & 256-bit archetype bitmasks | Accepted | `Entity`, `ComponentBitmask`, `Archetype`, `ComponentType`, `MemoryLayout` |
| 0003 | Type-safe query DSL & parallel dispatch | Accepted | `QueryBuilder`, `Query`, `Each1..4`, `Dispatcher` |
| 0004 | Relationship graph & composite edge keys | Accepted | `Relation`, `EdgeKey` |
| 0005 | Component views & the mutation model | Accepted | `Each1..4`, `Component` |
| 0006 | Off-heap rewind ring buffer | Accepted — implemented in Phase 4 | `RewindBuffer`, `OffHeapRewindBuffer`, `World#enableRewindBuffer/rewind` |
| 0007 | Mutable component views & the two-tier zero-cost DSL | Superseded by 0008 (codegen) | `Component`, `Systems`, `ChunkView` |
| 0008 | Platform baseline JDK 21 → 25; `java.lang.classfile` marshaling codegen | Accepted | build, `Marshaler`, `MarshalerFactory` |

---

## ADR-0001 — Public façade: `World` / `Hyperion` / builder DX

**Context.** The spec fixes the target developer experience: `Hyperion.createWorld()`, a fluent
`spawn().add(...).id()`, and `createSystem().with(...).each(...)`. Component definition and query
setup must each fit in ≤ 3 lines (DX-Agent guardrail).

**Decision.**
- `Hyperion` is a non-instantiable `final` factory class exposing `static World createWorld()` and
  `createWorld(WorldConfig)`. In Phase 1 these are contract stubs that fail fast with
  `UnsupportedOperationException`; the concrete `World` is wired in Phase 2.
- `World` is an interface — the single container for entity lifecycle, system registration,
  `tick()`, the relationship graph, and rewind.
- Entity construction is a separate `EntityBuilder` interface so the terminal `id()` returns a raw
  `long` handle, never an object.
- User component records implement the marker interface `Component`, which bounds the builder and
  query generics (`<T extends Component>`).

**Consequences.** The façade stays allocation-free on the hot path (handles are primitives). The
factory stub keeps the module compilable and usable as a contract before Phase 2.

**Guardrail compliance.** No implementation logic ships; the factory throws rather than simulating.

---

## ADR-0002 — 64-bit entity handles & 256-bit archetype bitmasks

**Context.** Core Invariants mandate raw `long` entity handles (high 32 = index, low 32 =
generation) and the query engine requires `O(1)` archetype matching via bitwise `AND` over
256-bit component masks. Storage is contiguous off-heap SoA via `MemorySegment`.

**Decision.**
- `Entity` is an interface exposing the **canonical handle codec** as pure `static` methods
  (`of/index/generation/isNull`) plus the bit-layout constants. This codec is a *layout contract*,
  not simulation logic, so it is owned here and shared verbatim by every agent.
- `ComponentBitmask` fixes the signature width at `BIT_WIDTH = 256` (`WORD_COUNT = 4` longs) and
  defines the superset `matches()` test used for query matching.
- `ComponentType` is a pure record descriptor (id = bitmask bit index, byte size, alignment, field
  count) assigned at registration.
- `Archetype` is a read-oriented contract over one SoA storage block; structural mutation is a
  Phase-2 implementation concern.
- `MemoryLayout` expresses SoA addressing arithmetic in primitive `long`/`int` offsets and
  **deliberately does not reference `java.lang.foreign.MemorySegment`** in its signatures, so the
  Phase-1 contract compiles on stock JDK 21 with no `--enable-preview`. The binding to
  `MemorySegment` is a Phase-2 detail.

**Consequences.** The 256-bit ceiling caps distinct component types at 256; recorded here as an
accepted limit. Generation-tagged handles give `O(1)` stale-handle detection with no indirection.

**Guardrail compliance.** Only the immutable handle/edge/bit-layout codecs carry bodies; these are
contracts required to be identical across phases. No storage or tick logic is present.

---

## ADR-0003 — Type-safe query DSL & parallel dispatch

**Context.** `each((long id, Position pos, Velocity vel) -> ...)` requires typed, boxing-free
lambdas of varying arity. Dispatch chunks archetypes across
`Executors.newVirtualThreadPerTaskExecutor()`.

**Decision.**
- `QueryBuilder.with(...)` is overloaded per arity and returns a typed `Stage1..4` that carries the
  component generics forward to a matching `EachN` callback. The stages form a **`sealed`
  hierarchy** (`permits Stage1..Stage4`) — the supported arities (1–4) are a closed part of the
  contract; a wider arity needs an ADR revision.
- `EachN` functional interfaces pass `entityId` as a primitive `long` and each component as a typed
  argument. No boxing.
- Completing a stage yields a `Query` handle (signature, `matchCount`, `run`, enable/disable) so
  systems can be toggled or run out of band by tooling.
- `Dispatcher` abstracts chunked virtual-thread execution behind `dispatch(Query)`.

**Consequences.** Query setup meets the ≤ 3-line DX budget while staying fully generic. Arities > 4
are intentionally excluded for Phase 1.

---

## ADR-0004 — Relationship graph & composite edge keys

**Context.** `world.link(source, Targeting.class, target)` and composite 64-bit edge keys
`(RelationTypeID << 32) | TargetEntityID`.

**Decision.**
- `Relation` is a marker interface for relation *types*; `World.link/unlink` are generic over
  `<R extends Relation>` and take the relation `Class`.
- `EdgeKey` exposes the canonical composite-key codec (`of/relationTypeId/targetEntityIndex`) as
  pure `static` methods — mirroring the `Entity` codec pattern.

**Consequences.** Relationship storage/indexing is deferred to implementation; only the key encoding
is frozen here.

---

## ADR-0005 — Component views & the mutation model

**Context.** The spec's illustrative loop mutates component fields (`pos.x += vel.dx`). Java
`record` components are immutable (final fields), so the lambda cannot mutate the record itself.

**Decision.** The `EachN` component arguments are documented as **mutable off-heap views** supplied
by the DX layer (Phase 3): writing through a view mutates the backing SoA column in place. The
user-facing `Component` record remains an immutable *data definition*; the view is the write path.
Reconciling the ergonomic record syntax with in-place SoA mutation (e.g. accessor-backed views or
generated flyweights) is an open DX-Agent design item tracked against this ADR.

**Consequences.** Keeps `Component` immutable and allocation-free while preserving the intended
write ergonomics. Flagged explicitly so Phase 3 does not treat records as directly mutable.

---

## ADR-0006 — Off-heap rewind ring buffer

**Context.** `enableRewindBuffer(120)` / `rewind(60)`; snapshots via direct
`MemorySegment.copyFrom()` in `O(N)` contiguous write time.

**Decision.** `RewindBuffer` is the contract for a fixed-depth off-heap ring buffer with `capture()`
and `restore(framesAgo)`, both direct native block copies — no per-entity serialization, no heap
allocation. `World.enableRewindBuffer/rewind` delegate to it.

**Consequences.** Rewind cost is bounded by total live storage size, independent of entity count
semantics; implementation owned by DevTools-Agent (Phase 4).

> **Update (Phase 4).** Implemented as `OffHeapRewindBuffer`: a ring of grow-only off-heap frame
> segments, each a direct `MemorySegment.copy` of the entity-store + archetype live rows. Capture on
> `tick()` is **0 heap bytes** in steady state; restore reverts data, entity counts, despawns
> (generation), and post-frame archetypes. See §7.1. Telemetry (`Telemetry`/`TuiReporter`/stdlib
> WebSocket `TelemetryServer`) meets the < 2% overhead gate.

---

## ADR-0007 — Mutable component views & the two-tier zero-cost DSL

**Status.** Accepted (Phase 3, DX-Agent). **Resolves the open item in ADR-0005.**

**Context.** ADR-0005 flagged that the spec's illustrative `pos.x += vel.dx` cannot compile against
an immutable `record` (record components are `final`; `pos.x` isn't even valid syntax for a record).
Phase 3 must deliver the ergonomic write DSL and the zero-cost-abstraction guardrail
("high-level DSL methods must compile down to direct primitive method calls without allocation")
while preserving the 1,000,000-entity throughput gate.

**Investigation (measured on the reference JDK 21).** For object-field views the per-field
load/store mechanism dominates cost. Over 200M get+set ops: direct field access ≈ 206 ms;
`Unsafe` offset ≈ 808 ms; `Field` primitive reflection ≈ 3.6 s; array-stored `VarHandle` ≈ 4.1 s.
Only real bytecode (`getfield`/`putfield`) is genuinely zero-cost, which requires **runtime code
generation**. The JDK Class-File API (JEP 457) that would enable stdlib-only codegen **ships in JDK
22**, not 21 — so on the target platform there is no allocation-free path that is simultaneously
object-ergonomic *and* direct-primitive-fast without an external bytecode library.

**Decision.**
1. **Component shapes.** `Component` now admits two shapes:
   - *Immutable `record`* — data/spawn and **read-only** systems (Phase-1 `each` path).
   - *Mutable class with public primitive fields + all-args constructor* — the **read/write** shape
     that makes `pos.x += vel.dx` compile and run. This is a documentation-level widening of the
     `Component` contract; the interface signature is unchanged.
2. **Two execution tiers, both zero-allocation:**
   - **Primitive tier** (`ChunkFunction` + `ChunkView`): the throughput path. The ergonomic DSL
     **compiles down to exactly this** — `Systems.of(w).with(...).each(...)` builds one primitive
     `ChunkFunction` (registered via `registerPrimitive`) closing over reused flyweight views.
     Meets the gate: **~6 ms/tick for 1M entities, 0 B/tick.**
   - **Ergonomic view tier**: the `each(pos, vel)` surface. Reuses one flyweight view per component
     (no per-row allocation) and marshals fields via `VarHandle`. **0 B/tick**, but ~20× the
     primitive tier's time on JDK 21 because the view marshaling cannot be lowered to `putfield`.
3. **Guidance.** Use the ergonomic tier for gameplay/moderate-scale systems; use the primitive tier
   for million-entity hot loops. On **JDK 22+** the view movers can be regenerated as direct-access
   classes via the Class-File API, collapsing the two tiers — a one-module, signature-free upgrade.

**Consequences.** The zero-**allocation** guardrail holds for the entire DSL. The zero-**time**-cost
guarantee holds for the primitive tier (which the DSL emits) and is a JDK-22+ codegen follow-up for
the ergonomic surface. No Phase-1 signature changed. Verified in
`com.hyperion.ecs.verify.DxVerification`.

> **Update (ADR-0008).** The "codegen follow-up" is now done. On the JDK-25 baseline the ergonomic
> tier is backed by `java.lang.classfile`-generated marshalers doing direct `getfield`/`putfield`,
> so the two tiers **collapse**: ergonomic `each()` runs at ~1.0–1.2× the raw primitive tier and
> meets the 1M @ 60 FPS gate. `ComponentBinder` (the `VarHandle` interim) was removed.

---

## ADR-0008 — Platform baseline JDK 21 → 25; `java.lang.classfile` marshaling codegen

**Status.** Accepted (JDK-25 migration). **Completes ADR-0007.**

**Context.** ADR-0007 established that object-field ergonomics (`pos.x += vel.dx`) at direct-field
throughput require runtime bytecode generation, and that the stdlib mechanism for it — the
Class-File API (`java.lang.classfile`) — is **not available on JDK 21** (JEP 457 preview lands in JDK
22; final in JDK 24, JEP 484). The project baseline is therefore raised to **JDK 25 (LTS)**.

**Decision.**
1. **Platform → JDK 25.** `maven.compiler.release = 25`; **all `--enable-preview` flags removed**
   (FFM final since JDK 22 / JEP 454; Class-File API final since JDK 24 / JEP 484). Verified: the
   entire Phase 1–3 codebase compiles and both verification harnesses pass under JDK 25 with no
   preview flags.
2. **Marshaling codegen.** The DX layer synthesizes, per system, a `Marshaler` hidden class via
   `java.lang.classfile` (`MarshalerFactory`). Its `load`/`store` bodies are straight-line
   `getfield`/`putfield` on the component views and primitive `invokevirtual` on `ChunkView` — no
   reflection, no `VarHandle`, no boxing. The generated call sites are monomorphic (one marshaler
   class per system's `ChunkFunction`), so they inline to direct-field access.
3. **Interim removed.** `ComponentBinder` (the array-`VarHandle` marshaler) is deleted; `Systems`
   now drives the generated `Marshaler` over reused view instances.

**Measured (1,000,000 entities, movement system, reference JDK 25).**

| Path | Before (JDK 21, VarHandle) | After (JDK 25, codegen) |
|---|---|---|
| Ergonomic `each()` | ~180 ms/tick, 0 B | **~6.7 ms/tick, 0 B** |
| Raw primitive `ChunkView` | ~6 ms/tick, 0 B | ~6.7 ms/tick, 0 B |
| Ergonomic ÷ primitive | ~28× | **~1.0–1.2×** |

Micro-benchmark, 200M field get+set ops: generated `getfield`/`putfield` ≈ **258 ms** vs direct
≈ 206 ms vs array-`VarHandle` ≈ 4096 ms — i.e. codegen is ~16× faster than the VarHandle interim
and within ~1.25× of hand-written direct access.

**Consequences.** The Zero-Cost Abstractions guardrail is now satisfied literally — the ergonomic DSL
compiles to direct primitive field access with 0-byte tick allocation. Standard-library-only
(no ASM/ByteBuddy). No public user-facing signature changed. The deviation from the spec's original
"Java 21+" target is deliberate and recorded here; JDK 25 is the current LTS. Cost: JDK 25 is now the
minimum toolchain/runtime.
