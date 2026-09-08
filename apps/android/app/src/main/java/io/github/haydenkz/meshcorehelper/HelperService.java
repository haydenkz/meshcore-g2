package io.github.haydenkz.meshcorehelper;

import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONException;
import org.json.JSONObject;
import androidx.lifecycle.MutableLiveData;

public final class HelperService extends Service {
    public static final String STOP = "io.github.haydenkz.meshcorehelper.STOP";
    private final IBinder binder = new LocalBinder();
    private final AtomicReference<String> json = new AtomicReference<>("{}");
    private BleCompanion companion;
    private StatusServer server;
    private MessageStore messages;
    private String radioId;
    private long lastSentAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private record PendingAck(long messageId, Runnable expiry) {}
    private final Map<Long, PendingAck> pendingAcks = new LinkedHashMap<>();
    private Long packetsSent;
    private Long packetsReceived;
    private final RadioLogs logs = new RadioLogs();
    public final MutableLiveData<List<RadioLog>> radioLogs = new MutableLiveData<>(Collections.emptyList());
    public String detail = "Starting phone helper…";
    public boolean available;
    public final MutableLiveData<HelperSnapshot> snapshot = new MutableLiveData<>(
            new HelperSnapshot("disconnected", "Choose a nearby companion.", "", null, null));

