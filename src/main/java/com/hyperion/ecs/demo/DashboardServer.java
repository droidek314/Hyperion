package com.hyperion.ecs.demo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Zero-dependency embedded HTTP + WebSocket server for the Phase-6 dashboard.
 *
 * <p><b>Standard library only</b> — no Spring/Javalin/Netty. A single {@link ServerSocket} on one
 * port serves both roles: a plain {@code GET /} returns the dashboard HTML, while a request carrying
 * {@code Upgrade: websocket} is switched to an RFC 6455 connection (hand-rolled SHA-1 + Base64
 * handshake). Every connection is handled on a <b>virtual thread</b>, so serialization and broadcast
 * never touch the simulation thread and {@code world.tick()} stays 0-byte allocated.
 *
 * <p>Direction of travel:
 * <ul>
 *   <li><b>server → client</b>: binary frames (the packed entity buffer, see {@code FrameCodec}).</li>
 *   <li><b>client → server</b>: text frames carrying JSON control commands, delivered to the
 *       {@link #onCommand(Consumer)} callback.</li>
 * </ul>
 *
 * @since Phase 6 (Demo-Visualization-Agent)
 */
public final class DashboardServer implements AutoCloseable {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** One live WebSocket connection; writes are serialized on {@code lock}. */
    private static final class Conn {
        final Socket socket;
        final OutputStream out;
        final Object lock = new Object();

        Conn(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
        }
    }

    private final ServerSocket serverSocket;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Conn> connections = new CopyOnWriteArrayList<>();
    private final byte[] html;
    private volatile Consumer<String> commandHandler = cmd -> { };
    private volatile boolean running;

    public DashboardServer(int port) throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        this.html = loadDashboardHtml();
    }

    /** Registers the handler invoked for each JSON control message received from a browser. */
    public DashboardServer onCommand(Consumer<String> handler) {
        this.commandHandler = handler;
        return this;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public int connectionCount() {
        return connections.size();
    }

    public DashboardServer start() {
        running = true;
        Thread accept = new Thread(this::acceptLoop, "hyperion-dashboard-accept");
        accept.setDaemon(true);
        accept.start();
        return this;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                workers.submit(() -> serve(s));
            } catch (IOException e) {
                break; // socket closed by close()
            }
        }
    }

    // ── Connection handling ─────────────────────────────────────────────────────────────

    private void serve(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine.isEmpty()) {
                socket.close();
                return;
            }
            String wsKey = null;
            boolean upgrade = false;
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    continue;
                }
                String name = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                if (name.equalsIgnoreCase("Sec-WebSocket-Key")) {
                    wsKey = value;
                } else if (name.equalsIgnoreCase("Upgrade") && value.equalsIgnoreCase("websocket")) {
                    upgrade = true;
                }
            }

            if (upgrade && wsKey != null) {
                out.write(handshake(wsKey).getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Conn conn = new Conn(socket, out);
                connections.add(conn);
                readControlLoop(conn, in);   // runs until the client disconnects
            } else {
                serveHttp(requestLine, out);
                socket.close();
            }
        } catch (IOException e) {
            closeQuietly(socket);
        }
    }

    private void serveHttp(String requestLine, OutputStream out) throws IOException {
        boolean root = requestLine.startsWith("GET / ") || requestLine.startsWith("GET /index.html");
        if (root) {
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + html.length + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(html);
        } else {
            byte[] body = "404 Not Found".getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 404 Not Found\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body);
        }
        out.flush();
    }

    /** Reads client → server frames (always masked) and dispatches text payloads as commands. */
    private void readControlLoop(Conn conn, InputStream in) {
        try {
            while (running && !conn.socket.isClosed()) {
                int b0 = in.read();
                if (b0 < 0) break;
                int opcode = b0 & 0x0F;
                int b1 = in.read();
                if (b1 < 0) break;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((long) in.read() << 8) | in.read();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }
                byte[] mask = new byte[4];
                if (masked) {
                    readFully(in, mask, 4);
                }
                byte[] payload = new byte[(int) len];
                readFully(in, payload, (int) len);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }
                if (opcode == 0x8) {           // close
                    break;
                } else if (opcode == 0x1) {    // text -> control command
                    commandHandler.accept(new String(payload, StandardCharsets.UTF_8));
                }
                // ping/pong/binary from client are ignored by this demo
            }
        } catch (IOException e) {
            // client vanished
        } finally {
            connections.remove(conn);
            closeQuietly(conn.socket);
        }
    }

    // ── Broadcast ───────────────────────────────────────────────────────────────────────

    /**
     * Sends {@code len} bytes of {@code buffer} as a binary frame to every connected browser.
     * Invoked from the broadcaster virtual thread — never from the simulation thread.
     */
    public void broadcastBinary(byte[] buffer, int len) {
        for (Conn c : connections) {
            try {
                synchronized (c.lock) {
                    writeFrameHeader(c.out, 0x82, len);
                    c.out.write(buffer, 0, len);
                    c.out.flush();
                }
            } catch (IOException e) {
                connections.remove(c);
                closeQuietly(c.socket);
            }
        }
    }

    private static void writeFrameHeader(OutputStream out, int opcode, int len) throws IOException {
        out.write(opcode);
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
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────

    private static String handshake(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            return "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + Base64.getEncoder().encodeToString(hash) + "\r\n\r\n";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void readFully(InputStream in, byte[] dst, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(dst, read, len - read);
            if (n < 0) throw new IOException("stream closed");
            read += n;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c, prev = -1;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                if (prev == '\r') sb.setLength(sb.length() - 1);
                break;
            }
            sb.append((char) c);
            prev = c;
        }
        return sb.toString();
    }

    /** Loads the single-file dashboard from the classpath, falling back to the source tree. */
    private static byte[] loadDashboardHtml() throws IOException {
        try (InputStream in = DashboardServer.class.getResourceAsStream("/dashboard.html")) {
            if (in != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                in.transferTo(bos);
                return bos.toByteArray();
            }
        }
        Path fallback = Path.of("src", "main", "resources", "dashboard.html");
        if (Files.exists(fallback)) {
            return Files.readAllBytes(fallback);
        }
        throw new IOException("dashboard.html not found on classpath or at " + fallback.toAbsolutePath());
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    @Override
    public void close() {
        running = false;
        for (Conn c : connections) {
            closeQuietly(c.socket);
        }
        connections.clear();
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // best effort
        }
        workers.close();
    }
}
