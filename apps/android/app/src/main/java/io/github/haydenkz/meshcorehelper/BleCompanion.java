package io.github.haydenkz.meshcorehelper;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;

/** All mutable state and GATT sequencing live on the main thread. */
@SuppressLint("MissingPermission") // Activity grants Bluetooth permissions before starting the service.
public final class BleCompanion {
    public interface Listener {
        void update(String state, String detail, String name, Integer version, Integer battery);
        default void radio(String id) {}
        default void message(ReceivedMessage message) {}
        default void channel(int index, String name) {}
        default void contact(String prefix, String name) {}
        default void packets(Long sent, Long received) {}
    }
    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothDevice device;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private CompanionProtocol.Handshake handshake;
    private MessageSync messageSync;
    private final Runnable syncPoll = () -> { if (messageSync != null) messageSync.poll(); };
    private Runnable timeout;
    private String name = "";
    private boolean pairing;
    private boolean disposed;
    private int negotiatedMtu = 23;
    private boolean discoveryStarted;

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction()) || !pairing || device == null) return;
            BluetoothDevice changed = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                    : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (!device.equals(changed)) return;
            // Read the real bond state rather than trusting an exported broadcast payload.
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                pairing = false;
                openGatt();
            } else if (device.getBondState() == BluetoothDevice.BOND_NONE) {
                fail("Pairing was cancelled or rejected. Retry and enter the radio's PIN.");
            }
        }
    };

    public BleCompanion(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(bondReceiver, filter);
    }
    public void connect(BluetoothDevice selected) {
        if (disposed) return;
        closeConnection();
        device = selected;
        name = selected.getName() == null ? "MeshCore companion" : selected.getName();
        try {
            if (selected.getBondState() != BluetoothDevice.BOND_BONDED) {
                pairing = true;
                state("pairing", "Complete the Android pairing prompt using your radio's PIN.");
                armTimeout(60000, "Pairing timed out. Dismiss the pairing prompt and retry.");
                if (selected.getBondState() == BluetoothDevice.BOND_NONE && !selected.createBond()) {
                    fail("Android could not start pairing. Check Bluetooth and retry.");
                }
            } else openGatt();
        } catch (SecurityException error) {
            fail("Bluetooth permission was removed. Grant Nearby devices and retry.");
        }
    }
    private void openGatt() {
        state("connecting", "Connecting to the companion…");
        armTimeout(15000, "Connection timed out. Keep the radio nearby and disconnect other MeshCore apps.");
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) fail("Android could not open a BLE connection.");
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int status, int newState) {
            handler.post(() -> {
                if (connection != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Radio disconnected (BLE status " + status + "). Select it again to reconnect.");
                } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                    state("connecting", "Negotiating the BLE packet size…");
                    armTimeout(10000, "BLE packet-size negotiation timed out.");
                    if (!connection.requestMtu(247)) fail("Could not negotiate the BLE packet size.");
                }
            });
        }
        @Override public void onMtuChanged(BluetoothGatt connection, int mtu, int status) {
            handler.post(() -> {
                if (connection != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu;
                if (discoveryStarted) return; // An unsolicited MTU update must not restart the handshake.
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("BLE packet-size negotiation failed (status " + status + "). Disconnect other radio apps and retry.");
                    return;
                }
                if (!CompanionProtocol.supportsHandshakeMtu(mtu)) {
                    fail("BLE packet size is " + mtu + "; reading this radio requires at least 93. Disconnect other radio apps, restart Bluetooth, and retry.");
                    return;
                }
                discoveryStarted = true;
                state("discovering", "Finding the MeshCore service…");
                armTimeout(10000, "Service discovery timed out.");
                if (!connection.discoverServices()) fail("Could not discover the radio's BLE services.");
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt connection, int status) {
            handler.post(() -> {
                if (connection != gatt) return;
                BluetoothGattService service = connection.getService(CompanionProtocol.SERVICE);
                if (status != BluetoothGatt.GATT_SUCCESS || service == null) {
                    fail("The selected device does not expose the MeshCore companion service."); return;
                }
                rx = service.getCharacteristic(CompanionProtocol.RX);
                BluetoothGattCharacteristic tx = service.getCharacteristic(CompanionProtocol.TX);
                if (rx == null || tx == null || (rx.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                        || (tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) {
                    fail("The radio is missing the required BLE write/notification characteristics."); return;
                }
                BluetoothGattDescriptor ccc = tx.getDescriptor(CompanionProtocol.CCC);
                if (ccc == null || !connection.setCharacteristicNotification(tx, true)) {
                    fail("Could not enable radio notifications."); return;
                }
                state("subscribing", "Enabling radio replies…");
                armTimeout(10000, "Enabling radio notifications timed out. Check pairing and retry.");
                boolean started;
                if (Build.VERSION.SDK_INT >= 33) {
                    started = connection.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
                } else {
                    ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    started = connection.writeDescriptor(ccc);
                }
                if (!started) fail("Android could not subscribe to radio replies.");
            });
        }
        @Override public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> {
                if (connection != gatt || !descriptor.getUuid().equals(CompanionProtocol.CCC)) return;
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Notification subscription failed. Check the radio's pairing PIN."); return; }
                state("initializing", "Reading radio identity and battery…");
                handshake = new CompanionProtocol.Handshake(BleCompanion.this::write,
                        identity -> {
                            disarmTimeout();
                            String radioId = handshake.radioId();
                            int channels = handshake.channels();
                            handshake = null;
                            listener.radio(radioId);
                            if (!identity.name().isBlank()) name = identity.name();
                            listener.update("connected", "BLE companion connected (MTU " + negotiatedMtu + ").", name, identity.protocolVersion(), identity.batteryMillivolts());
                            messageSync = new MessageSync(BleCompanion.this::write, new MessageSync.Listener() {
                                @Override public void message(ReceivedMessage message) { listener.message(message); }
                                @Override public void channel(int index, String name) { listener.channel(index, name); }
                                @Override public void contact(String prefix, String name) { listener.contact(prefix, name); }
                                @Override public void packets(Long sent, Long received) { listener.packets(sent, received); }
                                @Override public void idle() {
                                    disarmTimeout();
                                    handler.removeCallbacks(syncPoll);
                                    handler.postDelayed(syncPoll, 5000);
                                }
                            }, BleCompanion.this::fail, channels, identity.protocolVersion() >= 8);
                            messageSync.start();
                        }, BleCompanion.this::fail);
                handshake.start();
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
            handler.post(() -> {
                if (connection == gatt && characteristic.getUuid().equals(CompanionProtocol.RX)) {
                    if (handshake != null) handshake.onWrite(status == BluetoothGatt.GATT_SUCCESS);
                    else if (messageSync != null) messageSync.onWrite(status == BluetoothGatt.GATT_SUCCESS);
                }
            });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value) {
            receive(connection, characteristic, value);
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
            // Android 13+ uses the overload with an immutable value parameter.
            if (Build.VERSION.SDK_INT < 33) receive(connection, characteristic, characteristic.getValue());
        }
    };
    private void receive(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (value == null || !characteristic.getUuid().equals(CompanionProtocol.TX)) return;
        byte[] copy = Arrays.copyOf(value, value.length);
        handler.post(() -> {
            if (connection != gatt) return;
            try {
                if (handshake != null) handshake.onFrame(copy);
                else if (messageSync != null) {
                    if (copy.length > 0 && (copy[0] == 2 || copy[0] == 3)) armTimeout(10000, "Reading radio contacts timed out. Reconnect and retry.");
                    messageSync.onFrame(copy);
                }
            } catch (IllegalArgumentException error) {
                fail("The radio returned incomplete message information. Reconnect and retry.");
            } catch (android.database.sqlite.SQLiteException error) {
                fail("Could not save incoming messages. Check free phone storage and reconnect.");
            }
        });
    }
    private void write(byte[] frame) {
        handler.removeCallbacks(syncPoll);
        armTimeout(10000, messageSync == null ? "The radio did not complete the MeshCore handshake. Check firmware and retry." : "Reading radio messages timed out. Reconnect and retry.");
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) {
            started = gatt.writeCharacteristic(rx, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
        } else {
            rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            rx.setValue(frame);
            started = gatt.writeCharacteristic(rx);
        }
        if (!started) fail("Android could not write to the companion.");
    }
    private void state(String state, String detail) { listener.update(state, detail, name, null, null); }
    private void fail(String detail) { closeConnection(); state("error", detail); }
    public void disconnect() { closeConnection(); state("disconnected", "Companion disconnected."); }
    private void closeConnection() {
        disarmTimeout();
        pairing = false;
        device = null;
        if (handshake != null) handshake.cancel();
        handshake = null;
        if (messageSync != null) messageSync.cancel();
        messageSync = null;
        handler.removeCallbacks(syncPoll);
        BluetoothGatt previous = gatt;
        gatt = null;
        rx = null;
        negotiatedMtu = 23;
        discoveryStarted = false;
        if (previous != null) {
            try { previous.disconnect(); } catch (SecurityException ignored) { /* Still release the client. */ }
            previous.close();
        }
    }
    private void armTimeout(long delay, String message) {
        disarmTimeout();
        timeout = () -> fail(message);
        handler.postDelayed(timeout, delay);
    }
    private void disarmTimeout() {
        if (timeout != null) handler.removeCallbacks(timeout);
        timeout = null;
    }
    public void dispose() {
        if (disposed) return;
        disposed = true;
        closeConnection();
        context.unregisterReceiver(bondReceiver);
    }
}
