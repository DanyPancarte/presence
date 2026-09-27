package app.murmure.voice

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.murmure.MainActivity
import app.murmure.R

/**
 * Service de premier plan « micro » : garde l'accès au micro si l'écran se verrouille
 * ou si l'on change d'app pendant une dictée. La capture elle-même reste dans la session.
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFFFFB8A0.toInt())
            .setContentTitle("Murmure t'écoute")
            .setContentText("Dictée en cours · touche pour revenir")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(ID, n)
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "recording"
        private const val ID = 77

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RecordingService::class.java)) }
        }
    }
}
