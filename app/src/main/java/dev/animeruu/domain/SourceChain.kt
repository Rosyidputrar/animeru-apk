package dev.animeruu.domain

import kotlin.math.abs

/**
 * Menyusun rantai fallback pemutaran.
 * - Hanya server "direct" (MP4 langsung) yang dipakai; "semi" adalah halaman embed web.
 * - Target kualitas: pilihan pengguna, atau Otomatis (720p di jaringan tak-berkuota, 480p di seluler).
 * - Urutan: paling dekat ke target; bila berjarak sama, kualitas lebih rendah didahulukan
 *   (lebih mungkin lancar). Kualitas tak baku ditaruh paling belakang.
 */
object SourceChain {
    fun order(sources: List<StreamSource>, preferredHeight: Int, unmetered: Boolean): List<StreamSource> {
        val target = if (preferredHeight > 0) preferredHeight else if (unmetered) 720 else 480
        return sources.filter { it.direct }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<StreamSource>> { iv ->
                    val h = iv.value.height ?: return@compareBy Double.MAX_VALUE
                    if (h <= target) (target - h).toDouble() else (h - target) * 1.5 + 1
                }.thenBy { it.index }
            )
            .map { it.value }
    }
}

/**
 * Daftar episode API menurun (halaman 0 = terbaru) tanpa total. Untuk melompat ke
 * episode N atau ke halaman terakhir (episode paling awal) kita menebak halaman dari
 * selisih nomor lalu mengoreksi berdasarkan isi halaman yang benar-benar dimuat —
 * biasanya 1–2 permintaan, bukan memuat puluhan halaman berurutan.
 */
class EpisodeLocator(
    private val perPage: Int = 30,
    private val load: suspend (page: Int) -> Page<Episode>,
) {
    /** Halaman yang memuat episode bernomor [target], atau halaman terdekat. */
    suspend fun pageFor(target: Double, latest: Double): Int {
        var p = ((latest - target) / perPage).toInt().coerceAtLeast(0)
        val seen = HashSet<Int>()
        repeat(8) {
            if (!seen.add(p)) return p
            val page = load(p)
            val nums = page.items.mapNotNull { it.number }
            if (nums.isEmpty()) {
                if (p == 0) return 0
                p = (p - 1).coerceAtLeast(0) // menebak terlalu jauh
                return@repeat
            }
            val hi = nums.max()
            val lo = nums.min()
            p = when {
                target > hi -> (p - maxOf(1, ((target - hi) / perPage).toInt())).coerceAtLeast(0)
                target < lo && page.hasNext -> p + maxOf(1, ((lo - target) / perPage).toInt())
                else -> return p
            }
        }
        return p
    }

    /** Halaman terakhir (berisi episode paling awal). */
    suspend fun lastPage(latest: Double): Int {
        var p = ((latest - 1) / perPage).toInt().coerceAtLeast(0)
        val seen = HashSet<Int>()
        repeat(10) {
            if (!seen.add(p)) return p
            val page = load(p)
            p = when {
                page.items.isEmpty() && p > 0 -> p - 1
                page.hasNext -> p + 1
                else -> return p
            }
        }
        return p
    }
}
