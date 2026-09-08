package io.github.haydenkz.meshcorehelper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** MeshCore companion v2/v3 text replies. No channel secrets or full keys leave the radio. */
public record ReceivedMessage(String kind, String peer, String sender, String text, long sentAt) {
    public static boolean isReply(int code) { return code == 7 || code == 8 || code == 16 || code == 17 || code == 27; }
    public static ReceivedMessage parse(byte[] frame) {
        if (frame.length == 0) throw new IllegalArgumentException("Empty message reply");
        int code = frame[0] & 255;
        if (code == 27) return null; // Binary channel data is not a text message.
        if (!isReply(code)) throw new IllegalArgumentException("Not a message reply");
        boolean channel = code == 8 || code == 17;
        int at = code == 16 || code == 17 ? 4 : 1;
        int peerBytes = channel ? 1 : 6;
        if (frame.length < at + peerBytes + 6) throw new IllegalArgumentException("Incomplete message reply");
        String peer = channel ? Integer.toString(frame[at] & 255) : hex(frame, at, 6);
        at += peerBytes;
        at++; // path length
        int type = frame[at++] & 255;
        long sentAt = unsignedInt(frame, at) * 1000L;
        at += 4;
        String sender = "";
        if (type == 2 && !channel) {
            if (frame.length < at + 4) throw new IllegalArgumentException("Incomplete signed message");
            sender = hex(frame, at, 4);
            at += 4;
        } else if (type != 0) return null; // CLI replies are not chat messages.
        String text = utf8(frame, at, frame.length - at);
        if (channel) {
            int colon = text.indexOf(": ");
            if (colon > 0 && colon <= 64) { sender = text.substring(0, colon); text = text.substring(colon + 2); }
        }
        return new ReceivedMessage(channel ? "channel" : "direct", peer, sender, text, sentAt);
    }
    static long unsignedInt(byte[] bytes, int offset) {
        return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8) | ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24);
    }
    static String hex(byte[] bytes, int offset, int length) {
        StringBuilder result = new StringBuilder(length * 2);
        for (int i = offset; i < offset + length; i++) result.append(String.format(java.util.Locale.ROOT, "%02x", bytes[i] & 255));
        return result.toString();
    }
    static String utf8(byte[] bytes, int offset, int length) {
        int end = offset + length;
        while (end > offset && bytes[end - 1] == 0) end--;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, end - offset)).toString();
        } catch (CharacterCodingException error) { throw new IllegalArgumentException("Incomplete UTF-8 message", error); }
    }
    static String stringField(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) end++;
        return utf8(bytes, offset, end - offset);
    }
}
