package io.github.haydenkz.meshcorehelper;

/** Metadata from the radio's PUSH_CODE_LOG_RX_DATA (0x88), never helper HTTP traffic.
 * https://github.com/meshcore-dev/MeshCore/blob/main/examples/companion_radio/MyMesh.cpp
 */
public record RadioLog(long id, long receivedAt, String type, String route, int bytes, int rssi, float snr, PacketDetails details) {
    public record PacketDetails(int header, int version, int pathCount, int hashBytes, int payloadBytes,
            String path, String transportCodes, String rawHex, String note) {}

    public static RadioLog parse(long id, long receivedAt, byte[] frame) {
        if (frame == null || frame.length < 4 || (frame[0] & 255) != 0x88) return null;
        int header = frame[3] & 255;
        String type = switch ((header >> 2) & 15) {
            case 0 -> "Request";
            case 1 -> "Response";
            case 2 -> "Direct message";
            case 3 -> "Acknowledgement";
            case 4 -> "Advertisement";
            case 5 -> "Channel message";
            case 6 -> "Channel data";
            case 7 -> "Anonymous request";
            case 8 -> "Path";
            case 9 -> "Trace";
            case 10 -> "Multipart packet";
            case 11 -> "Control packet";
            case 15 -> "Custom packet";
            default -> "Unknown packet";
        };
        String route = switch (header & 3) {
            case 0 -> "Transport flood";
            case 1 -> "Flood";
            case 2 -> "Direct";
            default -> "Transport direct";
        };
        // Signal values are signed bytes; SNR is in quarter-dB units.
        return new RadioLog(id, receivedAt, type, route, frame.length - 3, frame[2], frame[1] / 4.0f, packetDetails(frame));
    }

    /** Layout verified against meshcore-dev/MeshCore src/Packet.cpp and src/Packet.h.
     * Raw RX frames can be malformed; never interpret missing bytes as a valid path.
     */
    private static PacketDetails packetDetails(byte[] frame) {
        int header = frame[3] & 255;
        int version = header >> 6;
        String raw = hex(frame, 3, frame.length);
        String transport = "";
        String note = "";
        int cursor = 4;
        if (version != 0) note = "Unsupported payload version; layout not decoded.";
        else if ((header & 3) == 0 || (header & 3) == 3) {
            if (frame.length < cursor + 4) note = "Incomplete transport codes.";
            else {
                transport = String.format(java.util.Locale.ROOT, "%04X · %04X",
                        (frame[4] & 255) | (frame[5] & 255) << 8, (frame[6] & 255) | (frame[7] & 255) << 8);
                cursor += 4;
            }
        }
        if (note.isEmpty() && frame.length <= cursor) note = "Missing path length.";
        int count = -1, hashBytes = -1, payloadBytes = -1;
        String path = "";
        if (note.isEmpty()) {
            int encoded = frame[cursor++] & 255;
            int size = (encoded >> 6) + 1;
            int hashes = encoded & 63;
            int length = size * hashes;
            if (size == 4 || length > 64) note = "Invalid or reserved path encoding.";
            else if (cursor + length > frame.length) note = "Incomplete path bytes.";
            else {
                count = hashes;
                hashBytes = size;
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < hashes; i++) {
                    if (i > 0) joined.append(" → ");
                    joined.append(hex(frame, cursor + i * size, cursor + (i + 1) * size).replace(" ", ""));
                }
                path = joined.toString();
                payloadBytes = frame.length - cursor - length;
                if (payloadBytes == 0) note = "Missing payload.";
                else if (payloadBytes > 184) note = "Payload exceeds the radio packet limit.";
            }
        }
        return new PacketDetails(header, version, count, hashBytes, payloadBytes, path, transport, raw, note);
    }
    private static String hex(byte[] bytes, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) result.append(' ');
            result.append(String.format(java.util.Locale.ROOT, "%02X", bytes[i] & 255));
        }
        return result.toString();
    }
}
