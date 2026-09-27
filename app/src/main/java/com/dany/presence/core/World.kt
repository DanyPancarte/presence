package com.dany.presence.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.dany.presence.data.Event
import com.dany.presence.data.MoodEntry
import com.dany.presence.data.Note
import com.dany.presence.data.PresenceDb
import com.dany.presence.data.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** An installed app, as a satellite of the instrument. */
data class AppSat(val packageName: String, val label: String)

/** Everything the scene draws, as one immutable snapshot. Nothing on screen is decorative. */
data class World(
    val tasks: List<Task> = emptyList(),
    val notes: List<Note> = emptyList(),
    val moods: List<MoodEntry> = emptyList(),
    val medTakenAt: Long? = null,
    val budgetTotal: Double = 600.0,
    val spent: Double = 0.0,
    val events: List<Event> = emptyList(),
    val apps: List<AppSat> = emptyList(),
)

class WorldRepo(context: Context, scope: CoroutineScope) {
    private val dao = PresenceDb.get(context).dao()
    private val pm = context.packageManager
    private val today = SimpleDateFormat("yyyy-MM-dd", Locale.CANADA_FRENCH).format(Date())
    private val monthStart = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis

    private val apps: List<AppSat> by lazy {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { AppSat(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    val world: StateFlow<World> = combine(
        dao.tasks(), dao.notes(), dao.moods(), dao.medFlow(today), dao.expenses(monthStart), dao.events(System.currentTimeMillis() - 86_400_000L * 7),
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        World(
            tasks = arr[0] as List<Task>, notes = arr[1] as List<Note>, moods = arr[2] as List<MoodEntry>,
            medTakenAt = (arr[3] as com.dany.presence.data.MedLog?)?.prisA,
            spent = (arr[4] as List<com.dany.presence.data.Expense>).sumOf { it.montant },
            events = arr[5] as List<Event>, apps = apps,
        )
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Eagerly, World())
}
