package io.github.haydenkz.meshcorehelper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/** Read-only initialization, based on the MeshCore companion protocol and meshcore.js. */
public final class CompanionProtocol {
    private CompanionProtocol() {}
    public static final UUID SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public record Identity(String name, int protocolVersion, int batteryMillivolts) {}

    // APP_START returns 58 fixed bytes plus at most 32 name bytes. DEVICE_QUERY
    // and battery replies are smaller. ATT notifications use three MTU bytes.
    // ESP32 MeshCore uses MTU 172/176; requiring 185 rejects supported radios.
    public static boolean supportsHandshakeMtu(int mtu) { return mtu >= 58 + 32 + 3; }

    public static byte[] appStart() {
        byte[] name = "MeshCore G2".getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[8 + name.length];
        frame[0] = 0x01;
        frame[1] = 1; // app version; remaining six bytes are reserved
        System.arraycopy(name, 0, frame, 8, name.length);
        return frame;
    }

    /** Both the write acknowledgement and matching notification precede the next command. */
    public static final class Handshake {
        private final Consumer<byte[]> write;
        private final Consumer<Identity> ready;
        private final Consumer<String> failed;
        private final int[] expected = {0x05, 0x0d, 0x0c};
        private int step;
        private boolean acknowledged;
        private boolean ended;
        private byte[] response;
        private String name;
        private int version;

        public Handshake(Consumer<byte[]> write, Consumer<Identity> ready, Consumer<String> failed) {
            this.write = write;
            this.ready = ready;
            this.failed = failed;
        }
        public void start() { write.accept(appStart()); }
        public void cancel() { ended = true; }
        public void onWrite(boolean success) {
            if (ended) return;
            if (!success) { fail("The radio rejected a BLE write. Check pairing and retry."); return; }
            acknowledged = true;
            advance();
        }
        public void onFrame(byte[] frame) {
            if (ended || frame.length == 0) return;
            if (frame[0] == 0x01) { fail("The radio returned a companion protocol error."); return; }
            if ((frame[0] & 0xff) != expected[step]) return; // unrelated push notification
            response = frame.clone();
            advance();
        }
        private void advance() {
            if (!acknowledged || response == null || ended) return;
            int minimum = new int[]{58, 2, 3}[step];
            if (response.length < minimum) { fail("The radio returned an incomplete reply."); return; }
            if (step == 0) {
                int end = 58;
                while (end < response.length && response[end] != 0) end++;
                name = new String(response, 58, end - 58, StandardCharsets.UTF_8).trim();
            } else if (step == 1) {
                version = response[1] & 0xff;
            } else {
                int voltage = (response[1] & 0xff) | ((response[2] & 0xff) << 8);
                ended = true;
                ready.accept(new Identity(name, version, voltage));
                return;
            }
            step++;
            acknowledged = false;
            response = null;
            write.accept(step == 1 ? new byte[]{0x16, 3} : new byte[]{0x14});
        }
        private void fail(String reason) { ended = true; failed.accept(reason); }
    }
}
