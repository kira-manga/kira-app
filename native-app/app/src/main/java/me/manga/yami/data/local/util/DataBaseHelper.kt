package me.manga.yamiapk.data.local.util

private const val SQLITE_MAX_VARS = 999
private const val SQLITE_SAFE_CHUNK = 900 // safe margin

// Generic chunker helper (optional)
private fun <T> List<T>.chunkedSafe(chunkSize: Int = SQLITE_SAFE_CHUNK): List<List<T>> =
    this.chunked(chunkSize)