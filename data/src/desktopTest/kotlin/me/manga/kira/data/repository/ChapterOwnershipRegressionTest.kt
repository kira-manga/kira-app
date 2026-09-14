package me.manga.kira.data.repository

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.platform.cbz.CbzReader
import me.manga.kira.platform.cbz.DefaultCbzReader
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.DesktopPageMediaInspector
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real Room identity tests; no schema migration or URL-keyed fake can supply the missing scope. */
class ChapterOwnershipRegressionTest : ChapterOwnershipFixture() {
    @Test
    fun duplicateUrlMutationsAffectOnlyTheExactOwnerAndSurviveReopen() =
        runTest {
            val a = seed(mangaA).single()
            val b = seed(mangaB).single()
            val bookmarks = ChapterBookmarkRepositoryImpl(db.chapterDao())
            val reads = MarkChapterReadRepositoryImpl(db.chapterDao())
            val badges = ChapterNewBadgeRepositoryImpl(db.chapterDao())
            val resolver = ChapterIdResolverImpl(db.chapterDao())
            val absent = manga("not-saved")

            assertEquals(b.id, resolver.resolveChapterId(mangaB, CHAPTER_OWNERSHIP_URL))
            assertEquals(a.id, resolver.resolveChapterId(mangaA, CHAPTER_OWNERSHIP_URL))
            assertNull(resolver.resolveChapterId(absent, CHAPTER_OWNERSHIP_URL))
            assertFalse(bookmarks.toggleBookmark(absent, CHAPTER_OWNERSHIP_URL))
            reads.markRead(absent, CHAPTER_OWNERSHIP_URL)
            reads.toggleRead(absent, CHAPTER_OWNERSHIP_URL)
            badges.clearNew(absent, CHAPTER_OWNERSHIP_URL)
            assertEquals(a, row(a.id))
            assertEquals(b, row(b.id), "matching title/API/language must not rescue an absent parent URL")

            assertTrue(bookmarks.toggleBookmark(mangaB, CHAPTER_OWNERSHIP_URL))
            badges.clearNew(mangaB, CHAPTER_OWNERSHIP_URL)
            assertEquals(b.copy(isBookmarked = true, isNew = false), row(b.id))
            db.chapterDao().updateChapter(b.copy(isBookmarked = true))
            reads.markRead(mangaB, CHAPTER_OWNERSHIP_URL)
            val read = row(b.id)
            assertEquals(
                b.copy(isBookmarked = true, isRead = true, isNew = false, lastReadDate = read.lastReadDate),
                read,
            )
            assertTrue(read.lastReadDate > b.lastReadDate)
            reads.toggleRead(mangaB, CHAPTER_OWNERSHIP_URL)
            val expected = read.copy(isRead = false)
            assertEquals(expected, row(b.id), "toggle must not rewrite the read timestamp or NEW flag")
            assertEquals(a, row(a.id), "the other valid chapter row is unchanged")

            db.close()
            db = openDatabase()
            assertEquals(a, row(a.id))
            assertEquals(expected, row(b.id))
            assertEquals(b.id, ChapterIdResolverImpl(db.chapterDao()).resolveChapterId(mangaB, CHAPTER_OWNERSHIP_URL))
        }

    @Test
    fun scopedBulkCrossesChunkBoundaryWithoutReadingOrTogglingTheOtherManga() =
        runTest {
            val urls = (0 until BULK_CHAPTER_COUNT).map { "$CHAPTER_OWNERSHIP_URL/$it" }
            val a = seed(mangaA, urls)
            val b = seed(mangaB, urls)
            val requested = urls + urls.first() + "missing"
            val expected = b.associate { it.url to it.id }
            val resolver = ChapterIdResolverImpl(db.chapterDao())
            val reads = MarkChapterReadRepositoryImpl(db.chapterDao())

            assertEquals(expected, resolver.resolveChapterIds(mangaB, requested))
            assertEquals(
                expected.values.toSet(),
                db.chapterDao().getChapterIdsByUrls(mangaB.url, requested).toSet(),
                "list and map queries agree across the 500-item chunk boundary",
            )
            assertTrue(resolver.resolveChapterIds(mangaB, emptyList()).isEmpty())
            assertTrue(resolver.resolveChapterIds(manga("absent"), requested).isEmpty())
            assertTrue(db.chapterDao().getChapterIdsByUrls(manga("absent").url, requested).isEmpty())

            reads.markRead(mangaB, requested)
            reads.markRead(mangaB, requested)
            ChapterBookmarkRepositoryImpl(db.chapterDao()).toggleBookmark(mangaB, requested)
            assertEquals(a, db.backupDao().getChaptersForManga(a.first().mangaId))
            assertEquals(
                b.map { it.copy(isRead = true, isBookmarked = true) },
                db.backupDao().getChaptersForManga(b.first().mangaId),
                "bulk read remains idempotent and preserves NEW/read timestamps",
            )
        }

