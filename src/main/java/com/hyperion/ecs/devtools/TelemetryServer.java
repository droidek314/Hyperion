package com.hyperion.ecs.devtools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal WebSocket telemetry server — <b>standard library only</b> (no ASM/Netty/etc.). Performs
 * the RFC 6455 upgrade handshake by hand ({@code MessageDigest}/{@code Base64}) and pushes
 * {@link Telemetry#toJson()} as unmasked text frames on an interval. Connections are served on
 * virtual threads.
 *
 * <p>This runs entirely off the simulation thread and only reads {@code volatile} telemetry
 * counters, so it adds no measurable overhead to {@code tick()} (the &lt; 2% guardrail concerns the
 * per-tick hook, which is {@link Telemetry#onTick}). Verified end-to-end against the JDK's own
 * {@code java.net.http.WebSocket} client.
 *
 * @since Phase 4 (DevTools-Agent)
 */
public final class TelemetryServer implements AutoCloseable {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Telemetry telemetry;
    private final long intervalMillis;
    private final ServerSocket serverSocket;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private Thread acceptThread;

    /**
     * @param telemetry      the telemetry sink to stream
     * @param port           TCP port to bind (0 = ephemeral; read back via {@link #port()})
     * @param intervalMillis push interval per connection
     */
    public TelemetryServer(Telemetry telemetry, int port, long intervalMillis) throws IOException {
        this.telemetry = telemetry;
        this.intervalMillis = intervalMillis;
        this.serverSocket = new ServerSocket();
        this.serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
    }

    /** @return the actual bound port. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Starts the accept loop on a background thread. */
    public TelemetryServer start() {
        running = true;
        acceptThread = new Thread(this::acceptLoop, "hyperion-telemetry-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return this;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                connections.submit(() -> serve(s));
            } catch (IOException e) {
                break; // serverSocket closed by stop()
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // ── RFC 6455 handshake ──
            readLine(in); // request line: GET / HTTP/1.1
            String key = null;
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0 && line.substring(0, idx).trim().equalsIgnoreCase("Sec-WebSocket-Key")) {
                    key = line.substring(idx + 1).trim();
                }
            }
            if (key == null) {
                return;
            }
            out.write(handshake(key).getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // ── push loop ──
            while (running && !socket.isClosed()) {
                sendText(out, telemetry.toJson());
                Thread.sleep(intervalMillis);
            }
        } catch (IOException | InterruptedException e) {
            // client disconnected or server stopping — end this connection quietly
        }
    }

    private static String handshake(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            String accept = Base64.getEncoder().encodeToString(hash);
            return "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes one unmasked text frame (server → client). */
    private static void sendText(OutputStream out, String text) throws IOException {
        byte[] p = text.getBytes(StandardCharsets.UTF_8);
        out.write(0x81); // FIN + text opcode
        int len = p.length;
        if (len < 126) {
            out.write(len);
        } else if (len < 65536) {
            out.write(126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) (((long) len >>> (8 * i)) & 0xFF));
            }
        }
        out.write(p);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c, prev = -1;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                if (prev == '\r') {
                    sb.setLength(sb.length() - 1);
                }
                break;
            }
            sb.append((char) c);
            prev = c;
        }
        return sb.toString();
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // best-effort
        }
        connections.close();
    }
}
