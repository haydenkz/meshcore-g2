package io.github.haydenkz.meshcorehelper

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.haydenkz.meshcorehelper.ui.HelperScreen
import io.github.haydenkz.meshcorehelper.ui.MeshCoreTheme

class MainActivity : ComponentActivity() {
    private val model: HelperViewModel by viewModels()
    private val inboxModel: InboxViewModel by viewModels()
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasBluetoothPermissions()) requestScan()
        else model.showNotice("Allow Nearby devices in app settings to discover your radio.")
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (hasBluetoothPermissions() && adapter?.isEnabled == true) requestScan()
        else model.showNotice("Turn on Bluetooth to find your companion.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val inbox by inboxModel.state.collectAsStateWithLifecycle()
            MeshCoreTheme {
                HelperScreen(
                    state = state,
                    onScan = ::requestScan,
                    onStopScan = model::stopScan,
                    onConnect = model::connect,
                    onDisconnect = model::disconnect,
                    onCopyKey = {
                        val clip = ClipData.newPlainText("MeshCore G2 connection key", HelperService.key(this))
                        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                        Toast.makeText(this, "Paste once into MeshCore G2 in the Even App.", Toast.LENGTH_LONG).show()
                    },
                    onDismissNotice = model::dismissNotice,
                    inbox = inbox,
                    onOpenConversation = inboxModel::open,
                    onCloseConversation = inboxModel::closeConversation,
                    onOlderMessages = inboxModel::older,
                    onSendMessage = { conversation, text ->
                        val error = model.sendMessage(conversation.kind, conversation.id, text)
                        if (error == null) inboxModel.refreshNow()
                        error
                    },
                )
            }
        }
    }
    private fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun requestScan() {
        if (!hasBluetoothPermissions()) {
            val requested = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 31) requested.addAll(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
            else requested.add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.launch(requested.toTypedArray())
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { model.showNotice("This phone does not support Bluetooth LE."); return }
        if (!adapter.isEnabled) { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        if (Build.VERSION.SDK_INT < 31) {
            val location = getSystemService(LocationManager::class.java)
            if (!location.isProviderEnabled(LocationManager.GPS_PROVIDER) && !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                model.showNotice("Enable Location in phone settings for BLE discovery on Android 11 and earlier.")
                return
            }
        }
        model.scan()
    }
    override fun onStart() { super.onStart(); model.onVisible(); inboxModel.onVisible() }
    override fun onStop() { model.onHidden(); inboxModel.onHidden(); super.onStop() }
}
