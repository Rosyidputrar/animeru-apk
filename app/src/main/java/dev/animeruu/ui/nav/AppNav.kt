package dev.animeruu.ui.nav

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.animeruu.AppContainer
import dev.animeruu.container
import dev.animeruu.domain.Anime
import dev.animeruu.ui.components.LocalNavScope
import dev.animeruu.ui.components.LocalSharedScope
import dev.animeruu.ui.components.springClick
import dev.animeruu.ui.detail.DetailActions
import dev.animeruu.ui.detail.DetailScreen
import dev.animeruu.ui.detail.DetailViewModel
import dev.animeruu.ui.explore.ExploreScreen
import dev.animeruu.ui.explore.ExploreViewModel
import dev.animeruu.ui.home.HomeActions
import dev.animeruu.ui.home.HomeScreen
import dev.animeruu.ui.home.HomeViewModel
import dev.animeruu.ui.library.LibraryActions
import dev.animeruu.ui.library.LibraryScreen
import dev.animeruu.ui.library.LibraryViewModel
import dev.animeruu.ui.player.PlayerScreen
import dev.animeruu.ui.player.PlayerViewModel
import dev.animeruu.ui.schedule.ScheduleScreen
import dev.animeruu.ui.schedule.ScheduleViewModel
import dev.animeruu.ui.settings.SettingsScreen
import dev.animeruu.ui.theme.Motion
import dev.animeruu.ui.theme.Palette
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object ScheduleRoute
@Serializable data class ExploreRoute(val genre: String? = null, val focus: Boolean = false)
@Serializable data object LibraryRoute
@Serializable data object SettingsRoute
@Serializable data class DetailRoute(val id: String, val sharedKey: String? = null)
@Serializable data class PlayerRoute(val animeId: String, val episodeId: String)

private data class Tab(val label: String, val icon: ImageVector, val route: Any, val matches: (NavBackStackEntry) -> Boolean)

private val TABS = listOf(
    Tab("Beranda", Icons.Rounded.Home, HomeRoute) { it.destination.hasRoute<HomeRoute>() },
    Tab("Jadwal", Icons.Rounded.CalendarMonth, ScheduleRoute) { it.destination.hasRoute<ScheduleRoute>() },
    Tab("Jelajah", Icons.Rounded.Explore, ExploreRoute()) { it.destination.hasRoute<ExploreRoute>() },
    Tab("Koleksi", Icons.Rounded.VideoLibrary, LibraryRoute) { it.destination.hasRoute<LibraryRoute>() },
)

private fun NavBackStackEntry.isTab() = TABS.any { it.matches(this) }

@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer, SavedStateHandle) -> VM): VM {
    val c = LocalContext.current.container
    return viewModel(initializer = { create(c, createSavedStateHandle()) })
}

/** Transisi layar: tab ↔ tab = crossfade + skala halus; push/pop = geser berpegas (paralaks 1/3). */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition = when {
    targetState.destination.hasRoute<PlayerRoute>() -> fadeIn(Motion.screenFloat) + scaleIn(Motion.screenFloat, 0.96f)
    initialState.isTab() && targetState.isTab() -> fadeIn(Motion.screenFloat) + scaleIn(Motion.screenFloat, 0.985f)
    else -> slideInHorizontally(Motion.screenOffset) { it } + fadeIn(Motion.screenFloat, 0.6f)
}
private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition = when {
    targetState.destination.hasRoute<PlayerRoute>() -> fadeOut(Motion.screenFloat)
    initialState.isTab() && targetState.isTab() -> fadeOut(Motion.screenFloat)
    else -> slideOutHorizontally(Motion.screenOffset) { -it / 3 } + fadeOut(Motion.screenFloat, 0.4f)
}
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition = when {
    initialState.destination.hasRoute<PlayerRoute>() -> fadeIn(Motion.screenFloat)
    initialState.isTab() && targetState.isTab() -> fadeIn(Motion.screenFloat)
    else -> slideInHorizontally(Motion.screenOffset) { -it / 3 } + fadeIn(Motion.screenFloat, 0.4f)
}
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition = when {
    initialState.destination.hasRoute<PlayerRoute>() -> fadeOut(Motion.screenFloat) + scaleOut(Motion.screenFloat, 0.96f)
    initialState.isTab() && targetState.isTab() -> fadeOut(Motion.screenFloat)
    else -> slideOutHorizontally(Motion.screenOffset) { it } + fadeOut(Motion.screenFloat, 0.6f)
}

