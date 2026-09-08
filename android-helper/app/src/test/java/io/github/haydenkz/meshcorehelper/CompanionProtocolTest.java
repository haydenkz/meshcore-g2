package io.github.haydenkz.meshcorehelper;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class CompanionProtocolTest {
    private final List<byte[]> writes = new ArrayList<>();
    private final List<CompanionProtocol.Identity> identities = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final CompanionProtocol.Handshake handshake = new CompanionProtocol.Handshake(writes::add, identities::add, errors::add);
    private byte[] selfInfo() {
        byte[] name = "Test radio".getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[58 + name.length];
        frame[0] = 5;
        System.arraycopy(name, 0, frame, 58, name.length);
        return frame;
    }
    @Test public void appStartUsesTheDocumentedHeader() {
        byte[] frame = CompanionProtocol.appStart();
        assertArrayEquals(new byte[]{1, 1, 0, 0, 0, 0, 0, 0}, java.util.Arrays.copyOf(frame, 8));
        assertEquals("MeshCore G2", new String(frame, 8, frame.length - 8, StandardCharsets.UTF_8));
    }
    @Test public void acceptsEsp32MtuAndRequiresEnoughRoomForInitialReplies() {
        assertTrue(CompanionProtocol.supportsHandshakeMtu(172));
        assertTrue(CompanionProtocol.supportsHandshakeMtu(176));
        assertTrue(CompanionProtocol.supportsHandshakeMtu(247));
        assertTrue(CompanionProtocol.supportsHandshakeMtu(517));
        assertTrue(CompanionProtocol.supportsHandshakeMtu(93));
        assertFalse(CompanionProtocol.supportsHandshakeMtu(92));
        assertFalse(CompanionProtocol.supportsHandshakeMtu(23));
    }
    @Test public void waitsForWriteAcknowledgementEvenIfNotificationArrivesFirst() {
        handshake.start();
        handshake.onFrame(selfInfo());
        assertEquals(1, writes.size());
        handshake.onWrite(true);
        assertArrayEquals(new byte[]{0x16, 3}, writes.get(1));
        handshake.onWrite(true);
        assertEquals(2, writes.size());
        handshake.onFrame(new byte[]{0x0d, 8});
        assertArrayEquals(new byte[]{0x14}, writes.get(2));
        handshake.onFrame(new byte[]{0x0c, 0x74, 0x0e});
        assertTrue(identities.isEmpty());
        handshake.onWrite(true);
        assertEquals(new CompanionProtocol.Identity("Test radio", 8, 3700), identities.get(0));
        assertTrue(errors.isEmpty());
    }
    @Test public void ignoresUnrelatedPushNotifications() {
        handshake.start();
        handshake.onWrite(true);
        handshake.onFrame(new byte[]{(byte) 0x83});
        assertEquals(1, writes.size());
        assertTrue(errors.isEmpty());
        handshake.onFrame(selfInfo());
        assertEquals(2, writes.size());
    }
    @Test public void incompleteRepliesNeverReportConnected() {
        handshake.start(); handshake.onWrite(true); handshake.onFrame(new byte[]{5, 0});
        assertEquals(1, errors.size()); assertTrue(identities.isEmpty()); assertEquals(1, writes.size());
    }
    @Test public void protocolErrorStopsInitialization() {
        handshake.start(); handshake.onFrame(new byte[]{1, 4}); handshake.onWrite(true);
        assertEquals(1, errors.size()); assertEquals(1, writes.size());
    }
    @Test public void failedWriteStopsInitialization() {
        handshake.start(); handshake.onWrite(false); handshake.onFrame(selfInfo());
        assertEquals(1, errors.size()); assertEquals(1, writes.size());
    }
    @Test public void disconnectIgnoresLateCallbacks() {
        handshake.start(); handshake.cancel(); handshake.onWrite(true); handshake.onFrame(selfInfo());
        assertEquals(1, writes.size()); assertTrue(identities.isEmpty()); assertTrue(errors.isEmpty());
    }
}
