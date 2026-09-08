package io.github.haydenkz.meshcorehelper;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class PacketStoreTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String database = "packets-test-" + UUID.randomUUID() + ".db";
    private PacketStore store = new PacketStore(context, database);
    @After public void cleanup() { store.close(); context.deleteDatabase(database); }

    @Test public void keepsExactlyNewestHundredAcrossRestartsRadiosAndSequenceResets() {
        for (int i = 1; i <= 105; i++) store.add("radio-a", packet(i, i * 1000L));
        store.close(); store = new PacketStore(context, database);
        List<PacketStore.SavedPacket> saved = store.all();
        assertEquals(100, saved.size());
        assertEquals(105000, saved.get(0).log().receivedAt());
        assertEquals(6000, saved.get(99).log().receivedAt());
        long newestId = saved.get(0).log().id();
        // A reconnect resets the in-memory ID; a clock correction can move time backwards.
        store.add("radio-b", packet(1, 500));
        store.close(); store = new PacketStore(context, database);
        saved = store.all();
        assertEquals(100, saved.size());
        assertTrue(saved.get(0).log().id() > newestId);
        assertEquals("radio-b", saved.get(0).radio());
        assertEquals(500, saved.get(0).log().receivedAt());
        assertEquals(7000, saved.get(99).log().receivedAt());
        try (android.database.Cursor rows = store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM packets", null)) {
            assertTrue(rows.moveToFirst()); assertEquals(100, rows.getInt(0));
        }
    }
    @Test public void preservesSignalsRawBytesAndMalformedDetailsWithoutDeduplicatingArrivals() {
        RadioLog packet = RadioLog.parse(1, 1234, new byte[]{(byte) 0x88, -13, -110, 0x14, 0x34, 0x12, 0x78, 0x56, 0x42, 0x11, 0x22, 0x33, 0x44, 0x55});
        RadioLog malformed = RadioLog.parse(2, 1250, new byte[]{(byte) 0x88, 10, -75, 0x15, 2, 1});
        store.add("radio-a", packet);
        store.add("radio-a", malformed);
        store.add("radio-a", malformed);
        store.close(); store = new PacketStore(context, database);
        List<PacketStore.SavedPacket> saved = store.all();
        assertEquals(3, saved.size());
        assertEquals(packet, saved.get(2).log());
        assertEquals(malformed, saved.get(1).log());
        assertEquals(malformed.details(), saved.get(0).log().details());
        assertFalse(saved.get(0).log().details().note().isEmpty());
    }
    private static RadioLog packet(long id, long time) {
        return RadioLog.parse(id, time, new byte[]{(byte) 0x88, 10, -100, 0x11, 0, 1});
    }
}
