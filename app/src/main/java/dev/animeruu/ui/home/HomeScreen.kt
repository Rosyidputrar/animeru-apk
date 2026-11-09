package dev.animeruu.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import dev.animeruu.core.Day
import dev.animeruu.core.Load
import dev.animeruu.core.formatDuration
import dev.animeruu.core.compact
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.animeruu.ui.components.sharedBounds
import dev.animeruu.data.local.HistoryEntity
import dev.animeruu.domain.Anime
import dev.animeruu.domain.HomeFeed
import dev.animeruu.domain.PromoBanner
import dev.animeruu.ui.components.AnimeArt
import dev.animeruu.ui.components.ArtKind
import dev.animeruu.ui.components.CardShape
import dev.animeruu.ui.components.ErrorState
import dev.animeruu.ui.components.GlassIconButton
import dev.animeruu.ui.components.MetaChip
import dev.animeruu.ui.components.PosterCard
import dev.animeruu.ui.components.PosterSkeleton
import dev.animeruu.ui.components.PrimaryButton
import dev.animeruu.ui.components.SectionHeader
import dev.animeruu.ui.components.StaleBanner
import dev.animeruu.ui.components.shimmer
import dev.animeruu.ui.components.springClick
import dev.animeruu.ui.theme.Motion
import dev.animeruu.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

data class HomeActions(
    val openAnime: (anime: Anime, sharedKey: String?) -> Unit,
    val resume: (HistoryEntity) -> Unit,
    val openSearch: () -> Unit,
    val openSchedule: () -> Unit,
    val openExplore: () -> Unit,
)

@Composable
fun HomeScreen(vm: HomeViewModel, actions: HomeActions) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val cont by vm.continueWatching.collectAsStateWithLifecycle()
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        AnimatedContent(
            targetState = feed is Load.Ready,
            transitionSpec = { fadeIn(Motion.gentle()) togetherWith fadeOut(Motion.gentle()) },
            label = "home",
        ) { ready ->
            when {
                ready -> {
                    val r = feed as? Load.Ready ?: return@AnimatedContent
                    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.load(pull = true) }) {
                        HomeContent(r.value, r.stale, cont, favs.map { it.animeId }.toSet(), listState, actions, vm::toggleFavorite)
                    }
                }
                feed is Load.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ErrorState((feed as Load.Failed).error, onRetry = { vm.load() })
                }
                else -> HomeSkeleton()
            }
        }
        HomeTopBar(listState, actions.openSearch)
    }
}

@Composable
private fun HomeTopBar(listState: LazyListState, onSearch: () -> Unit) {
    val density = LocalDensity.current
    val solid by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / with(density) { 320.dp.toPx() }).coerceIn(0f, 1f)
        }
    }
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Palette.Bg.copy(alpha = 0.55f + 0.4f * solid), Palette.Bg.copy(alpha = 0.92f * solid))))
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            "animeruu",
            style = MaterialTheme.typography.headlineSmall.copy(brush = Palette.AccentBrush, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
            modifier = Modifier.align(Alignment.CenterStart),
        )
        GlassIconButton(Icons.Rounded.Search, "Cari", onSearch, Modifier.align(Alignment.CenterEnd), size = 40.dp)
    }
}

@Composable
private fun HomeContent(
    feed: HomeFeed,
    stale: Boolean,
    cont: List<HistoryEntity>,
    favIds: Set<String>,
    listState: LazyListState,
    a: HomeActions,
    toggleFav: (Anime, Boolean) -> Unit,
) {
    val today = remember { Day.today() }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 130.dp)) {
        item(key = "hero", contentType = "hero") {
            if (feed.spotlight.isNotEmpty()) Hero(feed.spotlight, favIds, a, toggleFav, listState)
            else Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars).height(72.dp))
        }
        item(key = "stale") { StaleBanner(stale) }
        if (feed.promos.isNotEmpty()) item(key = "promo", contentType = "promo") { PromoStrip(feed.promos) }
        if (cont.isNotEmpty()) item(key = "cont", contentType = "cont") {
            RailSpacer()
            SectionHeader("Lanjutkan Menonton")
            Spacer(Modifier.height(12.dp))
            ContinueRail(cont, a.resume)
        }
        rail("today", "Tayang Hari Ini", "Rilis ${today.label}", feed.today, a, onMore = a.openSchedule)
        if (feed.hot.isNotEmpty()) item(key = "hot", contentType = "ranked") {
            RailSpacer()
            SectionHeader("Lagi Panas", subtitle = "10 teratas minggu ini", onMore = a.openExplore)
            Spacer(Modifier.height(12.dp))
            RankedRail(feed.hot.take(10), a)
        }
        rail("new", "Baru Ditambahkan", null, feed.new, a, onMore = a.openExplore)
        rail("popular", "Populer Sepanjang Masa", null, feed.popular, a, onMore = a.openExplore)
        if (feed.trailer.isNotEmpty()) item(key = "trailer", contentType = "wide") {
            RailSpacer()
            SectionHeader("Trailer", subtitle = "Cuplikan yang akan datang")
            Spacer(Modifier.height(12.dp))
            WideRail(feed.trailer, a)
        }
        rail("waiting", "Segera Tayang", "Masuk daftar tunggu", feed.waiting, a, badge = "SEGERA")
        rail("fyp", "Untukmu", null, feed.fyp, a)
        rail("random", "Acak Untukmu", "Tarik ke bawah untuk mengacak ulang", feed.random, a)
    }
}

