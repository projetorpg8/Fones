package com.fonecontrol.app.bt

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Bateria "geral" que o próprio Android já conhece (via HFP ou BLE Battery Service).
 * O método getBatteryLevel() é oculto no SDK, mas existe em todos os Androids 8+,
 * então é chamado por reflexão.
 */
object SystemBattery {
    const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    fun read(device: BluetoothDevice): Int? = try {
        val method = BluetoothDevice::class.java.getMethod("getBatteryLevel")
        val value = method.invoke(device) as Int
        if (value in 0..100) value else null
    } catch (_: Throwable) {
        null
    }

    /** Escuta mudanças da bateria geral. Retorna o receiver para depois chamar unregister. */
    fun listen(context: Context, device: BluetoothDevice, onLevel: (Int) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (dev?.address != device.address) return
                val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                if (level in 0..100) onLevel(level)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_BATTERY_LEVEL_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        return receiver
    }
}
