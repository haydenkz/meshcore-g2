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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.provider.Settings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.haydenkz.meshcorehelper.ui.HelperScreen
import io.github.haydenkz.meshcorehelper.ui.MeshCoreTheme

class MainActivity : ComponentActivity() {
    private val model: HelperViewModel by viewModels()
    private val inboxModel: InboxViewModel by viewModels()
    private var notificationsEnabled by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = MessageNotifications.enabled(this)
        if (!it) model.showNotice("Message alerts are off. You can enable them in notification settings.")
    }
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
        MessageNotifications.createChannels(this)
        if (savedInstanceState == null) openNotification(intent)
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val inbox by inboxModel.state.collectAsStateWithLifecycle()
            LifecycleResumeEffect(inbox.conversation?.id, inbox.conversation?.kind) {
                MessageNotifications.visible(this@MainActivity, inbox.conversation?.kind, inbox.conversation?.id)
                onPauseOrDispose { MessageNotifications.visible(this@MainActivity, null, null) }
            }
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
                    notificationsEnabled = notificationsEnabled,
                    onNotifications = ::notificationSettings,
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
    private fun notificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false) || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
    private fun openNotification(intent: Intent?) {
        if (intent?.action == MessageNotifications.OPEN_CHAT) inboxModel.openFromNotification(
            intent.getStringExtra(MessageNotifications.KIND), intent.getStringExtra(MessageNotifications.CONVERSATION))
    }
    // Consume each tap immediately; navigation state owns the open chat after that.
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); openNotification(intent) }
    override fun onResume() { super.onResume(); notificationsEnabled = MessageNotifications.enabled(this) }
    override fun onStart() { super.onStart(); model.onVisible(); inboxModel.onVisible() }
    override fun onStop() { model.onHidden(); inboxModel.onHidden(); super.onStop() }
}
