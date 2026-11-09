package dev.animeruu.feature

import android.content.Context
import androidx.core.content.edit

/** Rentang intro (opening) yang dipelajari untuk satu seri. */
data class IntroRange(val startMs: Long, val endMs: Long) {
    val lengthMs get() = endMs - startMs
}

/**
 * Mempelajari posisi intro dari cara pengguna melompat.
 *
 * Satu "lompatan" = rangkaian seek maju yang berdekatan (mis. ketuk-dua-kali +10 dtk berulang,
 * atau satu geser seek bar). Rangkaian dianggap lompat-intro bila:
 * - dimulai di 10 menit pertama,
 * - total maju 50–180 detik (panjang opening anime umumnya ±90 dtk),
 * - seek berikutnya terjadi ≤ 4 dtk setelah yang sebelumnya dan bersambung dari posisi itu.
 *
 * Kelas murni (tanpa Android) supaya bisa diuji unit.
 */
class IntroLearner(
    private val gapMs: Long = 4_000,
    private val minSkipMs: Long = 50_000,
    private val maxSkipMs: Long = 180_000,
    private val latestStartMs: Long = 10 * 60_000,
) {
    private var chainStart = -1L
    private var chainEnd = -1L
    private var lastAt = 0L

    /** Lapor satu seek. [now] = jam dinding (ms). */
    fun onSeek(fromMs: Long, toMs: Long, now: Long) {
        val continues = chainStart >= 0 && now - lastAt <= gapMs && kotlin.math.abs(fromMs - chainEnd) <= 3_000
        if (continues) chainEnd = toMs
        else { chainStart = fromMs; chainEnd = toMs }
        lastAt = now
    }

    /** Panggil berkala; mengembalikan intro yang terpelajari begitu rangkaian seek selesai. */
    fun poll(now: Long): IntroRange? {
        if (chainStart < 0 || now - lastAt <= gapMs) return null
        val r = IntroRange(chainStart, chainEnd)
        chainStart = -1; chainEnd = -1
        return r.takeIf { it.startMs <= latestStartMs && it.lengthMs in minSkipMs..maxSkipMs }
    }

    fun reset() { chainStart = -1; chainEnd = -1 }
}

/** Penyimpanan rentang intro per anime (lokal, SharedPreferences). */
class IntroStore(context: Context) {
    private val prefs = context.getSharedPreferences("intro_skips", Context.MODE_PRIVATE)

    fun get(animeId: String): IntroRange? = prefs.getString(animeId, null)?.split(',')?.let { p ->
        val s = p.getOrNull(0)?.toLongOrNull() ?: return null
        val e = p.getOrNull(1)?.toLongOrNull() ?: return null
        IntroRange(s, e)
    }

    /** Intro antar episode bergeser beberapa detik → simpan rata-rata berbobot dengan nilai lama. */
    fun learn(animeId: String, r: IntroRange): IntroRange {
        val old = get(animeId)
        val merged = if (old != null && kotlin.math.abs(old.startMs - r.startMs) < 60_000) {
            IntroRange((old.startMs * 2 + r.startMs) / 3, (old.endMs * 2 + r.endMs) / 3)
        } else r
        prefs.edit { putString(animeId, "${merged.startMs},${merged.endMs}") }
        return merged
    }

    fun forget(animeId: String) = prefs.edit { remove(animeId) }
    fun clear() = prefs.edit { clear() }
}
