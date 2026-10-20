package com.fonecontrol.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.fonecontrol.app.MainActivity
import com.fonecontrol.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mantém o microfone do fone ativo mesmo com o app fechado.
 * O Android desfaz a escolha de microfone quando o app que pediu morre,
 * por isso ela fica presa a um serviço em primeiro plano (com notificação).
 */
class MicRoutingService : Service() {

    private lateinit var router: MicRouter

    override fun onCreate() {
        super.onCreate()
        router = MicRouter(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val force = intent?.getBooleanExtra(EXTRA_FORCE_ALL, false) ?: false
                startInForeground(force)
                val ok = router.useHeadsetMic(force)
                _active.value = ok
                _lastError.value = if (ok) null else "Nenhum microfone de fone disponível agora"
                if (!ok) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(force: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Microfone do fone", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MicRoutingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Microfone do fone ativo")
            .setContentText(if (force) "Forçado para todos os apps" else "Usado em chamadas e apps de voz")
            .setContentIntent(open)
            .addAction(0, "Usar mic do celular", stop)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onDestroy() {
        router.usePhoneMic()
        _active.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "mic_routing"
        private const val NOTIFICATION_ID = 42
        const val ACTION_START = "com.fonecontrol.app.MIC_START"
        const val ACTION_STOP = "com.fonecontrol.app.MIC_STOP"
        const val EXTRA_FORCE_ALL = "force_all"

        private val _active = MutableStateFlow(false)
        val active: StateFlow<Boolean> = _active.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        fun start(context: Context, forceAllApps: Boolean) {
            val intent = Intent(context, MicRoutingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FORCE_ALL, forceAllApps)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MicRoutingService::class.java))
        }
    }
}
