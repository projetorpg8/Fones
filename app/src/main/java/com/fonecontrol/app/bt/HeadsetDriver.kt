package com.fonecontrol.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Um driver sabe ler a bateria de uma família de fones.
 * Para suportar uma marca nova, crie outra implementação e registre em [DriverFactory].
 */
interface HeadsetDriver {
    /** Nome amigável do driver (aparece na tela). */
    val label: String
    val battery: StateFlow<BatteryInfo>
    val log: StateFlow<List<String>>

    fun start()
    fun refresh()
    fun stop()
}

/** Pequeno log em memória para a seção "Diagnóstico" da tela. */
class DriverLog(private val max: Int = 60) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        _lines.update { (listOf(line) + it).take(max) }
    }
}

object DriverFactory {
    @SuppressLint("MissingPermission")
    fun create(context: Context, device: BluetoothDevice): HeadsetDriver {
        val name = (try { device.name } catch (_: SecurityException) { null }).orEmpty().lowercase()
        return when {
            name.contains("galaxy buds") || (name.startsWith("buds") && name.contains("samsung")) ->
                SamsungDriver(context, device)

            name.contains("redmi") || name.contains("xiaomi") || name.contains("mi true wireless") ||
                name.contains("airdots") || name.contains("mi buds") ->
                XiaomiDriver(context, device)

            else -> GenericDriver(context, device)
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
internal fun Byte.u(): Int = toInt() and 0xFF
