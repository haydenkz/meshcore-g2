package io.github.haydenkz.meshcorehelper;

import java.util.function.Consumer;

/** Serialize read-only commands and wait for BOTH BLE write completion and the radio reply.
 * Protocol: https://github.com/meshcore-dev/MeshCore/blob/main/examples/companion_radio/MyMesh.cpp
 */
public final class MessageSync {
    public interface Listener {
        void message(ReceivedMessage message);
        void channel(int index, String name);
        void contact(String prefix, String name);
        default void packets(Long sent, Long received) {}
        void idle();
    }
    private enum Operation { CONTACTS, CHANNEL, MESSAGES, STATS }
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
    public void cancel() { ended = true; }
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
        // New-contact pushes use the same payload as contacts returned by GET_CONTACTS.
        if ((code == 3 || code == 0x8a) && frame.length >= 132) {
            listener.contact(ReceivedMessage.hex(frame, 1, 6), ReceivedMessage.stringField(frame, 100, 32));
        }
        if (!waiting) return;
        if (code == 1) {
            if (operation == Operation.MESSAGES) { fail("The radio could not read its message queue. Reconnect and try again."); return; }
            if (operation == Operation.STATS) { supportsStats = false; listener.packets(null, null); }
            replied = true; // Missing channel/contact metadata must not prevent receiving text.
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
            if (channels > 0) send(Operation.CHANNEL, new byte[]{31, 0}); else poll();
        } else if (operation == Operation.CHANNEL) {
            channel++;
            if (channel < channels) send(Operation.CHANNEL, new byte[]{31, (byte) channel}); else poll();
        } else if (operation == Operation.STATS) {
            if (pendingPush) poll(); else listener.idle();
        } else if (!noMore || pendingPush) poll();
        else if (supportsStats) send(Operation.STATS, new byte[]{56, 2});
        else listener.idle();
    }
    private void fail(String message) { ended = true; failed.accept(message); }
}
