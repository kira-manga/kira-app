package me.manga.yamiapk.core.avif

enum class AvifSurfaceMode(internal val value: Int) {
    /**
     * Logic of adding alpha channel will be determined heuristically
     */
    AUTO(0),

    /**
     * Alpha channel will be dropped
     */
    RGB(1),

    /**
     * Alpha channel will be preserved
     */
    RGBA(2)
}