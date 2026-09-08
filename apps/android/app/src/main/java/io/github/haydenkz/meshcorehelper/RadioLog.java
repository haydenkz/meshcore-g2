package io.github.haydenkz.meshcorehelper;

/** Metadata from the radio's PUSH_CODE_LOG_RX_DATA (0x88), never helper HTTP traffic.
 * https://github.com/meshcore-dev/MeshCore/blob/main/examples/companion_radio/MyMesh.cpp
 */
public record RadioLog(long id, long receivedAt, String type, String route, int bytes, int rssi, float snr) {
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
        return new RadioLog(id, receivedAt, type, route, frame.length - 3, frame[2], frame[1] / 4.0f);
    }
}
