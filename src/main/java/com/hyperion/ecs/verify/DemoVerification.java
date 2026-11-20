package com.hyperion.ecs.verify;

import com.hyperion.ecs.demo.VisualDemoApp;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Verification harness for the Phase-6 visual demo: exercises the embedded HTTP/WebSocket server,
 * the binary frame protocol, and every interactive control end-to-end against a live instance.
 *
 * <ol>
 *   <li>HTTP: {@code GET /} serves the single-file canvas dashboard.</li>
 *   <li>WebSocket: binary frames decode correctly and report <b>0 B/tick</b> heap allocation.</li>
 *   <li>Pause / Step: paused flag propagates; stepping advances exactly one frame.</li>
 *   <li>Rewind: {@code world.rewind(60)} visibly moves the frame counter backward.</li>
 *   <li>Graph link: {@code Targeting} edges + {@code Relations} traversal recolour entities,
 *       observable in the streamed palette bytes.</li>
 * </ol>
 *
 * Run (JDK 25): {@code java -cp target/classes com.hyperion.ecs.verify.DemoVerification}
 *
 * @since Phase 6 (Demo-Visualization-Agent)
 */
public final class DemoVerification {

    private static final int HEADER = 32;
    private static final int STRIDE = 5;
    private static final int ENTITIES = 50_000;

    private static final BlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(256);

    public static void main(String[] args) throws Exception {
        System.out.println("== Hyperion ECS — Phase 6 Visual Demo Verification ==\n");

        VisualDemoApp app = new VisualDemoApp(ENTITIES, 0);   // port 0 = ephemeral
        int port = app.port();
        Thread sim = new Thread(app::run, "demo-sim");
        sim.setDaemon(true);
        sim.start();
        System.out.println("   demo started on port " + port + " with " + String.format("%,d", ENTITIES) + " entities\n");

        try {
            httpServesDashboard(port);
            WebSocket ws = openStream(port);
            binaryProtocol();
            pauseAndStep(ws);
            rewind(ws);
            graphLink(ws);
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
        } finally {
            app.stop();
        }
        System.out.println("\nAll Phase-6 verification checks passed.");
    }

    // ── 1. HTTP ─────────────────────────────────────────────────────────────────────────

    private static void httpServesDashboard(int port) throws Exception {
        System.out.println("[1] HTTP: GET / serves the canvas dashboard");
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        check(r.statusCode() == 200, "HTTP 200 (got " + r.statusCode() + ")");
        check(r.body().contains("<canvas"), "response contains a <canvas> element");
        check(r.body().contains("WebSocket"), "response wires up a WebSocket client");
        System.out.printf("    served %,d bytes of HTML%n    OK%n%n", r.body().length());
    }

    // ── 2. WebSocket stream + binary protocol ───────────────────────────────────────────

    private static WebSocket openStream(int port) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/"), new WebSocket.Listener() {
                    private ByteArrayOutputStream acc = new ByteArrayOutputStream();
                    @Override public void onOpen(WebSocket w) { w.request(1); }
                    @Override public CompletionStage<?> onBinary(WebSocket w, ByteBuffer data, boolean last) {
                        byte[] chunk = new byte[data.remaining()];
                        data.get(chunk);
                        acc.writeBytes(chunk);
                        if (last) {
                            frames.offer(acc.toByteArray());
                            acc = new ByteArrayOutputStream();
                        }
                        w.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
    }

    private static void binaryProtocol() throws Exception {
        System.out.println("[2] WebSocket: binary frame protocol");
        // Skip the first few frames: the very first capture eagerly sizes the whole rewind ring
        // (allocating MemorySegment objects). Steady state is what the Zero-GC invariant covers.
        byte[] f;
        do {
            f = nextFrame();
        } while (header(f).getInt(8) < 5);
        ByteBuffer h = header(f);
        int count = h.getInt(4);
        check(h.get(0) == 1, "msgType = 1 (FRAME)");
        check(count == ENTITIES, "entityCount = " + String.format("%,d", ENTITIES) + " (got " + count + ")");
        check(f.length == HEADER + count * STRIDE,
                "frame size = 32 + count*5 = " + (HEADER + count * STRIDE) + " bytes (got " + f.length + ")");
        int alloc = h.getInt(16);
        check(alloc == 0, "tick heap allocation = 0 bytes (got " + alloc + ") — Zero-GC holds under streaming");
        System.out.printf("    frame %,d | %,d entities | %,d B/frame | tick %.2f ms | heap %.1f MB | rewind %d/%d%n",
                h.getInt(8), count, f.length, h.getInt(12) / 1000.0, h.getInt(20) / 1024.0,
                h.getInt(24), h.getInt(28));
        System.out.println("    OK\n");
    }

