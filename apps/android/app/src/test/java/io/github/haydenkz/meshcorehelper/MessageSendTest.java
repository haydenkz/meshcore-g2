package io.github.haydenkz.meshcorehelper;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class MessageSendTest {
    private final List<byte[]> writes = new ArrayList<>();
    private final List<String> states = new ArrayList<>();
    private final List<Long> acknowledgements = new ArrayList<>();
    private final List<ReceivedMessage> received = new ArrayList<>();
    private final MessageSync sync = new MessageSync(writes::add, new MessageSync.Listener() {
        public void message(ReceivedMessage message) { received.add(message); }
        public void channel(int index, String name) {}
        public void contact(String prefix, String name) {}
        public void outgoing(long id, String state, Long ack, long timeoutMs) { states.add(id + ":" + state); }
        public void confirmed(long ack) { acknowledgements.add(ack); }
        public void idle() {}
    }, message -> fail(message), 0);
    private void ready() {
        sync.start(); sync.onWrite(true); sync.onFrame(new byte[]{4});
        sync.onWrite(true); sync.onFrame(new byte[]{10});
    }
    private boolean send(long id, String kind) {
        OutgoingMessage message = new OutgoingMessage(id, kind, kind.equals("channel") ? "0" : "001122334455", "Hi", id * 1000);
        return sync.enqueue(message, message.encode(150));
    }
    @Test public void sendWaitsForTheReadReplyAndItsBleAcknowledgement() {
        sync.start(); sync.onWrite(true); sync.onFrame(new byte[]{4});
        assertTrue(send(1, "channel"));
        sync.onFrame(MessageSyncTest.message(true, true, "Alice: Hello"));
        assertEquals(1, received.size()); assertEquals(2, writes.size());
        sync.onWrite(true);
        assertEquals(3, writes.size()); assertEquals(3, writes.get(2)[0]);
        sync.onFrame(new byte[]{0}); // Radio accepted the channel message, not a recipient receipt.
        assertEquals(List.of("1:sending"), states); assertEquals(3, writes.size());
        sync.onWrite(true);
        assertEquals(List.of("1:sending", "1:sent"), states);
        assertArrayEquals(new byte[]{10}, writes.get(3));
    }
    @Test public void directDeliveryRequiresMatchingRadioAckEvenBeforeBleWriteCompletion() {
        ready(); send(1, "direct");
        sync.onFrame(new byte[]{6, 1, -1, -1, -1, -1, (byte) 0xe8, 3, 0, 0});
        sync.onFrame(new byte[]{(byte) 0x82, -1, -1, -1, -1, 1, 0, 0, 0});
        sync.onWrite(true);
        assertEquals(List.of("1:sending", "1:delivered"), states);
        assertEquals(List.of(4294967295L), acknowledgements);
    }
    @Test public void unrelatedAcksAndAdvertPushesCannotCompleteASend() {
        ready(); send(1, "direct");
        byte[] advert = new byte[33]; advert[0] = (byte) 0x80;
        sync.onFrame(advert);
        sync.onFrame(new byte[]{(byte) 0x82, 9, 0, 0, 0});
        sync.onFrame(new byte[]{6, 0, 7}); // Incomplete send reply.
        sync.onWrite(true);
        assertEquals(List.of("1:sending"), states);
        assertEquals(3, writes.size());
        sync.onFrame(new byte[]{6, 0, 7, 0, 0, 0, 100, 0, 0, 0});
        assertEquals(List.of("1:sending", "1:awaiting_ack"), states);
    }
    @Test public void rejectedSendDoesNotBreakReceptionOrRetryAutomatically() {
        ready(); send(1, "channel");
        sync.onFrame(new byte[]{1, 2}); sync.onWrite(true);
        assertEquals(List.of("1:sending", "1:failed"), states);
        assertArrayEquals(new byte[]{10}, writes.get(3));
        sync.onWrite(true); sync.onFrame(MessageSyncTest.message(false, true, "Still listening"));
        assertEquals(1, received.size());
        assertEquals(1, writes.stream().filter(frame -> frame[0] == 3).count());
    }
    @Test public void disconnectDistinguishesUnsentQueuedMessagesAndAnUnconfirmedWrite() {
        ready(); send(1, "channel"); send(2, "direct");
        sync.cancel(); sync.onFrame(new byte[]{0}); sync.onWrite(true);
        assertEquals(List.of("1:sending", "1:unconfirmed", "2:failed"), states);
        assertEquals(3, writes.size()); assertFalse(send(3, "channel"));
    }
    @Test public void queueIsBoundedWhileTheRadioIsBusy() {
        ready(); send(1, "channel");
        for (int id = 2; id <= 9; id++) assertTrue(send(id, "channel"));
        assertFalse(send(10, "channel"));
    }
}
