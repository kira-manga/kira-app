package me.manga.kira.platform.cbz

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar

/**
 * Borrowed view of one admitted RGBA region. The owning CGContext must stay alive throughout the
 * synchronous native encode; neither the codec nor a test override may retain [pixels] afterward.
 * Width/height/stride describe this region, not the entire source image or a new allocation.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosRgbaBand(
    val pixels: CPointer<UByteVar>,
    val width: Int,
    val height: Int,
    val stride: Int,
)
