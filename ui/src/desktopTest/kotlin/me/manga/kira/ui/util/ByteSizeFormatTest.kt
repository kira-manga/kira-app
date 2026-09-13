package me.manga.kira.ui.util

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the shared Settings/Details size rendering: [formatByteSize] picks binary unit thresholds,
 * renders two localized decimals for KB through terminal TB, and substitutes through the real
 * `size_*` resource patterns. B deliberately keeps whole, unshaped integer digits in every locale.
 *
 * Each rendering pins and restores the JVM default locale so digits, separators and unit patterns
 * are machine-independent.
 */
@OptIn(ExperimentalTestApi::class)
class ByteSizeFormatTest {

    private fun renderAll(locale: Locale, vararg sizes: Long): List<String> {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
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
        val sizes = longArrayOf(
            202L,
            1_536L, // 1.5 KB
            5_767_168L, // 5.5 MB
            1_320_702_444L, // ~1.23 GB
            1_649_267_441_664L, // 1.5 TB
        )
        listOf(
            Locale.US to listOf("202 B", "1.50 KB", "5.50 MB", "1.23 GB", "1.50 TB"),
            Locale.FRENCH to listOf("202 o", "1,50 Ko", "5,50 Mo", "1,23 Go", "1,50 To"),
            Locale.forLanguageTag("ar") to listOf(
                "202 ب", "١٫٥٠ كيلوبايت", "٥٫٥٠ ميغابايت", "١٫٢٣ غيغابايت", "١٫٥٠ تيرابايت",
            ),
        ).forEach { (locale, expected) ->
            assertEquals(expected, renderAll(locale, *sizes), locale.toLanguageTag())
        }
    }

    @Test
    fun unitBoundaries_matchNativeThresholds() {
        val sizes = longArrayOf(
            1_023L, // below 1 KB stays bytes
            1_024L, // exactly 1 KB
            1_048_575L, // one byte under 1 MB stays KB (renders as 1024.00 KB, native behavior)
            1_048_576L, // exactly 1 MB
            1_073_741_823L, // one byte under 1 GB stays MB
            1_073_741_824L, // exactly 1 GB
            1_099_511_627_775L, // one byte under 1 TB stays GB, even when rounding to 1024.00
            1_099_511_627_776L, // exactly 1 TB
        )
        listOf(
            Locale.US to listOf(
                "1023 B", "1.00 KB", "1024.00 KB", "1.00 MB",
                "1024.00 MB", "1.00 GB", "1024.00 GB", "1.00 TB",
            ),
            Locale.FRENCH to listOf(
                "1023 o", "1,00 Ko", "1024,00 Ko", "1,00 Mo",
                "1024,00 Mo", "1,00 Go", "1024,00 Go", "1,00 To",
            ),
            Locale.forLanguageTag("ar") to listOf(
                "1023 ب", "١٫٠٠ كيلوبايت", "١٠٢٤٫٠٠ كيلوبايت", "١٫٠٠ ميغابايت",
                "١٠٢٤٫٠٠ ميغابايت", "١٫٠٠ غيغابايت", "١٠٢٤٫٠٠ غيغابايت", "١٫٠٠ تيرابايت",
            ),
        ).forEach { (locale, expected) ->
            assertEquals(expected, renderAll(locale, *sizes), locale.toLanguageTag())
        }
    }
}
