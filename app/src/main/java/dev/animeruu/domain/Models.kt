package dev.animeruu.domain

import androidx.compose.runtime.Immutable

enum class AnimeKind { SERIES, MOVIE, OTHER;
    /** Semua yang bukan MOVIE (SERIES, ONA, OVA, …) diperlakukan sebagai seri berepisode. */
    val hasEpisodeList get() = this != MOVIE
}

@Immutable
data class Anime(
    val id: String,
    val title: String,
    val synopsis: String? = null,
    val synonyms: String? = null,
    val posterUrl: String? = null,
    val coverUrl: String? = null,
    val kind: AnimeKind = AnimeKind.SERIES,
    val typeLabel: String? = null,
    val year: String? = null,
    val day: String? = null,
    val status: String? = null,
    val views: Long? = null,
    val favorites: Long? = null,
    val airedStart: String? = null,
    val airedEnd: String? = null,
    val genres: List<String> = emptyList(),
    val studio: String? = null,
    /** Hitung mundur tayang dari jadwal, mis. "21j 50m". */
    val countdown: String? = null,
    /** Waktu tayang berikutnya, mis. "2026-11-09 20:10:21". */
    val nextAirAt: String? = null,
    val seasonLabel: String? = null,
    val trailerUrl: String? = null,
)

/** Banner promosi dari rail `slider` (bukan anime — tautan web milik sumber). */
@Immutable
data class PromoBanner(val id: String, val imageUrl: String, val link: String?)

@Immutable
data class HomeFeed(
    val promos: List<PromoBanner>,
    val today: List<Anime>,
    val hot: List<Anime>,
    val new: List<Anime>,
    val popular: List<Anime>,
    val random: List<Anime>,
    val trailer: List<Anime>,
    val waiting: List<Anime>,
    val fyp: List<Anime>,
) {
    /** Pilihan untuk hero: rilis hari ini dulu, lalu yang terpanas; unik per id. */
    val spotlight: List<Anime> by lazy {
        (today + hot.take(6)).distinctBy { it.id }.take(7)
    }
}

@Immutable
data class Episode(
    val id: String,
    val animeId: String?,
    /** Nomor episode numerik (bisa pecahan, mis. 1071.5) — null bila tidak terbaca. */
    val number: Double?,
    val numberLabel: String,
    val title: String,
    val views: Long?,
    val releasedAt: String?,
    val thumbUrl: String?,
    val isNew: Boolean,
)

@Immutable
data class StreamSource(
    val id: String,
    val server: String,
    val qualityLabel: String,
    /** 360/480/720/1080; null bila label tidak baku (mis. "XX0p"). */
    val height: Int?,
    val url: String,
    val sizeMb: Double?,
    val direct: Boolean,
) {
    val displayName get() = "$qualityLabel · $server"
}

@Immutable
data class StreamInfo(
    val episode: Episode,
    val next: Episode?,
    val sources: List<StreamSource>,
) {
    val playable get() = sources.filter { it.direct }
}

@Immutable
data class AnimeDetail(
    val anime: Anime,
    /** Episode pertama/terkait — untuk MOVIE inilah yang diputar. */
    val leadEpisode: Episode?,
    val seasons: List<Anime>,
)

@Immutable
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val hasNext: Boolean,
    val nextPage: Int?,
    val stale: Boolean = false,
)

@Immutable
data class PosterArt(
    val posterUrl: String?,
    val coverUrl: String?,
    /** 0–10 (dikonversi dari skala Kitsu 0–100). */
    val rating: Double?,
    val synopsisEn: String?,
    val exact: Boolean,
    val episodes: Int?,
)
