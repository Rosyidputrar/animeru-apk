package dev.animeruu.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.animeruu.AppContainer
import dev.animeruu.core.Load
import dev.animeruu.data.local.HistoryEntity
import dev.animeruu.domain.HomeFeed
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _feed = MutableStateFlow<Load<HomeFeed>>(Load.Loading)
    val feed: StateFlow<Load<HomeFeed>> = _feed.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val continueWatching: StateFlow<List<HistoryEntity>> =
        c.library.continueWatching.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favorites = c.library.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var job: Job? = null

    init { load() }

    fun load(pull: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            if (pull) _refreshing.value = true
            else if (_feed.value !is Load.Ready) _feed.value = Load.Loading
            try {
                val (feed, stale) = c.anime.home()
                _feed.value = Load.Ready(feed, stale)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // Saat pull-to-refresh gagal, pertahankan data lama; tandai basi.
                val cur = _feed.value
                _feed.value = if (cur is Load.Ready) cur.copy(stale = true) else Load.Failed(t)
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun toggleFavorite(anime: dev.animeruu.domain.Anime, fav: Boolean) {
        viewModelScope.launch { c.library.setFavorite(anime, fav) }
    }
}
