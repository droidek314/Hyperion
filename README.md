# Hyperion

A zero-garbage, off-heap entity–component–system (ECS) engine for Java 25.

Hyperion keeps entity data outside the Java heap, in flat columns (struct-of-arrays) allocated
through the Foreign Function & Memory API. The update loop allocates nothing, so the garbage
collector has nothing to do, and the whole world can be copied as a few memory blocks every frame,
which makes instant rewind possible.

## Highlights

- 1,000,000 entities updated in about 7 ms per tick (8 cores, JDK 25)
- 0 bytes of heap allocation per tick, checked with thread allocation counters and JFR
- Off-heap archetype tables through `java.lang.foreign`
- 64-bit generational entity handles that detect use after delete
- Constant-time query matching with 256-bit component masks
- Readable `each(...)` systems, compiled to direct field access with `java.lang.classfile`
- Time-travel rewind through whole-block memory copies
- Parallel dispatch on virtual threads
- Telemetry with a WebSocket stream
- Standard library only

## Requirements

JDK 25 or newer. No other dependencies.

## Build

```bash
javac --release 25 -d target/classes $(find src/main/java -name '*.java')
```

## Example

```java
public final class Position implements Component {
    public float x, y;
    public Position(float x, float y) { this.x = x; this.y = y; }
}
// Velocity is declared the same way, with fields dx and dy.

HyperionWorld world = (HyperionWorld) Hyperion.createWorld(new WorldConfig(1_000_000, 256, 120));

long e = world.spawn().add(new Position(0, 0)).add(new Velocity(1, 2)).id();

Systems.of(world).with(Position.class, Velocity.class)
       .each((long id, Position p, Velocity v) -> { p.x += v.dx; p.y += v.dy; });

world.tick();       // 0 bytes of garbage per call
world.rewind(60);   // restore the state from 60 captured frames ago
```

Components that systems write to must be classes with public primitive fields. Records work as
read-only components.

## Live demo

A browser dashboard that streams a 50,000-entity bouncing-particle simulation from the engine in
real time. Build as above, then from the repository root:

```bash
java -cp target/classes com.hyperion.ecs.demo.VisualDemoApp
```

Open <http://localhost:8080>. Run it from the repository root, because the server loads
`src/main/resources/dashboard.html` from there. Entity count and port are optional arguments, for
example `VisualDemoApp 200000 9090`. Stop it with Ctrl+C.

| Key | Button | Action |
|---|---|---|
| Space | Pause / Resume | Pause or resume the simulation |
| S | Step Forward | Advance exactly one tick |
| R | Rewind 1s | Rewind 60 frames |
| Shift+R | Rewind 2s | Rewind 120 frames |
| L | Graph Link | Link a hunter to 48 targets with a `Targeting` relationship and highlight them |

The overlay shows the entity count, render FPS, tick duration, heap allocated per tick (it stays at
0 B), JVM heap use, the frame number and how full the rewind buffer is.

The simulation thread only runs `world.tick()`. Each frame is encoded and sent on a virtual thread
as a binary WebSocket message: a 32-byte header plus 5 bytes per entity (250,032 bytes at 50,000
entities). The HTTP and WebSocket server is written with the standard library only.

## Verify

Each harness prints its checks and stops with an error on the first failure.

```bash
java -cp target/classes com.hyperion.ecs.verify.PerfCoreVerification   # storage, lifecycle, zero-GC tick
java -cp target/classes com.hyperion.ecs.verify.DxVerification         # DSL, relationships, parallel dispatch
java -cp target/classes com.hyperion.ecs.verify.DevToolsVerification   # rewind, telemetry, WebSocket
java -cp target/classes com.hyperion.ecs.verify.DemoVerification       # live demo: server, frames, controls
java -cp target/classes com.hyperion.ecs.verify.QualityGate            # benchmarks; exits 1 if a gate fails
```

`QualityGate` enforces two release gates: no heap allocation during `tick()`, and 1,000,000
entities within the 16.6 ms budget for 60 FPS. `JfrAllocationProfile` records a JFR allocation
profile of the tick loop.

## Measured results

JDK 25.0.4, G1, 8 cores, 1,000,000 entities running `position += velocity`, median of 12 runs of
40 ticks:

| Path | Tick time | Heap allocation per tick |
|---|---:|---:|
| `Systems...each(...)` | 7.1 ms | 0 B |
| Primitive `ChunkFunction` | 7.8 ms | 0 B |

For comparison, the same update with plain Java objects, 300 frames each: immutable records
allocated 24 MB per tick and ran at 19.3 ms median with 12 garbage collections. Mutable objects ran
at 6.0 ms on a fresh heap, but at 24.0 ms once their iteration order no longer matched memory order.

## Known limitations

- Spawning is the slow path: about 0.5–1 s per million entities.
- Growing a table keeps its outgrown memory block reserved until the world is closed.
- Relationships (`link` / `unlink`) live on the heap and are not part of rewind snapshots.
- A JMH suite is in `src/jmh` (Maven profile `jmh`) but has not been run yet.

## Layout

| Package | Contents |
|---|---|
| `com.hyperion.ecs` | Public API: `World`, `Hyperion`, `Entity`, `Component` |
| `com.hyperion.ecs.core` | Engine: archetype tables, entity store, tick loop, rewind ring, dispatcher |
| `com.hyperion.ecs.dsl` | `Systems` DSL, bytecode-generated marshaler, `Relations` |
| `com.hyperion.ecs.devtools` | Telemetry, console reporter, WebSocket server |
| `com.hyperion.ecs.demo` | Live demo app and its HTTP / WebSocket dashboard server |
| `com.hyperion.ecs.verify` | Verification harnesses and the quality gate |
