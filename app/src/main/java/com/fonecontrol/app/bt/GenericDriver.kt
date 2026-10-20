package com.fonecontrol.app.bt

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Funciona com qualquer fone: bateria geral do Android + tentativa de ler
 * bateria por lado via Google Fast Pair (quando o fone anuncia).
 */
open class GenericDriver(
    protected val context: Context,
    protected val device: BluetoothDevice,
    override val label: String = "Genérico",
    private val useBle: Boolean = true,
    captureManufacturerId: Int? = null,
) : HeadsetDriver {

    protected val driverLog = DriverLog()
    override val log: StateFlow<List<String>> = driverLog.lines

    protected val _battery = MutableStateFlow(BatteryInfo(source = "Aguardando o fone…"))
    override val battery: StateFlow<BatteryInfo> = _battery.asStateFlow()

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val scanner = BleBatteryScanner(context, driverLog, captureManufacturerId)
    private var receiver: BroadcastReceiver? = null

    override fun start() {
        driverLog.add("Driver \"$label\" iniciado")
        try {
            receiver = SystemBattery.listen(context, device) { level ->
                driverLog.add("Android: bateria geral $level%")
                setOverall(level)
            }
        } catch (e: Exception) {
            driverLog.add("Não foi possível escutar a bateria do sistema (${e.message})")
        }
        readSystem()

        if (useBle) {
            scope.launch {
                scanner.battery.collect { fp ->
                    if (fp != null) {
                        _battery.update {
                            it.copy(left = fp.left, right = fp.right, case = fp.case, source = fp.source)
                        }
                    }
                }
            }
            scanner.start()
        }
    }

    override fun refresh() {
        readSystem()
        if (useBle) scanner.start()
    }

    override fun stop() {
        scanner.stop()
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        scope.cancel()
    }

    private fun readSystem() {
        val level = SystemBattery.read(device)
        if (level != null) setOverall(level) else driverLog.add("Android não informou bateria para este fone")
    }

    private fun setOverall(level: Int) {
        _battery.update {
            it.copy(
                overall = level,
                source = if (it.hasSides) it.source else "Android (valor único do fone)",
            )
        }
    }
}