@Composable
fun AnimeruuApp() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val ctx = LocalContext.current
    val app = ctx.applicationContext as Application

    val openAnime: (Anime, String?) -> Unit = { a, k -> nav.navigate(DetailRoute(a.id, k)) }
    val play: (String, String) -> Unit = { animeId, epId -> nav.navigate(PlayerRoute(animeId, epId)) { launchSingleTop = true } }

    // Izin notifikasi (Android 13+) diminta saat relevan — sekali, di awal sesi.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = ctx.getSharedPreferences("once", 0)
            if (!prefs.getBoolean("notif_asked", false)) {
                prefs.edit().putBoolean("notif_asked", true).apply()
                kotlinx.coroutines.delay(4000)
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        SharedTransitionLayout {
            CompositionLocalProvider(LocalSharedScope provides this) {
                NavHost(
                    nav, startDestination = HomeRoute, modifier = Modifier.fillMaxSize(),
                    enterTransition = { enter() }, exitTransition = { exit() },
                    popEnterTransition = { popEnter() }, popExitTransition = { popExit() },
                ) {
                    composable<HomeRoute> {
                        CompositionLocalProvider(LocalNavScope provides this) {
                            val vm = appViewModel { c, _ -> HomeViewModel(c) }
                            HomeScreen(vm, HomeActions(
                                openAnime = openAnime,
                                resume = { h -> play(h.animeId, h.episodeId) },
                                openSearch = { nav.navigateTab(ExploreRoute(focus = true)) },
                                openSchedule = { nav.navigateTab(ScheduleRoute) },
                                openExplore = { nav.navigateTab(ExploreRoute()) },
                            ))
                        }
                    }
                    composable<ScheduleRoute> {
                        CompositionLocalProvider(LocalNavScope provides this) {
                            ScheduleScreen(appViewModel { c, _ -> ScheduleViewModel(c) }, openAnime)
                        }
                    }
                    composable<ExploreRoute> { e ->
                        val r = e.toRoute<ExploreRoute>()
                        CompositionLocalProvider(LocalNavScope provides this) {
                            val vm = appViewModel { c, _ -> ExploreViewModel(c).also { if (r.genre != null) it.setGenre(r.genre) } }
                            ExploreScreen(vm, openAnime, focusSearch = r.focus)
                        }
                    }
                    composable<LibraryRoute> {
                        CompositionLocalProvider(LocalNavScope provides this) {
                            LibraryScreen(appViewModel { c, _ -> LibraryViewModel(c) }, LibraryActions(openAnime, play) { nav.navigate(SettingsRoute) })
                        }
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(ctx.container) { nav.popBackStack() }
                    }
                    composable<DetailRoute> { e ->
                        val r = e.toRoute<DetailRoute>()
                        CompositionLocalProvider(LocalNavScope provides this) {
                            val vm = appViewModel { c, _ -> DetailViewModel(c, r.id) }
                            DetailScreen(vm, r.sharedKey, DetailActions(
                                back = { nav.popBackStack() },
                                play = play,
                                openAnime = openAnime,
                                openGenre = { g -> nav.navigate(ExploreRoute(genre = g)) },
                            ))
                        }
                    }
                    composable<PlayerRoute> { e ->
                        val r = e.toRoute<PlayerRoute>()
                        val vm = appViewModel { c, _ -> PlayerViewModel(app, c, r.animeId, r.episodeId) }
                        PlayerScreen(vm) { nav.popBackStack() }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = entry?.isTab() ?: true,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(Motion.snappy()) { it } + fadeIn(),
            exit = slideOutVertically(Motion.snappy()) { it } + fadeOut(),
        ) {
            BottomBar(entry) { tab -> nav.navigateTab(tab.route) }
        }
    }
}

private fun NavHostController.navigateTab(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Bilah tab mengambang: kapsul kaca dengan indikator bergradien yang meluncur berpegas. */
@Composable
private fun BottomBar(entry: NavBackStackEntry?, onSelect: (Tab) -> Unit) {
    val selected = TABS.indexOfFirst { t -> entry?.let(t.matches) == true }.coerceAtLeast(0)
    Box(
        Modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, Palette.Bg.copy(alpha = 0.9f))))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(64.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.SurfaceHigh.copy(alpha = 0.96f))
                .border(0.5.dp, Palette.StrokeStrong, RoundedCornerShape(24.dp))
                .padding(5.dp)
        ) {
            val w = maxWidth / TABS.size
            val x by animateDpAsState(w * selected, Motion.snappy(), label = "tabx")
            Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(RoundedCornerShape(19.dp)).background(Palette.AccentBrush).graphicsLayer { alpha = 0.95f })
            Row(Modifier.fillMaxSize()) {
                TABS.forEachIndexed { i, t ->
                    val active = i == selected
                    val s by animateFloatAsState(if (active) 1f else 0.92f, Motion.press(), label = "s")
                    Column(
                        Modifier.weight(1f).fillMaxHeight().springClick(pressedScale = 0.9f, haptic = true) { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(t.icon, t.label, tint = if (active) Palette.Text else Palette.TextDim, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = s; scaleY = s })
                        Text(t.label, style = MaterialTheme.typography.labelSmall, color = if (active) Palette.Text else Palette.TextDim)
                    }
                }
            }
        }
    }
}
