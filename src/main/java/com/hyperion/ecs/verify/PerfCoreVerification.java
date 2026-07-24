package com.hyperion.ecs.verify;

import com.hyperion.ecs.Component;
import com.hyperion.ecs.Hyperion;
import com.hyperion.ecs.WorldConfig;
import com.hyperion.ecs.core.HyperionWorld;
import com.hyperion.ecs.query.Query;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;

/**
 * Standalone verification harness for the Phase-2 off-heap engine. Not a JMH suite (that is the
 * Phase-5 QA-Bench-Agent deliverable) — a lightweight, dependency-free check that the core
 * invariants hold:
 *
 * <ol>
 *   <li><b>Functional:</b> spawn / despawn (swap-remove) / generational handle validity.</li>
 *   <li><b>Zero-GC:</b> per-tick heap allocation is 0 bytes on the primitive system path,
 *       measured with {@code ThreadMXBean.getThreadAllocatedBytes}.</li>
 *   <li><b>Throughput:</b> an indicative entities/second figure for a 1,000,000-entity world.</li>
 *   <li><b>Compatibility:</b> the Phase-1 ergonomic {@code each(...)} record path executes.</li>
 * </ol>
 *
 * Run (JDK 25, no preview flag): {@code java -cp target/classes com.hyperion.ecs.verify.PerfCoreVerification}
 *
 * @since Phase 2 (Perf-Core-Agent)
 */
public final class PerfCoreVerification {

    public record Position(float x, float y) implements Component {}
    public record Velocity(float dx, float dy) implements Component {}
    public record Frozen() implements Component {}

    private static float sampleX;
    private static float sampleY;
    private static long eachInvocations;

    public static void main(String[] args) {
        System.out.println("== Hyperion ECS — Phase 2 Perf-Core Verification ==\n");
        functional();
        zeroGcAndThroughput();
        compatibilityEachPath();
        System.out.println("\nAll Phase-2 verification checks passed.");
    }

    // ── 1. Functional: lifecycle, swap-remove, generational handles ──────────────────────

    private static void functional() {
        System.out.println("[1] Functional: entity lifecycle & generational handles");
        try (HyperionWorld world = (HyperionWorld) Hyperion.createWorld(
                new WorldConfig(64, 256, 0))) {

            int POS = world.componentId(Position.class);

            long a = world.spawn().add(new Position(1, 1)).add(new Velocity(0, 0)).id();
            long b = world.spawn().add(new Position(2, 2)).add(new Velocity(0, 0)).id();
            long c = world.spawn().add(new Position(3, 3)).add(new Velocity(0, 0)).id();
            check(world.entityCount() == 3, "entityCount == 3");
            check(world.isAlive(a) && world.isAlive(b) && world.isAlive(c), "all alive");

            // Swap-remove: despawning 'b' relocates 'c' into b's row; both a and c stay valid.
            world.despawn(b);
            check(!world.isAlive(b), "b dead after despawn");
            check(world.isAlive(a) && world.isAlive(c), "a and c survive swap-remove");
            check(world.entityCount() == 2, "entityCount == 2 after despawn");

            // Read c's position back via a one-shot primitive reader to confirm data integrity.
            Query reader = world.createSystem(chunk -> {
                for (int r = 0; r < chunk.count(); r++) {
                    if (chunk.entity(r) == c) {
                        sampleX = chunk.getFloat(POS, 0, r);
                        sampleY = chunk.getFloat(POS, 1, r);
                    }
                }
            }, Position.class);
            reader.disable();  // keep it out of tick(); we run it explicitly
            reader.run();
            check(sampleX == 3 && sampleY == 3, "c's Position intact after relocation (" + sampleX + "," + sampleY + ")");

            // Reused slot: new spawn recycles b's slot with a bumped generation → old handle stays stale.
            long d = world.spawn().add(new Position(4, 4)).add(new Velocity(0, 0)).id();
            check(world.isAlive(d), "recycled entity d alive");
            check(!world.isAlive(b), "stale handle b still dead after slot reuse");
        }
        System.out.println("    OK\n");
    }

    // ── 2. Zero-GC hot path + throughput ─────────────────────────────────────────────────

