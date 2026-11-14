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

/**
 * Aturan intro yang murni (tanpa Android) supaya bisa diuji unit.
 * Posisi intro TIDAK pernah disimpan diam-diam: hasil deteksi hanya ditawarkan ke pengguna.
 */
object IntroRules {
    /** Panjang opening anime umum; dipakai bila hanya satu ujung yang ditandai. */
    const val DEFAULT_LENGTH_MS = 85_000L
    private const val MIN_LENGTH_MS = 5_000L

    /**
     * Haruskah lompatan [candidate] ditawarkan sebagai posisi intro?
     * - Melompat MENUJU intro (berakhir sebelum intro dimulai) bukan melewati intro.
     * - Bila hampir sama dengan yang tersimpan (selisih ≤ 5 dtk), tidak perlu bertanya.
     */
    fun shouldOffer(candidate: IntroRange, known: IntroRange?): Boolean {
        if (known == null) return true
        if (candidate.endMs <= known.startMs + 10_000) return false
        return kotlin.math.abs(candidate.startMs - known.startMs) > 5_000 || kotlin.math.abs(candidate.endMs - known.endMs) > 5_000
    }

    /** "Intro mulai di sini": pertahankan akhir lama bila masih masuk akal, selain itu +85 dtk. */
    fun markStart(known: IntroRange?, posMs: Long): IntroRange {
        val end = known?.endMs?.takeIf { it - posMs >= MIN_LENGTH_MS } ?: (posMs + DEFAULT_LENGTH_MS)
        return IntroRange(posMs, end)
    }

    /** "Intro selesai di sini": pertahankan awal lama bila masih masuk akal, selain itu −85 dtk. */
    fun markEnd(known: IntroRange?, posMs: Long): IntroRange {
        val start = known?.startMs?.takeIf { posMs - it >= MIN_LENGTH_MS } ?: (posMs - DEFAULT_LENGTH_MS).coerceAtLeast(0)
        return IntroRange(start, posMs)
    }

    /**
     * Lewati otomatis bila posisi berada di awal intro (bukan di tengah/akhirnya — bila pengguna
     * sengaja mundur ke tengah intro, jangan ditarik lagi).
     */
    fun inAutoSkipWindow(r: IntroRange, posMs: Long) = posMs in (r.startMs - 500)..(r.startMs + 4_000)
}

/** Penyimpanan rentang intro per anime (lokal, SharedPreferences). */
class IntroStore(context: Context) {
    private val prefs = context.getSharedPreferences("intro_skips", Context.MODE_PRIVATE)

    fun get(animeId: String): IntroRange? = prefs.getString(animeId, null)?.split(',')?.let { p ->
        val s = p.getOrNull(0)?.toLongOrNull() ?: return null
        val e = p.getOrNull(1)?.toLongOrNull() ?: return null
        IntroRange(s, e)
    }

    /** Disimpan persis seperti yang dikonfirmasi pengguna (tanpa rata-rata tersembunyi). */
    fun set(animeId: String, r: IntroRange): IntroRange {
        prefs.edit { putString(animeId, "${r.startMs},${r.endMs}") }
        return r
    }

    fun forget(animeId: String) = prefs.edit { remove(animeId) }
    fun clear() = prefs.edit { clear() }
}
