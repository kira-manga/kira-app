package me.manga.kira.ui.util

import androidx.compose.runtime.Composable
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.size_bytes
import me.manga.kira.ui.generated.resources.size_gigabytes
import me.manga.kira.ui.generated.resources.size_kilobytes
import me.manga.kira.ui.generated.resources.size_megabytes
import org.jetbrains.compose.resources.stringResource

/**
 * Bytes → human-readable size with LOCALIZED units + digits (native parity:
 * `R.string.{bytes,kilobytes,megabytes,gigabytes}` rendered through the default-locale `%.2f` —
 * e.g. `1.23 GB` → `1,23 Go` on French, Arabic-Indic digits with Arabic unit words on Arabic).
 *
 * The numeric slot is pre-rendered via [formatLocalizedTwoDecimals] because compose-resources
 * string formatting has no float support — the native `%.2f` became a `%1$s` slot in the
 * `size_*` patterns (`strings_pfix_size_units.xml`). The bytes branch keeps native's plain
 * un-shaped integer (`"$size B"`).
 *
 * Typed wire (2026-07 backlog L15): `:data` hands RAW bytes up (`SettingsSnapshot.cacheSizeBytes`);
 * this helper is the single place the number becomes display text.
 */
@Composable
fun formatByteSize(size: Long): String {
    val kb = 1024L
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        size >= gb -> stringResource(Res.string.size_gigabytes, formatLocalizedTwoDecimals(size.toDouble() / gb))
        size >= mb -> stringResource(Res.string.size_megabytes, formatLocalizedTwoDecimals(size.toDouble() / mb))
        size >= kb -> stringResource(Res.string.size_kilobytes, formatLocalizedTwoDecimals(size.toDouble() / kb))
        else -> stringResource(Res.string.size_bytes, size)
    }
}
