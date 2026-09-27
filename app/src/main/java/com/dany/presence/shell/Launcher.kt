package com.dany.presence.shell

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.dany.presence.core.Bus
import com.dany.presence.core.Signal

/** Opens satellites and knows whether Présence is the home screen. */
object Launcher {

    /** Starts the app's launcher activity in its own task. Emits [Signal.Launch] on [bus] when it opened. */
    fun launch(context: Context, packageName: String, bus: Bus? = null): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (ok) bus?.emit(Signal.Launch(packageName))
        return ok
    }

    /** True when Présence holds the HOME role (or resolves as the default HOME activity). */
    fun isDefaultHome(context: Context): Boolean {
        val rm = context.getSystemService(RoleManager::class.java)
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val r = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == context.packageName
    }

    /** System page where the user picks the home app. */
    fun openHomeSettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** True when the user granted notification access to PresenceNotificationListener. */
    fun hasNotificationAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { it.startsWith(context.packageName + "/") || it == context.packageName }
    }

    /** System page where the user grants notification access. */
    fun openNotificationAccess(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
