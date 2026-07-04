package me.manga.kira.ui.util

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the typed cache-size wire's display rendering (backlog L15b): [formatByteSize] must pick
 * the native unit thresholds (1024-based), render exactly two locale-aware fraction digits, and
 * substitute through the real `size_*` resource patterns (including the `%1$s` slot compose-
 * resources formatting requires in place of native's `%.2f`).
 *
 * The JVM default locale is pinned to US for the duration so the decimal separator ('.') and the
 * resolved base-locale patterns are machine-independent.
 */
@OptIn(ExperimentalTestApi::class)
class ByteSizeFormatTest {

    private fun renderAll(vararg sizes: Long): List<String> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            lateinit var rendered: List<String>
            runComposeUiTest {
                setContent {
                    rendered = sizes.map { formatByteSize(it) }
                }
            }
            return rendered
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun formatsEachUnitBranch_withTwoDecimals() {
        val (bytes, kilo, mega, giga) = renderAll(
            202L,
            1_536L, // 1.5 KB
            5_767_168L, // 5.5 MB
            1_320_702_444L, // ~1.23 GB
        )
        assertEquals("202 B", bytes)
        assertEquals("1.50 KB", kilo)
        assertEquals("5.50 MB", mega)
        assertEquals("1.23 GB", giga)
    }

    @Test
    fun unitBoundaries_matchNativeThresholds() {
        val (justUnderKb, exactKb, justUnderMb, exactGb) = renderAll(
            1_023L, // below 1 KB stays bytes
            1_024L, // exactly 1 KB
            1_048_575L, // one byte under 1 MB stays KB (renders as 1024.00 KB, native behavior)
            1_073_741_824L, // exactly 1 GB
        )
        assertEquals("1023 B", justUnderKb)
        assertEquals("1.00 KB", exactKb)
        assertEquals("1024.00 KB", justUnderMb)
        assertEquals("1.00 GB", exactGb)
    }
}
