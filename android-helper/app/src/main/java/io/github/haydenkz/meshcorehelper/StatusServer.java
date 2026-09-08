package io.github.haydenkz.meshcorehelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import fi.iki.elonen.NanoHTTPD;

/** Read-only, authenticated, loopback-only endpoint. No radio commands are exposed. */
public final class StatusServer extends NanoHTTPD {
    public static final int PORT = 8765;
    private final byte[] authorization;
    private final Supplier<String> status;
    private final AtomicInteger healthChecks = new AtomicInteger();
    private final AtomicInteger preflights = new AtomicInteger();
    private final AtomicInteger statusReads = new AtomicInteger();
    private final AtomicReference<String> lastResult = new AtomicReference<>("No HUD requests received.");
    private final AtomicLong lastHudReadAt = new AtomicLong();
    public long lastHudReadAt() { return lastHudReadAt.get(); }

    public String diagnostics() {
        return "Local connection: " + healthChecks.get() + " health checks, " + preflights.get()
                + " preflights, " + statusReads.get() + " status reads.\n" + lastResult.get();
    }

    public StatusServer(String key, Supplier<String> status) {
        super("127.0.0.1", PORT);
        authorization = ("Bearer " + key).getBytes(StandardCharsets.UTF_8);
        this.status = status;
    }
    // NanoHTTPD 2.3.1 gzips even empty 204 replies, leaving bytes that corrupt
    // the next response on a reused browser connection. Local replies are tiny.
    @Override protected boolean useGzipWhenAccepted(Response response) { return false; }

    @Override public Response serve(IHTTPSession session) {
        Response response;
        String host = session.getHeaders().getOrDefault("host", "");
        if (!host.equals("127.0.0.1:" + PORT)) {
            response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        } else if (session.getUri().equals("/health") && session.getMethod() == Method.GET) {
            healthChecks.incrementAndGet();
            response = newFixedLengthResponse(Response.Status.OK, "text/plain", "MeshCore phone helper is reachable. Return to the Even App and link it with your HUD key.");
        } else if (!session.getUri().equals("/v1/status")) {
            response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        } else if (session.getMethod() == Method.OPTIONS) {
            response = newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "");
        } else if (session.getMethod() != Method.GET) {
            response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Read only");
        } else if (!MessageDigest.isEqual(authorization,
                session.getHeaders().getOrDefault("authorization", "").getBytes(StandardCharsets.UTF_8))) {
            response = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Invalid HUD key");
        } else {
            lastHudReadAt.set(System.currentTimeMillis());
            response = newFixedLengthResponse(Response.Status.OK, "application/json", status.get());
        }
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Authorization");
        response.addHeader("Access-Control-Allow-Private-Network", "true");
        response.addHeader("Cache-Control", "no-store");
        if (host.equals("127.0.0.1:" + PORT) && session.getUri().equals("/v1/status")) {
            if (session.getMethod() == Method.OPTIONS) preflights.incrementAndGet();
            if (session.getMethod() == Method.GET) statusReads.incrementAndGet();
            lastResult.set("Last HUD request: " + session.getMethod() + " → HTTP " + response.getStatus().getRequestStatus());
        }
        return response;
    }
}
