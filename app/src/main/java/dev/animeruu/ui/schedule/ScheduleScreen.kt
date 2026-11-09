package dev.animeruu.ui.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.animeruu.AppContainer
import dev.animeruu.core.Day
import dev.animeruu.core.Load
import dev.animeruu.core.compact
import dev.animeruu.domain.Anime
import dev.animeruu.ui.components.AnimeArt
import dev.animeruu.ui.components.ArtKind
import dev.animeruu.ui.components.ErrorState
import dev.animeruu.ui.components.MetaChip
import dev.animeruu.ui.components.StaleBanner
import dev.animeruu.ui.components.StateMessage
import dev.animeruu.ui.components.sharedBounds
import dev.animeruu.ui.components.shimmer
import dev.animeruu.ui.components.springClick
import dev.animeruu.ui.theme.Motion
import dev.animeruu.ui.theme.Palette
import kotlinx.coroutines.launch

class ScheduleViewModel(private val c: AppContainer) : ViewModel() {
    val days = mutableStateMapOf<Day, Load<List<Anime>>>()

    fun ensure(day: Day, force: Boolean = false) {
        val cur = days[day]
        if (!force && (cur is Load.Ready || cur is Load.Loading)) return
        days[day] = Load.Loading
        viewModelScope.launch {
            days[day] = try {
                val (list, stale) = c.anime.schedule(day)
                Load.Ready(list, stale)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Load.Failed(t)
            }
        }
    }
}

@Composable
fun ScheduleScreen(vm: ScheduleViewModel, openAnime: (Anime, String?) -> Unit) {
    val today = remember { Day.today() }
    val pager = rememberPagerState(initialPage = today.ordinal) { Day.entries.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { p ->
            vm.ensure(Day.entries[p])
            // Pra-muat hari tetangga supaya swipe terasa instan.
            Day.entries.getOrNull(p + 1)?.let(vm::ensure)
            Day.entries.getOrNull(p - 1)?.let(vm::ensure)
        }
    }
    Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding()) {
        Text("Jadwal Tayang", style = MaterialTheme.typography.headlineMedium, color = Palette.Text, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
        Text("Rilis mingguan, waktu Indonesia Barat", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(start = 20.dp, top = 2.dp))
        Spacer(Modifier.height(16.dp))
        DaySelector(
            selected = pager.currentPage,
            fraction = pager.currentPageOffsetFraction,
            today = today,
            onSelect = { scope.launch { pager.animateScrollToPage(it, animationSpec = Motion.gentle()) } },
        )
        Spacer(Modifier.height(8.dp))
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            val day = Day.entries[page]
            val state = vm.days[day] ?: Load.Loading
            AnimatedContent(
                when (state) { is Load.Ready -> 1; is Load.Failed -> 2; else -> 0 },
                transitionSpec = { fadeIn(Motion.gentle()) togetherWith fadeOut(Motion.gentle()) },
                label = "day",
            ) { _ ->
                when (state) {
                    is Load.Ready -> if (state.value.isEmpty()) {
                        StateMessage(Icons.Rounded.EventBusy, "Tidak ada rilis", "Belum ada anime yang dijadwalkan tayang hari ${day.label}.", Modifier.fillMaxSize().padding(top = 60.dp))
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 130.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            item { StaleBanner(state.stale) }
                            items(state.value, key = { it.id }) { a -> ScheduleRow(a, openAnime) }
                        }
                    }
                    is Load.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ErrorState(state.error, onRetry = { vm.ensure(day, force = true) })
                    }
                    else -> Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        repeat(5) {
                            Row {
                                Box(Modifier.width(84.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).shimmer())
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.padding(top = 8.dp)) {
                                    Box(Modifier.fillMaxWidth(0.8f).height(14.dp).clip(RoundedCornerShape(4.dp)).shimmer())
                                    Spacer(Modifier.height(8.dp))
                                    Box(Modifier.fillMaxWidth(0.5f).height(12.dp).clip(RoundedCornerShape(4.dp)).shimmer())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySelector(selected: Int, fraction: Float, today: Day, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(56.dp)
            .clip(RoundedCornerShape(18.dp)).background(Palette.Surface).border(0.5.dp, Palette.Stroke, RoundedCornerShape(18.dp))
            .padding(4.dp)
    ) {
        val cell = maxWidth / Day.entries.size
        // Indikator mengikuti jari saat pager di-swipe (fraction), lalu menetap dengan pegas.
        val target = cell * (selected + fraction)
        val x by animateDpAsState(target, Motion.snappy(), label = "ind")
        Box(Modifier.offset(x = x).width(cell).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Palette.AccentBrush))
        Row(Modifier.fillMaxSize()) {
            Day.entries.forEachIndexed { i, d ->
                Column(
                    Modifier.weight(1f).fillMaxHeight().springClick(haptic = true) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(d.short, style = MaterialTheme.typography.labelLarge, color = if (i == selected) Palette.Text else Palette.TextDim)
                    if (d == today) Box(Modifier.padding(top = 3.dp).size(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i == selected) Palette.Text else Palette.Accent))
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(a: Anime, open: (Anime, String?) -> Unit) {
    val key = "poster-sched-${a.id}"
    Row(
        Modifier.fillMaxWidth().springClick(pressedScale = 0.97f) { open(a, key) }
            .clip(RoundedCornerShape(18.dp)).background(Palette.Surface).border(0.5.dp, Palette.Stroke, RoundedCornerShape(18.dp))
            .padding(10.dp),
    ) {
        Box(Modifier.width(84.dp).aspectRatio(2f / 3f).sharedBounds(key).clip(RoundedCornerShape(12.dp))) {
            AnimeArt(a.title, a.posterUrl, ArtKind.POSTER, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            val time = a.nextAirAt?.let { Regex("""\d{2}:\d{2}""").find(it)?.value }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (time != null) MetaChip(time, icon = Icons.Rounded.Schedule, tint = Palette.Accent)
                if (a.countdown != null) {
                    Spacer(Modifier.width(6.dp))
                    Text("dalam ${a.countdown.trim()}", style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(a.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(a.genres.take(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(a.status?.lowercase()?.replaceFirstChar { it.uppercase() }, a.views?.let { "${it.compact()} ditonton" }).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint,
            )
        }
    }
}
