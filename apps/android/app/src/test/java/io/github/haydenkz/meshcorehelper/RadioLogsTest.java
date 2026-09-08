package io.github.haydenkz.meshcorehelper;

import org.junit.Test;
import static org.junit.Assert.*;

public class RadioLogsTest {
    @Test public void decodesSignedSignalAndPacketMetadataWithoutIncludingBleFraming() {
        // 0x88, SNR -3.25 dB, RSSI -110 dBm, flood channel-text header, raw packet bytes.
        RadioLog log = RadioLog.parse(7, 123456789L,
                new byte[]{(byte) 0x88, -13, -110, 0x15, 0, 1, 2, 3});
        assertNotNull(log);
        assertEquals(7, log.id());
        assertEquals(123456789L, log.receivedAt());
        assertEquals("Channel message", log.type());
        assertEquals("Flood", log.route());
        assertEquals(5, log.bytes());
        assertEquals(-110, log.rssi());
        assertEquals(-3.25f, log.snr(), 0.0f);
    }

    @Test public void decodesHeaderBitFieldsIndependentlyIncludingReservedPacketTypes() {
        String[] types = {"Request", "Response", "Direct message", "Acknowledgement",
                "Advertisement", "Channel message", "Channel data", "Anonymous request",
                "Path", "Trace", "Multipart packet", "Control packet", "Unknown packet",
                "Unknown packet", "Unknown packet", "Custom packet"};
        String[] routes = {"Transport flood", "Flood", "Direct", "Transport direct"};
        for (int version = 0; version < 4; version++) {
            for (int type = 0; type < types.length; type++) {
                for (int route = 0; route < routes.length; route++) {
                    RadioLog log = RadioLog.parse(1, 1, new byte[]{(byte) 0x88, 29, -70,
                            (byte) (version << 6 | type << 2 | route), 0});
                    assertEquals(types[type], log.type());
                    assertEquals(routes[route], log.route());
                    assertEquals(7.25f, log.snr(), 0.0f);
                }
            }
        }
    }

    @Test public void ignoresOtherRepliesAndIncompleteLogFrames() {
        assertNull(RadioLog.parse(1, 1, null));
        for (byte[] frame : new byte[][]{ {}, {(byte) 0x88}, {(byte) 0x88, 1}, {(byte) 0x88, 1, 2} }) {
            assertNull(RadioLog.parse(1, 1, frame));
        }
        // Include identity, channel metadata, messages, ACKs, keys, and all other push codes.
        for (int code = 0; code < 256; code++) {
            if (code != 0x88) assertNull(RadioLog.parse(1, 1, new byte[]{(byte) code, 1, 2, 3, 4}));
        }
    }

    @Test public void decodesMultiBytePathsAndTransportCodesWithoutMistakingPayloadForPath() {
        RadioLog log = RadioLog.parse(1, 1, new byte[]{(byte) 0x88, 0, -90, 0x14, 0x34, 0x12, 0x78, 0x56, 0x42, 0x11, 0x22, 0x33, 0x44, 0x55});
        assertEquals(2, log.details().pathCount());
        assertEquals(2, log.details().hashBytes());
        assertEquals("1122 → 3344", log.details().path());
        assertEquals("1234 · 5678", log.details().transportCodes());
        assertEquals(1, log.details().payloadBytes());
        assertEquals("", log.details().note());
        assertEquals("14 34 12 78 56 42 11 22 33 44 55", log.details().rawHex());
    }
    @Test public void preservesMalformedPacketsForInspectionWithoutInventingPathMetadata() {
        for (byte[] raw : new byte[][] { {0x15}, {0x14, 1, 2}, {0x15, 2, 1}, {0x15, (byte) 0xC0}, {0x55, 0, 1}, {0x15, 0x7f} }) {
            byte[] frame = new byte[raw.length + 3];
            frame[0] = (byte) 0x88;
            System.arraycopy(raw, 0, frame, 3, raw.length);
            RadioLog log = RadioLog.parse(1, 1, frame);
            assertNotNull(log);
            assertFalse(log.details().note().isEmpty());
            assertEquals(-1, log.details().pathCount());
            assertEquals(-1, log.details().payloadBytes());
        }
    }

}
