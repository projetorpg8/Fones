package com.fonecontrol.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Escolhe o microfone de entrada.
 *
 * Com fone Bluetooth, o microfone do fone só funciona pelo perfil de chamada (HFP/SCO).
 * Enquanto ele estiver ativo, o áudio vira mono e com qualidade de ligação — é uma
 * limitação do próprio Bluetooth clássico, não do app.
 *
 * - Android 12+: AudioManager.setCommunicationDevice()
 * - Android 8–11: startBluetoothSco()
 * - "Forçar para todos os apps": coloca o sistema em modo de comunicação, para que
 *   gravadores e apps que não entram em modo de chamada também usem o mic do fone.
 */
class MicRouter(context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var forcedMode = false

    /** Existe um fone com microfone disponível agora? */
    fun headsetMicAvailable(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) findHeadsetDevice() != null
        else audio.isBluetoothScoAvailableOffCall

    /** @return true se o pedido foi aceito pelo sistema. */
    fun useHeadsetMic(forceAllApps: Boolean): Boolean {
        if (forceAllApps) {
            runCatching { audio.mode = AudioManager.MODE_IN_COMMUNICATION }
            forcedMode = true
        } else if (forcedMode) {
            runCatching { audio.mode = AudioManager.MODE_NORMAL }
            forcedMode = false
        }
        return if (Build.VERSION.SDK_INT >= 31) {
            val dev = findHeadsetDevice() ?: return false
            audio.setCommunicationDevice(dev)
        } else {
            @Suppress("DEPRECATION")
            run {
                audio.startBluetoothSco()
                audio.isBluetoothScoOn = true
            }
            true
        }
    }

    fun usePhoneMic() {
        if (Build.VERSION.SDK_INT >= 31) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            run {
                audio.isBluetoothScoOn = false
                audio.stopBluetoothSco()
            }
        }
        if (forcedMode) {
            runCatching { audio.mode = AudioManager.MODE_NORMAL }
            forcedMode = false
        }
    }

    private fun findHeadsetDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < 31) return null
        return audio.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }
}
