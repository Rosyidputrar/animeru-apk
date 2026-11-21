package dev.animeruu.data.remote

import dev.animeruu.core.cleanOrNull
import dev.animeruu.core.toDoubleOrNullSafe
import dev.animeruu.core.toLongOrNullSafe
import dev.animeruu.domain.Anime
import dev.animeruu.domain.AnimeDetail
import dev.animeruu.domain.AnimeKind
import dev.animeruu.domain.Episode
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

/** "15.2k" → 15200, "26.2k" → 26200, "26201" → 26201, "1.4k" → 1400. */
fun parseViews(raw: String?): Long? {
    val v = raw.cleanOrNull()?.lowercase() ?: return null
    return when {
        v.endsWith("k") -> v.dropLast(1).toDoubleOrNullSafe()?.times(1000)?.toLong()
        v.endsWith("m") -> v.dropLast(1).toDoubleOrNullSafe()?.times(1_000_000)?.toLong()
        else -> v.toLongOrNullSafe() ?: v.toDoubleOrNullSafe()?.toLong()
    }
}

private fun parseYear(raw: String?): String? =
    raw?.let { Regex("""\d{4}""").find(it)?.value }

private fun flexList(raw: List<Flex>): List<String> =
    raw.mapNotNull { it.cleanOrNull() }.distinct()

/**
 * Satu judul Nanime → [Anime].
 * @param dayLabel hari tayang dari jadwal (mis. "Sabtu"), bila pemanggil tahu konteksnya.
 * @param airDate tanggal tayang `yyyy-MM-dd` dari jadwal, bila ada.
 * @param airTime jam tayang `HH:MM` dari jadwal, bila ada (selain "Random").
 */
fun NanimeAnimeDto.toAnime(dayLabel: String? = null, airDate: String? = null, airTime: String? = null): Anime? {
    val id = id.cleanOrNull() ?: return null
    val title = nama_anime.cleanOrNull() ?: return null
    val labelRaw = label_anime.cleanOrNull()?.uppercase()
    val isMovie = labelRaw == "MOVIE" || content_type.cleanOrNull()?.uppercase() == "FILM"
    val day = dayLabel
        ?: schedules.firstOrNull { it.hari.cleanOrNull()?.equals("Random", true) == false }?.hari?.cleanOrNull()
    val time = airTime?.takeIf { it.matches(Regex("""\d{2}:\d{2}""")) }
        ?: jam.cleanOrNull()?.takeIf { it.matches(Regex("""\d{2}:\d{2}""")) }
    return Anime(
        id = id,
        title = title.trim(),
        synopsis = sinopsis_anime.cleanOrNull()?.normalizeText(),
        synonyms = listOfNotNull(title_en.cleanOrNull(), title_jp.cleanOrNull())
            .joinToString(" / ").takeIf { it.isNotEmpty() },
        posterUrl = absoluteUrl(gambar_anime),
        // Nanime tidak punya cover terpisah; poster dipakai juga sebagai cover (di-crop UI).
        coverUrl = absoluteUrl(gambar_anime),
        kind = if (isMovie) AnimeKind.MOVIE else AnimeKind.SERIES,
        typeLabel = labelRaw ?: content_type.cleanOrNull()?.uppercase(),
        year = parseYear(tanggal_rilis_anime),
        day = day,
        status = status_anime.cleanOrNull(),
        views = parseViews(view_anime),
        favorites = null, // Nanime tidak menyediakan jumlah favorit
        airedStart = tanggal_rilis_anime.cleanOrNull(),
        airedEnd = null,
        genres = flexList(genre_anime),
        studio = flexList(studio_anime).joinToString(", ").takeIf { it.isNotEmpty() },
        countdown = null, // Nanime tidak menyediakan hitung mundur
        nextAirAt = if (time != null && airDate != null) "$airDate $time" else null,
        seasonLabel = null, // Nanime tidak punya daftar season
        trailerUrl = null, // Nanime tidak menyediakan trailer YouTube
    )
}

fun List<NanimeAnimeDto>.toAnimeList(): List<Anime> = mapNotNull { it.toAnime() }.distinctBy { it.id }

fun NanimeEpisodeDto.toEpisode(): Episode? {
    val id = id.cleanOrNull() ?: return null
    val num = nomor_episode.toDoubleOrNullSafe()
    val label = when {
        num == null -> nomor_episode.cleanOrNull() ?: "?"
        num % 1.0 == 0.0 -> num.toLong().toString()
        else -> num.toString()
    }
    return Episode(
        id = id,
        animeId = anime_id.cleanOrNull() ?: anime?.id.cleanOrNull(),
        number = num,
        numberLabel = label,
        title = judul_episode.cleanOrNull() ?: "Episode $label",
        views = parseViews(view_episode),
        releasedAt = tanggal_rilis_episode.cleanOrNull(),
        thumbUrl = absoluteUrl(thumbnail_episode),
        isNew = false, // Nanime tidak menandai episode baru
    )
}

private val heightRegex = Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE)

fun NanimeQualityDto.toSource(): StreamSource? {
    val url = (hls_url.cleanOrNull() ?: source_quality.cleanOrNull())
        ?.takeIf { it.startsWith("http") } ?: return null
    val q = nama_quality.cleanOrNull() ?: "Auto"
    return StreamSource(
        id = id.cleanOrNull() ?: url,
        server = "Nanime",
        qualityLabel = q,
        height = heightRegex.find(q)?.groupValues?.get(1)?.toIntOrNull(),
        url = url,
        sizeMb = hls_size.toDoubleOrNullSafe(),
        direct = true, // semua kualitas Nanime adalah MP4 langsung
    )
}

/** Episode stream + episode sebelum/berikutnya (dari daftar episode yang sudah di-cache, bila ada). */
fun NanimeEpisodeDto.toStream(next: Episode? = null, prev: Episode? = null): StreamInfo? {
    val ep = toEpisode() ?: return null
    return StreamInfo(
        episode = ep,
        next = next,
        sources = qualities.mapNotNull { it.toSource() }.distinctBy { it.url },
        prev = prev,
    )
}

fun NanimeAnimeDto.toDetail(): AnimeDetail? {
    val a = toAnime() ?: return null
    val eps = episodes.mapNotNull { it.toEpisode() }.sortedBy { it.number ?: Double.MAX_VALUE }
    return AnimeDetail(
        anime = a,
        // Episode pertama (terendah) — untuk film inilah satu-satunya episode yang diputar.
        leadEpisode = eps.firstOrNull(),
        seasons = emptyList(), // Nanime tidak punya daftar season
    )
}
