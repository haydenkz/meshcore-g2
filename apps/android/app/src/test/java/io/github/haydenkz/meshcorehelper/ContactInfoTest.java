package io.github.haydenkz.meshcorehelper;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class ContactInfoTest {
    @Test public void decodesPublicContactMetadataAndKeyOnlyAdverts() {
        byte[] frame = contact(0x8a);
        ContactInfo info = ContactInfo.parse(frame);
        assertEquals("112233445566", info.prefix());
        assertEquals("Hill repeater", info.name()); assertEquals(2, info.type());
        byte[] known = java.util.Arrays.copyOf(frame, 33); known[0] = (byte) 0x80;
        ContactInfo advert = ContactInfo.parse(known);
        assertEquals(info.publicKey(), advert.publicKey()); assertEquals("", advert.name());
        for (byte[] invalid : new byte[][]{ {}, {(byte) 0x80}, new byte[]{(byte) 0x8a, 1}, new byte[]{(byte) 0x88, 1, 2, 3} }) assertNull(ContactInfo.parse(invalid));
    }
    @Test public void contactSyncNeverInventsRecentAdvertsAndPushesDoNotAdvanceTheQueue() {
        List<ContactInfo> contacts = new ArrayList<>(), adverts = new ArrayList<>();
        List<byte[]> writes = new ArrayList<>();
        MessageSync sync = new MessageSync(writes::add, new MessageSync.Listener() {
            public void message(ReceivedMessage message) {}
            public void channel(int index, String name) {}
            public void contact(String prefix, String name) {}
            public void contactInfo(ContactInfo info) { contacts.add(info); }
            public void advert(ContactInfo info) { adverts.add(info); }
            public void idle() {}
        }, message -> fail(message), 0);
        sync.start(); sync.onWrite(true); sync.onFrame(contact(3));
        assertEquals(1, contacts.size()); assertTrue(adverts.isEmpty());
        sync.onFrame(contact(0x8a));
        assertEquals(1, adverts.size()); assertEquals(1, writes.size());
        sync.onFrame(new byte[]{4}); assertEquals(2, writes.size());
    }
    @Test public void readsSavedAdvertTimeWithoutSubstitutingContactModificationTime() {
        byte[] frame = contact(3);
        ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putInt(132, (int) 0xf1234567L).putInt(144, 42);
        assertEquals(0xf1234567L * 1000, ContactInfo.parse(frame).lastAdvertAt());
        assertEquals(0, ContactInfo.parse(java.util.Arrays.copyOf(frame, 132)).lastAdvertAt());
        assertEquals(0, ContactInfo.parse(contact(3)).lastAdvertAt());
    }
    static byte[] contact(int code) {
        byte[] frame = new byte[148]; frame[0] = (byte) code;
        for (int i = 1; i <= 6; i++) frame[i] = (byte) (i * 17);
        frame[33] = 2;
        byte[] name = "Hill repeater".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(name, 0, frame, 100, name.length);
        return frame;
    }
}
