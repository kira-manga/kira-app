package me.manga.yamiapk.presentation.features.complaint.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.manga.yamiapk.R

@Composable
fun apiLevelToAndroidVersion(apiLevel: Int): String = when (apiLevel) {
    34 -> "Android 14"
    33 -> "Android 13"
    32 -> "Android 12L"
    31 -> "Android 12"
    30 -> "Android 11"
    29 -> "Android 10"
    28 -> "Android 9 (Pie)"
    27 -> "Android 8.1 (Oreo)"
    26 -> "Android 8.0 (Oreo)"
    25 -> "Android 7.1.1 (Nougat)"
    24 -> "Android 7.0 (Nougat)"
    23 -> "Android 6.0 (Marshmallow)"
    22 -> "Android 5.1 (Lollipop)"
    21 -> "Android 5.0 (Lollipop)"
    20 -> "Android 4.4W (KitKat Wear)"
    19 -> "Android 4.4 (KitKat)"
    18 -> "Android 4.3 (Jelly Bean)"
    17 -> "Android 4.2 (Jelly Bean)"
    16 -> "Android 4.1 (Jelly Bean)"
    15 -> "Android 4.0.3 (Ice Cream Sandwich)"
    14 -> "Android 4.0 (Ice Cream Sandwich)"
    0 -> stringResource(R.string.filter_all)

    else -> stringResource(R.string.unknown)
}
