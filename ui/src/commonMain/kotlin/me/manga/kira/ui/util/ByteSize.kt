package me.manga.kira.ui.util

import androidx.compose.runtime.Composable
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.size_bytes
import me.manga.kira.ui.generated.resources.size_gigabytes
import me.manga.kira.ui.generated.resources.size_kilobytes
import me.manga.kira.ui.generated.resources.size_megabytes
import me.manga.kira.ui.generated.resources.size_terabytes
import org.jetbrains.compose.resources.stringResource

/**
 * Bytes → human-readable size with localized units and two localized decimals for KB and above.
 * Matches native's default-locale `%.2f` for KB/MB/GB: `1.23 GB` becomes `1,23 Go` on French;
 * Arabic uses Arabic-Indic digits with localized unit words.
 * Binary thresholds select the unit before rounding; TB is the terminal unit for larger sizes.
 *
 * The numeric slot is pre-rendered via [formatLocalizedTwoDecimals] because compose-resources
 * string formatting has no float support — the native `%.2f` became a `%1$s` slot in the
 * `size_*` resource patterns. The bytes branch keeps native's plain, unshaped integer (`"$size B"`).
 *
 * Settings and Details keep raw byte counts in state; this composable formats them for the
 * current UI locale rather than retaining a preformatted string in state.
 */
@Composable
fun formatByteSize(size: Long): String {
    val kb = 1024L
    val mb = kb * 1024
    val gb = mb * 1024
    val tb = gb * 1024
    return when {
        size >= tb -> stringResource(Res.string.size_terabytes, formatLocalizedTwoDecimals(size.toDouble() / tb))
        size >= gb -> stringResource(Res.string.size_gigabytes, formatLocalizedTwoDecimals(size.toDouble() / gb))
        size >= mb -> stringResource(Res.string.size_megabytes, formatLocalizedTwoDecimals(size.toDouble() / mb))
        size >= kb -> stringResource(Res.string.size_kilobytes, formatLocalizedTwoDecimals(size.toDouble() / kb))
        else -> stringResource(Res.string.size_bytes, size)
    }
}
