package io.github.haydenkz.meshcorehelper;

import java.util.List;
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

    @Test public void retainsOnlyTheNewestPacketsAndPublishesStableSnapshots() {
        RadioLogs logs = new RadioLogs();
        List<RadioLog> first = logs.add(packet(1));
        for (int id = 2; id <= 205; id++) logs.add(packet(id));
        List<RadioLog> current = logs.snapshot();
        assertEquals(200, current.size());
        assertEquals(205, current.get(0).id());
        assertEquals(6, current.get(199).id());
        assertEquals(List.of(packet(1)), first);
        assertThrows(UnsupportedOperationException.class, () -> current.add(packet(206)));
        logs.clear();
        assertTrue(logs.snapshot().isEmpty());
        assertEquals(200, current.size());
        assertEquals(List.of(packet(206)), logs.add(packet(206)));
    }

    private static RadioLog packet(long id) {
        return new RadioLog(id, id * 1000, "Advertisement", "Flood", 32, -100, 2.5f);
    }
}
