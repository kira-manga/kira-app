package me.manga.kira.data.repository.selection

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.repository.libraryHistory
import me.manga.kira.data.repository.libraryNotification
import me.manga.kira.data.repository.libraryParent
import me.manga.kira.data.repository.librarySavedChapter
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Shared mobile persistence evidence, not Desktop qualification or production queue/native exclusion. */
class StrictSelectionMigrationTest {
    @Test
    fun savedAndIndependentAnchorsRetainEveryNonUrlColumnAfterReopen() =
        runTest {
            listOf(DownloadingState.QUEUED, DownloadingState.SUCCESS, DownloadingState.FAILED).forEach { state ->
                StrictSelectionMigrationFixture().use { f ->
                    val reader = f.db.readerProgressDao()
                    reader.ensureSnapshot("other", "https://unrelated.test/work", "https://unrelated.test/chapter")
                    val family = f.saved("$STRICT_OLD/work%2F?Case=A#F", "$STRICT_OLD/chapter%2f?x=%2F#F")
                    f.related(family, state)
                    f.anchor(family.work.url, family.chapter.url)
                    val before = f.snapshot()
                    f.migrate()
                    f.reopen()
                    assertEquals(before.withMovedUrls(), f.snapshot(), state.name)
                    val progress =
                        assertNotNull(
                            f.db.readerProgressDao().findSnapshot(
                                STRICT_API,
                                movedUrl(family.work.url),
                                movedUrl(family.chapter.url),
                            ),
                        )
                    assertNotEquals(family.work.id, progress.workId)
                    assertNotEquals(family.chapter.id, progress.chapterId)
                    assertEquals(1L, progress.workGeneration)
                    assertEquals(1L, progress.chapterGeneration)
                    assertEquals(STRICT_SAVED_PAGE, progress.pageIndex)
                }
            }
        }

    @Test
    fun anchorOnlyHistoryAndUnlinkedNotificationDoNotManufactureSavedRows() =
        runTest {
            StrictSelectionMigrationFixture().use { f ->
                f.anchor(STRICT_OLD, "$STRICT_OLD/")
                val work = libraryParent(STRICT_OLD, STRICT_API)
                val chapter = librarySavedChapter(work, "$STRICT_OLD/")
                f.db.backupDao().insertHistoryRow(libraryHistory(work, 0).copy(chapterUrl = chapter.url))
                f.db.notificationDao().insertNotificationsList(listOf(libraryNotification(work, chapter)))
                val before = f.snapshot()
                f.migrate()
                f.reopen()
                assertEquals(before.withMovedUrls(), f.snapshot())
                assertTrue(
                    f.db
                        .backupDao()
                        .getAllSavedManga()
                        .isEmpty(),
                )
                assertEquals(0L, f.number("SELECT COUNT(*) FROM saved_chapters"))
            }
        }

    @Test
    fun duplicateChapterUrlsStayParentScopedAndCurrentRawSpellingWinsForItsAnchor() =
        runTest {
            StrictSelectionMigrationFixture().use { f ->
                val first = f.saved()
                val second = f.saved("$STRICT_OLD/other-work")
                listOf(first, second).forEach {
                    f.related(it)
                    f.anchor(it.work.url, it.chapter.url)
                }
                f.saved("mirror://unsupported/work", "file://unsupported/chapter", "unrelated")
                val retained = "HTTPS://NEW.TEST:00443/retain%2F?case=A#F"
                val prior = "$STRICT_OLD/retain%2F?case=A#F"
                f.db.backupDao().insertChapterRow(librarySavedChapter(first.work, retained))
                val anchor = f.db.readerProgressDao().ensureSnapshot(STRICT_API, first.work.url, prior)
                val before = f.snapshot()
                f.migrate()
                f.reopen()
                assertEquals(before.withMovedUrls(mapOf(prior to retained)), f.snapshot())
                listOf(first, second).forEach { family ->
                    val saved = f.db.backupDao().getChaptersForManga(family.work.id)
                    assertEquals(1, saved.count { it.url == "$STRICT_NEW/chapter" })
                }
                assertEquals(anchor, f.db.readerProgressDao().findSnapshot(STRICT_API, "$STRICT_NEW/work", retained))
            }
        }

    @Test
    fun differentApiAnchorOnlyFamiliesMayShareAnUnoccupiedRawDestination() =
        runTest {
            StrictSelectionMigrationFixture().use { f ->
                f.anchor()
                val otherWork = "https://reader-old.test/work"
                val otherChapter = "https://reader-old.test/chapter"
                f.anchor(otherWork, otherChapter, api = "other")
                val before = f.snapshot()
                f.migrate(listOf(strictRule(), strictRule("other", previous = "reader-old.test")))
                f.reopen()
                val moved = mapOf(otherWork to "$STRICT_NEW/work", otherChapter to "$STRICT_NEW/chapter")
                assertEquals(before.withMovedUrls(moved), f.snapshot())
                assertTrue(
                    f.db
                        .backupDao()
                        .getAllSavedManga()
                        .isEmpty(),
                )
                assertEquals(2L, f.number("SELECT COUNT(*) FROM reader_work_state WHERE workUrl = '$STRICT_NEW/work'"))
            }
        }

    @Test
    fun realSecondFamilyIgnoreAfterSuccessfulUrlUpdatesEscapesWriterAndReopensUnchanged() =
        runTest {
            StrictSelectionMigrationFixture().use { f ->
                val first = f.saved()
                val second = f.saved("$STRICT_OLD/second")
                listOf(first, second).forEach {
                    f.related(it)
                    f.anchor(it.work.url, it.chapter.url)
                }
                f.execute(ignoreSecondAfterFirstMoved(first, second))
                val before = f.snapshot()
                val failure = assertFailsWith<SourceSelectionUnavailable> { f.migrate() }
                assertEquals("selection URL write count was 0", failure.message)
                f.reopen()
                assertEquals(before, f.snapshot())
            }
        }
}

/** The zero-count branch is reachable only AFTER actual earlier Room URL statements took effect. */
private fun ignoreSecondAfterFirstMoved(
    first: StrictSavedFamily,
    second: StrictSavedFamily,
): String =
    """
    CREATE TRIGGER strict_late_ignore BEFORE UPDATE OF url ON saved_manga
    WHEN OLD.id = ${second.work.id} BEGIN
      SELECT CASE WHEN
        (SELECT url FROM saved_manga WHERE id = ${first.work.id}) = '$STRICT_NEW/work'
        AND (SELECT url FROM saved_chapters WHERE id = ${first.chapter.id}) = '$STRICT_NEW/chapter'
      THEN RAISE(IGNORE) ELSE RAISE(ABORT, 'earlier URL writes did not occur') END;
    END
    """.trimIndent()

private fun movedUrl(raw: String): String =
    if (raw.startsWith("$STRICT_OLD/") || raw == STRICT_OLD) STRICT_NEW + raw.removePrefix(STRICT_OLD) else raw

/** Expected changes name only page URL columns. Receipt keys/payloads and all other columns stay exact. */
internal fun Map<String, StrictTableImage>.withMovedUrls(overrides: Map<String, String> = emptyMap()) =
    mapValues { (table, image) ->
        if (table == "reader_legacy_cleanup") {
            image
        } else {
            image.copy(
                rows =
                    image.rows.map { row ->
                        row.mapIndexed { index, value ->
                            if (
                                value != null &&
                                image.columns[index] in setOf("url", "workUrl", "mangaUrl", "chapterUrl")
                            ) {
                                overrides[value] ?: movedUrl(value)
                            } else {
                                value
                            }
                        }
                    },
            )
        }
    }
