package io.github.haydenkz.meshcorehelper;

import org.junit.Test;
import static org.junit.Assert.*;

public class OutgoingMessageTest {
    @Test public void encodesTheCompanionChannelAndDirectWireFormats() {
        assertArrayEquals(new byte[]{3, 0, 1, (byte) 0xd2, 2, (byte) 0x96, 0x49, 0x48, 0x65, 0x6c, 0x6c, 0x6f},
                new OutgoingMessage(1, "channel", "1", "Hello", 1234567890000L).encode(160));
        assertArrayEquals(new byte[]{2, 0, 0, 1, 0, 0, 0, 0, 0x11, 0x22, 0x33, 0x44, 0x55, (byte) 0xc3, (byte) 0xa9},
                new OutgoingMessage(1, "direct", "001122334455", "é", 1000).encode(160));
    }
    @Test public void accountsForUtf8SenderPrefixAndNegotiatedBleCapacity() {
        assertEquals(126, OutgoingMessage.byteLimit("channel", "é".repeat(16), 176));
        assertEquals(156, OutgoingMessage.byteLimit("direct", "Radio", 172));
        assertEquals(159, OutgoingMessage.byteLimit("direct", "Radio", 247));
        assertEquals(0, OutgoingMessage.byteLimit("channel", "Radio", 9));
        assertThrows(IllegalArgumentException.class, () -> new OutgoingMessage(1, "channel", "0", "é".repeat(64), 1000).encode(126));
    }
    @Test public void rejectsInvalidDestinationsBlankTextAndEmbeddedNullsBeforeSending() {
        for (String text : new String[]{"", "  \n", "Hello\0world"})
            assertThrows(IllegalArgumentException.class, () -> new OutgoingMessage(1, "channel", "0", text, 1000).encode(160));
        for (String peer : new String[]{"-1", "256", "bad"})
            assertThrows(IllegalArgumentException.class, () -> new OutgoingMessage(1, "channel", peer, "Hi", 1000).encode(160));
        assertThrows(IllegalArgumentException.class, () -> new OutgoingMessage(1, "direct", "bad", "Hi", 1000).encode(160));
        assertThrows(IllegalArgumentException.class, () -> new OutgoingMessage(1, "other", "0", "Hi", 1000).encode(160));
    }
}
