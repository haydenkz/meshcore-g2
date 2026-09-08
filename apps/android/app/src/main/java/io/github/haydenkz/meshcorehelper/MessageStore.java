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

/** Private, persistent received-message history. HTTP reads use cursor pagination. */
public final class MessageStore extends SQLiteOpenHelper implements StatusServer.Inbox {
    private static final int PAGE_SIZE = 16;
    public MessageStore(Context context) { super(context, "inbox.db", null, 1); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, radio TEXT NOT NULL, kind TEXT NOT NULL, peer TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL, sent_at INTEGER NOT NULL, received_at INTEGER NOT NULL, UNIQUE(radio,kind,peer,sender,text,sent_at))");
        db.execSQL("CREATE INDEX inbox_page ON messages(kind,id DESC)");
        db.execSQL("CREATE INDEX chat_page ON messages(radio,kind,peer,id DESC)");
        db.execSQL("CREATE TABLE names (radio TEXT NOT NULL, kind TEXT NOT NULL, peer TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(radio,kind,peer))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("Unsupported inbox database migration"); }
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
    }
    private static String conversationId(Cursor row) { return row.getString(row.getColumnIndexOrThrow("radio")) + ":" + row.getString(row.getColumnIndexOrThrow("peer")); }
    private static String name(Cursor row) {
        String value = row.getString(row.getColumnIndexOrThrow("name"));
        if (value != null && !value.isBlank()) return value;
        String peer = row.getString(row.getColumnIndexOrThrow("peer"));
        return row.getString(row.getColumnIndexOrThrow("kind")).equals("channel") ? "Channel " + peer : peer;
    }
    private static final String FROM = " FROM messages m LEFT JOIN names n ON n.radio=m.radio AND n.kind=m.kind AND n.peer=m.peer ";
    private static final String COLUMNS = "m.id,m.radio,m.kind,m.peer,m.sender,m.text,m.sent_at,m.received_at,n.name";
    public String messages(String kind, String conversation, long before) {
        if (!kind.equals("channel") && !kind.equals("direct")) throw new IllegalArgumentException("Invalid message kind");
        List<String> args = new ArrayList<>(); args.add(kind); args.add(Long.toString(before));
        String where = " WHERE m.kind=? AND m.id<?";
        if (kind.equals("direct")) {
            if (conversation == null || !conversation.matches("[a-f0-9]{64}:[a-f0-9]{12}")) throw new IllegalArgumentException("Invalid chat");
            String[] pieces = conversation.split(":", 2);
            where += " AND m.radio=? AND m.peer=?";
            args.add(pieces[0]); args.add(pieces[1]);
        }
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT " + COLUMNS + FROM + where + " ORDER BY m.id DESC LIMIT " + (PAGE_SIZE + 1), args.toArray(new String[0]))) {
            JSONArray items = new JSONArray();
            while (items.length() < PAGE_SIZE && rows.moveToNext()) {
                String sender = rows.getString(rows.getColumnIndexOrThrow("sender"));
                items.put(new JSONObject().put("id", rows.getLong(0)).put("kind", kind).put("conversationId", conversationId(rows))
                        .put("conversationName", name(rows)).put("senderName", sender.isEmpty() && kind.equals("direct") ? name(rows) : sender)
                        .put("text", rows.getString(rows.getColumnIndexOrThrow("text")))
                        .put("sentAt", rows.getLong(rows.getColumnIndexOrThrow("sent_at"))).put("receivedAt", rows.getLong(rows.getColumnIndexOrThrow("received_at"))));
            }
            return new JSONObject().put("schema", 1).put("items", items).put("hasMore", rows.moveToNext()).toString();
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
