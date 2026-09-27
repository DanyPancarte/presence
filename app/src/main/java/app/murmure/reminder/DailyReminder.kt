package app.murmure.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.murmure.MainActivity
import app.murmure.MurmureApp
import app.murmure.R
import app.murmure.core.AppSettings
import app.murmure.core.Dates
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object DailyReminder {
    const val CHANNEL = "ritual"
    private const val WORK = "daily_ritual"

    fun schedule(context: Context, s: AppSettings) {
        val wm = WorkManager.getInstance(context)
        if (!s.reminderEnabled) { wm.cancelUniqueWork(WORK); return }
        val now = LocalDateTime.now(Dates.zone)
        var next = now.toLocalDate().atTime(s.reminderMinutes / 60, s.reminderMinutes % 60)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMinutes()
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as MurmureApp
        val today = Dates.dayKey()
        val done = runCatching { app.repo.dao.notes().any { it.isDaily && it.dayKey == today } }.getOrDefault(false)
        if (done) return Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return Result.success()
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_RITUAL, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(applicationContext, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(applicationContext, DailyReminder.CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFFC9B6FF.toInt())
            .setContentTitle("Raconte ta journée ✨")
            .setContentText("5 minutes max. Juste toi, ta voix, et ta journée.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(42, n) }
        return Result.success()
    }
}
