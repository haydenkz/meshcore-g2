package io.github.haydenkz.meshcorehelper;

/** Public contact metadata from GET_CONTACTS and the companion's advert pushes. */
public record ContactInfo(String publicKey, String name, int type, long lastAdvertAt) {
    public ContactInfo(String publicKey, String name, int type) { this(publicKey, name, type, 0); }
    public String prefix() { return publicKey.substring(0, 12); }
    public static ContactInfo parse(byte[] frame) {
        if (frame == null || frame.length == 0) return null;
        int code = frame[0] & 255;
        if (code == 0x80 && frame.length >= 33)
            return new ContactInfo(ReceivedMessage.hex(frame, 1, 32), "", 0);
        if ((code != 3 && code != 0x8a) || frame.length < 132) return null;
        try {
            return new ContactInfo(ReceivedMessage.hex(frame, 1, 32),
                    ReceivedMessage.stringField(frame, 100, 32), frame[33] & 255,
                    frame.length >= 136 ? ReceivedMessage.unsignedInt(frame, 132) * 1000 : 0);
        } catch (IllegalArgumentException error) { return null; }
    }
    public static String typeName(int type) {
        return switch (type) {
            case 1 -> "Chat node";
            case 2 -> "Repeater";
            case 3 -> "Room server";
            case 4 -> "Sensor";
            default -> "Unknown node";
        };
    }
}
