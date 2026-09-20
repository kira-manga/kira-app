package me.manga.kira.data.repository.selection

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.identity.AcceptedSourceAliasRule
import me.manga.kira.data.repository.libraryHistory
import me.manga.kira.data.repository.libraryParent
import me.manga.kira.data.repository.librarySavedChapter
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.sources.contracts.PreviousHostAuthority
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class StrictSelectionCollisionTest {
    @Test
    fun rejectedMemberOrCollisionLeavesEveryFamilyAndReceiptUnchangedAfterReopen() =
        runTest {
            val cases =
                ownerRejections() + linkRejections() + childRejections() +
                    authorityRejections() + activeQueueRejections()
            cases.forEach { case ->
                StrictSelectionMigrationFixture().use { f ->
                    val family = f.saved()
                    f.related(family)
                    f.anchor()
                    case.seed(f, family)
                    val before = f.snapshot()
                    assertFailsWith<SourceSelectionUnavailable>(case.name) { f.migrate(case.rules) }
                    f.reopen()
                    assertEquals(before, f.snapshot(), case.name)
                }
            }
        }

    @Test
    fun completeRowCountIsInclusiveAtTheCapAndRejectsOneMoreBeforeWriting() =
        runTest {
            StrictSelectionMigrationFixture().use { f ->
                val family = f.saved()
                val limit = SelectionMigrationLimits.MAX_INSPECTED_ROWS
                val remaining = limit - 2
                f.execute(
                    "WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < $remaining) " +
                        "INSERT INTO reader_work_state(api, workUrl) " +
                        "SELECT 'unrelated', 'https://unrelated.test/' || n FROM seq",
                )
                val atCap = f.db.sourceSelectionMigrationDao().inspectionSize()
                assertEquals(limit.toLong(), atCap.rowCount)
                f.migrate()
                f.reopen()
                val moved = f.db.mangaDao().getMangaById(family.work.id)
                assertEquals("$STRICT_NEW/work", moved?.url)
                f.db.readerProgressDao().ensureWork("unrelated", "https://unrelated.test/overflow")
                f.db.backupDao().updateMangaRow(family.work)
                val failure = assertFailsWith<SourceSelectionUnavailable> { f.migrate() }
                assertEquals("selection row limit exceeded", failure.message)
                f.reopen()
                assertEquals(family.work, f.db.mangaDao().getMangaById(family.work.id))
                val beyondCap = f.db.sourceSelectionMigrationDao().inspectionSize()
                assertEquals(limit + 1L, beyondCap.rowCount)
                assertNotNull(f.db.readerProgressDao().findWork("unrelated", "https://unrelated.test/overflow"))
            }
        }

    @Test
    fun utf8BytesNotCharacterCountAdmitExactCapAndRejectTheAdditionalLocatorBytes() =
        runTest {
            StrictSelectionMigrationFixture().use { f ->
                val family = f.saved()
                val limit = SelectionMigrationLimits.MAX_LOCATOR_BYTES
                val initial = f.db.sourceSelectionMigrationDao().inspectionSize()
                val padded = paddingLocator(limit - initial.locatorBytes.toInt())
                val anchor = f.db.readerProgressDao().ensureWork("unrelated", padded)
                val atCap = f.db.sourceSelectionMigrationDao().inspectionSize()
                assertEquals(limit.toLong(), atCap.locatorBytes)
                f.migrate()
                f.reopen()
                val moved = f.db.mangaDao().getMangaById(family.work.id)
                assertEquals("$STRICT_NEW/work", moved?.url)
                f.execute("UPDATE reader_work_state SET workUrl = workUrl || 'é' WHERE workId = ${anchor.workId}")
                f.db.backupDao().updateMangaRow(family.work)
                val failure = assertFailsWith<SourceSelectionUnavailable> { f.migrate() }
                assertEquals("selection locator byte limit exceeded", failure.message)
                f.reopen()
                assertEquals(family.work, f.db.mangaDao().getMangaById(family.work.id))
                val expanded = f.db.readerProgressDao().findWork("unrelated", padded + "é")
                assertEquals(anchor.copy(workUrl = padded + "é"), expanded)
                val beyondCap = f.db.sourceSelectionMigrationDao().inspectionSize()
                assertEquals(limit + 2L, beyondCap.locatorBytes)
            }
        }
}

private data class StrictRejectionCase(
    val name: String,
    val rules: List<AcceptedSourceAliasRule> = listOf(strictRule()),
    val seed: suspend StrictSelectionMigrationFixture.(StrictSavedFamily) -> Unit,
)

