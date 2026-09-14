package me.manga.kira.core.cbz

import java.io.File
import java.io.IOException

internal fun validateCbzSourceBounds(
    file: File,
    width: Int,
    height: Int,
) {
    if (width <= 0 || height <= 0) throw IOException("Invalid CBZ source: ${file.name}")
}
