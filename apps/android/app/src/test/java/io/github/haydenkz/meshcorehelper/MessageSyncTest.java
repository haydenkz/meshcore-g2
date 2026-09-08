package io.github.haydenkz.meshcorehelper;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MessageSyncTest {
    static byte[] message(boolean channel, boolean v3, String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        int start = v3 ? 4 : 1;
        int header = start + (channel ? 1 : 6) + 6;
        byte[] frame = new byte[header + body.length];
        frame[0] = (byte) (channel ? v3 ? 17 : 8 : v3 ? 16 : 7);
        if (v3) frame[1] = -12;
        int at = start;
        if (channel) frame[at++] = 2;
        else for (int i = 0; i < 6; i++) frame[at++] = (byte) (i + 1);
        frame[at++] = (byte) 255;
        frame[at++] = 0;
        frame[at] = (byte) 255; frame[at + 1] = (byte) 255; frame[at + 2] = (byte) 255; frame[at + 3] = (byte) 255;
        System.arraycopy(body, 0, frame, header, body.length);
        return frame;
    }
    @Test public void parsesV2AndV3MessagesWithoutMixingChannelsAndDirectChats() {
        for (boolean v3 : new boolean[]{false, true}) {
            ReceivedMessage channel = ReceivedMessage.parse(message(true, v3, "Alice: Hello mesh 🌲"));
            assertEquals("channel", channel.kind()); assertEquals("2", channel.peer());
            assertEquals("Alice", channel.sender()); assertEquals("Hello mesh 🌲", channel.text());
            assertEquals(4294967295000L, channel.sentAt());
            ReceivedMessage direct = ReceivedMessage.parse(message(false, v3, "Private hello"));
            assertEquals("direct", direct.kind()); assertEquals("010203040506", direct.peer());
            assertEquals("Private hello", direct.text());
        }
    }
    @Test public void rejectsTruncatedRepliesAndIgnoresNonChatPayloads() {
        for (byte[] invalid : new byte[][]{new byte[0], {17, 0}, {16, 0, 0, 0}}) {
            try { ReceivedMessage.parse(invalid); fail("Expected incomplete frame failure"); } catch (IllegalArgumentException expected) { }
        }
        byte[] cli = message(false, true, "commands"); cli[11] = 1;
        assertNull(ReceivedMessage.parse(cli));
        assertNull(ReceivedMessage.parse(new byte[]{27, 1, 2}));
        byte[] utf8 = message(true, true, "é");
        try { ReceivedMessage.parse(java.util.Arrays.copyOf(utf8, utf8.length - 1)); fail("Expected invalid UTF-8 failure"); } catch (IllegalArgumentException expected) { }
    }
    private final List<byte[]> writes = new ArrayList<>();
    private final List<ReceivedMessage> messages = new ArrayList<>();
    private final List<String> channels = new ArrayList<>();
    private final List<String> contacts = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private int idle;
    private final MessageSync sync = new MessageSync(writes::add, new MessageSync.Listener() {
        public void message(ReceivedMessage message) { messages.add(message); }
        public void channel(int index, String name) { channels.add(index + ":" + name); }
        public void contact(String prefix, String name) { contacts.add(prefix + ":" + name); }
        public void idle() { idle++; }
    }, errors::add, 1);
    private void initialize() {
        sync.start(); assertArrayEquals(new byte[]{4}, writes.get(0));
        sync.onFrame(new byte[]{2, 0, 0, 0, 0});
        sync.onFrame(new byte[]{4}); assertEquals(1, writes.size());
        sync.onWrite(true); assertArrayEquals(new byte[]{31, 0}, writes.get(1));
        byte[] channel = new byte[50]; channel[0] = 18;
        System.arraycopy("Public".getBytes(StandardCharsets.UTF_8), 0, channel, 2, 6);
        java.util.Arrays.fill(channel, 34, 50, (byte) 255); // Channel secret is never parsed or exposed.
        sync.onWrite(true); sync.onFrame(channel);
        assertEquals(List.of("0:Public"), channels);
        assertArrayEquals(new byte[]{10}, writes.get(2));
    }
    @Test public void drainsBothQueuesSeriallyAndDeduplicatesConcurrentPushRequests() {
        initialize();
        sync.onFrame(message(true, true, "Alice: Channel hello"));
        sync.onFrame(new byte[]{(byte) 0x83}); sync.onFrame(new byte[]{(byte) 0x83});
        assertEquals(3, writes.size()); assertEquals(1, messages.size());
        sync.onWrite(true); assertEquals(4, writes.size());
        sync.onWrite(true); sync.onFrame(message(false, true, "Direct hello"));
        assertEquals(2, messages.size()); assertEquals(5, writes.size());
        sync.onFrame(new byte[]{10}); assertEquals(0, idle);
        sync.onWrite(true); assertEquals(1, idle);
        sync.onFrame(new byte[]{(byte) 0x83}); assertEquals(6, writes.size());
        assertTrue(errors.isEmpty());
    }
    @Test public void pendingPushAtEndOfQueueIsNotLost() {
        initialize();
        sync.onFrame(new byte[]{10}); sync.onFrame(new byte[]{(byte) 0x83}); sync.onWrite(true);
        assertEquals(4, writes.size()); assertEquals(0, idle);
        sync.onWrite(true); sync.onFrame(new byte[]{10}); assertEquals(1, idle);
    }
    @Test public void cancellationAndFailedWritesDoNotKeepDraining() {
        initialize(); sync.cancel(); sync.onWrite(true); sync.onFrame(message(true, true, "Alice: ignored")); sync.poll();
        assertEquals(3, writes.size()); assertTrue(messages.isEmpty());
        MessageSync broken = new MessageSync(writes::add, new MessageSync.Listener() {
            public void message(ReceivedMessage message) { fail(); }
            public void channel(int index, String name) { }
            public void contact(String prefix, String name) { }
            public void idle() { fail(); }
        }, errors::add, 0);
        broken.start(); broken.onWrite(false); broken.onFrame(new byte[]{4});
        assertEquals(1, errors.size()); assertEquals(4, writes.size());
    }
    @Test public void pollsActualPacketTotalsAndWaitsForWriteAcknowledgementBeforeHandlingPushes() {
        List<Long> totals = new ArrayList<>();
        MessageSync stats = statsSync(totals);
        stats.start(); stats.onWrite(true); stats.onFrame(new byte[]{4});
        stats.onWrite(true); stats.onFrame(new byte[]{10});
        assertArrayEquals(new byte[]{56, 2}, writes.get(2));
        byte[] reply = new byte[26]; reply[0] = 24; reply[1] = 2;
        java.util.Arrays.fill(reply, 2, 6, (byte) 255); reply[6] = 7;
        stats.onFrame(reply);
        assertEquals(List.of(7L, 4294967295L), totals);
        stats.onFrame(new byte[]{(byte) 0x83});
        assertEquals(3, writes.size());
        stats.onWrite(true); assertArrayEquals(new byte[]{10}, writes.get(3));
        stats.onWrite(true); stats.onFrame(new byte[]{10});
        stats.onWrite(true); stats.onFrame(reply);
        assertEquals(1, idle); assertTrue(errors.isEmpty());
    }
    @Test public void rejectedPacketStatsLeaveMessageReceptionWorkingWithoutInventingZeroCounts() {
        List<Long> totals = new ArrayList<>();
        MessageSync stats = statsSync(totals);
        stats.start(); stats.onWrite(true); stats.onFrame(new byte[]{4});
        stats.onWrite(true); stats.onFrame(new byte[]{10});
        stats.onWrite(true); stats.onFrame(new byte[]{1, 1});
        assertEquals(2, totals.size()); assertNull(totals.get(0)); assertNull(totals.get(1));
        assertEquals(1, idle);
        stats.poll(); stats.onWrite(true); stats.onFrame(message(true, true, "Alice: still receiving"));
        stats.onWrite(true); stats.onFrame(new byte[]{10});
        assertEquals(1, messages.size()); assertEquals(2, idle);
        assertArrayEquals(new byte[]{10}, writes.get(writes.size() - 1));
        assertTrue(errors.isEmpty());
    }
    private MessageSync statsSync(List<Long> totals) {
        return new MessageSync(writes::add, new MessageSync.Listener() {
            public void message(ReceivedMessage message) { messages.add(message); }
            public void channel(int index, String name) { }
            public void contact(String prefix, String name) { }
            public void packets(Long sent, Long received) { totals.add(sent); totals.add(received); }
            public void idle() { idle++; }
        }, errors::add, 0, true);
    }
}
