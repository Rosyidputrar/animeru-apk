package dev.animeruu.update

import dev.animeruu.data.remote.Flex
import kotlinx.serialization.Serializable

@Serializable
data class GhAsset(
    val name: Flex = null,
    val browser_download_url: Flex = null,
    val size: Long = 0,
    val content_type: Flex = null,
    /** GitHub mengisi "sha256:<hex>" untuk aset rilis baru. */
    val digest: Flex = null,
)

@Serializable
data class GhRelease(
    val tag_name: Flex = null,
    val name: Flex = null,
    val body: Flex = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val published_at: Flex = null,
    val html_url: Flex = null,
    val assets: List<GhAsset> = emptyList(),
)

/** Rilis yang layak dipasang: punya versi terbaca dan satu aset APK. */
@Serializable
data class ReleaseInfo(
    val version: String,
    val tag: String,
    val title: String,
    val notes: String,
    val publishedAt: String?,
    val pageUrl: String?,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    val sha256: String?,
)

fun GhRelease.toInfo(): ReleaseInfo? {
    if (draft || prerelease) return null
    val tag = tag_name?.trim().orEmpty()
    val v = Version.parse(tag) ?: return null
    val apk = assets.firstOrNull { it.name?.endsWith(".apk", true) == true && it.browser_download_url != null } ?: return null
    return ReleaseInfo(
        version = v.toString(),
        tag = tag,
        title = name?.takeIf { it.isNotBlank() } ?: "Animeruu $tag",
        notes = cleanNotes(body.orEmpty()),
        publishedAt = published_at,
        pageUrl = html_url,
        apkUrl = apk.browser_download_url!!,
        apkName = apk.name!!,
        sizeBytes = apk.size,
        sha256 = apk.digest?.takeIf { it.startsWith("sha256:", true) }?.substringAfter(':')?.lowercase(),
    )
}

/** Markdown catatan rilis → teks rapi (tanpa tautan PR mentah & penanda markdown). */
fun cleanNotes(md: String): String = md.lines()
    .map { line ->
        line.trim()
            .replace(Regex("""^#{1,6}\s*"""), "")
            .replace(Regex("""^[*-]\s+"""), "• ")
            .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
            .replace(Regex("""(?<![\w*])[*_](\S(?:.*?\S)?)[*_](?![\w*])"""), "$1")
            .replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""\[(.+?)]\((.+?)\)"""), "$1")
            .replace(Regex("""\s+by @\S+ in https://\S+"""), "")
            .replace(Regex("""https://github\.com/\S+/(pull|compare)/\S+"""), "")
    }
    .filterNot { it.startsWith("Full Changelog", true) }
    .joinToString("\n")
    .replace(Regex("""\n{3,}"""), "\n\n")
    .trim()
