package io.github.haydenkz.meshcorehelper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** A single explicit user send. Never retried automatically. */
public record OutgoingMessage(long id, String kind, String peer, String text, long sentAt) {
    public static int byteLimit(String kind, String radioName, int mtu) {
        boolean channel = kind.equals("channel");
        // Group messages include the radio's UTF-8 name and ': ' on air.
        int textLimit = 160 - (channel ? radioName.getBytes(StandardCharsets.UTF_8).length + 2 : 0);
        return Math.max(0, Math.min(textLimit, Math.min(172, mtu - 3) - (channel ? 7 : 13)));
    }
    public byte[] encode(int maxBytes) {
        boolean channel = kind.equals("channel");
        if (!channel && !kind.equals("direct")) throw new IllegalArgumentException("Invalid conversation.");
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (text.isBlank() || text.indexOf('\0') >= 0) throw new IllegalArgumentException("Enter a message.");
        if (body.length > maxBytes) throw new IllegalArgumentException("Message is too long. Limit: " + maxBytes + " UTF-8 bytes.");
        long seconds = sentAt / 1000;
        if (seconds < 1 || seconds > 0xffffffffL) throw new IllegalArgumentException("Check the phone's clock.");
        ByteBuffer frame = ByteBuffer.allocate((channel ? 7 : 13) + body.length).order(ByteOrder.LITTLE_ENDIAN);
        if (channel) {
            int index;
            try { index = Integer.parseInt(peer); } catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid channel."); }
            if (index < 0 || index > 255) throw new IllegalArgumentException("Invalid channel.");
            frame.put((byte) 3).put((byte) 0).put((byte) index).putInt((int) seconds);
        } else {
            if (!peer.matches("[a-f0-9]{12}")) throw new IllegalArgumentException("Invalid direct chat.");
            frame.put((byte) 2).put((byte) 0).put((byte) 0).putInt((int) seconds);
            for (int i = 0; i < peer.length(); i += 2) frame.put((byte) Integer.parseInt(peer.substring(i, i + 2), 16));
        }
        return frame.put(body).array();
    }
}
