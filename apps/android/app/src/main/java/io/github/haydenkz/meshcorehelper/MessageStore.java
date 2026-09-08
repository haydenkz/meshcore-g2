package io.github.haydenkz.meshcorehelper;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared private history for the Android UI and authenticated glasses reads. */
public final class MessageStore extends SQLiteOpenHelper implements StatusServer.Inbox {
    private static final int PAGE_SIZE = 16;
    public MessageStore(Context context) { this(context, "inbox.db"); }
    MessageStore(Context context, String databaseName) { super(context, databaseName, null, 2); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, radio TEXT NOT NULL, kind TEXT NOT NULL, peer TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, sent_at INTEGER NOT NULL, received_at INTEGER NOT NULL, UNIQUE(radio,kind,peer,sender,text,sent_at))");
        db.execSQL("CREATE INDEX inbox_page ON messages(kind,id DESC)");
        db.execSQL("CREATE INDEX chat_page ON messages(radio,kind,peer,id DESC)");
        db.execSQL("CREATE TABLE names (radio TEXT NOT NULL, kind TEXT NOT NULL, peer TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(radio,kind,peer))");
        upgradeHistory(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) upgradeHistory(db);
    }
    private static void upgradeHistory(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE messages ADD COLUMN direction TEXT NOT NULL DEFAULT 'in'");
        db.execSQL("ALTER TABLE messages ADD COLUMN delivery TEXT NOT NULL DEFAULT 'received'");
        db.execSQL("INSERT OR IGNORE INTO names(radio,kind,peer,name) SELECT radio,kind,peer,'' FROM messages GROUP BY radio,kind,peer");
        db.execSQL("CREATE TABLE nodes (radio TEXT NOT NULL, public_key TEXT NOT NULL, name TEXT NOT NULL, type INTEGER NOT NULL, PRIMARY KEY(radio,public_key))");
    }
    public void name(String radio, String kind, String peer, String name) {
        if (name.isBlank()) return;
        ContentValues values = new ContentValues();
        values.put("radio", radio); values.put("kind", kind); values.put("peer", peer); values.put("name", name);
        getWritableDatabase().insertWithOnConflict("names", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
    public void add(String radio, ReceivedMessage message) {
        ContentValues values = new ContentValues();
        values.put("radio", radio); values.put("kind", message.kind()); values.put("peer", message.peer());
        values.put("sender", message.sender()); values.put("text", message.text()); values.put("sent_at", message.sentAt()); values.put("received_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        ensureName(radio, message.kind(), message.peer());
    }
    private void ensureName(String radio, String kind, String peer) {
        ContentValues values = new ContentValues();
        values.put("radio", radio); values.put("kind", kind); values.put("peer", peer); values.put("name", "");
        getWritableDatabase().insertWithOnConflict("names", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }
    public void contact(String radio, ContactInfo info) {
        if (radio == null) return;
        if (!info.name().isBlank()) {
            name(radio, "direct", info.prefix(), info.name());
            ContentValues values = new ContentValues();
            values.put("radio", radio); values.put("public_key", info.publicKey()); values.put("name", info.name()); values.put("type", info.type());
            getWritableDatabase().insertWithOnConflict("nodes", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }
    public long outgoing(String radio, String kind, String peer, String text, long sentAt) {
        ContentValues values = new ContentValues();
        values.put("radio", radio); values.put("kind", kind); values.put("peer", peer);
        values.put("sender", "You"); values.put("text", text); values.put("sent_at", sentAt);
        values.put("received_at", System.currentTimeMillis()); values.put("direction", "out"); values.put("delivery", "queued");
        ensureName(radio, kind, peer);
        return getWritableDatabase().insertOrThrow("messages", null, values);
    }
    public void delivery(long id, String state) {
        ContentValues values = new ContentValues(); values.put("delivery", state);
        getWritableDatabase().update("messages", values, "id=? AND direction='out'", new String[]{Long.toString(id)});
    }
    public void interruptOutgoing() {
        getWritableDatabase().execSQL("UPDATE messages SET delivery='unconfirmed' WHERE direction='out' AND delivery IN ('queued','sending','awaiting_ack')");
    }
    public long lastOutgoingAt() {
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT MAX(sent_at) FROM messages WHERE direction='out'", null)) {
            return rows.moveToFirst() ? rows.getLong(0) : 0;
        }
    }
    private static String conversationId(Cursor row) { return row.getString(row.getColumnIndexOrThrow("radio")) + ":" + row.getString(row.getColumnIndexOrThrow("peer")); }
    private static String name(Cursor row) {
        String value = row.getString(row.getColumnIndexOrThrow("name"));
        if (value != null && !value.isBlank()) return value;
        String peer = row.getString(row.getColumnIndexOrThrow("peer"));
        return row.getString(row.getColumnIndexOrThrow("kind")).equals("channel") ? "Channel " + peer : peer;
    }
    private static final String FROM = " FROM messages m LEFT JOIN names n ON n.radio=m.radio AND n.kind=m.kind AND n.peer=m.peer ";
    private static final String COLUMNS = "m.id,m.radio,m.kind,m.peer,m.sender,m.text,m.sent_at,m.received_at,n.name,m.direction,m.delivery";
    public String messages(String kind, String conversation, long before) {
        return messages(kind, conversation, before, PAGE_SIZE);
    }
    public String messages(String kind, String conversation, long before, int limit) {
        if (!kind.equals("channel") && !kind.equals("direct")) throw new IllegalArgumentException("Invalid message kind");
        List<String> args = new ArrayList<>(); args.add(kind); args.add(Long.toString(before));
        String where = " WHERE m.kind=? AND m.id<?";
        if (conversation != null || kind.equals("direct")) {
            if (conversation == null || !conversation.matches(kind.equals("direct") ? "[a-f0-9]{64}:[a-f0-9]{12}" : "[a-f0-9]{64}:[0-9]{1,3}")) throw new IllegalArgumentException("Invalid chat");
            String[] pieces = conversation.split(":", 2);
            where += " AND m.radio=? AND m.peer=?";
            args.add(pieces[0]); args.add(pieces[1]);
        }
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT " + COLUMNS + FROM + where + " ORDER BY m.id DESC LIMIT " + (limit + 1), args.toArray(new String[0]))) {
            JSONArray items = new JSONArray();
            while (items.length() < limit && rows.moveToNext()) {
                String sender = rows.getString(rows.getColumnIndexOrThrow("sender"));
                items.put(new JSONObject().put("id", rows.getLong(0)).put("kind", kind).put("conversationId", conversationId(rows))
                        .put("conversationName", name(rows)).put("senderName", sender.isEmpty() && kind.equals("direct") ? name(rows) : sender)
                        .put("text", rows.getString(rows.getColumnIndexOrThrow("text")))
                        .put("sentAt", rows.getLong(rows.getColumnIndexOrThrow("sent_at"))).put("receivedAt", rows.getLong(rows.getColumnIndexOrThrow("received_at")))
                        .put("direction", rows.getString(rows.getColumnIndexOrThrow("direction"))).put("delivery", rows.getString(rows.getColumnIndexOrThrow("delivery"))));
            }
            return new JSONObject().put("schema", 1).put("items", items).put("hasMore", rows.moveToNext()).toString();
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }
    /** Conversation list includes configured channels and chat contacts even before their first message. */
    public String conversations(String kind) {
        if (!kind.equals("channel") && !kind.equals("direct")) throw new IllegalArgumentException("Invalid message kind");
        String query = "SELECT n.radio,n.peer,n.name,m.id,m.text,m.received_at FROM names n LEFT JOIN messages m ON m.id=(SELECT MAX(id) FROM messages WHERE radio=n.radio AND kind=n.kind AND peer=n.peer) WHERE n.kind=? AND (n.kind='channel' OR m.id IS NOT NULL OR EXISTS(SELECT 1 FROM nodes WHERE radio=n.radio AND substr(public_key,1,12)=n.peer AND type=1)) ORDER BY m.id DESC,n.name COLLATE NOCASE,n.radio,n.peer";
        try (Cursor rows = getReadableDatabase().rawQuery(query, new String[]{kind})) {
            JSONArray items = new JSONArray();
            while (rows.moveToNext()) {
                String name = rows.getString(2);
                if (name.isBlank()) name = kind.equals("channel") ? "Channel " + rows.getString(1) : rows.getString(1);
                items.put(new JSONObject().put("id", rows.getString(0) + ":" + rows.getString(1)).put("kind", kind).put("name", name)
                        .put("lastMessageId", rows.getLong(3)).put("preview", rows.isNull(4) ? "" : rows.getString(4)).put("updatedAt", rows.getLong(5)));
            }
            return items.toString();
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }
    public String chats(long before) {
        String where = " WHERE m.kind='direct' AND m.id IN (SELECT MAX(id) FROM messages WHERE kind='direct' GROUP BY radio,peer) AND m.id<? ORDER BY m.id DESC LIMIT " + (PAGE_SIZE + 1);
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT " + COLUMNS + FROM + where, new String[]{Long.toString(before)})) {
            JSONArray items = new JSONArray();
            while (items.length() < PAGE_SIZE && rows.moveToNext()) {
                items.put(new JSONObject().put("id", conversationId(rows)).put("name", name(rows)).put("lastMessageId", rows.getLong(0))
                        .put("updatedAt", rows.getLong(rows.getColumnIndexOrThrow("received_at"))).put("preview", rows.getString(rows.getColumnIndexOrThrow("text"))));
            }
            return new JSONObject().put("schema", 1).put("items", items).put("hasMore", rows.moveToNext()).toString();
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }
}
