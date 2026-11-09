package dev.animeruu.feature

/**
 * Logika murni untuk notifikasi episode baru.
 * @param known nomor episode terakhir yang sudah diketahui (null = belum ada baseline).
 * @param latest nomor episode terbaru dari API.
 * @return jumlah episode baru yang layak dinotifikasi (0 bila baseline baru / tidak ada yang baru).
 */
fun newEpisodeCount(known: Double?, latest: Double?): Int {
    if (known == null || latest == null) return 0
    if (latest <= known) return 0
    // Episode bernomor pecahan (mis. 1071.5) dihitung sebagai satu rilis.
    return maxOf(1, (latest - known).toInt())
}