    public final class LocalBinder extends Binder { public HelperService service() { return HelperService.this; } }
    public static String key(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("helper", MODE_PRIVATE);
        String key = preferences.getString("key", null);
        if (key == null) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            StringBuilder encoded = new StringBuilder();
            for (byte value : bytes) encoded.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            key = encoded.toString();
            preferences.edit().putString("key", key).apply();
        }
        return key;
    }
    @Override public void onCreate() {
        super.onCreate();
        messages = new MessageStore(this);
        messages.interruptOutgoing();
        lastSentAt = messages.lastOutgoingAt();
        startHelper();
    }
    private void startHelper() {
        available = false;
        if (server != null) server.stop();
        if (companion != null) companion.dispose();
        companion = new BleCompanion(this, new BleCompanion.Listener() {
            @Override public void update(String state, String detail, String name, Integer version, Integer battery) { HelperService.this.update(state, detail, name, version, battery); }
            @Override public void radio(String id) { radioId = id; }
            @Override public void message(ReceivedMessage message) {
                if (messages.add(radioId, message)) {
                    MessageNotifications.received(HelperService.this, radioId, message,
                            messages.conversationName(radioId, message.kind(), message.peer()));
                }
            }
            @Override public void channel(int index, String name) { messages.name(radioId, "channel", Integer.toString(index), name); }
            @Override public void contact(String prefix, String name) { messages.name(radioId, "direct", prefix, name); }
            @Override public void contactInfo(ContactInfo info) { messages.contact(radioId, info); }
            @Override public void advert(ContactInfo info) { messages.advert(radioId, info, System.currentTimeMillis()); }
            @Override public void outgoing(long id, String state, Long ack, long timeoutMs) { trackOutgoing(id, state, ack, timeoutMs); }
            @Override public void confirmed(long ack) {
                PendingAck pending = pendingAcks.remove(ack);
                if (pending != null) { handler.removeCallbacks(pending.expiry()); messages.delivery(pending.messageId(), "delivered"); }
            }
            @Override public void radioLog(RadioLog entry) { radioLogs.setValue(logs.add(entry)); }
            @Override public void packets(Long sent, Long received) {
                packetsSent = sent; packetsReceived = received;
                HelperSnapshot current = snapshot.getValue();
                if (current != null) update(current.state(), current.detail(), current.name(), current.protocolVersion(), current.batteryMillivolts());
            }
        });
        update("disconnected", "Choose a nearby MeshCore companion.", "", null, null);
        server = new StatusServer(key(this), json::get, messages);
        try { server.start(5000, true); available = true; }
        catch (IOException error) { detail = "Cannot open the phone helper port. Stop any other helper and retry."; }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { shutdown(); stopSelf(); return START_NOT_STICKY; }
        if (!available) startHelper();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("ble", "MeshCore BLE connection", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, HelperService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "ble")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("MeshCore G2")
                .setContentText("Radio connection active for your glasses.")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(1, notification);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    public void connect(BluetoothDevice device) {
        if (!available) return;
        logs.clear(); radioLogs.setValue(logs.snapshot());
        companion.connect(device);
    }
    public void disconnect() { companion.disconnect(); }
    public String radioId() { return radioId; }
    public int messageLimit(String kind) { return companion == null ? 0 : companion.messageLimit(kind); }
    public void sendMessage(String kind, String conversation, String text) {
        HelperSnapshot current = snapshot.getValue();
        if (!available || current == null || !current.state().equals("connected")) throw new IllegalStateException("Connect a radio to send messages.");
        String[] pieces = conversation.split(":", 2);
        if (pieces.length != 2 || !pieces[0].equals(radioId)) throw new IllegalArgumentException("Connect the radio for this conversation.");
        long sentAt = Math.max(System.currentTimeMillis() / 1000 * 1000, lastSentAt + 1000);
        OutgoingMessage draft = new OutgoingMessage(0, kind, pieces[1], text.strip(), sentAt);
        draft.encode(messageLimit(kind)); // Validate before adding anything to the outbox.
        long id = messages.outgoing(radioId, kind, draft.peer(), draft.text(), sentAt);
        lastSentAt = sentAt;
        if (!companion.sendMessage(new OutgoingMessage(id, kind, draft.peer(), draft.text(), sentAt))) {
            messages.delivery(id, "failed");
            throw new IllegalStateException("The radio is busy. Try sending again in a moment.");
        }
    }
    private void trackOutgoing(long id, String state, Long ack, long timeoutMs) {
        messages.delivery(id, state);
        if (!state.equals("awaiting_ack") || ack == null) return;
        PendingAck previous = pendingAcks.remove(ack);
        if (previous != null) { handler.removeCallbacks(previous.expiry()); messages.delivery(previous.messageId(), "unconfirmed"); }
        if (pendingAcks.size() >= 64) {
            Long oldest = pendingAcks.keySet().iterator().next();
            PendingAck removed = pendingAcks.remove(oldest);
            handler.removeCallbacks(removed.expiry()); messages.delivery(removed.messageId(), "unconfirmed");
        }
        Runnable expiry = () -> {
            PendingAck pending = pendingAcks.get(ack);
            if (pending != null && pending.messageId() == id) { pendingAcks.remove(ack); messages.delivery(id, "unconfirmed"); }
        };
        pendingAcks.put(ack, new PendingAck(id, expiry));
        handler.postDelayed(expiry, Math.max(1000, Math.min(600000, timeoutMs)));
    }
    private void interruptOutgoing() {
        for (PendingAck pending : pendingAcks.values()) handler.removeCallbacks(pending.expiry());
        pendingAcks.clear();
        if (messages != null) messages.interruptOutgoing();
    }
    public long lastHudReadAt() { return server == null ? 0 : server.lastHudReadAt(); }
    private void update(String state, String message, String name, Integer version, Integer battery) {
        if (!state.equals("connected")) { packetsSent = null; packetsReceived = null; interruptOutgoing(); }
        detail = message + (name.isEmpty() ? "" : "\n" + name)
                + (battery == null ? "" : "\nBattery: " + battery + " mV");
        JSONObject snapshot = new JSONObject();
        try {
            snapshot.put("schema", 1).put("state", state).put("detail", message).put("name", name)
                    .put("protocolVersion", version == null ? JSONObject.NULL : version)
                    .put("batteryMillivolts", battery == null ? JSONObject.NULL : battery)
                    .put("packetsSent", packetsSent == null ? JSONObject.NULL : packetsSent)
                    .put("packetsReceived", packetsReceived == null ? JSONObject.NULL : packetsReceived);
        } catch (JSONException error) { throw new IllegalStateException(error); }
        json.set(snapshot.toString());
        this.snapshot.setValue(new HelperSnapshot(state, message, name, version, battery));
    }
    private void shutdown() {
        detail = "Helper stopped. Tap Scan to start it again.";
        available = false;
        if (companion != null) companion.dispose();
        interruptOutgoing();
        if (server != null) server.stop();
        snapshot.setValue(new HelperSnapshot("disconnected", "Helper stopped. Scan to reconnect.", "", null, null));
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onDestroy() { shutdown(); if (messages != null) messages.close(); super.onDestroy(); }
}
