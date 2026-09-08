package io.github.haydenkz.meshcorehelper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NearbyRadio(val address: String, val name: String, val rssi: Int)
data class HelperUiState(
    val radio: HelperSnapshot = HelperSnapshot("disconnected", "Your radio, connected to your glasses.", "", null, null),
    val running: Boolean = false,
    val scanning: Boolean = false,
    val devices: List<NearbyRadio> = emptyList(),
    val notice: String? = null,
    val diagnostics: String = "The helper starts when you scan for a radio.",
    val hudLinked: Boolean = false,
)

/** Owns discovery and service binding across Compose recomposition and rotation. */
@SuppressLint("MissingPermission")
class HelperViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val mutableState = MutableStateFlow(HelperUiState())
    val state = mutableState.asStateFlow()
    private var helper: HelperService? = null
    private var bound = false
    private var visible = false
    private var scanOnBind = false
    private var scanner: BluetoothLeScanner? = null
    private var scanDeadline: Job? = null
    private var observation: Job? = null
    private val devices = mutableMapOf<String, BluetoothDevice>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!visible) { releaseBinding(); return }
            val service = (binder as HelperService.LocalBinder).service()
            helper = service
            observation?.cancel()
            observation = viewModelScope.launch {
                launch { service.snapshot.asFlow().collect { radio ->
                    mutableState.update { it.copy(radio = radio, running = service.available) }
                } }
                while (true) {
                    val lastRead = service.lastHudReadAt()
                    mutableState.update { it.copy(
                        running = service.available,
                        diagnostics = service.diagnostics(),
                        hudLinked = service.available && lastRead > 0 && System.currentTimeMillis() - lastRead < 15000,
                    ) }
                    delay(1000)
                }
            }
            if (scanOnBind && service.available) beginScan()
            scanOnBind = false
        }
        override fun onServiceDisconnected(name: ComponentName) {
            helper = null
            observation?.cancel()
            showStoppedHelper()
        }
    }

    fun onVisible() {
        visible = true
        if (!bound) {
            bound = app.bindService(Intent(app, HelperService::class.java), connection, 0)
            if (!bound) showStoppedHelper()
        }
    }
    fun onHidden() {
        visible = false
        stopScan()
        releaseBinding()
    }
    fun showNotice(message: String) { mutableState.update { it.copy(notice = message) } }
    fun dismissNotice() { mutableState.update { it.copy(notice = null) } }
    private fun showStoppedHelper() {
        mutableState.update { it.copy(
            running = false, hudLinked = false,
            radio = HelperSnapshot("disconnected", "Find a radio to start your connection.", "", null, null),
        ) }
    }

    fun scan() {
        if (!hasBluetoothPermissions()) { showNotice("Allow Nearby devices to find your companion."); return }
        if (helper?.available == true) { beginScan(); return }
        releaseBinding()
        scanOnBind = true
        val intent = Intent(app, HelperService::class.java)
        try {
            app.startForegroundService(intent)
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) showNotice("Could not start the helper. Close the app and retry.")
        } catch (error: RuntimeException) {
            scanOnBind = false
            showNotice("Could not start Bluetooth. Check permissions and try again.")
        }
    }
    private fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun beginScan() {
        stopScan()
        devices.clear()
        mutableState.update { it.copy(devices = emptyList(), scanning = true, notice = null) }
        try {
            scanner = app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            val active = scanner ?: run {
                stopScan(); showNotice("Turn on Bluetooth to find your companion."); return
            }
            active.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(CompanionProtocol.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback,
            )
            scanDeadline = viewModelScope.launch { delay(10000); stopScan() }
        } catch (error: RuntimeException) {
            stopScan(); showNotice("Could not scan. Check Bluetooth and Nearby devices permission.")
        }
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) = addDevice(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::addDevice) }
        override fun onScanFailed(code: Int) {
            viewModelScope.launch { stopScan(); showNotice("Scan stopped ($code). Wait a moment and retry.") }
        }
    }
    private fun addDevice(result: ScanResult) {
        viewModelScope.launch {
            if (!mutableState.value.scanning) return@launch
            val device = result.device
            devices[device.address] = device
            val rawName = result.scanRecord?.deviceName ?: device.name ?: "MeshCore companion"
            val radio = NearbyRadio(device.address, rawName.filterNot { it.isISOControl() }.take(48), result.rssi)
            mutableState.update { state -> state.copy(devices = (state.devices.filterNot { it.address == radio.address } + radio).sortedByDescending { it.rssi }) }
        }
    }
    fun stopScan() {
        scanDeadline?.cancel()
        scanDeadline = null
        if (mutableState.value.scanning) {
            try { scanner?.stopScan(scanCallback) } catch (_: RuntimeException) { /* Adapter/permission changed. */ }
        }
        mutableState.update { it.copy(scanning = false) }
    }
    fun connect(radio: NearbyRadio) {
        val device = devices[radio.address] ?: return
        stopScan()
        dismissNotice()
        helper?.connect(device)
    }
    fun disconnect() { stopScan(); helper?.disconnect() }
    fun stopHelper() {
        stopScan()
        releaseBinding()
        app.stopService(Intent(app, HelperService::class.java))
        mutableState.update { HelperUiState(notice = "Helper stopped.") }
    }
    fun diagnostics(): String = "MeshCore G2 Helper ${BuildConfig.VERSION_NAME}\n" +
        "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
        "${state.value.radio.detail()}\n${state.value.diagnostics}"

    private fun releaseBinding() {
        scanOnBind = false
        observation?.cancel()
        observation = null
        if (bound) app.unbindService(connection)
        bound = false
        helper = null
    }
    override fun onCleared() { onHidden(); super.onCleared() }
}
