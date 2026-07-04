package me.manga.yamiapk.core.avif

import androidx.annotation.Keep

@Keep
enum class AvifChromaSubsampling(val value: Int) {
    /**
     * On auto mode chroma subsampling will be determined based on quality
     */
    AUTO(0),
    YUV420(1),
    YUV422(2),
    YUV444(3),
    YUV400(4),
    LOSELESS(5)
}