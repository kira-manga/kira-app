package me.manga.yamiapk.presentation.features.reader.data

import me.manga.yamiapk.presentation.features.reader.data.ReadingMode

// Helper: detect if readingMode is paginated
 val ReadingMode.isPaged: Boolean
    get() = this in listOf(
        ReadingMode.LEFT_TO_RIGHT,
        ReadingMode.RIGHT_TO_LEFT,
        ReadingMode.VERTICAL,
        ReadingMode.DEFAULT
    )
