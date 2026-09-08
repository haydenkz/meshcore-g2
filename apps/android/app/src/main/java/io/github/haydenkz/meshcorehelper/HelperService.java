package io.github.haydenkz.meshcorehelper;

import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.io.IOException;
import java.security.SecureRandom;
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
    private Long packetsSent;
    private Long packetsReceived;
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
        startHelper();
    }
    private void startHelper() {
        available = false;
        if (server != null) server.stop();
        if (companion != null) companion.dispose();
        companion = new BleCompanion(this, new BleCompanion.Listener() {
            @Override public void update(String state, String detail, String name, Integer version, Integer battery) { HelperService.this.update(state, detail, name, version, battery); }
            @Override public void radio(String id) { radioId = id; }
            @Override public void message(ReceivedMessage message) { messages.add(radioId, message); }
            @Override public void channel(int index, String name) { messages.name(radioId, "channel", Integer.toString(index), name); }
            @Override public void contact(String prefix, String name) { messages.name(radioId, "direct", prefix, name); }
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
                .setContentTitle("MeshCore G2 Helper")
                .setContentText("Keeps the radio connection available to the HUD on this phone.")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(1, notification);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    public void connect(BluetoothDevice device) { if (available) companion.connect(device); }
    public void disconnect() { companion.disconnect(); }
    public String diagnostics() { return server == null ? "Local helper not running." : server.diagnostics(); }
    public long lastHudReadAt() { return server == null ? 0 : server.lastHudReadAt(); }
    private void update(String state, String message, String name, Integer version, Integer battery) {
        if (!state.equals("connected")) { packetsSent = null; packetsReceived = null; }
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
        if (server != null) server.stop();
        snapshot.setValue(new HelperSnapshot("disconnected", "Helper stopped. Scan to reconnect.", "", null, null));
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onDestroy() { shutdown(); if (messages != null) messages.close(); super.onDestroy(); }
}
