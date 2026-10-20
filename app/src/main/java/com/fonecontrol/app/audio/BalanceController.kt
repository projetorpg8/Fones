package com.fonecontrol.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Volume separado por lado.
 *
 * O Android não tem API pública para isso, então o app combina duas coisas:
 *  - volume de mídia do sistema = o maior dos dois lados;
 *  - "balanço de áudio" do sistema (o mesmo da Acessibilidade) para abaixar o lado menor.
 *
 * O balanço fica em Settings.System "master_balance" (-1 = só esquerdo, +1 = só direito)
 * e exige a permissão WRITE_SECURE_SETTINGS, concedida uma única vez pelo ADB.
 */
class BalanceController(private val context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    data class Levels(val left: Int, val right: Int)

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun readBalance(): Float {
        val value = try {
            Settings.System.getFloat(context.contentResolver, KEY_MASTER_BALANCE, 0f)
        } catch (_: Exception) {
            0f
        }
        return value.coerceIn(-1f, 1f)
    }

    /** Lê o estado atual (volume de mídia + balanço) e converte para esquerdo/direito 0..100. */
    fun read(): Levels {
        val maxIndex = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxIndex
        val b = if (isSupported) readBalance() else 0f
        return if (b >= 0f) {
            Levels(left = (volume * (1f - b)).roundToInt(), right = volume)
        } else {
            Levels(left = volume, right = (volume * (1f + b)).roundToInt())
        }
    }

    data class ApplyResult(val error: String?, val debug: String)

    /**
     * Aplica níveis 0..100 para cada lado e devolve o que o sistema realmente fez,
     * para a linha de diagnóstico da tela.
     */
    fun apply(left: Int, right: Int, setVolume: Boolean = true): ApplyResult {
        val l = left.coerceIn(0, 100)
        val r = right.coerceIn(0, 100)
        val top = max(l, r)
        val dbg = StringBuilder()
        var error: String? = null

        if (setVolume) {
            val maxIndex = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val minIndex = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
            val index = (top / 100f * maxIndex).roundToInt().coerceIn(minIndex, maxIndex)
            val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            try {
                if (index != before) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
                }
            } catch (e: SecurityException) {
                error = "O sistema bloqueou a mudança de volume (modo Não perturbe?)"
            }
            val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            dbg.append("Volume mídia: pedido $index/$maxIndex, antes $before, agora $after")
            if (error == null && after != index) {
                error = "O Android não aceitou a mudança de volume (pedido $index, ficou $after)"
            }
        }

        dbg.append(" · modo de áudio ${modeName(audio.mode)}")

        if (!isSupported) return ApplyResult(error ?: "Volume por lado precisa de Android 10 ou mais novo", dbg.toString())
        if (!hasPermission()) {
            dbg.append(" · balanço: sem permissão")
            return ApplyResult(error ?: "Falta liberar a permissão pelo ADB (veja abaixo)", dbg.toString())
        }

        val balance = when {
            top == 0 -> 0f
            r >= l -> 1f - l.toFloat() / r
            else -> -(1f - r.toFloat() / l)
        }
        try {
            val ok = Settings.System.putFloat(context.contentResolver, KEY_MASTER_BALANCE, balance)
            val read = readBalance()
            dbg.append(" · balanço: gravado %.2f (ok=%s), lido %.2f".format(balance, ok, read))
            if (error == null && (!ok || kotlin.math.abs(read - balance) > 0.01f)) {
                error = "O Android não aceitou o balanço esquerda/direita"
            }
        } catch (e: Exception) {
            dbg.append(" · balanço: erro ${e.javaClass.simpleName}")
            if (error == null) error = "Não foi possível alterar o balanço: ${e.message}"
        }
        return ApplyResult(error, dbg.toString())
    }

    private fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_IN_CALL -> "em ligação"
        AudioManager.MODE_IN_COMMUNICATION -> "comunicação (VoIP)"
        AudioManager.MODE_RINGTONE -> "toque"
        else -> mode.toString()
    }

    fun resetBalance() {
        if (isSupported && hasPermission()) {
            runCatching { Settings.System.putFloat(context.contentResolver, KEY_MASTER_BALANCE, 0f) }
        }
    }

    companion object {
        const val KEY_MASTER_BALANCE = "master_balance"
        const val ADB_COMMAND =
            "adb shell pm grant com.fonecontrol.app android.permission.WRITE_SECURE_SETTINGS"
    }
}
