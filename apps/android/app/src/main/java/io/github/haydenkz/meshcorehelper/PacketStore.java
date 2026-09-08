package io.github.haydenkz.meshcorehelper;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/** Private, bounded RX history. Only radio log pushes are saved here. */
public final class PacketStore extends SQLiteOpenHelper {
    public static final int LIMIT = 100;
    public record SavedPacket(RadioLog log, String radio) {}

    public PacketStore(Context context) { this(context, "packets.db"); }
    PacketStore(Context context, String name) { super(context, name, null, 1); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE packets (id INTEGER PRIMARY KEY AUTOINCREMENT, radio TEXT NOT NULL, received_at INTEGER NOT NULL, "
                + "type TEXT NOT NULL, route TEXT NOT NULL, bytes INTEGER NOT NULL, rssi INTEGER NOT NULL, snr REAL NOT NULL, "
                + "header INTEGER NOT NULL, version INTEGER NOT NULL, path_count INTEGER NOT NULL, hash_bytes INTEGER NOT NULL, "
                + "payload_bytes INTEGER NOT NULL, path TEXT NOT NULL, transport TEXT NOT NULL, raw_hex TEXT NOT NULL, note TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public void add(String radio, RadioLog log) {
        ContentValues values = new ContentValues();
        RadioLog.PacketDetails d = log.details();
        values.put("radio", radio == null ? "" : radio); values.put("received_at", log.receivedAt());
        values.put("type", log.type()); values.put("route", log.route()); values.put("bytes", log.bytes());
        values.put("rssi", log.rssi()); values.put("snr", log.snr());
        values.put("header", d.header()); values.put("version", d.version()); values.put("path_count", d.pathCount());
        values.put("hash_bytes", d.hashBytes()); values.put("payload_bytes", d.payloadBytes()); values.put("path", d.path());
        values.put("transport", d.transportCodes()); values.put("raw_hex", d.rawHex()); values.put("note", d.note());
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // Database IDs remain unique when a new connection resets the radio's log sequence.
            db.insertOrThrow("packets", null, values);
            db.execSQL("DELETE FROM packets WHERE id NOT IN (SELECT id FROM packets ORDER BY id DESC LIMIT " + LIMIT + ")");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public List<SavedPacket> all() {
        List<SavedPacket> packets = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().rawQuery("SELECT id,received_at,type,route,bytes,rssi,snr,header,version,path_count,hash_bytes,payload_bytes,path,transport,raw_hex,note,radio FROM packets ORDER BY id DESC LIMIT " + LIMIT, null)) {
            while (rows.moveToNext()) packets.add(new SavedPacket(new RadioLog(rows.getLong(0), rows.getLong(1), rows.getString(2), rows.getString(3),
                    rows.getInt(4), rows.getInt(5), rows.getFloat(6), new RadioLog.PacketDetails(rows.getInt(7), rows.getInt(8), rows.getInt(9),
                    rows.getInt(10), rows.getInt(11), rows.getString(12), rows.getString(13), rows.getString(14), rows.getString(15))), rows.getString(16)));
        }
        return packets;
    }
}
