package app.murmure.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.murmure.MurmureApp
import app.murmure.ui.capture.HomeScreen
import app.murmure.ui.capture.RecordScreen
import app.murmure.ui.explore.ExploreScreen
import app.murmure.ui.explore.FolderScreen
import app.murmure.ui.note.NoteScreen
import app.murmure.ui.onboarding.OnboardingScreen
import app.murmure.ui.insights.InsightsScreen
import app.murmure.ui.review.ReviewScreen
import app.murmure.ui.review.NoteReviewScreen
import app.murmure.ui.settings.SettingsScreen
import app.murmure.ui.tasks.TasksScreen
import app.murmure.ui.theme.M

object Routes {
    const val HOME = "home"
    const val EXPLORE = "explore"
    const val TASKS = "tasks"
    const val PORTRAIT = "portrait"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    fun record(daily: Boolean = false) = "record?daily=$daily"
    fun review(id: String) = "review/$id"
    fun reclass(id: String) = "reclass/$id"
    fun note(id: String) = "note/$id"
    fun folder(id: String) = "folder/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "CAPTURE", Icons.Rounded.GraphicEq),
    Tab(Routes.EXPLORE, "FLUX", Icons.Rounded.Hub),
    Tab(Routes.TASKS, "AGENDA", Icons.Rounded.CheckCircle),
    Tab(Routes.PORTRAIT, "INSIGHTS", Icons.Rounded.AutoAwesome),
)

@Composable
fun MurmureRoot(openRitual: Boolean, onRitualHandled: () -> Unit) {
    val app = MurmureApp.instance
    val settings by app.settings.state.collectAsState()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val start = remember { if (settings.onboarded) Routes.HOME else Routes.ONBOARDING }

    LaunchedEffect(openRitual) {
        if (openRitual && settings.onboarded) {
            nav.navigate(Routes.record(true)) { launchSingleTop = true }
            onRitualHandled()
        }
    }

    Box(Modifier.fillMaxSize().background(M.Ink)) {
        NavHost(
            nav, startDestination = start, modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 14 } },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(180)) + slideOutVertically(tween(260)) { it / 14 } },
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onDone = {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                })
            }
            composable(Routes.HOME) { TabFrame { HomeScreen(nav) } }
            composable(Routes.EXPLORE) { TabFrame { ExploreScreen(nav) } }
            composable(Routes.TASKS) { TabFrame { TasksScreen(nav) } }
            composable(Routes.PORTRAIT) { TabFrame { InsightsScreen(nav) } }
            composable(Routes.SETTINGS) { Frame { SettingsScreen(nav) } }
            composable(
                "record?daily={daily}",
                arguments = listOf(navArgument("daily") { type = NavType.BoolType; defaultValue = false }),
            ) { e -> Frame { RecordScreen(nav, daily = e.arguments?.getBoolean("daily") == true) } }
            composable("review/{id}") { e -> Frame { ReviewScreen(nav, e.arguments?.getString("id")!!) } }
            composable("reclass/{id}") { e -> Frame { NoteReviewScreen(nav, e.arguments?.getString("id")!!) } }
            composable("note/{id}") { e -> Frame { NoteScreen(nav, e.arguments?.getString("id")!!) } }
            composable("folder/{id}") { e -> Frame { FolderScreen(nav, e.arguments?.getString("id")!!) } }
        }

        AnimatedVisibility(
            visible = tabs.any { it.route == route },
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) { BottomBar(nav, route) }
    }
}

@Composable
private fun Frame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(M.Ink).statusBarsPadding().navigationBarsPadding()) { content() }
}

@Composable
private fun TabFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(M.Ink).statusBarsPadding().navigationBarsPadding().padding(bottom = 86.dp)) { content() }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    val view = androidx.compose.ui.platform.LocalView.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(M.Ink)
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(M.Surface)
            .border(1.dp, M.Line, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        tabs.forEachIndexed { idx, tab ->
            val selected = route == tab.route
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) M.Surface2 else M.Surface)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        app.murmure.ui.components.Feedback.tap(view)
                        if (!selected) nav.navigate(tab.route) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("0${idx + 1}", style = MaterialTheme.typography.labelSmall, color = if (selected) M.Copper else M.Faint)
                Spacer(Modifier.height(3.dp))
                Text(tab.label, style = MaterialTheme.typography.labelSmall, color = if (selected) M.Text else M.Muted)
            }
        }
    }
}
