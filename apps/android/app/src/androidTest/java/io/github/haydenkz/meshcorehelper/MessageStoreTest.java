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
}
