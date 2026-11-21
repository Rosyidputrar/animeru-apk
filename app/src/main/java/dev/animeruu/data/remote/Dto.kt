package dev.animeruu.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Upstream tidak konsisten soal tipe: angka bisa datang sebagai string ("15.2k", "8,18"),
 * number, bahkan null. Serializer ini menerima semuanya dan menghasilkan String? yang aman.
 */
object FlexString : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String? {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        val el = json.decodeJsonElement()
        return when {
            el is JsonNull -> null
            el is JsonPrimitive -> el.content
            else -> null
        }
    }
    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeString("") else encoder.encodeString(value)
    }
}

typealias Flex = @Serializable(with = FlexString::class) String?

/** Amplop mentah anime-r: `{status, message, data}` atau `{status, message, items, ...}`. */
@Serializable
data class NanimeObjectResponse<T>(
    val status: Int? = null,
    val message: Flex = null,
    val data: T? = null,
    val error: Flex = null,
)

@Serializable
data class NanimePaginationDto(
    val page: Int = 1,
    val limit: Int = 20,
    val total: Int? = null,
    val totalPages: Int? = null,
    val hasNext: Boolean? = null,
    val hasPrev: Boolean? = null,
)

@Serializable
data class NanimeMetaDto(
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val totalPages: Int? = null,
)

/**
 * Daftar mentah anime-r. Bentuknya tidak seragam antar endpoint:
 * - `/anime/latest`, `/anime/popular/donghua`, `/anime/genre/:genre` → `items` + `total`/`totalPages` di root.
 * - `/anime`, `/anime/search` (berpaginasi), `/anime/a-z` → `data` + `pagination` atau `meta`.
 * - `/anime/search` (live, tanpa page) → `data` saja, tanpa info pagination.
 */
@Serializable
data class NanimeListResponse<T>(
    val status: Int? = null,
    val message: Flex = null,
    val data: List<T>? = null,
    val items: List<T>? = null,
    val pagination: NanimePaginationDto? = null,
    val meta: NanimeMetaDto? = null,
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val totalPages: Int? = null,
)

/** Badan error buatan anime-r sendiri (429/400/502) maupun passthrough upstream. */
@Serializable
data class NanimeErrorDto(
    val message: Flex = null,
    val error: Flex = null,
    val creator: Flex = null,
)

@Serializable
data class NanimeScheduleDto(
    val schedule_id: Flex = null,
    val hari: Flex = null,
    val jam: Flex = null,
    val is_active: Boolean = true,
)

@Serializable
data class NanimeLastEpisodeDto(
    val id: Flex = null,
    val nomor_episode: Flex = null,
    val judul_episode: Flex = null,
    val tanggal_rilis_episode: Flex = null,
)

/**
 * Satu judul anime Nanime. Semua opsional — hanya id/nama yang dianggap wajib dan itu
 * pun divalidasi di mapper, bukan di sini. Field tambahan (`jam`, `schedule_id`,
 * `last_episode`) hanya ada di beberapa endpoint (jadwal / terbaru) dan diabaikan di lain tempat.
 */
@Serializable
data class NanimeAnimeDto(
    val id: Flex = null,
    val nama_anime: Flex = null,
    val gambar_anime: Flex = null,
    val tags_anime: List<Flex> = emptyList(),
    val rating_anime: Flex = null,
    val view_anime: Flex = null,
    val tanggal_rilis_anime: Flex = null,
    val status_anime: Flex = null,
    val genre_anime: List<Flex> = emptyList(),
    val sinopsis_anime: Flex = null,
    val label_anime: Flex = null,
    val studio_anime: List<Flex> = emptyList(),
    val content_type: Flex = null,
    val title_en: Flex = null,
    val title_jp: Flex = null,
    val schedules: List<NanimeScheduleDto> = emptyList(),
    val episodes: List<NanimeEpisodeDto> = emptyList(),
    val episodes_count: Flex = null,
    val last_episode: NanimeLastEpisodeDto? = null,
    val jam: Flex = null,
    val schedule_id: Flex = null,
    val latest_episode_number: Flex = null,
)

@Serializable
data class NanimeQualityDto(
    val id: Flex = null,
    val episode_id: Flex = null,
    val nama_quality: Flex = null,
    val source_quality: Flex = null,
    val hls_url: Flex = null,
    val hls_size: Flex = null,
)

/**
 * Satu episode Nanime. `qualities` berisi URL MP4 langsung per kualitas (360p–1080p).
 * Field `anime` di endpoint daftar/stream hanya ringkasan (id, nama, gambar, rating,
 * views, status) — bukan objek penuh — jadi aman dari rekursi.
 */
@Serializable
data class NanimeEpisodeDto(
    val id: Flex = null,
    val anime_id: Flex = null,
    val nomor_episode: Flex = null,
    val judul_episode: Flex = null,
    val thumbnail_episode: Flex = null,
    val deskripsi_episode: Flex = null,
    val durasi_episode: Flex = null,
    val tanggal_rilis_episode: Flex = null,
    val view_episode: Flex = null,
    val intro_start_seconds: Flex = null,
    val intro_duration_seconds: Flex = null,
    val outro_start_seconds: Flex = null,
    val outro_duration_seconds: Flex = null,
    val hls_master_url: Flex = null,
    val qualities: List<NanimeQualityDto> = emptyList(),
    val anime: NanimeAnimeDto? = null,
)

/** Satu hari di `/anime/schedule`: kunci peta adalah tanggal ISO `yyyy-MM-dd`. */
@Serializable
data class NanimeScheduleDayDto(
    val date: Flex = null,
    val hari: Flex = null,
    val day_key: Flex = null,
    val anime: List<NanimeAnimeDto> = emptyList(),
)

@Serializable
data class NanimeGenresDataDto(
    val genres: List<String> = emptyList(),
    val totalGenres: Int? = null,
    val totalAnimes: Int? = null,
)