@Composable private fun RailSpacer() = Spacer(Modifier.height(28.dp))

private fun androidx.compose.foundation.lazy.LazyListScope.rail(
    key: String,
    title: String,
    subtitle: String?,
    items: List<Anime>,
    a: HomeActions,
    badge: String? = null,
    onMore: (() -> Unit)? = null,
) {
    if (items.isEmpty()) return
    item(key = key, contentType = "rail") {
        RailSpacer()
        SectionHeader(title, subtitle = subtitle, onMore = onMore)
        Spacer(Modifier.height(12.dp))
        PosterRail(key, items, a, badge)
    }
}

@Composable
private fun PosterRail(railKey: String, items: List<Anime>, a: HomeActions, badge: String?) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
    ) {
        items(items, key = { it.id }, contentType = { "poster" }) { anime ->
            val k = "poster-$railKey-${anime.id}"
            PosterCard(anime, 124.dp, onClick = { a.openAnime(anime, k) }, sharedKey = k, badge = badge)
        }
    }
}

@Composable
private fun RankedRail(items: List<Anime>, a: HomeActions) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        flingBehavior = rememberSnapFlingBehavior(state, SnapPosition.Start),
    ) {
        itemsIndexed(items, key = { _, it -> it.id }) { i, anime ->
            val k = "poster-hot-${anime.id}"
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontSize = 92.sp, lineHeight = 92.sp, fontWeight = FontWeight.Black,
                        drawStyle = Stroke(width = 5f), letterSpacing = (-6).sp,
                    ),
                    color = Palette.TextFaint,
                    modifier = Modifier.offset(x = 10.dp, y = 14.dp),
                )
                PosterCard(anime, 118.dp, onClick = { a.openAnime(anime, k) }, sharedKey = k, subtitle = anime.views?.let { "${it.compact()} ditonton" })
            }
        }
    }
}