    private static void zeroGcAndThroughput() {
        final int N = 1_000_000;
        System.out.println("[2] Zero-GC & throughput: " + N + " entities, movement system");

        try (HyperionWorld world = (HyperionWorld) Hyperion.createWorld(
                new WorldConfig(N, 256, 0))) {

            final int POS = world.componentId(Position.class);
            final int VEL = world.componentId(Velocity.class);

            for (int i = 0; i < N; i++) {
                world.spawn()
                        .add(new Position(0f, 0f))
                        .add(new Velocity(1f, 2f))
                        .id();
            }
            check(world.entityCount() == N, "spawned " + N + " entities");

            // Primitive, zero-allocation movement system — the path Phase 3 compiles down to.
            world.createSystem(chunk -> {
                int n = chunk.count();
                for (int r = 0; r < n; r++) {
                    float x = chunk.getFloat(POS, 0, r);
                    float y = chunk.getFloat(POS, 1, r);
                    float dx = chunk.getFloat(VEL, 0, r);
                    float dy = chunk.getFloat(VEL, 1, r);
                    chunk.setFloat(POS, 0, r, x + dx);
                    chunk.setFloat(POS, 1, r, y + dy);
                }
            }, Position.class, Velocity.class);

            final int warmup = 100;
            final int measure = 300;
            for (int i = 0; i < warmup; i++) {
                world.tick();
            }

            ThreadMXBean tmx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            long tid = Thread.currentThread().threadId();

            long allocBefore = tmx.getThreadAllocatedBytes(tid);
            long nanoBefore = System.nanoTime();
            for (int i = 0; i < measure; i++) {
                world.tick();
            }
            long nanoAfter = System.nanoTime();
            long allocAfter = tmx.getThreadAllocatedBytes(tid);

            long totalTicks = warmup + measure;
            long bytesPerTick = (allocAfter - allocBefore) / measure;
            double msPerTick = (nanoAfter - nanoBefore) / 1_000_000.0 / measure;
            double entPerSec = N / (msPerTick / 1000.0);

            System.out.printf("    heap alloc/tick : %d bytes  (measured over %d ticks)%n", bytesPerTick, measure);
            System.out.printf("    time/tick       : %.3f ms  -> %s (budget 16.6 ms for 60 FPS)%n",
                    msPerTick, msPerTick < 16.6 ? "WITHIN budget" : "over budget");
            System.out.printf("    throughput      : %.1f million entities/sec%n", entPerSec / 1e6);

            check(bytesPerTick == 0, "ZERO heap allocation per tick (got " + bytesPerTick + " bytes)");

            // Correctness: entity spawned first sits at row 0; after totalTicks it moved by velocity.
            world.createSystem(chunk -> {
                if (chunk.count() > 0) {
                    sampleX = chunk.getFloat(POS, 0, 0);
                    sampleY = chunk.getFloat(POS, 1, 0);
                }
            }, Position.class, Velocity.class).run();
            check(sampleX == totalTicks * 1f && sampleY == totalTicks * 2f,
                    "row-0 position advanced by velocity over " + totalTicks + " ticks ("
                            + sampleX + "," + sampleY + ")");
        }
        System.out.println("    OK\n");
    }

    // ── 3. Phase-1 ergonomic each(...) compatibility path ────────────────────────────────

    private static void compatibilityEachPath() {
        System.out.println("[3] Compatibility: Phase-1 ergonomic each(...) record path + without()");
        try (HyperionWorld world = (HyperionWorld) Hyperion.createWorld(
                new WorldConfig(64, 256, 0))) {

            world.spawn().add(new Position(10, 10)).add(new Velocity(1, 1)).id();
            world.spawn().add(new Position(20, 20)).add(new Velocity(1, 1)).id();
            world.spawn().add(new Position(30, 30)).add(new Velocity(1, 1)).add(new Frozen()).id();

            eachInvocations = 0;
            // Exclude Frozen: only the two non-frozen entities should be visited.
            world.createSystem()
                    .with(Position.class, Velocity.class)
                    .without(Frozen.class)
                    .each((long id, Position pos, Velocity vel) -> eachInvocations++);

            world.tick();
            check(eachInvocations == 2, "each() visited 2 non-Frozen entities (got " + eachInvocations + ")");
        }
        System.out.println("    OK\n");
    }

    private static void check(boolean cond, String label) {
        if (!cond) {
            throw new AssertionError("FAILED: " + label);
        }
        System.out.println("    ✓ " + label);
    }
}
