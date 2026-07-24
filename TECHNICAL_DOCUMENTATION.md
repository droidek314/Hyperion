# Hyperion ECS — Technical Documentation

> **Continuous document.** Per the Core Invariants, every agent updates this file on completing
> its phase. This is the **Phase 1 (Lead-Architect)** baseline: it establishes the structure,
> the API surface, and the memory-layout contracts. Later phases fill the marked sections.

**Status by phase**

| Phase | Agent | Section owner | Status |
|-------|-------|---------------|--------|
| 1 | Lead-Architect | §1–§4 (contracts) | ✅ Complete |
| 2 | Perf-Core-Agent | §5 Memory Layout & Storage | ✅ Complete |
| 3 | DX-Agent | §6 DSL & Concurrency | ✅ Complete |
| 4 | DevTools-Agent | §7 Rewind & Telemetry | ✅ Complete |
| 5 | QA-Bench-Agent | §8 Benchmarks & Validation | ✅ Complete — **gates PASS** |

---

## 1. Overview & Invariants

Hyperion is an archetype-based ECS engineered for a single target: **1,000,000 active entities at
60+ FPS** (< 16.6 ms tick budget) with **zero heap allocation** during the simulation tick and
query loops. The design rests on four load-bearing decisions:

- **Raw `long` entity handles** — no per-entity objects. High 32 bits = slot index, low 32 bits =
  generation, giving `O(1)` stale-handle detection.
- **Off-heap Struct-of-Arrays storage** — component fields live in contiguous native buffers
  (`java.lang.foreign.MemorySegment`), grouped by archetype.
- **256-bit archetype bitmasks** — query-to-archetype matching is a bitwise `AND` over four
  64-bit words (`O(1)` per archetype).
- **Virtual-thread dispatch** — archetype row-chunks are mapped onto
  `Executors.newVirtualThreadPerTaskExecutor()`.

**Platform: JDK 25 (LTS).** The engine leans on three now-final JDK capabilities with no preview
flags: the Foreign Function & Memory API (off-heap storage), virtual threads (parallel dispatch),
and the Class-File API (runtime marshaling codegen, §6.3). Baseline raised from JDK 21 in ADR-0008.

Invariants are frozen in [`ADR.md`](ADR.md); API signatures may not change without a revising ADR.

---

## 2. Package & Module Map

```
com.hyperion.ecs                      Public API façade
├── Hyperion                          Factory entry point  → World createWorld()
├── World                             Entity lifecycle, systems, tick(), graph, rewind
├── WorldConfig  (record)             Off-heap sizing parameters
├── Component    (marker)             Implemented by user component records
├── Relation     (marker)             Implemented by user relation types
├── Entity       (codec)              64-bit handle pack/unpack contract
└── EntityBuilder                     spawn().add(...).id()

com.hyperion.ecs.query                Query DSL & execution
├── QueryBuilder                      with(...) → sealed Stage1..Stage4
├── Query                             Registered, executable system handle
├── Each1 / Each2 / Each3 / Each4     Typed, boxing-free per-entity callbacks
└── Dispatcher                        Chunked virtual-thread execution contract

com.hyperion.ecs.storage              Off-heap layout contracts
├── ComponentType   (record)          Registered component descriptor
├── ComponentBitmask                  256-bit signature; matches() = superset AND test
├── Archetype                         One SoA storage block (read contract)
├── MemoryLayout                      SoA byte-offset addressing arithmetic
└── EdgeKey         (codec)           Composite (relationType<<32)|target key

com.hyperion.ecs.rewind               Time-travel
└── RewindBuffer                      Off-heap snapshot ring buffer

com.hyperion.ecs.core                 Phase-2 engine (Perf-Core-Agent)
├── HyperionWorld                     World impl: lifecycle, archetypes, zero-GC tick()
├── ArchetypeTable                    Off-heap SoA store (impl of Archetype + MemoryLayout)
├── EntityStore                       Off-heap generational entity slots + free list
├── ComponentRegistry / RegisteredComponent   Component id assignment + reflective layout
├── NativeBitmask                     256-bit signature impl (4×long), AND matching
├── ChunkView / ChunkFunction         Primitive zero-alloc execution substrate
├── RegisteredSystem                  Query impl (primitive + compatibility kinds)
├── FieldKind                         Primitive field ↔ ValueLayout mapping
├── VirtualThreadDispatcher           Phase-3 parallel chunk execution (impl of Dispatcher)
├── OffHeapRewindBuffer               Phase-4 snapshot ring buffer (impl of RewindBuffer)
├── TickListener                      Phase-4 per-tick hook seam (avoids core→devtools dependency)
└── Hyperion*Builder                  Concrete Entity/Query builders

com.hyperion.ecs.dsl                  Phase-3 ergonomics (DX-Agent)
├── Systems                           Zero-cost system DSL, compiles to a primitive ChunkFunction
├── Marshaler / MarshalerFactory      java.lang.classfile-generated direct-field view marshaling
└── Relations                         Graph relationship query syntax

com.hyperion.ecs.devtools             Phase-4 telemetry & tooling (DevTools-Agent)
├── Telemetry                         Allocation-free per-tick metrics sink (implements TickListener)
├── TuiReporter                       One-line console dashboard
└── TelemetryServer                   Stdlib-only RFC-6455 WebSocket telemetry stream
```

