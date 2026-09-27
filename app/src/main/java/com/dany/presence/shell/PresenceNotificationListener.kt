package com.dany.presence.shell

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dany.presence.core.Signal

/**
 * Forwards each new notification as [Signal.Notified] (app label, title) on [BusHolder.bus].
 * Needs BIND_NOTIFICATION_LISTENER_SERVICE in the manifest and the user's notification access.
 * Ongoing and group-summary notifications are ignored; so are our own.
 */
class PresenceNotificationListener : NotificationListenerService() {
    private val labels = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val bus = BusHolder.bus ?: return
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            .ifEmpty { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty() }
        if (title.isEmpty()) return
        bus.emit(Signal.Notified(label(sbn.packageName), title))
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }
}
