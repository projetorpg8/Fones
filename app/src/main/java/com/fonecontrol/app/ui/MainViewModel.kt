package com.fonecontrol.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fonecontrol.app.audio.BalanceController
import com.fonecontrol.app.audio.MicRouter
import com.fonecontrol.app.audio.MicRoutingService
import com.fonecontrol.app.bt.DriverFactory
import com.fonecontrol.app.bt.HeadsetDriver
import com.fonecontrol.app.bt.HeadsetMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HeadsetUi(val address: String, val name: String)

data class VolumeUi(
    val left: Int = 50,
    val right: Int = 50,
    val linked: Boolean = true,
    val supported: Boolean = true,
    val hasPermission: Boolean = false,
    val message: String? = null,
    val debug: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    private val monitor = HeadsetMonitor(app)
    private val balance = BalanceController(app)
    private val micRouter = MicRouter(app)

    private val _permissionsOk = MutableStateFlow(false)
    val permissionsOk: StateFlow<Boolean> = _permissionsOk.asStateFlow()

    private val _headsets = MutableStateFlow<List<HeadsetUi>>(emptyList())
    val headsets: StateFlow<List<HeadsetUi>> = _headsets.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _driver = MutableStateFlow<HeadsetDriver?>(null)
    val driver: StateFlow<HeadsetDriver?> = _driver.asStateFlow()

    private val _volume = MutableStateFlow(VolumeUi())
    val volume: StateFlow<VolumeUi> = _volume.asStateFlow()

    private val _forceAllApps = MutableStateFlow(false)
    val forceAllApps: StateFlow<Boolean> = _forceAllApps.asStateFlow()

    val micActive: StateFlow<Boolean> = MicRoutingService.active
    val micError: StateFlow<String?> = MicRoutingService.lastError

    private val _headsetMicAvailable = MutableStateFlow(false)
    val headsetMicAvailable: StateFlow<Boolean> = _headsetMicAvailable.asStateFlow()

    private var devicesByAddress: Map<String, BluetoothDevice> = emptyMap()

    init {
        viewModelScope.launch {
            monitor.devices.collect { onDevicesChanged(it) }
        }
    }

    // ---------- Permissões ----------

    fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Chamado no onResume e depois do pedido de permissões. */
    fun refreshAll() {
        val ok = hasBluetoothPermission()
        _permissionsOk.value = ok
        if (ok) {
            monitor.start()
            monitor.refresh()
            _driver.value?.refresh()
        }
        readVolume()
        _headsetMicAvailable.value = runCatching { micRouter.headsetMicAvailable() }.getOrDefault(false)
    }

    // ---------- Fones ----------

    @SuppressLint("MissingPermission")
    private fun onDevicesChanged(devices: List<BluetoothDevice>) {
        devicesByAddress = devices.associateBy { it.address }
        _headsets.value = devices.map {
            val name = (try { it.name } catch (_: SecurityException) { null }) ?: it.address
            HeadsetUi(it.address, name)
        }
        val current = _selected.value
        if (current == null || current !in devicesByAddress) {
            select(devices.firstOrNull()?.address)
        }
        _headsetMicAvailable.value = runCatching { micRouter.headsetMicAvailable() }.getOrDefault(false)
    }

    fun select(address: String?) {
        if (address == _selected.value && _driver.value != null) return
        _driver.value?.stop()
        _selected.value = address
        val device = address?.let { devicesByAddress[it] }
        _driver.value = device?.let { DriverFactory.create(ctx, it).also { d -> d.start() } }
    }

    fun refreshBattery() {
        _driver.value?.refresh()
    }

    // ---------- Volume por lado ----------

    private fun readVolume() {
        val levels = balance.read()
        _volume.update {
            it.copy(
                left = levels.left,
                right = levels.right,
                supported = balance.isSupported,
                hasPermission = balance.hasPermission(),
            )
        }
    }

    fun setLeft(value: Int) = setLevels(value, if (_volume.value.linked) value else _volume.value.right)
    fun setRight(value: Int) = setLevels(if (_volume.value.linked) value else _volume.value.left, value)

    private fun setLevels(left: Int, right: Int) {
        val result = balance.apply(left, right)
        _volume.update { it.copy(left = left, right = right, message = result.error, debug = result.debug) }
    }

    fun setLinked(linked: Boolean) {
        _volume.update { it.copy(linked = linked) }
        if (linked) {
            val v = _volume.value
            val top = maxOf(v.left, v.right)
            setLevels(top, top)
        }
    }

    fun centerBalance() {
        val v = _volume.value
        val top = maxOf(v.left, v.right)
        setLevels(top, top)
    }

    // ---------- Microfone ----------

    fun useHeadsetMic() = MicRoutingService.start(ctx, _forceAllApps.value)

    fun usePhoneMic() = MicRoutingService.stop(ctx)

    fun setForceAllApps(force: Boolean) {
        _forceAllApps.value = force
        if (micActive.value) MicRoutingService.start(ctx, force)
    }

    override fun onCleared() {
        _driver.value?.stop()
        monitor.stop()
        super.onCleared()
    }
}
