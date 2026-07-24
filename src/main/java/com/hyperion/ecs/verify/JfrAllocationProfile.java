package com.hyperion.ecs.verify;

import com.hyperion.ecs.Component;
import com.hyperion.ecs.Hyperion;
import com.hyperion.ecs.WorldConfig;
import com.hyperion.ecs.core.HyperionWorld;
import com.hyperion.ecs.dsl.Systems;

import com.sun.management.ThreadMXBean;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase-5 JFR allocation profiler (QA-Bench-Agent). Produces the recording that backs the
 * <b>Fail-on-GC</b> gate evidence.
 *
 * <p>The recording is started <em>programmatically</em> via {@link jdk.jfr.Recording} so it covers
 * <b>only the steady-state tick loop</b> — entity spawning is structural and legitimately allocates,
 * so a whole-process recording would drown the signal. Anything attributed to
 * {@code com.hyperion.ecs} inside the recorded window is a genuine tick-path allocation and a gate
 * failure.
 *
 * <p>Writes {@code qa/hyperion-tick-alloc.jfr}, analysed with the JDK's own {@code jfr} CLI.
 *
 * Run (JDK 25): {@code java -cp target/classes com.hyperion.ecs.verify.JfrAllocationProfile}
 *
 * @since Phase 5 (QA-Bench-Agent)
 */
public final class JfrAllocationProfile {

    public static final class Position implements Component {
        public float x, y;
        public Position(float x, float y) { this.x = x; this.y = y; }
    }
    public static final class Velocity implements Component {
        public float dx, dy;
        public Velocity(float dx, float dy) { this.dx = dx; this.dy = dy; }
    }

    public static void main(String[] args) throws Exception {
        final int N = 1_000_000;
        final int recordedTicks = 400;
        Path out = Path.of("qa", "hyperion-tick-alloc.jfr");
        Files.createDirectories(out.getParent());

        System.out.println("== Hyperion ECS — Phase 5 JFR Allocation Profile ==");
        System.out.println("   JDK " + System.getProperty("java.version"));

        try (HyperionWorld world = (HyperionWorld) Hyperion.createWorld(new WorldConfig(N, 256, 0))) {
            for (int i = 0; i < N; i++) {
                world.spawn().add(new Position(0, 0)).add(new Velocity(1, 2)).id();
            }
            Systems.of(world).with(Position.class, Velocity.class)
                    .each((long id, Position p, Velocity v) -> { p.x += v.dx; p.y += v.dy; });

            // Warm up so JIT compilation/deopt does not occur inside the recorded window.
            for (int i = 0; i < 300; i++) world.tick();

            ThreadMXBean tmx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            long tid = Thread.currentThread().threadId();

            Configuration profile = Configuration.getConfiguration("profile");
            try (Recording recording = new Recording(profile)) {
                recording.setName("hyperion-tick");
                recording.start();

                long a0 = tmx.getThreadAllocatedBytes(tid);
                long t0 = System.nanoTime();
                for (int i = 0; i < recordedTicks; i++) world.tick();
                long elapsed = System.nanoTime() - t0;
                long allocated = tmx.getThreadAllocatedBytes(tid) - a0;

                recording.stop();
                recording.dump(out);

                System.out.printf("   recorded %d ticks of %,d entities in %.2f s (%.2f ms/tick)%n",
                        recordedTicks, N, elapsed / 1e9, elapsed / 1e6 / recordedTicks);
                System.out.printf("   ThreadMXBean allocated during window : %,d bytes total (%,d B/tick)%n",
                        allocated, allocated / recordedTicks);
                System.out.println("   recording written to: " + out.toAbsolutePath());
            }
        }
    }

    private JfrAllocationProfile() {
    }
}
