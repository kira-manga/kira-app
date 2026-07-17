package me.manga.kira.locale

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageDirectionTest {
    @Test
    fun arabicTags_areRtl_acrossCaseRegionAndWhitespace() {
        assertTrue(isRtlLanguageTag("ar"))
        assertTrue(isRtlLanguageTag("AR"))
        assertTrue(isRtlLanguageTag(" ar-EG "))
        assertTrue(isRtlLanguageTag("ar_SA"))
    }

    @Test
    fun otherSupportedRtlLanguageTags_areRtl() {
        listOf("fa", "he-IL", "ur_PK", "ps", "sd", "ug", "yi", "dv").forEach {
            assertTrue(isRtlLanguageTag(it), it)
        }
    }

    @Test
    fun ltrAndMalformedTags_areNotRtl() {
        listOf("en", "de-DE", "id", "", "  ", "-").forEach {
            assertFalse(isRtlLanguageTag(it), it)
        }
    }
}
