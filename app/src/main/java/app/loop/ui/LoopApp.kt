package app.loop.ui

import androidx.compose.material3.MaterialTheme
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.loop.ui.screens.AboutScreen
import app.loop.ui.screens.AlarmScreen
import app.loop.ui.screens.CycleScreen
import app.loop.ui.screens.JournalScreen
import app.loop.ui.screens.StrengthEditScreen
import app.loop.ui.screens.StrengthScreen
import app.loop.ui.screens.OverviewScreen
import app.loop.ui.screens.BreatheScreen
import app.loop.ui.screens.CategoryScreen
import app.loop.ui.screens.ExploreScreen
import app.loop.ui.screens.HelpScreen
import app.loop.ui.screens.ImportScreen
import app.loop.ui.screens.IntervalsScreen
import app.loop.ui.screens.LiveScreen
import app.loop.ui.screens.LogWorkoutScreen
import app.loop.ui.screens.MetricScreen
import app.loop.ui.screens.MoodCheckInScreen
import app.loop.ui.screens.OnboardingScreen
import app.loop.ui.screens.PracticeScreen
import app.loop.ui.screens.ProfileScreen
import app.loop.ui.screens.TodayScreen
import app.loop.ui.screens.WorkoutDetailScreen
import app.loop.ui.screens.WorkoutsScreen
import app.loop.ui.screens.YouScreen

private fun Tab.icon(): ImageVector = when (this) {
    Tab.TODAY -> Icons.Rounded.Today
    Tab.OVERVIEW -> Icons.Rounded.Insights
    Tab.LOG -> Icons.Rounded.MedicalServices
    Tab.EXPLORE -> Icons.Rounded.Explore
    Tab.PRACTICE -> Icons.Rounded.SelfImprovement
    Tab.YOU -> Icons.Rounded.Person
}

@Composable
fun LoopApp(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val dash by vm.dashboard.collectAsStateWithLifecycle()

    if (!settings.onboarded) {
        Box(Modifier.background(MaterialTheme.colorScheme.background)) { OnboardingScreen(vm) }
        return
    }

    val top: Route? = vm.stack.lastOrNull()
    BackHandler(enabled = top != null || vm.tab != Tab.TODAY) { if (!vm.pop()) vm.tab = Tab.TODAY }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (top == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = vm.tab == t, onClick = { vm.selectTab(t) },
                            icon = { Icon(t.icon(), t.label) }, label = { Text(t.label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.sp)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSurface, selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                                unselectedIconColor = MaterialTheme.colorScheme.outline, unselectedTextColor = MaterialTheme.colorScheme.outline,
                            ),
                        )
                    }
                }
            }
        },
    ) { inner ->
        val key: Any = top ?: vm.tab
        AnimatedContent(
            targetState = key,
            modifier = Modifier.padding(inner),
            transitionSpec = {
                if (targetState is Route && initialState !is Route)
                    (slideInHorizontally(tween(260)) { it / 8 } + fadeIn(tween(260))) togetherWith fadeOut(tween(160))
                else if (initialState is Route && targetState !is Route)
                    fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(220)) { it / 8 } + fadeOut(tween(160)))
                else fadeIn(tween(200)) togetherWith fadeOut(tween(120))
            },
            label = "screen",
        ) { k ->
            when (k) {
                is Route -> RouteContent(k, vm, dash)
                Tab.TODAY -> TodayScreen(vm, dash)
                Tab.OVERVIEW -> OverviewScreen(vm, dash)
                Tab.LOG -> app.loop.ui.screens.HealthLogScreen(vm, dash)
                Tab.EXPLORE -> ExploreScreen(vm, dash)
                Tab.PRACTICE -> PracticeScreen(vm, dash)
                Tab.YOU -> YouScreen(vm, dash)
                else -> {}
            }
        }
    }
}

@Composable
private fun RouteContent(r: Route, vm: AppViewModel, dash: Dashboard) {
    when (r) {
        is Route.CategoryScreen -> CategoryScreen(vm, dash, r.category)
        is Route.MetricScreen -> MetricScreen(vm, dash, r.id)
        is Route.WorkoutScreen -> WorkoutDetailScreen(vm, dash, r.id)
        Route.Workouts -> WorkoutsScreen(vm, dash)
        Route.Live, Route.Devices -> LiveScreen(vm, dash)
        Route.Breathe -> BreatheScreen(vm, dash)
        Route.Intervals -> IntervalsScreen(vm)
        Route.ImportData -> ImportScreen(vm, dash)
        Route.Profile -> ProfileScreen(vm, dash)
        Route.Help -> HelpScreen(vm)
        Route.About -> AboutScreen(vm)
        Route.MoodCheckIn -> MoodCheckInScreen(vm, dash)
        Route.LogWorkout -> LogWorkoutScreen(vm)
        Route.Journal -> JournalScreen(vm, dash)
        Route.Cycle -> if (dash.settings.female) CycleScreen(vm, dash)
        Route.Strength -> StrengthScreen(vm, dash)
        Route.StrengthEdit -> StrengthEditScreen(vm, dash)
        Route.Alarm -> AlarmScreen(vm, dash)
    }
}

/** Shared list padding for every screen. */
val ScreenPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp)
