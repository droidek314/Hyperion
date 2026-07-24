package com.hyperion.ecs.bench;

import com.hyperion.ecs.Component;
import com.hyperion.ecs.Hyperion;
import com.hyperion.ecs.WorldConfig;
import com.hyperion.ecs.core.HyperionWorld;
import com.hyperion.ecs.core.VirtualThreadDispatcher;
import com.hyperion.ecs.dsl.Systems;
import com.hyperion.ecs.query.Query;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Phase-5 JMH benchmark suite (QA-Bench-Agent) — the spec-mandated benchmark artifact.
 *
 * <p><b>Build/run</b> (requires network access to resolve the JMH artifacts):
 * <pre>
 *   mvn -Pjmh clean package
 *   java -jar target/benchmarks.jar -prof gc
 * </pre>
 * The {@code -prof gc} profiler reports {@code gc.alloc.rate.norm}, which is the JMH-native
 * expression of the <b>Fail-on-GC</b> gate: it must read <b>0 B/op</b> for every {@code tick}
 * benchmark below.
 *
 * <p><b>Status note.</b> This suite is a source artifact only — it is <em>not</em> compiled or
 * executed in the current environment, which has no Maven/Gradle and no JMH jars available offline.
 * It therefore lives in the {@code src/jmh/java} source root, which the default build does not
 * compile, so it cannot break {@code mvn package}. The executed Phase-5 numbers reported in
 * TECHNICAL_DOCUMENTATION §8 come from {@code com.hyperion.ecs.verify.QualityGate}, a JDK-only
 * harness that mirrors this methodology (separate warmup/measurement iterations, per-iteration
 * aggregation, percentile reporting) and from real JFR recordings.
 *
 * @since Phase 5 (QA-Bench-Agent)
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
public class HyperionBenchmark {

    // Mutable class components (ADR-0007) — the zero-cost read/write shape.
    public static final class Position implements Component {
        public float x, y;
        public Position(float x, float y) { this.x = x; this.y = y; }
    }
    public static final class Velocity implements Component {
        public float dx, dy;
        public Velocity(float dx, float dy) { this.dx = dx; this.dy = dy; }
    }
    public static final class Heat implements Component {
        public float v;
        public Heat(float v) { this.v = v; }
    }

    /** Entity counts spanning the target scale. */
    @Param({"100000", "1000000"})
    public int entities;

    private HyperionWorld dslWorld;
    private HyperionWorld primWorld;
    private HyperionWorld rewindWorld;
    private HyperionWorld heavyWorld;
    private Query heavySystem;
    private VirtualThreadDispatcher dispatcher;

    @Setup(Level.Trial)
    public void setup() {
        // 1) Ergonomic DSL (java.lang.classfile codegen marshaler)
        dslWorld = (HyperionWorld) Hyperion.createWorld(new WorldConfig(entities, 256, 0));
        fill(dslWorld, entities);
        Systems.of(dslWorld).with(Position.class, Velocity.class)
                .each((long id, Position p, Velocity v) -> { p.x += v.dx; p.y += v.dy; });

        // 2) Raw primitive ChunkFunction
        primWorld = (HyperionWorld) Hyperion.createWorld(new WorldConfig(entities, 256, 0));
        fill(primWorld, entities);
        final int POS = primWorld.componentId(Position.class);
        final int VEL = primWorld.componentId(Velocity.class);
        primWorld.createSystem(chunk -> {
            int n = chunk.count();
            for (int r = 0; r < n; r++) {
                chunk.setFloat(POS, 0, r, chunk.getFloat(POS, 0, r) + chunk.getFloat(VEL, 0, r));
                chunk.setFloat(POS, 1, r, chunk.getFloat(POS, 1, r) + chunk.getFloat(VEL, 1, r));
            }
        }, Position.class, Velocity.class);

        // 3) DSL + off-heap rewind snapshot capture every frame
        rewindWorld = (HyperionWorld) Hyperion.createWorld(new WorldConfig(entities, 256, 16));
        fill(rewindWorld, entities);
        Systems.of(rewindWorld).with(Position.class, Velocity.class)
                .each((long id, Position p, Velocity v) -> { p.x += v.dx; p.y += v.dy; });

        // 4) Compute-heavy stateless system for parallel dispatch
        heavyWorld = (HyperionWorld) Hyperion.createWorld(new WorldConfig(entities, 256, 0));
        for (int i = 0; i < entities; i++) {
            heavyWorld.spawn().add(new Position(i % 100, i % 50)).add(new Heat(0)).id();
        }
        final int HP = heavyWorld.componentId(Position.class);
        final int HH = heavyWorld.componentId(Heat.class);
        heavySystem = heavyWorld.createSystem(chunk -> {
            int n = chunk.count();
            for (int r = 0; r < n; r++) {
                float x = chunk.getFloat(HP, 0, r), y = chunk.getFloat(HP, 1, r);
                float acc = 0f;
                for (int k = 0; k < 96; k++) acc += (float) Math.sqrt(x * x + y * y + k);
                chunk.setFloat(HH, 0, r, acc);
            }
        }, Position.class, Heat.class);
        heavySystem.disable();
        dispatcher = new VirtualThreadDispatcher(heavyWorld, 32_768);
    }

    private static void fill(HyperionWorld w, int n) {
        for (int i = 0; i < n; i++) {
            w.spawn().add(new Position(0, 0)).add(new Velocity(1, 2)).id();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        dispatcher.close();
        dslWorld.close();
        primWorld.close();
        rewindWorld.close();
        heavyWorld.close();
    }

    // ── Throughput gate benchmarks: must be < 16.6 ms at 1,000,000 entities ──────────────

    /** Ergonomic DSL tick. Gate: < 16.6 ms/op @ 1M; gc.alloc.rate.norm must be 0 B/op. */
    @Benchmark
    public void tickDsl() {
        dslWorld.tick();
    }

    /** Raw primitive ChunkFunction tick. Gate: < 16.6 ms/op @ 1M; 0 B/op. */
    @Benchmark
    public void tickPrimitive() {
        primWorld.tick();
    }

    /** Tick including a copy-free off-heap snapshot capture. Gate: 0 B/op. */
    @Benchmark
    public void tickWithRewindCapture() {
        rewindWorld.tick();
    }

    // ── Concurrency ─────────────────────────────────────────────────────────────────────

    @Benchmark
    public void heavySingleThread() {
        heavySystem.run();
    }

    @Benchmark
    public void heavyVirtualThreads() {
        dispatcher.dispatch(heavySystem);
    }

    // ── Structural (allocation is expected here — not gated) ────────────────────────────

    @Benchmark
    @Warmup(iterations = 1, time = 1)
    @Measurement(iterations = 3, time = 1)
    public void spawnDespawnChurn(Blackhole bh) {
        long e = dslWorld.spawn().add(new Position(1, 1)).add(new Velocity(1, 1)).id();
        bh.consume(e);
        dslWorld.despawn(e);
    }
}
