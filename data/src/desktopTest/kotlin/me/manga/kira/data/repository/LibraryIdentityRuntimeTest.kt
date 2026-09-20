package me.manga.kira.data.repository

import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.LibraryRefreshRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryIdentityRuntimeTest {
    @Test
    fun same_title_distinct_works_resolve_and_mutate_by_owner_only() = runTest {
        LibraryIdentityFixture().use { f ->
            val first = f.parent()
            val second = f.parent(libraryParent("https://current.test/work/two").copy(language = "ar"))
            assertEquals(first.title, second.title)
            assertNotEquals(first.language, second.language)
            assertNotEquals(first.id, second.id)
            assertEquals(first.id, f.repository.get(first.savedIdentity().locator).getOrNull()?.identity?.id)
            assertEquals(second.id, f.repository.get(second.savedIdentity().locator).getOrNull()?.identity?.id)
            assertTrue(f.repository.toggleLiked(second.savedIdentity()).isSuccess)
            assertEquals(first, f.db.mangaDao().getMangaById(first.id))
            assertEquals(second.copy(isLiked = false), f.db.mangaDao().getMangaById(second.id))
            assertNull(f.repository.get(WorkLocator(first.api, "https://current.test/work/missing")).getOrNull())
        }
    }

    @Test
    fun accepted_alias_and_renamed_metadata_keep_the_stored_parent_id_and_raw_url() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent(libraryParent(LIBRARY_PREVIOUS_URL))
            val current = WorkLocator(parent.api, LIBRARY_CURRENT_URL)
            val fetched = libraryFetched(parent, title = "A different title", language = "ja").copy(requested = current)
            val owner = assertNotNull(f.repository.addToLibrary(fetched).getOrNull())
            assertEquals(parent.savedIdentity(), owner)
            val observed = assertNotNull(f.savedDetails.observeSavedDetails(current).first().getOrNull())
            assertEquals(parent.id, observed.owner.id)
            assertEquals(parent.url, observed.details.url)
            assertEquals("A different title", observed.details.title)
            assertEquals("ja", observed.details.language)
            assertEquals(1, f.db.backupDao().getAllSavedManga().size)
        }
    }

    @Test
    fun metadata_after_affinity_open_and_child_writes_preserves_all_user_state() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val child = f.chapter(librarySavedChapter(parent))
            val download = libraryDownload(parent, child)
            val downloadId = f.db.chapterDownloadingDao().insert(download)
            val fetched = libraryFetched(parent, listOf(libraryChapter(child.url)))
            f.repository.toggleLiked(parent.savedIdentity())
            f.repository.toggleWatchingNow(parent.savedIdentity())
            f.repository.markOpened(parent.savedIdentity())
            val local = assertNotNull(f.db.mangaDao().getMangaById(parent.id))
            assertTrue(f.repository.refresh(listOf(LibraryRefreshRequest(parent.savedIdentity(), fetched)), false).isSuccess)
            val actual = assertNotNull(f.db.mangaDao().getMangaById(parent.id))
            assertEquals(local.savedTimestamp, actual.savedTimestamp)
            assertEquals(local.lastOpenTimestamp, actual.lastOpenTimestamp)
            assertEquals(local.isLiked, actual.isLiked)
            assertEquals(local.isWatchingNow, actual.isWatchingNow)
            assertEquals(fetched.details.title, actual.title)
            assertEquals(fetched.details.language, actual.language)
            assertEquals(child, f.db.chapterDao().getChapterByIdSuspend(child.id))
            assertEquals(download.copy(id = downloadId), f.db.chapterDownloadingDao().getDownloadByChapter(child.id))
        }
    }

    @Test
    fun cross_api_exact_url_collision_is_failure_for_reads_add_and_cover() = runTest {
        LibraryIdentityFixture().use { f ->
            val foreign = f.parent(libraryParent(api = "foreign"))
            val work = WorkLocator(LIBRARY_TEST_API, foreign.url)
            assertTrue(f.repository.get(work).isFailure)
            assertTrue(f.repository.observeMembership(work).first().isFailure)
            assertTrue(f.savedDetails.observeSavedDetails(work).first().isFailure)
            assertTrue(f.repository.addToLibrary(libraryFetched()).isFailure)
            assertTrue(f.covers.updateCoverIfChanged(foreign.savedIdentity().copy(locator = work), work, "cover").isFailure)
            assertEquals(listOf(foreign), f.db.backupDao().getAllSavedManga())
        }
    }

    @Test
    fun exact_plus_alias_ambiguity_never_picks_the_exact_row() = runTest {
        LibraryIdentityFixture().use { f ->
            val current = f.parent()
            val previous = f.parent(libraryParent(LIBRARY_PREVIOUS_URL))
            assertTrue(f.repository.get(current.savedIdentity().locator).isFailure)
            assertTrue(f.repository.toggleWatchingNow(current.savedIdentity()).isFailure)
            assertTrue(f.repository.refresh(listOf(libraryRequest(current)), true).isFailure)
            assertEquals(listOf(current, previous), f.db.backupDao().getAllSavedManga())
        }
    }

    @Test
    fun returned_work_change_and_deleted_readded_owner_cannot_retarget_refresh() = runTest {
        LibraryIdentityFixture().use { f ->
            val original = f.parent()
            val wrong = libraryFetched(original, url = "https://current.test/work/unrelated")
            assertTrue(f.repository.refresh(listOf(LibraryRefreshRequest(original.savedIdentity(), wrong)), true).isFailure)
            assertEquals(original, f.db.mangaDao().getMangaById(original.id))
            assertEquals(1, f.db.libraryDeo().deleteMangaForExactOwner(original.id, original.api, original.url))
            val replacement = f.parent(original.copy(id = 0L))
            assertNotEquals(original.id, replacement.id)
            assertTrue(f.repository.refresh(listOf(libraryRequest(original)), true).isFailure)
            assertTrue(f.repository.toggleLiked(original.savedIdentity()).isFailure)
            assertTrue(f.covers.updateCoverIfChanged(original.savedIdentity(), original.savedIdentity().locator, "new").isFailure)
            assertEquals(listOf(replacement), f.db.backupDao().getAllSavedManga())
        }
    }

    @Test
    fun accepted_policy_is_acquired_after_waiting_for_the_real_writer() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent(libraryParent(LIBRARY_PREVIOUS_URL))
            val current = WorkLocator(parent.api, LIBRARY_CURRENT_URL)
            val request = LibraryRefreshRequest(parent.savedIdentity(), libraryFetched(parent).copy(requested = current))
            val result = f.afterWriterWait(
                operation = { f.repository.refresh(listOf(request), false) },
                change = {
                    assertEquals(0, f.snapshots.readCount)
                    f.snapshots.accept(libraryPolicy(revision = 2L, previousHosts = emptyList()))
                },
            )
            assertTrue(result.isFailure, "a snapshot cached before the writer wait would incorrectly accept this alias")
            assertEquals(1, f.snapshots.readCount)
            assertEquals(parent, f.db.mangaDao().getMangaById(parent.id))
        }
    }

    @Test
    fun membership_and_saved_details_invalidate_when_accepted_alias_is_revoked() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val previous = WorkLocator(parent.api, LIBRARY_PREVIOUS_URL)
            f.savedDetails.observeSavedDetails(previous).test {
                assertEquals(parent.id, awaitItem().getOrNull()?.owner?.id)
                f.repository.observeMembership(previous).test {
                    assertEquals(AppResult.Success(parent.savedIdentity()), awaitItem())
                    f.snapshots.accept(libraryPolicy(revision = 2L, previousHosts = emptyList()))
                    assertTrue(awaitItem().isFailure)
                    cancelAndIgnoreRemainingEvents()
                }
                assertTrue(awaitItem().isFailure)
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(f.savedDetails.observeSavedDetails(previous).first().isFailure)
            assertEquals(parent.id, f.savedDetails.observeSavedDetails(parent.savedIdentity().locator).first().getOrNull()?.owner?.id)
        }
    }

    @Test
    fun blank_cover_keeps_everything_and_valid_cover_only_updates_proven_related_rows() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val chapter = f.chapter(librarySavedChapter(parent))
            val aliasHistory = libraryHistory(parent, 0L).copy(mangaUrl = LIBRARY_PREVIOUS_URL)
            f.db.historyDao().insertHistory(aliasHistory)
            f.db.historyDao().insertHistory(libraryHistory(parent.copy(api = "foreign"), 0L))
            f.db.notificationDao().insertNotificationsList(listOf(libraryNotification(parent, chapter)))
            val histories = f.db.backupDao().getAllHistoryOnce()
            val notifications = f.db.notificationDao().getAllNotifications().first()
            val owner = parent.savedIdentity()
            assertTrue(f.covers.updateCoverIfChanged(owner, owner.locator, "").isSuccess)
            assertEquals(parent, f.db.mangaDao().getMangaById(parent.id))
            assertEquals(histories, f.db.backupDao().getAllHistoryOnce())
            assertTrue(f.covers.updateCoverIfChanged(owner, owner.locator, "https://images.test/new").isSuccess)
            assertEquals(parent.copy(imageUrl = "https://images.test/new"), f.db.mangaDao().getMangaById(parent.id))
            assertEquals(histories.map { if (it.api == parent.api) it.copy(mangaImageUrl = "https://images.test/new") else it },
                f.db.backupDao().getAllHistoryOnce())
            assertEquals(notifications.map { it.copy(mangaImageUrl = "https://images.test/new") },
                f.db.notificationDao().getAllNotifications().first())
            assertEquals(chapter, f.db.chapterDao().getChapterByIdSuspend(chapter.id))
        }
    }

    @Test
    fun chapter_alias_requires_reconciliation_instead_of_duplicate_insert_or_rewrite() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val chapter = f.chapter(librarySavedChapter(parent, "$LIBRARY_PREVIOUS_URL/chapter/one"))
            val request = libraryRequest(parent, listOf(libraryChapter("$LIBRARY_CURRENT_URL/chapter/one")))
            assertTrue(f.repository.refresh(listOf(request), true).isFailure)
            assertEquals(listOf(chapter), f.db.backupDao().getChaptersForManga(parent.id))
            assertEquals(parent, f.db.mangaDao().getMangaById(parent.id))
        }
    }
}

private suspend fun <T> LibraryIdentityFixture.afterWriterWait(
    operation: suspend () -> T,
    change: () -> Unit,
): T = coroutineScope {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val attempted = CompletableDeferred<Unit>()
    val blocker = launch(Dispatchers.Default) {
        RoomMangaWriteTransaction(db).write { entered.complete(Unit); release.await() }
    }
    entered.await()
    transactions.onAttempt = { attempted.complete(Unit) }
    val pending = async(Dispatchers.Default) { operation() }
    try { attempted.await(); change() } finally { release.complete(Unit) }
    pending.await().also { blocker.join() }
}
