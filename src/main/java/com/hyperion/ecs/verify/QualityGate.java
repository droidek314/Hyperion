package com.hyperion.ecs.verify;

import com.hyperion.ecs.Component;
import com.hyperion.ecs.Hyperion;
import com.hyperion.ecs.WorldConfig;
import com.hyperion.ecs.core.HyperionWorld;
import com.hyperion.ecs.core.VirtualThreadDispatcher;
import com.hyperion.ecs.dsl.Systems;
import com.hyperion.ecs.query.Query;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Phase-5 quality gatekeeper (QA-Bench-Agent). Runs the statistical benchmark suite and enforces the
 * two release gates, then exits with a CI-usable status code — the "PR pass/fail flag".
 *
 * <ul>
 *   <li><b>GATE 1 — Fail-on-GC:</b> every simulation scenario must allocate <b>0 heap bytes</b> per
 *       {@code tick()} (measured with {@code ThreadMXBean.getThreadAllocatedBytes}, min-over-windows
 *       so one-off JIT/JVM allocations cannot mask the steady-state figure).</li>
 *   <li><b>GATE 2 — Throughput:</b> 1,000,000 entities must tick within the 16.6 ms / 60 FPS budget,
 *       gated on the <b>median</b> iteration (not the best case).</li>
 * </ul>
 *
 * <p>Exit code {@code 0} = all gates pass, {@code 1} = at least one gate failed.
 *
 * <p>This harness is JDK-only by necessity: no Maven/Gradle or JMH jars are available in this
 * environment (see {@code src/jmh/java} for the spec-mandated JMH suite artifact). Methodology
 * mirrors JMH: separate warmup/measurement iterations, per-iteration aggregation, and percentile
 * reporting rather than a single averaged run.
 *
 * Run (JDK 25): {@code java -cp target/classes com.hyperion.ecs.verify.QualityGate}
 *
 * @since Phase 5 (QA-Bench-Agent)
 */
public final class QualityGate {

    private static final double BUDGET_MS = 16.6;   // 60 FPS tick budget
    private static final int ENTITIES_1M = 1_000_000;

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

    /** One benchmark scenario result. */
    record Result(String name, long entities, double minMs, double medMs, double p95Ms, double maxMs,
                  long bytesPerTick, boolean throughputGated) {
        double fps() { return medMs > 0 ? 1000.0 / medMs : 0; }
        boolean gcPass() { return bytesPerTick == 0; }
        boolean throughputPass() { return !throughputGated || medMs < BUDGET_MS; }
    }

    public static void main(String[] args) {
        System.out.println("== Hyperion ECS — Phase 5 Quality Gate (QA-Bench-Agent) ==");
        System.out.println("   JDK " + System.getProperty("java.version")
                + " | " + Runtime.getRuntime().availableProcessors() + " cores\n");

        List<Result> results = new ArrayList<>();
        results.addAll(bench1M());
        results.add(benchRewind());
        double speedup = benchParallel();

        report(results, speedup);
        System.exit(verdict(results) ? 0 : 1);
    }

    // ── Scenarios ────────────────────────────────────────────────────────────────────────

    /** Both DSL tiers over 1M entities, sharing one world so the 1M spawn happens once. */
    private static List<Result> bench1M() {
        List<Result> out = new ArrayList<>();
        try (HyperionWorld world = (HyperionWorld) Hyperion.createWorld(
                new WorldConfig(ENTITIES_1M, 256, 0))) {
            long t0 = System.nanoTime();
            for (int i = 0; i < ENTITIES_1M; i++) {
                world.spawn().add(new Position(0, 0)).add(new Velocity(1, 2)).id();
            }
            System.out.printf("   spawned %,d entities in %.2f s%n%n",
                    ENTITIES_1M, (System.nanoTime() - t0) / 1e9);

            final int POS = world.componentId(Position.class);
            final int VEL = world.componentId(Velocity.class);

            Query ergo = Systems.of(world).with(Position.class, Velocity.class)
                    .each((long id, Position p, Velocity v) -> { p.x += v.dx; p.y += v.dy; });
            Query prim = world.createSystem(chunk -> {
                int n = chunk.count();
                for (int r = 0; r < n; r++) {
                    chunk.setFloat(POS, 0, r, chunk.getFloat(POS, 0, r) + chunk.getFloat(VEL, 0, r));
                    chunk.setFloat(POS, 1, r, chunk.getFloat(POS, 1, r) + chunk.getFloat(VEL, 1, r));
                }
            }, Position.class, Velocity.class);

            // Measure one tier at a time.
            prim.disable();
            ergo.enable();
            out.add(measure(world, "DSL each() [codegen marshaler]", ENTITIES_1M, true));

            ergo.disable();
            prim.enable();
            out.add(measure(world, "primitive ChunkFunction", ENTITIES_1M, true));
        }
        return out;
    }

    /** Rewind-enabled tick: movement + copy-free off-heap snapshot capture every frame. */
    private static Result benchRewind() {
        final int N = 200_000;
        try (HyperionWorld world = (HyperionWorld) Hyperion.createWorld(new WorldConfig(N, 256, 16))) {
            for (int i = 0; i < N; i++) {
                world.spawn().add(new Position(0, 0)).add(new Velocity(1, 1)).id();
            }
            Systems.of(world).with(Position.class, Velocity.class)
                    .each((long id, Position p, Velocity v) -> { p.x += v.dx; p.y += v.dy; });
            return measure(world, "DSL each() + rewind capture", N, false);
        }
    }

