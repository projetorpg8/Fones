package com.fonecontrol.app.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Escuta anúncios BLE à procura de bateria por lado.
 *
 * 1) Google Fast Pair (UUID 0xFE2C): especificação pública que inclui um campo de
 *    bateria com esquerdo / direito / estojo. Muitos fones Xiaomi, Redmi, JBL, Sony
 *    e outros com "Fast Pair" mandam isso.
 * 2) Opcionalmente, guarda no log os dados brutos de um fabricante (ex.: Xiaomi 0x038F)
 *    para ajudar a descobrir o formato de modelos que não usam Fast Pair.
 *
 * Como o endereço BLE é aleatório e diferente do endereço Bluetooth clássico,
 * o anúncio mais forte (mais perto) com bateria é considerado o do seu fone.
 */
class BleBatteryScanner(
    private val context: Context,
    private val log: DriverLog,
    private val captureManufacturerId: Int? = null,
) {
    private val _battery = MutableStateFlow<BatteryInfo?>(null)
    val battery: StateFlow<BatteryInfo?> = _battery.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    private val seenRaw = LinkedHashSet<String>()

    private data class Seen(val rssi: Int, val info: BatteryInfo, val time: Long)
    private val recent = HashMap<String, Seen>()

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun hasPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.ACCESS_FINE_LOCATION
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log.add("BLE: falha ao escanear (código $errorCode)")
        }
    }

    @SuppressLint("MissingPermission")
    fun start(durationMs: Long = 25_000) {
        if (scanning) return
        if (!hasPermission()) {
            log.add("BLE: sem permissão de busca de dispositivos próximos")
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: run {
            log.add("BLE: Bluetooth desligado ou sem suporte a BLE")
            return
        }
        val filters = mutableListOf(
            ScanFilter.Builder().setServiceData(FAST_PAIR_UUID, ByteArray(0)).build(),
        )
        captureManufacturerId?.let {
            filters += ScanFilter.Builder().setManufacturerData(it, ByteArray(0)).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(filters, settings, callback)
            scanning = true
            log.add("BLE: procurando anúncios por ${durationMs / 1000}s")
            handler.postDelayed({ stop() }, durationMs)
        } catch (e: Exception) {
            log.add("BLE: erro ao iniciar busca (${e.message})")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
        }
    }

    private fun handle(result: ScanResult) {
        val record = result.scanRecord ?: return

        record.getServiceData(FAST_PAIR_UUID)?.let { data ->
            parseFastPair(data)?.let { info ->
                val now = System.currentTimeMillis()
                recent[result.device.address] = Seen(result.rssi, info, now)
                recent.entries.removeAll { now - it.value.time > 20_000 }
                val best = recent.values.maxByOrNull { it.rssi }
                if (best != null && best.info != _battery.value) {
                    _battery.value = best.info
                    log.add("Fast Pair: L=${best.info.left?.level} R=${best.info.right?.level} " +
                        "estojo=${best.info.case?.level} (RSSI ${best.rssi})")
                }
            }
        }

        captureManufacturerId?.let { id ->
            record.getManufacturerSpecificData(id)?.let { data ->
                val hex = data.toHex()
                if (seenRaw.add(hex)) {
                    if (seenRaw.size > 30) seenRaw.remove(seenRaw.first())
                    log.add("Bruto 0x%04X (RSSI %d): %s".format(id, result.rssi, hex))
                }
            }
        }
    }

    companion object {
        val FAST_PAIR_UUID: ParcelUuid = ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

        /**
         * Formato do anúncio "não descobrível" do Fast Pair:
         * byte 0 = versão/flags; depois campos com cabeçalho 0bLLLLTTTT
         * (L = tamanho, T = tipo). Tipo 3 ou 4 = bateria, com 3 bytes:
         * esquerdo, direito, estojo — cada um 0bSVVVVVVV (S = carregando, V = 0..100, 127 = desconhecido).
         * Anúncio de 3 bytes é só o Model ID (modo de pareamento) e não traz bateria.
         */
        fun parseFastPair(data: ByteArray): BatteryInfo? {
            if (data.size <= 3) return null
            var i = 1
            while (i < data.size) {
                val header = data[i].u()
                val len = (header shr 4) and 0x0F
                val type = header and 0x0F
                i++
                if ((type == 3 || type == 4) && len == 3 && i + 3 <= data.size) {
                    fun side(b: Byte): SideBattery? {
                        val v = b.u() and 0x7F
                        return if (v > 100) null else SideBattery(v, charging = (b.u() and 0x80) != 0)
                    }
                    val left = side(data[i])
                    val right = side(data[i + 1])
                    val case = side(data[i + 2])
                    if (left == null && right == null && case == null) return null
                    return BatteryInfo(left, right, case, source = "Google Fast Pair (BLE)")
                }
                i += len
            }
            return null
        }
    }
}