**Build.** Maven, `maven.compiler.release = 25` (**JDK 25 LTS baseline**, ADR-0008). **No
`--enable-preview` flags** are needed anywhere: the `java.lang.foreign` (FFM) API is final since
JDK 22 (JEP 454), and the `java.lang.classfile` (Class-File API) used by the DX marshaling codegen
is final since JDK 24 (JEP 484). Virtual threads are final since JDK 21 (JEP 444). Verified: the
whole Phase 1–3 codebase compiles under `--release 25` and both harnesses run with no preview flags.

> **Migration note (JDK 21 → 25).** The prior baseline compiled with `--enable-preview --release 21`
> solely for FFM. On JDK 25 that flag is dropped project-wide (a build-only change); the Phase-2 FFM
> usage (`Arena.ofShared`, aligned `ValueLayout` get/set, `MemorySegment.copy`) already matches the
> final API and needed no code change. The migration additionally **unlocked** the DX codegen (§6.3).

---

## 3. Memory Layout Contracts (frozen in Phase 1)

### 3.1 Entity handle (`Entity`)

```
 bit 63                             bit 32 bit 31                              bit 0
 ┌───────────────────────────────────────┬───────────────────────────────────────┐
 │            INDEX (32 bits)             │          GENERATION (32 bits)          │
 └───────────────────────────────────────┴───────────────────────────────────────┘
```

| Operation | Definition |
|-----------|------------|
| `of(index, gen)` | `((long) index << 32) \| (gen & 0xFFFF_FFFFL)` |
| `index(h)` | `(int) (h >>> 32)` |
| `generation(h)` | `(int) h` |
| `NULL` | `-1L` (reserved; never spawned) |

Generation lets a recycled slot invalidate outstanding handles: `World.isAlive(h)` compares the
handle's generation with the slot's current generation.

### 3.2 Component signature (`ComponentBitmask`)

- Width `BIT_WIDTH = 256`, backed by `WORD_COUNT = 4` × `long`.
- A component type's `id` **is** its bit index → caps distinct components at 256.
- Match test: archetype **matches** query iff `(archetype AND query) == query`.

### 3.3 Struct-of-Arrays addressing (`MemoryLayout`)

Component fields are stored column-major per archetype. For field `f` of component `c` at row `r`:

```
address = columnBaseOffset(c, f) + (long) r * fieldStride(c, f)
```

Offsets are primitive `long`/`int`. The binding of these offsets to a concrete
`MemorySegment` is a **Phase-2** responsibility (Perf-Core-Agent fills §5).

### 3.4 Relationship edge key (`EdgeKey`)

```
 key = (RelationTypeID << 32) | TargetEntityIndex
```

| Operation | Definition |
|-----------|------------|
| `of(rel, target)` | `((long) rel << 32) \| (target & 0xFFFF_FFFFL)` |
| `relationTypeId(k)` | `(int) (k >>> 32)` |
| `targetEntityIndex(k)` | `(int) k` |

---

## 4. Public API Walkthrough (target DX)

**Define components** (user space):
```java
public record Position(float x, float y) implements Component {}
public record Velocity(float dx, float dy) implements Component {}
```

**Spawn:**
```java
World world = Hyperion.createWorld();
long entity = world.spawn()
    .add(new Position(0f, 0f))
    .add(new Velocity(1f, 1f))
    .id();
```

**Register a system & run the zero-GC loop:**
```java
world.createSystem()
     .with(Position.class, Velocity.class)      // QueryBuilder.Stage2<Position, Velocity>
     .each((long id, Position pos, Velocity vel) -> { /* mutate via SoA views */ });

while (running) {
    world.tick();                                // 0 bytes allocated
}
```

**Relationships & rewind:**
```java
world.link(sourceEntity, Targeting.class, targetEntity);
world.enableRewindBuffer(120);
world.rewind(60);
```

> **Mutation model (ADR-0005 → resolved in ADR-0007).** Immutable `record` components are read-only
> in systems. For the `pos.x += vel.dx` write ergonomics, define components as **mutable classes with
> public primitive fields**; the Phase-3 `Systems` DSL drives them through reused zero-allocation
> flyweight views. See §6.1–6.3.

---

## Appendix A — Deliverables Checklist

**Phase 1 (Lead-Architect)**
- [x] `World.java`, `Entity.java`, `Query.java` and the full contract surface (14 types)
- [x] Memory-layout contracts (`Entity`, `EdgeKey`, `ComponentBitmask`, `MemoryLayout`)
- [x] `ADR.md` with immutable-signature ADRs (0001–0006)
- [x] `TECHNICAL_DOCUMENTATION.md` structure (this document)
- [x] Maven build; pure-contract layer compiles with no preview flags (JDK 21 originally, JDK 25 now)

