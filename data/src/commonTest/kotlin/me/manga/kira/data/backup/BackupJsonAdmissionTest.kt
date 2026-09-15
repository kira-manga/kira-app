@file:Suppress("MagicNumber")

package me.manga.kira.data.backup

import me.manga.kira.platform.backup.BackupImportLimitExceeded
import me.manga.kira.platform.backup.BackupImportPolicy
import me.manga.kira.platform.backup.BackupJsonLimits
import me.manga.kira.platform.backup.BackupRecordLimits
import me.manga.kira.platform.backup.InvalidBackupArchive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BackupJsonAdmissionTest {
    @Test
    fun manifestByteLimitIsExact() {
        val text = "{\"unknown\":true}"
        val size = text.encodeToByteArray().size.toLong()
        assertEquals(text, admit(text, BackupImportPolicy(json = BackupJsonLimits(maxBytes = size))))
        assertFailsWith<BackupImportLimitExceeded> {
            admit(text, BackupImportPolicy(json = BackupJsonLimits(maxBytes = size - 1)))
        }
    }

    @Test
    fun unknownFieldsAreAllowedButTheirNestingIsBounded() {
        val policy = BackupImportPolicy(json = BackupJsonLimits(maxDepth = 3))
        admit("""{"additive":[{}]}""", policy)
        assertFailsWith<BackupImportLimitExceeded> { admit("""{"additive":[[{}]]}""", policy) }
    }

    @Test
    fun decodedUtf8StringLimitsApplyToEscapesAndIgnoredStrings() {
        val policy = BackupImportPolicy(json = BackupJsonLimits(maxStringBytes = 16))
        admit("""{"extra":"${"é".repeat(8)}"}""", policy)
        admit("""{"extra":"${"\\u00e9".repeat(8)}"}""", policy)
        admit("""{"extra":"${"\\uD83D\\uDE00".repeat(4)}"}""", policy)
        for (value in listOf("é".repeat(9), "\\u00e9".repeat(9), "\\uD83D\\uDE00".repeat(5))) {
            assertFailsWith<BackupImportLimitExceeded> { admit("""{"extra":"$value"}""", policy) }
        }
    }

    @Test
    fun largerDescriptionAllowanceAppliesOnlyToTheMangaDescriptionField() {
        val policy = BackupImportPolicy(json = BackupJsonLimits(maxStringBytes = 16, maxDescriptionBytes = 20))
        admit("""{"mangas":[{"description":"${"a".repeat(20)}"}]}""", policy)
        assertFailsWith<BackupImportLimitExceeded> {
            admit("""{"mangas":[{"description":"${"a".repeat(21)}"}]}""", policy)
        }
        assertFailsWith<BackupImportLimitExceeded> { admit("""{"description":"${"a".repeat(17)}"}""", policy) }
        assertFailsWith<BackupImportLimitExceeded> { admit("""{"mangas":[{"title":"${"a".repeat(17)}"}]}""", policy) }
    }

    @Test
    fun mangaAndCombinedChapterHistoryCountsAreAdmittedBeforeDtoDecoding() {
        val policy = BackupImportPolicy(records = BackupRecordLimits(maxMangas = 1, maxChaptersAndHistory = 2))
        admit("""{"mangas":[{"chapters":[{}]}],"history":[{}]}""", policy)
        assertFailsWith<BackupImportLimitExceeded> { admit("""{"mangas":[{},{}]}""", policy) }
        assertFailsWith<BackupImportLimitExceeded> {
            admit("""{"mangas":[{"chapters":[{},{}]}],"history":[{}]}""", policy)
        }
        assertFailsWith<BackupImportLimitExceeded> { admit("""{"history":[{},{},{}]}""", policy) }
    }

    @Test
    fun duplicateRecognizedKeysIncludingEscapedSpellingsAreRejected() {
        for (text in listOf(
            """{"mangas":[],"mangas":[]}""",
            """{"mangas":[],"\u006dangas":[]}""",
            """{"mangas":[{"title":"first","title":"second"}]}""",
            """{"mangas":[{"chapters":[{"url":"first","url":"second"}]}]}""",
            """{"history":[{"mangaUrl":"first","mangaUrl":"second"}]}""",
        )) assertFailsWith<InvalidBackupArchive> { admit(text) }
    }

    @Test
    fun malformedUtf8SurrogatesNumbersAndTrailingJsonAreRejected() {
        assertFailsWith<InvalidBackupArchive> { admitBackupJson(byteArrayOf(0xFF.toByte()), BackupImportPolicy()) {} }
        for (text in listOf(
            """{"extra":"\uD800"}""", """{"extra":"\uDC00"}""", """{"extra":"\uD800a"}""",
            """{"extra":01}""", """{"extra":1e}""", """{"extra":-}""", """{"extra":1.}""",
            "{} {}", "[]", "{\"extra\":\"\n\"}", """{"extra":[1,]}""", """{"extra":true,}""",
        )) assertFailsWith<InvalidBackupArchive>(text) { admit(text) }
    }

    @Test
    fun longIgnoredWhitespaceStillChecksCancellation() {
        val cancelled = CancellationException("cancel scan")
        var checkpoints = 0
        val thrown = assertFailsWith<CancellationException> {
            admitBackupJson(("{" + " ".repeat(4096) + "}").encodeToByteArray(), BackupImportPolicy()) {
                if (++checkpoints == 2) throw cancelled
            }
        }
        assertSame(cancelled, thrown)
    }

    private fun admit(text: String, policy: BackupImportPolicy = BackupImportPolicy()): String =
        admitBackupJson(text.encodeToByteArray(), policy) {}
}