private fun ownerRejections(): List<StrictRejectionCase> {
    val moving = listOf(strictRule(), strictRule("other", "https://third.test", "new.test"))
    val colliding = listOf(strictRule(), strictRule("other", previous = "other-old.test"))
    return listOf(
        StrictRejectionCase("exact plus alias saved owner") { saved("$STRICT_NEW/work", "$STRICT_NEW/chapter") },
        StrictRejectionCase("exact plus alias work anchor") {
            db.readerProgressDao().ensureWork(STRICT_API, "$STRICT_NEW/work")
        },
        StrictRejectionCase("cross-api global destination") {
            saved("$STRICT_NEW/work", "$STRICT_NEW/chapter", "other")
        },
        StrictRejectionCase("occupied destination owner also moving", moving) {
            saved("$STRICT_NEW/work", "$STRICT_NEW/chapter", "other")
        },
        StrictRejectionCase("two plans choose empty global destination", colliding) {
            saved("https://other-old.test/work", "https://other-old.test/chapter", "other")
        },
        StrictRejectionCase("anchor-only destination has cross-api saved occupant") {
            anchor("$STRICT_OLD/unsaved", "$STRICT_OLD/unsaved/chapter")
            saved("$STRICT_NEW/unsaved", "$STRICT_NEW/unsaved/chapter", "other")
        },
        StrictRejectionCase(
            "anchor-only destination has another planned saved owner",
            listOf(strictRule(), strictRule("other", previous = "reader-old.test")),
        ) {
            anchor("https://reader-old.test/work", "https://reader-old.test/chapter", api = "other")
        },
    )
}

private fun linkRejections(): List<StrictRejectionCase> =
    listOf(
        StrictRejectionCase("linked history wrong API") { family ->
            execute("UPDATE history_items SET api = 'other', mangaId = ${family.work.id}")
        },
        StrictRejectionCase("history wrong nonzero parent is not rescued by its URL") {
            val other = saved("https://unrelated.test/work", "https://unrelated.test/chapter", "other")
            execute("UPDATE history_items SET mangaId = ${other.work.id}")
        },
        StrictRejectionCase("notification wrong nonzero child is not rescued by its URL") {
            val other = saved("$STRICT_NEW/other", "$STRICT_NEW/other-chapter")
            execute("UPDATE notifications SET chapterId = ${other.chapter.id}")
        },
        StrictRejectionCase("queue wrong API") { execute("UPDATE chapter_downloads SET api = 'other'") },
        StrictRejectionCase("queue missing saved child") {
            execute("UPDATE chapter_downloads SET chapterId = 9999999")
        },
        StrictRejectionCase("queue parent and child links disagree") {
            val other = saved("$STRICT_NEW/other", "$STRICT_NEW/other-chapter")
            execute("UPDATE chapter_downloads SET mangaId = ${other.work.id}")
        },
    )

private fun childRejections(): List<StrictRejectionCase> =
    listOf(
        StrictRejectionCase("exact plus alias saved chapter") { family ->
            db.backupDao().insertChapterRow(librarySavedChapter(family.work, "$STRICT_NEW/chapter"))
        },
        StrictRejectionCase("exact plus alias reader chapter") { family ->
            db.readerProgressDao().ensureSnapshot(STRICT_API, family.work.url, "$STRICT_NEW/chapter")
        },
        StrictRejectionCase("complete saved family includes malformed current-host sibling") { family ->
            db.backupDao().insertChapterRow(librarySavedChapter(family.work, "$STRICT_NEW/bad%zz"))
        },
        StrictRejectionCase("queue URL must equal its saved child raw URL") {
            execute("UPDATE chapter_downloads SET url = '$STRICT_NEW/chapter'")
        },
        StrictRejectionCase("history chapter requires scoped ownership") {
            execute("UPDATE history_items SET chapterUrl = '$STRICT_NEW/unowned-chapter'")
        },
        StrictRejectionCase("affected history orphan") {
            db.backupDao().insertHistoryRow(libraryHistory(libraryParent("$STRICT_OLD/orphan", STRICT_API)))
        },
    )

private fun authorityRejections(): List<StrictRejectionCase> =
    listOf(PreviousHostAuthority.UNKNOWN, PreviousHostAuthority.PROVEN_INCOMPATIBLE).map { authority ->
        StrictRejectionCase(
            "later affected family has $authority history",
            listOf(strictRule(), strictRule("blocked", previous = "blocked-old.test", authority = authority)),
        ) { saved("https://blocked-old.test/blocked-work", "https://blocked-old.test/blocked-chapter", "blocked") }
    } +
        listOf(
            StrictRejectionCase("malformed old-host authority is still affected") { family ->
                execute("UPDATE saved_manga SET url = 'https://user@old.test/work' WHERE id = ${family.work.id}")
            },
            StrictRejectionCase("undeclared source on affected host") {
                saved("$STRICT_OLD/unknown-source", "$STRICT_OLD/unknown-chapter", "unknown")
            },
        )

private fun activeQueueRejections(): List<StrictRejectionCase> =
    listOf(DownloadingState.RUNNING, DownloadingState.COMPRESSING, DownloadingState.DOWNLOADED).map { state ->
        StrictRejectionCase("affected $state queue") { execute("UPDATE chapter_downloads SET state = '${state.name}'") }
    }

/** Include both the row API and a genuinely multi-byte suffix in the exact SQLite UTF-8 boundary. */
private fun paddingLocator(totalBytes: Int): String {
    val prefix = "https://unrelated.test/"
    val available = totalBytes - "unrelated".encodeToByteArray().size - prefix.encodeToByteArray().size
    return prefix + "é".repeat(available / 2) + "x".repeat(available % 2)
}