    @Test
    fun bookmarkAndDownloadFlowsTrackLateSaveParentOnlyChangesRemovalAndNewIds() =
        runTest {
            val a = seed(mangaA, bookmarked = true).single()
            db.chapterDownloadingDao().insert(download(a))
            val bookmarks = ChapterBookmarkRepositoryImpl(db.chapterDao())
            val downloads =
                DownloadsRepositoryImpl(
                    legacy = FakeDownloadRepository(),
                    chapterDownloadDao = db.chapterDownloadingDao(),
                )

            bookmarks.observeBookmark(mangaB, CHAPTER_OWNERSHIP_URL).test {
                val bookmarkEvents = this
                downloads.observeForManga(mangaB).distinctUntilChanged().test {
                    assertFalse(bookmarkEvents.awaitItem(), "A is bookmarked, but B is not saved")
                    assertTrue(awaitItem().isEmpty())
                    val b = seed(mangaB, bookmarked = true).single()
                    db.chapterDownloadingDao().insert(download(b))
                    assertTrue(bookmarkEvents.awaitItem())
                    assertEquals(listOf(b.id), awaitItem().map { it.chapterId })

                    // Only saved_manga changes: child/ledger writes cannot mask missing parent tracking.
                    val parent = assertNotNull(db.backupDao().getMangaByUrl(mangaB.url))
                    db.backupDao().updateMangaRow(parent.copy(url = "${mangaB.url}/moved"))
                    assertFalse(bookmarkEvents.awaitItem())
                    assertTrue(awaitItem().isEmpty())
                    assertEquals(b, row(b.id))
                    assertNotNull(db.chapterDownloadingDao().getDownloadByChapter(b.id))
                    db.backupDao().updateMangaRow(parent)
                    assertTrue(bookmarkEvents.awaitItem())
                    assertEquals(listOf(b.id), awaitItem().map { it.chapterId })

                    db.chapterDownloadingDao().updateProgress(a.id, OTHER_OWNER_PROGRESS)
                    db.chapterDownloadingDao().updateProgress(b.id, OBSERVED_OWNER_PROGRESS)
                    val changed = awaitItem().single()
                    assertEquals(b.id, changed.chapterId)
                    assertEquals(b.mangaId, changed.mangaId)
                    assertEquals(OBSERVED_OWNER_PROGRESS, changed.progress)

                    db.libraryDeo().deleteMangaById(b.mangaId)
                    assertFalse(bookmarkEvents.awaitItem())
                    assertTrue(awaitItem().isEmpty())
                    assertNull(db.chapterDao().getChapterByIdSuspend(b.id))
                    assertNull(db.chapterDownloadingDao().getDownloadByChapter(b.id))
                    val reinserted = seed(mangaB, bookmarked = true).single()
                    assertNotEquals(b.id, reinserted.id)
                    assertNotEquals(b.mangaId, reinserted.mangaId)
                    db.chapterDownloadingDao().insert(download(reinserted))
                    assertTrue(bookmarkEvents.awaitItem())
                    val restored = awaitItem().single()
                    assertEquals(reinserted.id, restored.chapterId)
                    assertEquals(reinserted.mangaId, restored.mangaId)
                    assertEquals(a, row(a.id))
                    cancelAndIgnoreRemainingEvents()
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun completedDownloadPublishesOnlyItsExactOwnersLedgerAndSavedFlag() =
        runTest {
            val a = seed(mangaA).single()
            val b = seed(mangaB).single()
            val page = writeFile(appFs.chapterDir(a.mangaId, a.id) / "page.webp", "A")
            val preparedA = a.copy(localImagePaths = listOf(page.toString()))
            db.chapterDao().updateChapter(preparedA)
            db.chapterDownloadingDao().insert(download(a))
            db.chapterDownloadingDao().insert(download(b))

            assertCompletionStaysWithOwner(preparedA, b, assertNotNull(fs.metadata(page).size))
        }

    private suspend fun assertCompletionStaysWithOwner(
        a: SavedChapterEntity,
        b: SavedChapterEntity,
        sizeBytes: Long,
    ) {
        val dao = db.chapterDownloadingDao()
        val expected = assertNotNull(dao.getDownloadByChapter(a.id))
        val untouched = assertNotNull(dao.getDownloadByChapter(b.id))
        dao.observeDownloadsForMangaUrl(mangaA.url).distinctUntilChanged().test {
            val aEvents = this
            dao.observeDownloadsForMangaUrl(mangaB.url).test {
                assertEquals(listOf(expected), aEvents.awaitItem())
                assertEquals(listOf(untouched), awaitItem())
                assertTrue(dao.completeDownload(expected, a.localImagePaths, sizeBytes))
                val completed = expected.copy(state = DownloadingState.SUCCESS, progress = 100, sizeBytes = sizeBytes)
                assertEquals(listOf(completed), aEvents.awaitItem())
                assertEquals(a.copy(isDownloaded = true), row(a.id))
                assertEquals(b, row(b.id))
                // Table invalidation may re-query B too; unchanged data, not silence, proves isolation.
                assertEquals(listOf(untouched), dao.observeDownloadsForMangaUrl(mangaB.url).first())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun localPagesAndExtractedCleanupUseBWhileMissingBFilesFallBackToSourceB() =
        runTest {
            val a = seed(mangaA).single()
            val b = seed(mangaB).single()
            val pageA = writeFile(appFs.chapterDir(a.mangaId, a.id) / "page.webp", "A")
            val pageB = writeFile(appFs.chapterDir(b.mangaId, b.id) / "page.webp", "B")
            fs.write(pageA) { write(recoveryTestPng()) }
            fs.write(pageB) { write(recoveryTestPng()) }
            db.chapterDao().updateChapter(a.copy(isDownloaded = true, localImagePaths = listOf(pageA.toString())))
            db.chapterDao().updateChapter(b.copy(isDownloaded = true, localImagePaths = listOf(pageB.toString())))
            val cacheA = writeFile(extractedPage(a), "extracted A")
            val cacheB = writeFile(extractedPage(b), "extracted B")
            val cleanup = CompletableDeferred<Pair<Long, Long>>()
            val inspector = DesktopPageMediaInspector()
            val realCbz = DefaultCbzReader(appFs, dispatchers, inspector)
            val cbz =
                object : CbzReader by realCbz {
                    override suspend fun cleanupExtractedCache(
                        mangaId: Long,
                        chapterId: Long,
                    ) {
                        realCbz.cleanupExtractedCache(mangaId, chapterId)
                        cleanup.complete(mangaId to chapterId)
                    }
                }
            val source = OwnerPagesSource()
            val repo =
                ChapterPagesRepositoryImpl(
                    dispatchers,
                    db.chapterDao(),
                    cbz,
                    chapterOwnershipRegistry(source),
                    DownloadedPageFiles(appFs, inspector),
                )
            val chapter = Chapter("1", "shared", CHAPTER_OWNERSHIP_URL, null, false, false)

            assertEquals(
                AppResult.Success(listOf(Page("file://$pageB", emptyMap()))),
                repo.fetchPages(mangaB, chapter).first(),
            )
            assertTrue(source.requests.isEmpty())
            repo.clearExtractedPages(mangaB, chapter)
            val cleaned = withContext(Dispatchers.Default) { withTimeout(5.seconds) { cleanup.await() } }
            assertEquals(b.mangaId to b.id, cleaned)
            assertTrue(fs.exists(cacheA))
            assertFalse(fs.exists(cacheB))
            assertTrue(fs.exists(pageA))
            assertTrue(fs.exists(pageB), "extraction cleanup must leave the downloaded source files intact")

            fs.delete(pageB)
            assertEquals(
                AppResult.Success(listOf(Page("${mangaB.url}/network-page", emptyMap()))),
                repo.fetchPages(mangaB, chapter).first(),
            )
            assertEquals(listOf(mangaB to chapter), source.requests)
            assertTrue(fs.exists(pageA), "A remains readable but must never become B's fallback")
        }
}

private const val BULK_CHAPTER_COUNT = 502
private const val OTHER_OWNER_PROGRESS = 91
private const val OBSERVED_OWNER_PROGRESS = 53