@Composable
private fun WideRail(items: List<Anime>, a: HomeActions) {
    val uri = LocalUriHandler.current
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.id }) { anime ->
            Column(Modifier.width(260.dp).springClick { a.openAnime(anime, null) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(CardShape).border(0.5.dp, Palette.Stroke, CardShape)) {
                    AnimeArt(anime.title, anime.coverUrl, ArtKind.COVER, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
                    if (anime.trailerUrl != null) {
                        GlassIconButton(
                            Icons.Rounded.PlayArrow, "Putar trailer", { runCatching { uri.openUri(anime.trailerUrl) } },
                            Modifier.align(Alignment.Center), size = 48.dp,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(anime.title, style = MaterialTheme.typography.titleSmall, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(anime.studio ?: anime.genres.take(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.TextFaint, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ContinueRail(items: List<HistoryEntity>, onResume: (HistoryEntity) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.animeId }) { h ->
            Column(Modifier.width(232.dp).springClick { onResume(h) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(CardShape).border(0.5.dp, Palette.Stroke, CardShape)) {
                    AnimeArt(h.animeTitle, h.episodeThumb ?: h.coverUrl, ArtKind.COVER, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
                    Icon(
                        Icons.Rounded.PlayArrow, null, tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(8.dp),
                    )
                    Text(
                        if (h.isMovie) "Film" else "Episode ${h.episodeLabel}",
                        style = MaterialTheme.typography.labelMedium, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 12.dp),
                    )
                    if (h.durationMs > 0) {
                        Text(
                            "sisa ${formatDuration((h.durationMs - h.positionMs).coerceAtLeast(0))}",
                            style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 12.dp),
                        )
                        val frac = (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f)
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.2f)))
                        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(frac).height(3.dp).background(Palette.AccentBrush))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(h.animeTitle, style = MaterialTheme.typography.titleSmall, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PromoStrip(promos: List<PromoBanner>) {
    // Banner promosi sumber (CDN sering menolak): dimuat di belakang layar dulu, dan strip
    // hanya muncul — dengan animasi — untuk banner yang benar-benar berhasil dimuat.
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val ok = remember { mutableStateListOf<PromoBanner>() }
    LaunchedEffect(promos) {
        promos.forEach { p ->
            val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(p.imageUrl).build())
            if (r is SuccessResult && ok.none { it.id == p.id }) ok.add(p)
        }
    }
    AnimatedVisibility(ok.isNotEmpty(), enter = expandVertically(Motion.gentle()) + fadeIn(), exit = fadeOut()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            items(ok, key = { it.id }) { p ->
                AsyncImage(
                    model = p.imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.height(110.dp).aspectRatio(2.4f).clip(CardShape)
                        .springClick { p.link?.let { runCatching { uri.openUri(it) } } },
                )
            }
        }
    }
}

@Composable
private fun Hero(
    items: List<Anime>,
    favIds: Set<String>,
    a: HomeActions,
    toggleFav: (Anime, Boolean) -> Unit,
    listState: LazyListState,
) {
    val pager = rememberPagerState { items.size }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(pager, dragged) {
        if (dragged) return@LaunchedEffect
        while (true) {
            delay(6500)
            pager.animateScrollToPage((pager.currentPage + 1) % items.size, animationSpec = Motion.gentle())
        }
    }
    Box(
        Modifier.fillMaxWidth().height(560.dp)
            .graphicsLayer {
                // Parallax vertikal saat beranda digulir.
                if (listState.firstVisibleItemIndex == 0) {
                    translationY = listState.firstVisibleItemScrollOffset * 0.45f
                    alpha = 1f - (listState.firstVisibleItemScrollOffset / 900f).coerceIn(0f, 0.6f)
                }
            }
    ) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { items[it].id }) { page ->
            HeroPage(items[page], page, pager, items[page].id in favIds, a, toggleFav)
        }
        PagerDots(pager, items.size, Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
    }
}

@Composable
private fun HeroPage(anime: Anime, page: Int, pager: PagerState, fav: Boolean, a: HomeActions, toggleFav: (Anime, Boolean) -> Unit) {
    val sharedKey = "poster-hero-${anime.id}"
    Box(Modifier.fillMaxSize().clipToBounds()) {
        AnimeArt(
            anime.title, anime.coverUrl ?: anime.posterUrl, ArtKind.COVER, small = false, showMonogram = false,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val off = (pager.currentPage - page) + pager.currentPageOffsetFraction
                translationX = off * size.width * 0.35f // parallax horizontal
                val s = lerp(1.08f, 1f, 1f - off.absoluteValue.coerceIn(0f, 1f))
                scaleX = s; scaleY = s
            },
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Palette.Bg.copy(alpha = 0.35f), 0.3f to Color.Transparent, 0.62f to Palette.Bg.copy(alpha = 0.7f), 1f to Palette.Bg)))
        Column(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp).padding(bottom = 30.dp)
                .graphicsLayer {
                    val off = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
                    alpha = 1f - off.absoluteValue.coerceIn(0f, 1f)
                    translationX = off * 60f
                },
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    Modifier.width(92.dp).aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(12.dp)).border(0.5.dp, Palette.StrokeStrong, RoundedCornerShape(12.dp))
                        .springClick { a.openAnime(anime, sharedKey) }
                ) {
                    AnimeArt(anime.title, anime.posterUrl, ArtKind.POSTER, Modifier.fillMaxSize().sharedBounds(sharedKey))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    MetaChip(if (anime.countdown != null || anime.nextAirAt != null) "TAYANG HARI INI" else "SOROTAN", icon = Icons.Rounded.Whatshot, tint = Palette.Accent)
                    Spacer(Modifier.height(8.dp))
                    Text(anime.title, style = MaterialTheme.typography.headlineSmall, color = Palette.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(anime.year, anime.typeLabel?.lowercase()?.replaceFirstChar { it.uppercase() }, anime.genres.take(2).joinToString(", ").ifEmpty { null }).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Tonton Sekarang", { a.openAnime(anime, sharedKey) }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
                Spacer(Modifier.width(10.dp))
                val rot by animateFloatAsState(if (fav) 360f else 0f, Motion.snappy(), label = "fav")
                Box(
                    Modifier.size(48.dp).springClick(pressedScale = 0.88f, haptic = true) { toggleFav(anime, !fav) }
                        .clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.1f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (fav) Icons.Rounded.Check else Icons.Rounded.Add, if (fav) "Hapus favorit" else "Tambah favorit", tint = if (fav) Palette.Accent else Palette.Text, modifier = Modifier.graphicsLayer { rotationZ = rot })
                }
            }
        }
    }
}

@Composable
private fun PagerDots(pager: PagerState, count: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val active = pager.currentPage == i
            val w by animateDpAsState(if (active) 20.dp else 6.dp, Motion.snappy(), label = "dot")
            Box(Modifier.height(6.dp).width(w).clip(CircleShape).background(if (active) Palette.Text else Palette.TextFaint.copy(alpha = 0.6f)))
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(560.dp).shimmer())
        repeat(2) {
            Spacer(Modifier.height(28.dp))
            Box(Modifier.padding(horizontal = 20.dp).width(160.dp).height(18.dp).clip(RoundedCornerShape(6.dp)).shimmer())
            Spacer(Modifier.height(14.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { PosterSkeleton(124.dp) }
            }
        }
    }
}
