package com.fonecontrol.app.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Galaxy Buds: conversa com o fone pelo mesmo canal RFCOMM que o app Galaxy Wearable usa.
 * Protocolo documentado pelo projeto open source GalaxyBudsClient (ThePBone).
 *
 * Quadro (Buds+, Live, Pro, Buds2, Buds2 Pro, FE, Buds3…):
 *   0xFD | cabeçalho (2 bytes, little-endian, 10 bits = tamanho) | id | payload | CRC16 (2) | 0xDD
 * Quadro (Galaxy Buds original, 2019):
 *   0xFE | tipo | tamanho | id | payload | CRC16 (2) | 0xEE
 * "tamanho" conta id + payload + CRC.
 *
 * Mensagens usadas:
 *   0x60 STATUS_UPDATED          payload[1]=L, [2]=R, [6]=estojo
 *   0x61 EXTENDED_STATUS_UPDATED payload[2]=L, [3]=R, [7]=estojo
 *
 * Atenção: se o app Galaxy Wearable estiver conectado ao fone, ele pode ocupar o canal.
 */
class SamsungDriver(context: Context, device: BluetoothDevice) :
    GenericDriver(context, device, label = "Samsung Galaxy Buds", useBle = false) {

    private var socket: BluetoothSocket? = null
    private var job: Job? = null

    private val isLegacyModel: Boolean
        @SuppressLint("MissingPermission")
        get() {
            val name = (try { device.name } catch (_: SecurityException) { null }).orEmpty()
            // O primeiro Galaxy Buds se chama apenas "Galaxy Buds (XXXX)".
            return name.startsWith("Galaxy Buds (")
        }

    override fun start() {
        super.start()
        connect()
    }

    override fun refresh() {
        super.refresh()
        if (socket?.isConnected != true) connect()
    }

    override fun stop() {
        job?.cancel()
        runCatching { socket?.close() }
        socket = null
        super.stop()
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        if (job?.isActive == true) return
        job = scope.launch {
            val order = if (isLegacyModel) listOf(UUID_LEGACY, UUID_NEW) else listOf(UUID_NEW, UUID_LEGACY)
            for (uuid in order) {
                val legacy = uuid == UUID_LEGACY
                val connected = withContext(Dispatchers.IO) {
                    try {
                        context.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery()
                        val s = device.createRfcommSocketToServiceRecord(uuid)
                        s.connect()
                        socket = s
                        true
                    } catch (e: IOException) {
                        driverLog.add("RFCOMM ${if (legacy) "antigo" else "novo"}: falhou (${e.message})")
                        false
                    } catch (e: SecurityException) {
                        driverLog.add("RFCOMM: sem permissão de Bluetooth")
                        false
                    }
                }
                if (connected) {
                    driverLog.add("Conectado ao canal da Samsung (${if (legacy) "Buds 2019" else "Buds+ ou mais novo"})")
                    _battery.update { it.copy(source = "Protocolo Samsung (RFCOMM)") }
                    val s = socket ?: return@launch
                    withContext(Dispatchers.IO) { readLoop(s.inputStream, legacy) }
                    driverLog.add("Canal da Samsung fechado")
                    return@launch
                }
            }
            driverLog.add("Dica: feche o app Galaxy Wearable e toque em Atualizar")
        }
    }

    private suspend fun readLoop(input: InputStream, legacy: Boolean) {
        val chunk = ByteArray(1024)
        var pending = ByteArray(0)
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = input.read(chunk)
                if (n < 0) break
                pending += chunk.copyOfRange(0, n)
                val consumed = parseFrames(pending, legacy)
                pending = pending.copyOfRange(consumed, pending.size)
                if (pending.size > 4096) pending = ByteArray(0)
            }
        } catch (_: IOException) {
            // socket fechado
        }
    }

    /** Lê todos os quadros completos e retorna quantos bytes foram consumidos. */
    private fun parseFrames(data: ByteArray, legacy: Boolean): Int {
        val som = if (legacy) 0xFE else 0xFD
        val eom = if (legacy) 0xEE else 0xDD
        var i = 0
        while (true) {
            while (i < data.size && data[i].u() != som) i++
            if (i + 3 > data.size) return i
            val size = if (legacy) data[i + 2].u() else (data[i + 1].u() or (data[i + 2].u() shl 8)) and 0x3FF
            if (size < 3 || size > 1000) { i++; continue }
            val total = 3 + size + 1
            if (i + total > data.size) return i
            if (data[i + total - 1].u() != eom) { i++; continue }
            val id = data[i + 3].u()
            val payload = data.copyOfRange(i + 4, i + 3 + size - 2)
            handleMessage(id, payload, legacy)
            i += total
        }
    }

    private fun handleMessage(id: Int, p: ByteArray, legacy: Boolean) {
        fun pct(index: Int): SideBattery? =
            if (index < p.size && p[index].u() in 0..100) SideBattery(p[index].u()) else null

        when (id) {
            MSG_EXTENDED_STATUS -> {
                driverLog.add("Status estendido: ${p.take(12).toByteArray().toHex()}")
                updateSides(pct(2), pct(3), if (legacy) null else pct(7))
            }
            MSG_STATUS -> updateSides(pct(1), pct(2), if (legacy) null else pct(6))
        }
    }

    private fun updateSides(left: SideBattery?, right: SideBattery?, case: SideBattery?) {
        if (left == null && right == null) return
        scope.launch {
            _battery.update {
                it.copy(left = left, right = right, case = case ?: it.case, source = "Protocolo Samsung (RFCOMM)")
            }
        }
    }

    companion object {
        val UUID_NEW: UUID = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2")
        val UUID_LEGACY: UUID = UUID.fromString("00001102-0000-1000-8000-00805f9b34fd")
        const val MSG_STATUS = 0x60
        const val MSG_EXTENDED_STATUS = 0x61
    }
}
