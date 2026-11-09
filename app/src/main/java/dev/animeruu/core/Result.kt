package dev.animeruu.core

/** Kesalahan yang sudah dinormalisasi supaya UI bisa memberi pesan yang manusiawi. */
sealed class AppError(message: String) : Exception(message) {
    /** Tidak ada koneksi / timeout / DNS. */
    class Offline(cause: Throwable? = null) : AppError("Tidak ada koneksi internet") {
        init { cause?.let { initCause(it) } }
    }
    /** Server (API atau upstream-nya) gagal. */
    class Server(val code: Int, message: String) : AppError(message)
    /** Sumber tidak ditemukan (404). */
    class NotFound(message: String = "Tidak ditemukan") : AppError(message)
    /** Respons tidak bisa diparse. */
    class Parse(cause: Throwable) : AppError("Data dari server tidak terbaca") {
        init { initCause(cause) }
    }
}

fun Throwable.userMessage(): String = when (this) {
    is AppError.Offline -> "Sepertinya kamu sedang offline. Periksa koneksi lalu coba lagi."
    is AppError.Server -> if (code == 502) "Server sumber sedang sibuk. Coba lagi sebentar lagi." else "Server sedang bermasalah ($code)."
    is AppError.NotFound -> message ?: "Tidak ditemukan"
    is AppError.Parse -> "Data dari server tidak terbaca."
    else -> "Terjadi kesalahan. Coba lagi."
}

/** Status muat generik untuk layar. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T, val stale: Boolean = false) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}
