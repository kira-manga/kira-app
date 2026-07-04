package me.manga.yamiapk.core.avif


enum class ScalingQuality(internal val level: Int) {
    /**
     * Bilinear
     */
    DEFAULT(0),

    /**
     * Nearest neighbors
     */
    FASTEST(1),

    /**
     * Lanczos 3
     */
    HIGH(2)
}