package me.manga.yamiapk.presentation.features.reader.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import me.manga.yamiapk.R

enum class ReadingMode(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int
) {
    DEFAULT(
        R.drawable.ic_reader_continuous_vertical_24dp,
        R.string.reading_mode_default
    ),
    RIGHT_TO_LEFT(
        R.drawable.ic_reader_rtl_24dp,
        R.string.reading_mode_right_to_left
    ),
    LEFT_TO_RIGHT(
        R.drawable.ic_reader_ltr,
        R.string.reading_mode_left_to_right
    ),
    VERTICAL(
        R.drawable.ic_reader_vertical_24dp,
        R.string.reading_mode_vertical
    ),
    WEBTOON(
        R.drawable.ic_reader_webtoon_24dp,
        R.string.reading_mode_webtoon
    ),
    CONTINUOUS_VERTICAL(
        R.drawable.ic_reader_continuous_vertical_24dp,
        R.string.reading_mode_continuous_vertical
    );
}