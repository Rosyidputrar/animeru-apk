package dev.animeruu.data.repo

import dev.animeruu.data.local.AppDatabase
import dev.animeruu.data.local.EpisodeProgressEntity
import dev.animeruu.data.local.FavoriteEntity
import dev.animeruu.data.local.HistoryEntity
import dev.animeruu.domain.Anime
import dev.animeruu.domain.AnimeKind
import dev.animeruu.domain.Episode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Favorit, riwayat, dan posisi resume — sepenuhnya lokal perangkat. */
class LibraryRepository(private val db: AppDatabase) {

    val favorites: Flow<List<FavoriteEntity>> = db.favorites().observeAll()
    val history: Flow<List<HistoryEntity>> = db.history().observeAll()

    /** "Lanjutkan menonton": yang belum selesai (< 92%) atau seri yang mungkin punya episode lanjut. */
    val continueWatching: Flow<List<HistoryEntity>> = history.map { list ->
        list.filter { it.durationMs <= 0 || it.positionMs < it.durationMs * 0.92 || !it.isMovie }.take(15)
    }

    fun isFavorite(id: String): Flow<Boolean> = db.favorites().observeIsFavorite(id)

    suspend fun setFavorite(anime: Anime, fav: Boolean) {
        if (fav) db.favorites().upsert(
            FavoriteEntity(
                animeId = anime.id, title = anime.title, posterUrl = anime.posterUrl, coverUrl = anime.coverUrl,
                kind = anime.kind.name, genres = anime.genres.joinToString(","), year = anime.year,
                addedAt = System.currentTimeMillis(),
            )
        ) else db.favorites().delete(anime.id)
    }

    fun historyFor(animeId: String): Flow<HistoryEntity?> = db.history().observe(animeId)

    fun progressFor(animeId: String): Flow<Map<String, EpisodeProgressEntity>> =
        db.history().observeProgressFor(animeId).map { l -> l.associateBy { it.episodeId } }

    suspend fun progress(episodeId: String) = db.history().progress(episodeId)

    suspend fun record(anime: Anime, episode: Episode, positionMs: Long, durationMs: Long) {
        val now = System.currentTimeMillis()
        db.history().upsertProgress(EpisodeProgressEntity(episode.id, anime.id, positionMs, durationMs, now))
        db.history().upsert(
            HistoryEntity(
                animeId = anime.id, animeTitle = anime.title, posterUrl = anime.posterUrl, coverUrl = anime.coverUrl,
                episodeId = episode.id, episodeLabel = episode.numberLabel, episodeTitle = episode.title,
                episodeThumb = episode.thumbUrl, positionMs = positionMs, durationMs = durationMs,
                updatedAt = now, isMovie = anime.kind == AnimeKind.MOVIE,
            )
        )
    }

    suspend fun removeHistory(animeId: String) = db.history().delete(animeId)

    /** "Urungkan" setelah menghapus satu baris riwayat (posisi episode tidak ikut terhapus). */
    suspend fun restoreHistory(h: HistoryEntity) = db.history().upsert(h)

    suspend fun clearHistory() {
        db.history().clear()
        db.history().clearProgress()
    }
}
