package io.github.haydenkz.meshcorehelper;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class StatusServerTest {
    @Test public void gzipPreflightLeavesNoBodyBeforeNextKeepAliveResponse() throws Exception {
        StatusServer server = new StatusServer("test-key", () -> "{\"schema\":1}");
        server.start(1000, true);
        try (Socket socket = new Socket("127.0.0.1", StatusServer.PORT)) {
            socket.setSoTimeout(2000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String preflight = "OPTIONS /v1/status HTTP/1.1\r\nHost: 127.0.0.1:8765\r\n"
                    + "Origin: http://127.0.0.1:5173\r\nAccess-Control-Request-Method: GET\r\n"
                    + "Access-Control-Request-Headers: authorization\r\nAccept-Encoding: gzip\r\n\r\n";
            socket.getOutputStream().write(preflight.getBytes(StandardCharsets.US_ASCII));
            assertTrue(reader.readLine().startsWith("HTTP/1.1 204"));
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                assertFalse(header.toLowerCase(java.util.Locale.ROOT).startsWith("content-encoding:"));
                assertFalse(header.toLowerCase(java.util.Locale.ROOT).startsWith("transfer-encoding:"));
            }
            String get = "GET /v1/status HTTP/1.1\r\nHost: 127.0.0.1:8765\r\n"
                    + "Authorization: Bearer test-key\r\nAccept-Encoding: gzip\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(get.getBytes(StandardCharsets.US_ASCII));
            // Read the same socket: any gzip trailer/chunk after the 204 breaks this.
            assertTrue(reader.readLine().startsWith("HTTP/1.1 200"));
            while ((header = reader.readLine()) != null && !header.isEmpty()) { }
            assertEquals("{\"schema\":1}", reader.readLine());
        } finally { server.stop(); }
    }
    private record Reply(int code, String body, String cache, String allowedHeaders) {}
    private Reply request(String route, String method, String key) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:8765" + route).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setRequestMethod(method);
        if (key != null) connection.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = connection.getResponseCode();
            String body = "";
            if (code == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    body = reader.readLine();
                }
            }
            return new Reply(code, body, connection.getHeaderField("Cache-Control"), connection.getHeaderField("Access-Control-Allow-Headers"));
        } finally { connection.disconnect(); }
    }
    @Test public void onlyAuthenticatedReadOnlyLoopbackRequestsReceiveStatus() throws Exception {
        StatusServer server = new StatusServer("test-key", () -> "{\"schema\":1}");
        server.start(1000, true);
        try {
            assertEquals(401, request("/v1/status", "GET", null).code());
            assertEquals(401, request("/v1/status", "GET", "wrong-key").code());
            assertEquals(0, server.lastHudReadAt());
            Reply accepted = request("/v1/status", "GET", "test-key");
            assertEquals(200, accepted.code());
            assertTrue(server.lastHudReadAt() > 0);
            assertEquals("{\"schema\":1}", accepted.body());
            assertEquals("no-store", accepted.cache());
            Reply preflight = request("/v1/status", "OPTIONS", null);
            assertEquals(204, preflight.code());
            assertEquals("Authorization", preflight.allowedHeaders());
            assertEquals(405, request("/v1/status", "POST", "test-key").code());
            assertEquals(404, request("/v1/connect", "GET", "test-key").code());
            assertEquals(200, request("/health", "GET", null).code());
            assertTrue(server.diagnostics().contains("1 health checks, 1 preflights, 3 status reads"));
            assertTrue(server.diagnostics().contains("HTTP 405"));
            assertFalse(server.diagnostics().contains("test-key"));
            assertFalse(server.diagnostics().contains("wrong-key"));
        } finally { server.stop(); }
    }
    @Test public void messageHistoryUsesTheSameAuthenticationAndValidatesCursors() throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        StatusServer server = new StatusServer("test-key", () -> "{}", new StatusServer.Inbox() {
            public String messages(String kind, String peer, long before) { calls.add(kind + ":" + peer + ":" + before); return "{\"schema\":1,\"items\":[],\"hasMore\":false}"; }
            public String chats(long before) { calls.add("chats:" + before); return "{\"schema\":1,\"items\":[],\"hasMore\":false}"; }
            public String adverts(long before) { calls.add("adverts:" + before); return "{\"schema\":1,\"items\":[],\"hasMore\":false}"; }
        });
        server.start(1000, true);
        try {
            assertEquals(401, request("/v1/messages?kind=channel", "GET", null).code());
            assertEquals(401, request("/v1/chats", "GET", "wrong").code());
            assertEquals(401, request("/v1/adverts", "GET", null).code());
            assertTrue(calls.isEmpty());
            assertEquals(204, request("/v1/messages", "OPTIONS", null).code());
            assertEquals(405, request("/v1/messages", "POST", "test-key").code());
            assertEquals(200, request("/v1/messages?kind=direct&peer=radio%3Aalice&before=4", "GET", "test-key").code());
            assertEquals(200, request("/v1/chats?before=8", "GET", "test-key").code());
            assertEquals(java.util.List.of("direct:radio:alice:4", "chats:8"), calls);
            assertEquals(400, request("/v1/chats?before=0", "GET", "test-key").code());
            assertEquals(400, request("/v1/chats?before=bad", "GET", "test-key").code());
            assertEquals(2, calls.size());
            assertEquals(204, request("/v1/adverts", "OPTIONS", null).code());
            assertEquals(405, request("/v1/adverts", "POST", "test-key").code());
            assertEquals(400, request("/v1/adverts?before=bad", "GET", "test-key").code());
            assertEquals(200, request("/v1/adverts?before=18", "GET", "test-key").code());
            assertEquals("adverts:18", calls.get(2));
        } finally { server.stop(); }
    }
}
