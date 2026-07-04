package me.manga.yamiapk.core.cbz

data class CbzSettings(
    val maxParallelDecode: Int,
    val maxParallelCompress: Int,
    val regionDecodeThreshold: Int,
    val samplingThreshold: Long,
    val webpQuality: Int
)
