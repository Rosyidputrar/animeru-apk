package dev.animeruu.data.remote

import dev.animeruu.core.cleanOrNull
import dev.animeruu.core.toDoubleOrNullSafe
import dev.animeruu.core.toLongOrNullSafe
import dev.animeruu.domain.Anime
import dev.animeruu.domain.AnimeDetail
import dev.animeruu.domain.AnimeKind
import dev.animeruu.domain.Episode
import dev.animeruu.domain.HomeFeed
import dev.animeruu.domain.PosterArt
import dev.animeruu.domain.PromoBanner
import dev.animeruu.domain.StreamInfo
import dev.animeruu.domain.StreamSource

private fun absoluteUrl(raw: String?): String? {
    val v = raw.cleanOrNull() ?: return null
    return when {
        v.startsWith("http://") || v.startsWith("https://") -> v
        v.startsWith("//") -> "https:$v"
        v.startsWith("/") -> ApiClient.CDN_HOST + v
        else -> "${ApiClient.CDN_HOST}/$v"
    }
}

private fun String.normalizeText(): String = replace("\r\n", "\n").replace('\r', '\n').trim()

/** null bila item tidak punya id/title (data upstream tidak lengkap → dibuang, bukan crash). */
fun MovieDto.toAnime(): Anime? {
    val id = (id_movie.cleanOrNull()?.takeIf { url_youtube != null } ?: id.cleanOrNull()) ?: return null
    val title = title.cleanOrNull() ?: return null
    val kindRaw = type.cleanOrNull()?.uppercase()
    return Anime(
        id = id,
        title = title.trim(),
        synopsis = synopsis.cleanOrNull()?.normalizeText(),
        synonyms = synonyms.cleanOrNull(),
        posterUrl = absoluteUrl(image_poster),
        coverUrl = absoluteUrl(image_cover),
        kind = when (kindRaw) {
            "MOVIE" -> AnimeKind.MOVIE
            "SERIES" -> AnimeKind.SERIES
            null -> AnimeKind.SERIES
            else -> AnimeKind.OTHER
        },
        typeLabel = kindRaw,
        year = year.cleanOrNull(),
        day = day.cleanOrNull()?.takeIf { !it.equals("RANDOM", true) },
        status = status.cleanOrNull(),
        views = views.toLongOrNullSafe(),
        favorites = favorites.toLongOrNullSafe(),
        airedStart = aired_start.cleanOrNull(),
        airedEnd = aired_end.cleanOrNull(),
        genres = genre.cleanOrNull()?.split(',')?.mapNotNull { it.cleanOrNull() }?.distinct().orEmpty(),
        studio = studio.cleanOrNull(),
        countdown = time.cleanOrNull(),
        nextAirAt = key_time.cleanOrNull(),
        seasonLabel = season.cleanOrNull(),
        trailerUrl = url_youtube.cleanOrNull(),
    )
}

fun List<MovieDto>.toAnimeList(): List<Anime> = mapNotNull { it.toAnime() }.distinctBy { it.id }

fun MovieDto.toPromo(): PromoBanner? {
    val img = absoluteUrl(image) ?: return null
    return PromoBanner(id = id.cleanOrNull() ?: img, imageUrl = img, link = link.cleanOrNull())
}

fun HomeDto.toDomain() = HomeFeed(
    promos = slider.filter { it.title.cleanOrNull() == null }.mapNotNull { it.toPromo() },
    // Bila suatu saat slider berisi anime sungguhan, ia ikut ke rail "today" sebagai sorotan.
    today = (slider.filter { it.title.cleanOrNull() != null } + today).toAnimeList(),
    hot = hot.toAnimeList(),
    new = new.toAnimeList(),
    popular = popular.toAnimeList(),
    random = random.toAnimeList(),
    trailer = trailer.toAnimeList(),
    waiting = waiting.toAnimeList(),
    fyp = fyp.toAnimeList(),
)

fun EpisodeDto.toEpisode(): Episode? {
    val id = id.cleanOrNull() ?: return null
    val num = index.toDoubleOrNullSafe()
    val label = when {
        num == null -> index.cleanOrNull() ?: "?"
        num % 1.0 == 0.0 -> num.toLong().toString()
        else -> num.toString()
    }
    return Episode(
        id = id,
        animeId = id_movie.cleanOrNull(),
        number = num,
        numberLabel = label,
        title = title.cleanOrNull() ?: "Episode $label",
        views = views.toLongOrNullSafe(),
        releasedAt = key_time.cleanOrNull(),
        thumbUrl = absoluteUrl(image),
        isNew = is_new == "1",
    )
}

private val heightRegex = Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE)

fun ServerDto.toSource(): StreamSource? {
    val url = link.cleanOrNull()?.takeIf { it.startsWith("http") } ?: return null
    val q = quality.cleanOrNull() ?: "Auto"
    return StreamSource(
        id = id.cleanOrNull() ?: url,
        server = name.cleanOrNull() ?: "Server",
        qualityLabel = q,
        height = heightRegex.find(q)?.groupValues?.get(1)?.toIntOrNull(),
        url = url,
        sizeMb = key_file_size.toDoubleOrNullSafe(),
        direct = type.equals("direct", true),
    )
}

fun StreamDto.toDomain(): StreamInfo? {
    val ep = episode?.toEpisode() ?: return null
    return StreamInfo(
        episode = ep,
        next = episode_next?.toEpisode(),
        sources = server.mapNotNull { it.toSource() }.distinctBy { it.url },
    )
}

fun DetailDto.toDomain(): AnimeDetail? {
    val a = movie?.toAnime() ?: return null
    return AnimeDetail(
        anime = a,
        leadEpisode = episode?.toEpisode(),
        seasons = season.toAnimeList(),
    )
}

fun PosterDto.toDomain() = PosterArt(
    posterUrl = poster.cleanOrNull(),
    coverUrl = cover.cleanOrNull(),
    rating = rating.toDoubleOrNullSafe()?.let { it / 10.0 },
    synopsisEn = synopsis.cleanOrNull(),
    exact = match.equals("exact", true),
    episodes = episodes.toLongOrNullSafe()?.toInt(),
)