    /** Informational: virtual-thread dispatch speedup on a compute-heavy stateless system. */
    private static double benchParallel() {
        final int N = 500_000;
        try (HyperionWorld world = (HyperionWorld) Hyperion.createWorld(new WorldConfig(N, 256, 0))) {
            for (int i = 0; i < N; i++) {
                world.spawn().add(new Position(i % 100, i % 50)).add(new Heat(0)).id();
            }
            final int POS = world.componentId(Position.class);
            final int HEAT = world.componentId(Heat.class);
            Query heavy = world.createSystem(chunk -> {
                int n = chunk.count();
                for (int r = 0; r < n; r++) {
                    float x = chunk.getFloat(POS, 0, r), y = chunk.getFloat(POS, 1, r);
                    float acc = 0f;
                    for (int k = 0; k < 96; k++) acc += (float) Math.sqrt(x * x + y * y + k);
                    chunk.setFloat(HEAT, 0, r, acc);
                }
            }, Position.class, Heat.class);
            heavy.disable();

            for (int i = 0; i < 4; i++) heavy.run();
            long best1 = Long.MAX_VALUE;
            for (int r = 0; r < 5; r++) {
                long t = System.nanoTime();
                heavy.run();
                best1 = Math.min(best1, System.nanoTime() - t);
            }
            long bestN = Long.MAX_VALUE;
            try (VirtualThreadDispatcher d = new VirtualThreadDispatcher(world, 32_768)) {
                for (int i = 0; i < 4; i++) d.dispatch(heavy);
                for (int r = 0; r < 5; r++) {
                    long t = System.nanoTime();
                    d.dispatch(heavy);
                    bestN = Math.min(bestN, System.nanoTime() - t);
                }
            }
            return (double) best1 / bestN;
        }
    }

    // ── Measurement (JMH-style: warmup iters, measurement iters, percentiles) ────────────

    private static Result measure(HyperionWorld world, String name, long entities, boolean gated) {
        final int warmupIters = 3, measureIters = 12, ticksPerIter = 40;

        for (int i = 0; i < warmupIters * ticksPerIter; i++) world.tick();

        double[] msPerTick = new double[measureIters];
        for (int it = 0; it < measureIters; it++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < ticksPerIter; k++) world.tick();
            msPerTick[it] = (System.nanoTime() - t0) / 1e6 / ticksPerIter;
        }
        Arrays.sort(msPerTick);

        // Allocation: min-over-windows isolates steady-state per-tick cost from one-off JVM noise.
        ThreadMXBean tmx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long bytesPerTick = Long.MAX_VALUE;
        for (int round = 0; round < 8; round++) {
            long a0 = tmx.getThreadAllocatedBytes(tid);
            for (int k = 0; k < 60; k++) world.tick();
            bytesPerTick = Math.min(bytesPerTick, (tmx.getThreadAllocatedBytes(tid) - a0) / 60);
        }

        return new Result(name, entities,
                msPerTick[0],
                msPerTick[measureIters / 2],
                msPerTick[(int) (measureIters * 0.95)],
                msPerTick[measureIters - 1],
                bytesPerTick, gated);
    }

    // ── Reporting & verdict ──────────────────────────────────────────────────────────────

    private static void report(List<Result> results, double speedup) {
        System.out.println("── Benchmark suite (12 measurement iterations × 40 ticks each) ──────────────────");
        System.out.printf("%-34s %9s %8s %8s %8s %8s %9s%n",
                "scenario", "entities", "min ms", "med ms", "p95 ms", "max ms", "B/tick");
        for (Result r : results) {
            System.out.printf("%-34s %,9d %8.2f %8.2f %8.2f %8.2f %9d%n",
                    r.name(), r.entities(), r.minMs(), r.medMs(), r.p95Ms(), r.maxMs(), r.bytesPerTick());
        }
        System.out.println();
        for (Result r : results) {
            if (r.throughputGated()) {
                System.out.printf("   %-34s median %.2f ms -> %.1f FPS%n", r.name(), r.medMs(), r.fps());
            }
        }
        System.out.printf("   %-34s %.2f×  (virtual threads, %d cores)%n",
                "parallel dispatch speedup", speedup, Runtime.getRuntime().availableProcessors());
        System.out.println();
    }

    private static boolean verdict(List<Result> results) {
        System.out.println("── Release gates ───────────────────────────────────────────────────────────────");
        boolean gcPass = results.stream().allMatch(Result::gcPass);
        boolean thrPass = results.stream().allMatch(Result::throughputPass);

        System.out.printf("   [%s] GATE 1  Fail-on-GC        : 0 heap bytes/tick in all %d scenarios%n",
                gcPass ? "PASS" : "FAIL", results.size());
        for (Result r : results) {
            if (!r.gcPass()) {
                System.out.printf("          ! %s allocated %d B/tick%n", r.name(), r.bytesPerTick());
            }
        }
        System.out.printf("   [%s] GATE 2  Throughput        : 1,000,000 entities @ >= 60 FPS (< %.1f ms median)%n",
                thrPass ? "PASS" : "FAIL", BUDGET_MS);
        for (Result r : results) {
            if (!r.throughputPass()) {
                System.out.printf("          ! %s median %.2f ms exceeds budget%n", r.name(), r.medMs());
            }
        }

        boolean ok = gcPass && thrPass;
        System.out.println();
        System.out.println(ok
                ? "   VERDICT: PASS — all release gates satisfied. (exit 0)"
                : "   VERDICT: FAIL — release blocked. (exit 1)");
        return ok;
    }

    private QualityGate() {
    }
}
