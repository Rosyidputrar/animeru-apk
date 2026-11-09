package dev.animeruu.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** Nama hari sesuai kontrak API (Bahasa Indonesia, huruf besar). */
enum class Day(val api: String, val label: String, val short: String, val dow: DayOfWeek) {
    SENIN("SENIN", "Senin", "Sen", DayOfWeek.MONDAY),
    SELASA("SELASA", "Selasa", "Sel", DayOfWeek.TUESDAY),
    RABU("RABU", "Rabu", "Rab", DayOfWeek.WEDNESDAY),
    KAMIS("KAMIS", "Kamis", "Kam", DayOfWeek.THURSDAY),
    JUMAT("JUMAT", "Jumat", "Jum", DayOfWeek.FRIDAY),
    SABTU("SABTU", "Sabtu", "Sab", DayOfWeek.SATURDAY),
    MINGGU("MINGGU", "Minggu", "Min", DayOfWeek.SUNDAY);

    companion object {
        /** Hari ini menurut Asia/Jakarta — sama dengan bawaan API. */
        fun today(): Day {
            val dow = LocalDate.now(ZoneId.of("Asia/Jakarta")).dayOfWeek
            return entries.first { it.dow == dow }
        }
        fun fromApi(v: String?): Day? = entries.firstOrNull { it.api.equals(v?.trim(), true) }
    }
}
