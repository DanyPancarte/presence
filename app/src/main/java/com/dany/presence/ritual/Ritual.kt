package com.dany.presence.ritual

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dany.presence.MainActivity
import java.util.Calendar

/**
 * The ritual. Exact alarms (not WorkManager: a full-screen wake at 17:30 sharp needs the exact
 * clock, WorkManager is allowed ±minutes). Each alarm re-arms the next occurrence when it fires.
 *
 *  - Relances : lun–ven 17:30 et 20:30, dim 10:00, jamais le samedi
 *  - Médicament : tous les jours 08:00, confirmation vocale
 */
object Ritual {
    const val EXTRA_MODE = "mode"
    const val MODE_RELANCE = "relance"
    const val MODE_MED = "med"
    private const val CHANNEL = "presence.rituel"

    fun scheduleAll(ctx: Context) {
        schedule(ctx, MODE_RELANCE)
        schedule(ctx, MODE_MED)
    }

    fun schedule(ctx: Context, mode: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = next(mode) ?: return
        val pi = PendingIntent.getBroadcast(ctx, mode.hashCode(), Intent(ctx, RitualReceiver::class.java).putExtra(EXTRA_MODE, mode), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Next occurrence strictly after now. */
    fun next(mode: String, from: Calendar = Calendar.getInstance()): Long? {
        val slots: (Int) -> List<Pair<Int, Int>> = { dow ->
            when (mode) {
                MODE_MED -> listOf(8 to 0)
                else -> when (dow) {
                    Calendar.SATURDAY -> emptyList()
                    Calendar.SUNDAY -> listOf(10 to 0)
                    else -> listOf(17 to 30, 20 to 30)
                }
            }
        }
        val c = from.clone() as Calendar
        for (d in 0..7) {
            for ((h, m) in slots(c.get(Calendar.DAY_OF_WEEK))) {
                val t = (c.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                if (t.timeInMillis > from.timeInMillis) return t.timeInMillis
            }
            c.add(Calendar.DAY_OF_YEAR, 1)
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        }
        return null
    }

    /** Full-screen intent: the app opens straight on the hologram, listening, TTS in the earbuds. */
    fun fire(ctx: Context, mode: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rituel", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Relances et rappel médicament"
            setBypassDnd(false)
        })
        val open = Intent(ctx, MainActivity::class.java).apply {
            putExtra(EXTRA_MODE, mode)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val full = PendingIntent.getActivity(ctx, mode.hashCode() + 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (mode == MODE_MED) "Médicament" else "Présence")
            .setContentText(if (mode == MODE_MED) "Tu l'as pris ?" else "On fait le point.")
            .setCategory(if (mode == MODE_MED) Notification.CATEGORY_REMINDER else Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_MAX)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setAutoCancel(true)
            .build()
        nm.notify(mode.hashCode(), n)
        // Also try to open directly (works when the app is allowed to draw over / is recent).
        runCatching { ctx.startActivity(open) }
    }

    fun greeting(mode: String): String = when (mode) {
        MODE_MED -> "Ton médicament. Tu l'as pris ?"
        else -> when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 0..12 -> "Dimanche, logistique. On regarde la semaine ?"
            in 13..18 -> "Fin de journée. C'était quoi, le vrai truc aujourd'hui ?"
            else -> "T'es libre. Il y a un 97 qui t'attend. On le finit ou on le tue ?"
        }
    }
}

class RitualReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val mode = intent.getStringExtra(Ritual.EXTRA_MODE) ?: return
        Ritual.fire(ctx, mode)
        Ritual.schedule(ctx, mode)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Ritual.scheduleAll(ctx)
    }
}
