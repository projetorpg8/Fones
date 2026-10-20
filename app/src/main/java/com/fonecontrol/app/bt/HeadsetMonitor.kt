package com.fonecontrol.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Mantém a lista de fones de áudio Bluetooth conectados agora. */
class HeadsetMonitor(private val context: Context) {

    private val _devices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val devices: StateFlow<List<BluetoothDevice>> = _devices.asStateFlow()

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var a2dp: BluetoothProfile? = null
    private var headset: BluetoothProfile? = null
    private var started = false

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = proxy
                BluetoothProfile.HEADSET -> headset = proxy
            }
            refresh()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dp = null
                BluetoothProfile.HEADSET -> headset = null
            }
            refresh()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = refresh()
    }

    fun start() {
        if (started) return
        val ad = adapter ?: return
        started = true
        ad.getProfileProxy(context, listener, BluetoothProfile.A2DP)
        ad.getProfileProxy(context, listener, BluetoothProfile.HEADSET)
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    @SuppressLint("MissingPermission")
    fun refresh() {
        val list = try {
            (a2dp?.connectedDevices.orEmpty() + headset?.connectedDevices.orEmpty())
                .distinctBy { it.address }
        } catch (_: SecurityException) {
            emptyList()
        }
        _devices.value = list
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        adapter?.let { ad ->
            a2dp?.let { ad.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headset?.let { ad.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        }
        a2dp = null
        headset = null
    }
}
