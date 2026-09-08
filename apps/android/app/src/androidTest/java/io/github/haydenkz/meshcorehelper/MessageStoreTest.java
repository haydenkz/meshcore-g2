package io.github.haydenkz.meshcorehelper;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MessageStoreTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String database = "inbox-test-" + UUID.randomUUID() + ".db";
    private final String radio = "a".repeat(64);
    private MessageStore store;
    private MessageStore open() { store = new MessageStore(context, database); return store; }
    @After public void cleanup() { if (store != null) store.close(); context.deleteDatabase(database); }

    @Test public void onlyNewlySavedIncomingMessagesAreEligibleForNotification() {
        open();
        ReceivedMessage incoming = new ReceivedMessage("channel", "0", "Alice", "Meet at the trailhead", 1000);
        assertTrue(store.add(radio, incoming));
        assertFalse(store.add(radio, incoming));
        store.close(); open();
        assertFalse(store.add(radio, incoming));
        assertTrue(store.add(radio, new ReceivedMessage("channel", "1", "Alice", incoming.text(), incoming.sentAt())));
        assertTrue(store.add("b".repeat(64), incoming));
    }

    @Test public void migrationPreservesExistingHistoryAndMakesUnnamedChatsVisible() throws Exception {
        try (SQLiteDatabase db = context.openOrCreateDatabase(database, 0, null)) {
            db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, radio TEXT NOT NULL, kind TEXT NOT NULL, peer TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, sent_at INTEGER NOT NULL, received_at INTEGER NOT NULL, UNIQUE(radio,kind,peer,sender,text,sent_at))");
            db.execSQL("CREATE TABLE names (radio TEXT NOT NULL, kind TEXT NOT NULL, peer TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(radio,kind,peer))");
            db.execSQL("INSERT INTO messages VALUES (41,?,'direct','112233445566','','Keep this history',1000,2000)", new Object[]{radio});
            db.setVersion(1);
        }
        open();
        JSONObject item = new JSONObject(store.messages("direct", radio + ":112233445566", Long.MAX_VALUE)).getJSONArray("items").getJSONObject(0);
        assertEquals(41, item.getLong("id")); assertEquals("Keep this history", item.getString("text"));
        assertEquals("in", item.getString("direction")); assertEquals("received", item.getString("delivery"));
        assertEquals(1, new JSONArray(store.conversations("direct")).length());
        store.close(); open();
        assertEquals(1, new JSONArray(store.conversations("direct")).length());
    }
    @Test public void conversationsAndGlassesReadTheSameMessagesWithoutMixingRadiosOrChannels() throws Exception {
        open();
        store.name(radio, "channel", "0", "Public"); store.name(radio, "channel", "1", "Local");
        store.name(radio, "channel", "2", "Empty channel");
        store.add(radio, new ReceivedMessage("channel", "0", "Alice", "Public hello", 1000));
        store.add(radio, new ReceivedMessage("channel", "1", "Bob", "Local hello", 2000));
        store.add("b".repeat(64), new ReceivedMessage("channel", "0", "Other radio", "Different radio", 3000));
        JSONArray own = new JSONObject(store.messages("channel", radio + ":0", Long.MAX_VALUE)).getJSONArray("items");
        assertEquals(1, own.length()); assertEquals("Public hello", own.getJSONObject(0).getString("text"));
        assertEquals(3, new JSONObject(store.messages("channel", null, Long.MAX_VALUE)).getJSONArray("items").length());
        assertEquals(4, new JSONArray(store.conversations("channel")).length());
    }
    @Test public void sentMessagesAndDeliveryChangesAreSharedAndSurviveRestart() throws Exception {
        open();
        store.name(radio, "direct", "112233445566", "Alice");
        long id = store.outgoing(radio, "direct", "112233445566", "Hello Alice", 5000);
        store.delivery(id, "awaiting_ack");
        store.close(); open(); store.interruptOutgoing();
        JSONObject message = new JSONObject(store.messages("direct", radio + ":112233445566", Long.MAX_VALUE)).getJSONArray("items").getJSONObject(0);
        assertEquals("out", message.getString("direction")); assertEquals("You", message.getString("senderName"));
        assertEquals("unconfirmed", message.getString("delivery")); assertEquals(5000, store.lastOutgoingAt());
        store.delivery(id, "delivered"); store.interruptOutgoing();
        assertTrue(store.messages("direct", radio + ":112233445566", Long.MAX_VALUE).contains("delivered"));
        assertEquals(id, new JSONObject(store.chats(Long.MAX_VALUE)).getJSONArray("items").getJSONObject(0).getLong("lastMessageId"));
    }
    @Test public void allAdvertsUseTimestampOrderAndTheSamePaginationForBothApps() throws Exception {
        open();
        String key = "12".repeat(32);
        store.contact(radio, new ContactInfo(key, "Hill repeater", 2));
        store.advert(radio, new ContactInfo(key, "", 0), 1000);
        assertEquals("Hill repeater", new JSONObject(store.adverts(Long.MAX_VALUE)).getJSONArray("items").getJSONObject(0).getString("name"));
        // Repeated and out-of-order timestamps must not lose nodes at page boundaries.
        for (int i = 0; i < 205; i++) store.advert(radio, new ContactInfo(String.format(java.util.Locale.ROOT, "%064x", i), "Node " + i, 1), 2000 + i % 7);
        store.advert(radio, new ContactInfo(key, "", 0), 5000);
        JSONObject first = new JSONObject(store.adverts(Long.MAX_VALUE));
        JSONArray nativeItems = new JSONObject(store.allAdverts()).getJSONArray("items");
        assertEquals(206, nativeItems.length()); assertTrue(first.getBoolean("hasMore"));
        assertEquals("Hill repeater", nativeItems.getJSONObject(0).getString("name"));
        for (int i = 0; i < 16; i++) assertEquals(nativeItems.getJSONObject(i).toString(), first.getJSONArray("items").getJSONObject(i).toString());
        long before = first.getJSONArray("items").getJSONObject(15).getLong("id");
        assertEquals(nativeItems.getJSONObject(16).getLong("id"), new JSONObject(store.adverts(before)).getJSONArray("items").getJSONObject(0).getLong("id"));
        int seen = 0;
        JSONObject page = first;
        long previousTime = Long.MAX_VALUE;
        while (true) {
            JSONArray items = page.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                assertEquals(nativeItems.getJSONObject(seen++).getLong("id"), item.getLong("id"));
                assertTrue(item.getLong("receivedAt") <= previousTime);
                previousTime = item.getLong("receivedAt");
            }
            if (!page.getBoolean("hasMore")) break;
            page = new JSONObject(store.adverts(items.getJSONObject(items.length() - 1).getLong("id")));
        }
        assertEquals(206, seen);
        store.close(); open();
        assertEquals(206, new JSONObject(store.allAdverts()).getJSONArray("items").length());
    }
    @Test public void savedCompanionAdvertsAppearWithoutWaitingForANewPushOrAnAgeCutoff() throws Exception {
        open();
        String key = "12".repeat(32);
        store.contact(radio, new ContactInfo(key, "Old repeater", 2, 1000));
        store.contact(radio, new ContactInfo("34".repeat(32), "Never advertised", 1));
        JSONObject saved = new JSONObject(store.allAdverts()).getJSONArray("items").getJSONObject(0);
        assertEquals(1, new JSONObject(store.allAdverts()).getJSONArray("items").length());
        assertEquals(1000, saved.getLong("receivedAt"));
        long id = saved.getLong("id");
        store.advert(radio, new ContactInfo(key, "", 0), 5000);
        store.contact(radio, new ContactInfo(key, "Renamed repeater", 2, 2000));
        JSONObject latest = new JSONObject(store.allAdverts()).getJSONArray("items").getJSONObject(0);
        assertEquals(id, latest.getLong("id"));
        assertEquals(5000, latest.getLong("receivedAt"));
        assertEquals("Renamed repeater", latest.getString("name"));
        store.close(); open();
        assertEquals(1, new JSONObject(store.allAdverts()).getJSONArray("items").length());
    }
    @Test public void advertUpgradeSupportsChatOnlyAndEarlierPreviewDatabases() throws Exception {
        open();
        long messageId = store.outgoing(radio, "direct", "112233445566", "Keep my chat", 1000);
        store.getWritableDatabase().execSQL("DROP TABLE adverts");
        store.getWritableDatabase().setVersion(2);
        store.close(); open();
        assertEquals(0, new JSONObject(store.allAdverts()).getJSONArray("items").length());
        assertEquals(messageId, new JSONObject(store.chats(Long.MAX_VALUE)).getJSONArray("items").getJSONObject(0).getLong("lastMessageId"));
        store.contact(radio, new ContactInfo("12".repeat(32), "Keep my advert", 2, 2000));
        store.getWritableDatabase().setVersion(2);
        store.close(); open();
        assertEquals("Keep my advert", new JSONObject(store.allAdverts()).getJSONArray("items").getJSONObject(0).getString("name"));
    }
}
