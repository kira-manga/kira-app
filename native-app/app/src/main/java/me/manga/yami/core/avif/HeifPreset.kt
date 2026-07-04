package me.manga.yamiapk.core.avif

import androidx.annotation.Keep

@Keep

enum class HeifPreset(internal val value: Int) {
    PLACEBO(0),
    VERYSLOW(1),
    SLOWER(2),
    SLOW(3),
    MEDIUM(4),
    FAST(5),
    FASTER(6),
    VERYFAST(7),
    SUPERFAST(8),
    ULTRAFAST(9),
}