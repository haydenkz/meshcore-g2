package io.github.haydenkz.meshcorehelper;

import java.util.function.Consumer;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Serialize sync and explicit sends; wait for BOTH BLE write completion and the radio reply.
 * Protocol: https://github.com/meshcore-dev/MeshCore/blob/main/examples/companion_radio/MyMesh.cpp
 */
public final class MessageSync {
    public interface Listener {
        void message(ReceivedMessage message);
        void channel(int index, String name);
        void contact(String prefix, String name);
        default void contactInfo(ContactInfo info) { contact(info.prefix(), info.name()); }
        default void outgoing(long id, String state, Long ack, long timeoutMs) {}
        default void confirmed(long ack) {}
        default void packets(Long sent, Long received) {}
        void idle();
    }
    private enum Operation { CONTACTS, CHANNEL, MESSAGES, STATS, SEND }
    private record Pending(OutgoingMessage message, byte[] frame) {}
    private final ArrayDeque<Pending> outbox = new ArrayDeque<>();
    private final Set<Long> earlyAcks = new HashSet<>();
    private Pending sending;
    private String sendState;
    private Long sendAck;
    private long sendTimeout;
    private boolean initialized;
    private boolean supportsStats;
    private final Consumer<byte[]> write;
    private final Consumer<String> failed;
    private final Listener listener;
    private final int channels;
    private Operation operation;
    private int channel;
    private boolean acknowledged;
    private boolean replied;
    private boolean waiting;
    private boolean ended;
    private boolean pendingPush;
    private boolean noMore;
    public MessageSync(Consumer<byte[]> write, Listener listener, Consumer<String> failed, int channels) {
        this(write, listener, failed, channels, false);
    }
    public MessageSync(Consumer<byte[]> write, Listener listener, Consumer<String> failed, int channels, boolean supportsStats) {
        this.write = write; this.listener = listener; this.failed = failed; this.channels = Math.max(0, Math.min(255, channels));
        this.supportsStats = supportsStats;
    }
    public void start() { send(Operation.CONTACTS, new byte[]{4}); }
    public void cancel() {
        if (ended) return;
        ended = true;
        if (sending != null) listener.outgoing(sending.message().id(), "unconfirmed", null, 0);
        sending = null;
        while (!outbox.isEmpty()) listener.outgoing(outbox.removeFirst().message().id(), "failed", null, 0);
        earlyAcks.clear();
    }
    public boolean enqueue(OutgoingMessage message, byte[] frame) {
        if (ended || outbox.size() >= 8) return false;
        outbox.addLast(new Pending(message, frame.clone()));
        if (initialized && !waiting) sendNext();
        return true;
    }
    private boolean sendNext() {
        if (outbox.isEmpty()) return false;
        sending = outbox.removeFirst();
        sendState = "unconfirmed"; sendAck = null; sendTimeout = 0; earlyAcks.clear();
        listener.outgoing(sending.message().id(), "sending", null, 0);
        send(Operation.SEND, sending.frame());
        return true;
    }
    public void poll() {
        if (ended) return;
        if (waiting) { pendingPush = true; return; }
        pendingPush = false;
        send(Operation.MESSAGES, new byte[]{10});
    }
    private void send(Operation next, byte[] frame) {
        if (ended) return;
        operation = next; acknowledged = false; replied = false; waiting = true; noMore = false;
        write.accept(frame);
    }
    public void onWrite(boolean success) {
        if (ended || !waiting) return;
        if (!success) { fail("The radio rejected a message sync request. Reconnect and try again."); return; }
        acknowledged = true; advance();
    }
    public void onFrame(byte[] frame) {
        if (ended || frame.length == 0) return;
        int code = frame[0] & 255;
        if (code == 0x83) { poll(); return; }
        if (code == 0x82) {
            if (frame.length >= 5) {
                long ack = ReceivedMessage.unsignedInt(frame, 1);
                if (sending != null && earlyAcks.size() < 16) earlyAcks.add(ack);
                listener.confirmed(ack);
            }
            return;
        }
        ContactInfo contact = ContactInfo.parse(frame);
        if (contact != null && code != 0x80) listener.contactInfo(contact);
        // Discovery pushes are independent of the outstanding command. Initial
        // GET_CONTACTS rows populate names but are not newly received adverts.
        if (code == 0x80 || code == 0x8a) {
            return;
        }
        if (!waiting) return;
        if (code == 1) {
            if (operation == Operation.SEND) sendState = "failed";
            if (operation == Operation.MESSAGES) { fail("The radio could not read its message queue. Reconnect and try again."); return; }
            if (operation == Operation.STATS) { supportsStats = false; listener.packets(null, null); }
            replied = true; // Missing channel/contact metadata must not prevent receiving text.
        } else if (operation == Operation.SEND && sending != null) {
            if (sending.message().kind().equals("channel") && code == 0) {
                sendState = "sent"; replied = true;
            } else if (sending.message().kind().equals("direct") && code == 6 && frame.length >= 10) {
                sendState = "awaiting_ack";
                sendAck = ReceivedMessage.unsignedInt(frame, 2);
                sendTimeout = ReceivedMessage.unsignedInt(frame, 6);
                replied = true;
            }
        } else if (operation == Operation.CONTACTS && code == 4) replied = true;
        else if (operation == Operation.CHANNEL && code == 18 && frame.length >= 34 && (frame[1] & 255) == channel) {
            listener.channel(channel, ReceivedMessage.stringField(frame, 2, 32));
            replied = true;
        } else if (operation == Operation.STATS && code == 24 && frame.length >= 2 && frame[1] == 2) {
            // MeshCore CMD_GET_STATS / STATS_TYPE_PACKETS: RX uint32, TX uint32,
            // then four flood/direct counters. The later RX-error field is optional.
            if (frame.length >= 26) listener.packets(ReceivedMessage.unsignedInt(frame, 6), ReceivedMessage.unsignedInt(frame, 2));
            else listener.packets(null, null);
            replied = true;
        } else if (operation == Operation.MESSAGES && (code == 10 || ReceivedMessage.isReply(code))) {
            if (code == 10) noMore = true;
            else {
                try { ReceivedMessage message = ReceivedMessage.parse(frame); if (message != null) listener.message(message); }
                catch (IllegalArgumentException error) { fail("An incoming message was incomplete. Reconnect the radio and retry."); return; }
            }
            replied = true;
        }
        advance();
    }
    private void advance() {
        if (ended || !acknowledged || !replied) return;
        waiting = false;
        if (operation == Operation.CONTACTS) {
            if (channels > 0) send(Operation.CHANNEL, new byte[]{31, 0});
            else { initialized = true; if (!sendNext()) poll(); }
        } else if (operation == Operation.CHANNEL) {
            channel++;
            if (channel < channels) send(Operation.CHANNEL, new byte[]{31, (byte) channel});
            else { initialized = true; if (!sendNext()) poll(); }
        } else if (operation == Operation.SEND) {
            Pending completed = sending;
            sending = null;
            boolean delivered = sendAck != null && earlyAcks.contains(sendAck);
            listener.outgoing(completed.message().id(), delivered ? "delivered" : sendState, sendAck, sendTimeout);
            if (!sendNext()) poll();
        } else if (sendNext()) {
            // Resume draining received messages after the explicit sends finish.
        } else if (operation == Operation.STATS) {
            if (pendingPush) poll(); else listener.idle();
        } else if (!noMore || pendingPush) poll();
        else if (supportsStats) send(Operation.STATS, new byte[]{56, 2});
        else listener.idle();
    }
    private void fail(String message) { cancel(); failed.accept(message); }
}
