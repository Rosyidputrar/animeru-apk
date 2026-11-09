package dev.animeruu.ui.explore

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.animeruu.AppContainer
import dev.animeruu.core.userMessage
import dev.animeruu.domain.Anime
import dev.animeruu.ui.components.ErrorState
import dev.animeruu.ui.components.Pill
import dev.animeruu.ui.components.PosterCard
import dev.animeruu.ui.components.PosterSkeleton
import dev.animeruu.ui.components.PrimaryButton
import dev.animeruu.ui.components.StaleBanner
import dev.animeruu.ui.components.StateMessage
import dev.animeruu.ui.components.springClick
import dev.animeruu.ui.theme.Motion
import dev.animeruu.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

val GENRES = listOf(
    "Action", "Adventure", "Comedy", "Demons", "Drama", "Ecchi", "Fantasy", "Game", "Harem", "Historical",
    "Horror", "Isekai", "Magic", "Martial Arts", "Mecha", "Military", "Music", "Mystery", "Parody", "Psychological",
    "Romance", "School", "Sci-Fi", "Seinen", "Shoujo", "Shounen", "Slice of Life", "Sports", "Super Power", "Supernatural",
)

data class ExploreState(
    val query: String = "",
    val genre: String? = null,
    val items: List<Anime> = emptyList(),
    val nextPage: Int? = 0,
    val loading: Boolean = false,
    val error: Throwable? = null,
    val stale: Boolean = false,
) {
    val initialLoading get() = loading && items.isEmpty()
    val endReached get() = nextPage == null
}

class ExploreViewModel(private val c: AppContainer) : ViewModel() {
    var state by mutableStateOf(ExploreState())
        private set

    private var job: Job? = null
    private var generation = 0

    init { reload(debounce = false) }

    fun setQuery(q: String) {
        if (q == state.query) return
        state = state.copy(query = q)
        reload(debounce = true)
    }

    fun setGenre(g: String?) {
        if (g == state.genre) return
        state = state.copy(genre = g)
        reload(debounce = false)
    }

    fun retry() = if (state.items.isEmpty()) reload(false) else loadMore()

    private fun reload(debounce: Boolean) {
        job?.cancel()
        val gen = ++generation
        state = state.copy(items = emptyList(), nextPage = 0, error = null, loading = true, stale = false)
        job = viewModelScope.launch {
            if (debounce) delay(380)
            fetch(gen, 0)
        }
    }

    fun loadMore() {
        val s = state
        val p = s.nextPage ?: return
        if (s.loading || s.error != null && s.items.isEmpty()) return
        val gen = generation
        state = s.copy(loading = true, error = null)
        job = viewModelScope.launch { fetch(gen, p) }
    }

    private suspend fun fetch(gen: Int, page: Int) {
        try {
            val r = c.anime.search(state.query, state.genre, page)
            if (gen != generation) return
            val merged = (state.items + r.items).distinctBy { it.id }
            state = state.copy(
                items = merged,
                nextPage = if (r.hasNext) (r.nextPage ?: (page + 1)) else null,
                loading = false,
                stale = r.stale,
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (gen != generation) return
            state = state.copy(loading = false, error = t)
        }
    }
}

@Composable
fun ExploreScreen(vm: ExploreViewModel, openAnime: (Anime, String?) -> Unit, focusSearch: Boolean) {
    val s = vm.state
    val grid = rememberLazyGridState()
    val focus = LocalFocusManager.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(focusSearch) { if (focusSearch) runCatching { fr.requestFocus() } }

    // Infinite scroll: minta halaman berikut saat tersisa ±2 baris.
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= grid.layoutInfo.totalItemsCount - 9 }
            .collect { if (!vm.state.loading && vm.state.error == null) vm.loadMore() }
    }
    LaunchedEffect(s.query, s.genre) { if (grid.firstVisibleItemIndex > 0) grid.scrollToItem(0) }

    Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding()) {
        Text("Jelajah", style = MaterialTheme.typography.headlineMedium, color = Palette.Text, modifier = Modifier.padding(start = 20.dp, top = 12.dp))
        Spacer(Modifier.height(14.dp))
        SearchField(s.query, vm::setQuery, fr, onDone = { focus.clearFocus() })
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Pill("Semua", s.genre == null, { vm.setGenre(null) }) }
            items(GENRES, key = { it }) { g -> Pill(g, s.genre == g, { vm.setGenre(g) }) }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxSize()) {
            when {
                s.items.isEmpty() && s.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    ErrorState(s.error, onRetry = vm::retry, modifier = Modifier.padding(top = 40.dp))
                }
                s.items.isEmpty() && !s.loading -> StateMessage(
                    Icons.Rounded.SearchOff,
                    "Tidak ada hasil",
                    buildString {
                        append("Tidak ada anime")
                        if (s.query.isNotBlank()) append(" yang cocok dengan “${s.query.trim()}”")
                        if (s.genre != null) append(" di genre ${s.genre}")
                        append(". Coba kata kunci lain atau ejaan berbeda.")
                    },
                    Modifier.padding(top = 40.dp),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(108.dp),
                    state = grid,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 130.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { StaleBanner(s.stale) }
                    if (s.initialLoading) {
                        items(12) { PosterSkeleton(null) }
                    } else {
                        items(s.items, key = { it.id }, contentType = { "poster" }) { a ->
                            val k = "poster-explore-${a.id}"
                            PosterCard(a, null, onClick = { focus.clearFocus(); openAnime(a, k) }, sharedKey = k, modifier = Modifier.animateItem(fadeInSpec = Motion.gentle(), placementSpec = Motion.snappy()))
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                            Footer(s, vm::retry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Footer(s: ExploreState, retry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        when {
            s.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.error.userMessage(), style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Muat lagi", retry)
            }
            s.loading -> CircularProgressIndicator(Modifier.size(26.dp), color = Palette.Accent, strokeWidth = 2.5.dp)
            s.endReached && s.items.isNotEmpty() -> Text("— ${s.items.size} judul • sudah sampai ujung —", style = MaterialTheme.typography.labelMedium, color = Palette.TextFaint)
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, fr: FocusRequester, onDone: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(if (focused) 1f else 0f, Motion.snappy(), label = "glow")
    Row(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.SurfaceHigh)
            .border(1.dp, Palette.Accent.copy(alpha = 0.12f + 0.5f * glow), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Cari judul anime…", style = MaterialTheme.typography.bodyLarge, color = Palette.TextFaint)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDone() }),
                modifier = Modifier.fillMaxWidth().focusRequester(fr).onFocusChanged { focused = it.isFocused },
            )
        }
        AnimatedVisibility(value.isNotEmpty(), enter = scaleIn(Motion.press()) + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(
                Icons.Rounded.Close, "Hapus", tint = Palette.TextDim,
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).springClick { onChange("") }.padding(4.dp),
            )
        }
    }
}
