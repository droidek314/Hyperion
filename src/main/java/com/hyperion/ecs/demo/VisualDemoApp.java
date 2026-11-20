package com.hyperion.ecs.demo;

import com.hyperion.ecs.Component;
import com.hyperion.ecs.Hyperion;
import com.hyperion.ecs.Relation;
import com.hyperion.ecs.WorldConfig;
import com.hyperion.ecs.core.HyperionWorld;
import com.hyperion.ecs.dsl.Relations;
import com.hyperion.ecs.dsl.Systems;
import com.hyperion.ecs.query.Query;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Phase-6 visual demo: a 50,000+ entity bouncing-particle simulation streamed live to an HTML5
 * canvas dashboard at {@code http://localhost:8080}.
 *
 * <p><b>Threading model (Zero-Impact guardrail).</b> The simulation thread only ever runs
 * {@code world.tick()} plus a strict ping-pong handoff. Frame <em>serialization</em> and WebSocket
 * <em>broadcast</em> both execute on a virtual thread from
 * {@code Executors.newVirtualThreadPerTaskExecutor()}:
 *
 * <pre>
 *   sim thread : tick() -> frameRequest.release() -> frameReady.acquire() -> pace to 60 FPS
 *   broadcaster: frameRequest.acquire() -> encode -> frameReady.release() -> send (overlaps next tick)
 * </pre>
 *
 * Encode and send are sequential on the one broadcaster thread, so the shared frame buffer needs no
 * double-buffering, and the simulation never observes serialization allocation. Per-tick heap
 * allocation is measured live and streamed to the HUD as proof the Zero-GC invariant still holds.
 *
 * <p><b>Component shapes.</b> The spec sketches {@code Position}/{@code Velocity}/{@code RenderColor}
 * as records; per <b>ADR-0007</b> immutable records are read-only inside systems and cannot express
 * the bounce update ({@code p.x += v.dx; v.dx = -v.dx}). They are therefore mutable class components
 * — the project's established zero-cost read/write shape, driven by the {@code java.lang.classfile}
 * codegen marshaler.
 *
 * <p>Run: {@code java -cp target/classes com.hyperion.ecs.demo.VisualDemoApp [entities] [port]}
 *
 * @since Phase 6 (Demo-Visualization-Agent)
 */
public final class VisualDemoApp {

    // ── Components (mutable class shape, ADR-0007) ──────────────────────────────────────

    public static final class Position implements Component {
        public float x, y;
        public Position(float x, float y) { this.x = x; this.y = y; }
    }

    public static final class Velocity implements Component {
        public float dx, dy;
        public Velocity(float dx, float dy) { this.dx = dx; this.dy = dy; }
    }

    /** Palette index 0..7, resolved to RGB client-side. */
    public static final class RenderColor implements Component {
        public int color;
        public RenderColor(int color) { this.color = color; }
    }

    /** Directed relationship type used by the graph-link demo control. */
    public record Targeting() implements Relation { }

    // ── Simulation constants ────────────────────────────────────────────────────────────

    private static final float WORLD_W = 1600f;
    private static final float WORLD_H = 900f;
    private static final int REWIND_DEPTH = 150;     // supports rewind of 60-120 frames
    private static final int HEADER_BYTES = 32;
    private static final int BYTES_PER_ENTITY = 5;   // uint16 x, uint16 y, uint8 palette
    private static final int PALETTE_SIZE = 8;
    private static final int COLOR_HUNTER = 6;
    private static final int COLOR_TARGET = 7;
    private static final int LINK_TARGETS = 48;
    private static final long FRAME_NANOS = 16_666_667L; // 60 FPS

    private final int entityCount;
    private final HyperionWorld world;
    private final DashboardServer server;
    private final long[] handles;
    private final byte[] frameBuffer;
    private final int[] cursor = new int[1];
    private final Random random = new Random(20260724L);

    private final Semaphore frameRequest = new Semaphore(0);
    private final Semaphore frameReady = new Semaphore(0);
    private final ExecutorService broadcaster = Executors.newVirtualThreadPerTaskExecutor();

    private Query encoder;
    private final CountDownLatch terminated = new CountDownLatch(1);
    private volatile Thread simThread;
    private volatile boolean started;
    private boolean tornDown;
    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile boolean stepRequested;
    private volatile int pendingRewind;
    private volatile boolean linkRequested;

    private volatile long frameNumber;
    private volatile long tickNanos;
    private volatile long tickAllocBytes;

    public static void main(String[] args) throws Exception {
        int entities = args.length > 0 ? Integer.parseInt(args[0]) : 50_000;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        new VisualDemoApp(entities, port).run();
    }

    public VisualDemoApp(int entityCount, int port) throws Exception {
        this.entityCount = entityCount;
        this.world = (HyperionWorld) Hyperion.createWorld(
                new WorldConfig(entityCount, 256, REWIND_DEPTH));
        this.handles = new long[entityCount];
        this.frameBuffer = new byte[HEADER_BYTES + entityCount * BYTES_PER_ENTITY];
        this.server = new DashboardServer(port).onCommand(this::handleCommand);
        buildWorld();
    }

    // ── World construction ──────────────────────────────────────────────────────────────

    private void buildWorld() {
        for (int i = 0; i < entityCount; i++) {
            float speed = 0.6f + random.nextFloat() * 2.6f;
            double angle = random.nextDouble() * Math.PI * 2;
            handles[i] = world.spawn()
                    .add(new Position(random.nextFloat() * WORLD_W, random.nextFloat() * WORLD_H))
                    .add(new Velocity((float) Math.cos(angle) * speed, (float) Math.sin(angle) * speed))
                    .add(new RenderColor(random.nextInt(PALETTE_SIZE - 2)))
                    .id();
        }

        // Bouncing-particle system — mutable views, compiled to a primitive ChunkFunction.
        Systems.of(world)
                .with(Position.class, Velocity.class)
                .each((long id, Position p, Velocity v) -> {
                    p.x += v.dx;
                    p.y += v.dy;
                    if (p.x < 0f)            { p.x = 0f;      v.dx = -v.dx; }
                    else if (p.x > WORLD_W)  { p.x = WORLD_W; v.dx = -v.dx; }
                    if (p.y < 0f)            { p.y = 0f;      v.dy = -v.dy; }
                    else if (p.y > WORLD_H)  { p.y = WORLD_H; v.dy = -v.dy; }
                });

        // Frame encoder — a primitive, zero-allocation system run explicitly by the broadcaster.
        final int POS = world.componentId(Position.class);
        final int COL = world.componentId(RenderColor.class);
        encoder = world.createSystem(chunk -> {
            int n = chunk.count();
            int idx = cursor[0];
            byte[] buf = frameBuffer;
            for (int r = 0; r < n; r++) {
                int ux = (int) (chunk.getFloat(POS, 0, r) * (65535f / WORLD_W));
                int uy = (int) (chunk.getFloat(POS, 1, r) * (65535f / WORLD_H));
                if (ux < 0) ux = 0; else if (ux > 65535) ux = 65535;
                if (uy < 0) uy = 0; else if (uy > 65535) uy = 65535;
                int off = HEADER_BYTES + idx * BYTES_PER_ENTITY;
                buf[off]     = (byte) ux;
                buf[off + 1] = (byte) (ux >>> 8);
                buf[off + 2] = (byte) uy;
                buf[off + 3] = (byte) (uy >>> 8);
                buf[off + 4] = (byte) chunk.getInt(COL, 0, r);
                idx++;
            }
            cursor[0] = idx;
        }, Position.class, RenderColor.class);
        encoder.disable();   // driven manually, never from tick()
    }

    // ── Main loop ───────────────────────────────────────────────────────────────────────

    public void run() {
        simThread = Thread.currentThread();
        started = true;
        server.start();
        broadcaster.submit(this::broadcastLoop);

        System.out.println("== Hyperion ECS — Phase 6 Visual Demo ==");
        System.out.printf("   entities   : %,d%n", entityCount);
        System.out.printf("   rewind ring: %d frames%n", REWIND_DEPTH);
        System.out.printf("   dashboard  : http://localhost:%d%n", server.port());
        System.out.println("   controls   : [space] pause  [s] step  [r] rewind 60  [R] rewind 120  [l] link");
        System.out.println("   Ctrl-C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        ThreadMXBean tmx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        long next = System.nanoTime();

        try {
            while (running) {
                applyPendingActions();

                if (!paused || stepRequested) {
                    stepRequested = false;
                    long a0 = tmx.getThreadAllocatedBytes(tid);
                    long t0 = System.nanoTime();
                    world.tick();
                    tickNanos = System.nanoTime() - t0;
                    tickAllocBytes = tmx.getThreadAllocatedBytes(tid) - a0;
                    frameNumber++;
                }

                // Hand the frame to the broadcaster virtual thread and wait only for encoding.
                frameRequest.release();
                frameReady.acquire();

                next += FRAME_NANOS;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } else {
                    next = System.nanoTime();   // fell behind; resync
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
            teardown();
        }
    }

    /** Structural / control actions run on the simulation thread, never concurrently with tick(). */
    private void applyPendingActions() {
        int rewind = pendingRewind;
        if (rewind > 0) {
            pendingRewind = 0;
            try {
                world.rewind(rewind);
                // rewind(1) is the newest captured frame, so rewind(n) steps back n - 1 ticks.
                frameNumber = Math.max(0, frameNumber - (rewind - 1));
                System.out.println("   [rewind] " + rewind + " frames");
            } catch (RuntimeException e) {
                System.out.println("   [rewind] unavailable: " + e.getMessage());
            }
        }
        if (linkRequested) {
            linkRequested = false;
            long hunter = handles[random.nextInt(handles.length)];
            for (int i = 0; i < LINK_TARGETS; i++) {
                world.link(hunter, Targeting.class, handles[random.nextInt(handles.length)]);
            }
            world.setComponentInt(hunter, RenderColor.class, 0, COLOR_HUNTER);
            int[] n = {0};
            Relations.of(world, hunter).targets(Targeting.class, target -> {
                world.setComponentInt(target, RenderColor.class, 0, COLOR_TARGET);
                n[0]++;
            });
            System.out.println("   [link] hunter -> " + n[0] + " Targeting edges (highlighted)");
        }
    }

    // ── Broadcaster (virtual thread): serialize + send, off the simulation thread ───────

    private void broadcastLoop() {
        while (running) {
            try {
                frameRequest.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                frameReady.release();            // never leave the simulation thread waiting
                return;
            }
            cursor[0] = 0;
            encoder.run();                       // fills the payload (zero-allocation)
            int count = cursor[0];
            writeHeader(count);
            int len = HEADER_BYTES + count * BYTES_PER_ENTITY;
            frameReady.release();                // simulation may proceed immediately
            server.broadcastBinary(frameBuffer, len);   // network I/O overlaps the next tick
        }
    }

    private void writeHeader(int count) {
        Runtime rt = Runtime.getRuntime();
        long heapUsedKB = (rt.totalMemory() - rt.freeMemory()) / 1024L;
        byte[] b = frameBuffer;
        b[0] = 1;                                    // msgType = FRAME
        b[1] = (byte) (paused ? 1 : 0);              // flags
        putU16(b, 2, PALETTE_SIZE);
        putU32(b, 4, count);
        putU32(b, 8, (int) frameNumber);
        putU32(b, 12, (int) (tickNanos / 1000L));    // tick duration in microseconds
        putU32(b, 16, (int) Math.min(tickAllocBytes, 0xFFFFFFFFL));
        putU32(b, 20, (int) Math.min(heapUsedKB, 0xFFFFFFFFL));
        putU32(b, 24, world.rewindBuffer() != null ? world.rewindBuffer().retainedFrames() : 0);
        putU32(b, 28, world.rewindBuffer() != null ? world.rewindBuffer().capacityFrames() : 0);
    }

    private static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }

    private static void putU32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    // ── Control commands from the browser ───────────────────────────────────────────────

    private void handleCommand(String json) {
        String cmd = extract(json, "cmd");
        switch (cmd) {
            case "pause"  -> paused = true;
            case "resume" -> paused = false;
            case "toggle" -> paused = !paused;
            case "step"   -> { paused = true; stepRequested = true; }
            case "link"   -> linkRequested = true;
            case "rewind" -> {
                String frames = extract(json, "frames");
                int n = frames.isEmpty() ? 60 : Integer.parseInt(frames);
                pendingRewind = Math.max(1, Math.min(n, REWIND_DEPTH));
            }
            default -> System.out.println("   [warn] unknown command: " + json);
        }
    }

    /** Minimal field extraction — the control vocabulary is fixed, so no JSON parser is needed. */
    private static String extract(String json, String key) {
        int k = json.indexOf('"' + key + '"');
        if (k < 0) return "";
        int colon = json.indexOf(':', k);
        if (colon < 0) return "";
        int i = colon + 1;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '"')) i++;
        int start = i;
        while (i < json.length() && "\",} ".indexOf(json.charAt(i)) < 0) i++;
        return json.substring(start, i);
    }

    /** @return the bound dashboard port (useful when constructed with port 0). */
    public int port() {
        return server.port();
    }

    /**
     * Requests shutdown and waits for it. Only the simulation thread tears the world down, after it
     * has left its loop, so the off-heap arena is never closed underneath a running {@code tick()}.
     * Safe to call from any thread (including the JVM shutdown hook); idempotent.
     */
    public void stop() {
        running = false;
        if (!started) {
            teardown();                     // run() never started: nothing else will clean up
            return;
        }
        if (Thread.currentThread() == simThread) {
            return;                         // run()'s finally block performs the teardown
        }
        try {
            terminated.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Ordered teardown, executed once on the simulation thread after the loop exits. */
    private synchronized void teardown() {
        if (tornDown) {
            return;
        }
        tornDown = true;
        server.close();                     // no new viewers; open sockets are dropped
        frameRequest.release();             // wake the broadcaster so it can observe !running
        broadcaster.close();                // waits until the broadcaster has exited
        world.close();                      // safe: neither thread touches the world any more
        terminated.countDown();
        System.out.println("   demo stopped.");
    }
}