    // ── 3. Pause / Step ─────────────────────────────────────────────────────────────────

    private static void pauseAndStep(WebSocket ws) throws Exception {
        System.out.println("[3] Controls: pause / step");
        send(ws, "{\"cmd\":\"pause\"}");
        byte[] f = awaitFlag(true);
        check((header(f).get(1) & 1) != 0, "paused flag set in streamed frames");

        int before = header(f).getInt(8);
        for (int i = 0; i < 5; i++) nextFrame();
        check(header(nextFrame()).getInt(8) == before, "frame counter frozen while paused");

        send(ws, "{\"cmd\":\"step\"}");
        Thread.sleep(250);
        drain();
        int after = header(nextFrame()).getInt(8);
        check(after == before + 1, "step advanced exactly one frame (" + before + " -> " + after + ")");

        send(ws, "{\"cmd\":\"resume\"}");
        byte[] resumed = awaitFlag(false);
        check((header(resumed).get(1) & 1) == 0, "resume cleared the paused flag");
        System.out.println("    OK\n");
    }

    // ── 4. Time-travel rewind ───────────────────────────────────────────────────────────

    private static void rewind(WebSocket ws) throws Exception {
        System.out.println("[4] Time-travel: world.rewind(60)");
        int frame;
        do {
            frame = header(nextFrame()).getInt(8);
        } while (frame < 80);   // need >= 60 retained snapshots

        send(ws, "{\"cmd\":\"rewind\",\"frames\":60}");
        Thread.sleep(400);
        drain();
        int after = header(nextFrame()).getInt(8);
        check(after < frame, "frame counter jumped backward (" + frame + " -> " + after + ")");
        System.out.println("    OK\n");
    }

    // ── 5. Entity graph link ────────────────────────────────────────────────────────────

    private static void graphLink(WebSocket ws) throws Exception {
        System.out.println("[5] Entity graph: link via Targeting + Relations recolour");
        int before = countPalette(nextFrame(), 7);
        check(before == 0, "no highlighted entities before link (got " + before + ")");

        send(ws, "{\"cmd\":\"link\"}");
        Thread.sleep(500);
        drain();
        int after = countPalette(nextFrame(), 7);
        check(after > 0, "Targeting edges recoloured " + after + " entities (visible on canvas)");
        System.out.println("    OK\n");
    }

    private static int countPalette(byte[] frame, int palette) {
        int count = header(frame).getInt(4);
        int n = 0;
        for (int i = 0; i < count; i++) {
            if ((frame[HEADER + i * STRIDE + 4] & 0xFF) == palette) n++;
        }
        return n;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────

    private static ByteBuffer header(byte[] frame) {
        return ByteBuffer.wrap(frame, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] nextFrame() throws Exception {
        byte[] f = frames.poll(5, TimeUnit.SECONDS);
        if (f == null) throw new AssertionError("FAILED: timed out waiting for a stream frame");
        return f;
    }

    private static byte[] awaitFlag(boolean paused) throws Exception {
        for (int i = 0; i < 60; i++) {
            byte[] f = nextFrame();
            if (((header(f).get(1) & 1) != 0) == paused) return f;
        }
        throw new AssertionError("FAILED: paused flag never became " + paused);
    }

    private static void drain() {
        frames.clear();
    }

    private static void send(WebSocket ws, String json) throws Exception {
        ws.sendText(json, true).get(2, TimeUnit.SECONDS);
    }

    private static void check(boolean cond, String label) {
        if (!cond) throw new AssertionError("FAILED: " + label);
        System.out.println("    ✓ " + label);
    }

    private DemoVerification() {
    }
}
