package dev.animeruu.core

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Parser defensif: semua angka API datang sebagai string, bisa kosong/null/"UNKNOWN". */
fun String?.toLongOrNullSafe(): Long? = this?.trim()?.takeIf { it.isNotEmpty() }?.let {
    it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong()
}

fun String?.toDoubleOrNullSafe(): Double? = this?.trim()?.replace(',', '.')?.toDoubleOrNull()

fun String?.cleanOrNull(): String? = this?.trim()?.takeIf {
    it.isNotEmpty() && !it.equals("null", true) && it != "-" && !it.equals("UNKNOWN", true)
}

private val idSymbols = DecimalFormatSymbols(Locale("in", "ID"))

/** 16095803 → "16,1 jt", 15944 → "15,9 rb". */
fun Long.compact(): String = when {
    this >= 1_000_000_000 -> DecimalFormat("#.#", idSymbols).format(this / 1_000_000_000.0) + " M"
    this >= 1_000_000 -> DecimalFormat("#.#", idSymbols).format(this / 1_000_000.0) + " jt"
    this >= 1_000 -> DecimalFormat("#.#", idSymbols).format(this / 1_000.0) + " rb"
    else -> toString()
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
