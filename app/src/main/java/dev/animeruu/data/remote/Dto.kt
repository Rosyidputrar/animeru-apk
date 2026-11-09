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
 * Upstream tidak konsisten soal tipe: angka bisa datang sebagai string, number, bahkan
 * objek/array kosong. Serializer ini menerima semuanya dan menghasilkan String? yang aman.
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

@Serializable
data class Envelope<T>(
    val ok: Boolean = false,
    val data: T? = null,
    val pagination: PaginationDto? = null,
    val error: ErrorDto? = null,
)

@Serializable
data class ErrorDto(val message: Flex = null, val upstream_status: Flex = null)

@Serializable
data class PaginationDto(
    val page: Int = 0,
    val per_page: Int = 0,
    val count: Int = 0,
    val has_next: Boolean = false,
    val next_page: Int? = null,
    val prev_page: Int? = null,
)

/**
 * Superset dari semua bentuk "item" yang muncul di API: Movie, item slider promosi,
 * trailer (id_movie + url_youtube), dan season. Semua opsional — hanya id/title yang
 * dianggap wajib dan itu pun divalidasi di mapper, bukan di sini.
 */
@Serializable
data class MovieDto(
    val id: Flex = null,
    val title: Flex = null,
    val synopsis: Flex = null,
    val synonyms: Flex = null,
    val image_poster: Flex = null,
    val image_cover: Flex = null,
    val image: Flex = null,
    val link: Flex = null,
    val type: Flex = null,
    val year: Flex = null,
    val day: Flex = null,
    val status: Flex = null,
    val views: Flex = null,
    val favorites: Flex = null,
    val aired_start: Flex = null,
    val aired_end: Flex = null,
    val genre: Flex = null,
    val studio: Flex = null,
    val key_status: Flex = null,
    val time: Flex = null,
    val key_time: Flex = null,
    val season: Flex = null,
    val id_movie: Flex = null,
    val url_youtube: Flex = null,
)

@Serializable
data class HomeDto(
    val slider: List<MovieDto> = emptyList(),
    val today: List<MovieDto> = emptyList(),
    val hot: List<MovieDto> = emptyList(),
    val new: List<MovieDto> = emptyList(),
    val popular: List<MovieDto> = emptyList(),
    val random: List<MovieDto> = emptyList(),
    val trailer: List<MovieDto> = emptyList(),
    val waiting: List<MovieDto> = emptyList(),
    val fyp: List<MovieDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    val id: Flex = null,
    val index: Flex = null,
    val title: Flex = null,
    val views: Flex = null,
    val id_movie: Flex = null,
    val key_time: Flex = null,
    val image: Flex = null,
    val is_new: Flex = null,
)

@Serializable
data class DetailDto(
    val movie: MovieDto? = null,
    val episode: EpisodeDto? = null,
    val season: List<MovieDto> = emptyList(),
)

@Serializable
data class ServerDto(
    val id: Flex = null,
    val name: Flex = null,
    val type: Flex = null,
    val quality: Flex = null,
    val link: Flex = null,
    val key_file_size: Flex = null,
)

@Serializable
data class StreamDto(
    val episode: EpisodeDto? = null,
    val episode_next: EpisodeDto? = null,
    val server: List<ServerDto> = emptyList(),
)

@Serializable
data class PosterDto(
    val kitsu_id: Flex = null,
    val title: Flex = null,
    val match: Flex = null,
    val subtype: Flex = null,
    val status: Flex = null,
    val rating: Flex = null,
    val episodes: Flex = null,
    val synopsis: Flex = null,
    val poster: Flex = null,
    val cover: Flex = null,
)
