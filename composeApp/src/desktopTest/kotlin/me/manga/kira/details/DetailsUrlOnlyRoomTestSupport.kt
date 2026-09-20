@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.manga.kira.details

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.repository.SavedMangaDetailsRepositoryImpl
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.LibraryRefreshReceipt
import me.manga.kira.domain.model.library.SavedWorkDetails
import me.manga.kira.presentation.details.DetailsIntent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SAVED_TWIN_TOTAL_CHAPTERS = 3

internal fun assertNewRow(
    row: SavedChapterEntity,
    mangaId: Long,
    expectedUrl: String,
) {
    assertEquals(mangaId, row.mangaId)
    assertEquals(expectedUrl, row.url)
    assertTrue(row.isNew)
    assertTrue(row.fetchedAt > 0L)
    assertFalse(row.isRead)
    assertFalse(row.isBookmarked)
}

internal fun persistedCounts(fixture: DetailsUrlOnlyRoomFixture): List<Int> =
    fixture.library.results.map { result ->
        assertIs<AppResult.Success<List<LibraryRefreshReceipt>>>(result).value.sumOf { it.addedChapters }
    }

internal fun Manga.workLocator() = WorkLocator(api, url)

internal suspend fun assertDetailsRowCounts(fixture: DetailsUrlOnlyRoomFixture, mangas: Int, chapters: Int) {
    assertEquals(
        mangas,
        fixture.db
            .statisticsDeo()
            .getTotalMangaCount()
            .first(),
    )
    assertDetailsChapterCount(fixture, chapters)
}

internal suspend fun assertDetailsChapterCount(fixture: DetailsUrlOnlyRoomFixture, expected: Int) {
    assertEquals(
        expected,
        fixture.db
            .statisticsDeo()
            .getTotalChaptersCount()
            .first(),
    )
}

internal suspend fun assertReopenedDetails(
    fixture: DetailsUrlOnlyRoomFixture,
    owner: SavedWorkIdentity,
    first: Chapter,
    second: Chapter,
) {
    val saved = SavedMangaDetailsRepositoryImpl(fixture.owners, fixture.db.chapterDao(), fixture.dispatchers)
    val reopened = assertNotNull(
        assertIs<AppResult.Success<SavedWorkDetails?>>(saved.observeSavedDetails(owner.locator).first()).value,
    )
    assertEquals(owner, reopened.owner)
    assertEquals(setOf(first.url, second.url), reopened.details.chapters.map { it.url }.toSet())
    assertTrue(reopened.details.chapters.single { it.url == second.url }.isNew)
}

internal fun TestScope.retryDetails(fixture: DetailsUrlOnlyRoomFixture, times: Int) {
    repeat(times) {
        fixture.vm.submit(DetailsIntent.OnRetry)
        advanceUntilIdle()
    }
}

/** The historical case's real Room phases, extracted only to keep each test function bounded. */
internal suspend fun TestScope.verifySavedMetadataTwins(
    fixture: DetailsUrlOnlyRoomFixture,
    manga: Manga,
    first: Chapter,
) {
    val savedA = fixture.seed(manga, first)
    val other = manga.copy(url = "${manga.url}/saved-twin")
    val otherFirst = roomChapter(other, "1")
    val otherNew = roomChapter(other, "2")
    val savedB = fixture.seed(other, otherFirst)
    val parentA = assertNotNull(fixture.db.mangaDao().getMangaById(savedA.mangaId))
    val parentB = assertNotNull(fixture.db.mangaDao().getMangaById(savedB.mangaId))
    val ownerB = SavedWorkIdentity(savedB.mangaId, other.workLocator())
    // Both raw fetch addresses must identify retained B: a returned A URL is rejected.
    val response = roomDetails(manga, listOf(otherNew, otherFirst))
    val cached = openCachedTwin(fixture, other, otherFirst, ownerB, response)
    val rejected = assertMismatchedTwinRejected(fixture, manga, other, ownerB, cached)
    assertTwinRowsUnchanged(fixture, listOf(parentA, parentB), listOf(savedA, savedB))

    // Re-enter through real scoped cache reads, then prove a correct B response can persist.
    // The historical selector is preserved, but the mismatched response is not acceptance.
    refreshCorrectTwin(fixture, manga, other, ownerB, listOf(otherNew, otherFirst))
    assertCorrectTwinAccepted(fixture, other, ownerB, rejected)
    assertEquals(parentA, fixture.db.mangaDao().getMangaById(parentA.id))
    assertEquals(listOf(savedA), fixture.db.chapterDao().getChaptersByMangaIdR(savedA.mangaId))
    assertTwinNewRow(fixture, savedB, otherNew)
    assertDetailsRowCounts(fixture, mangas = 2, chapters = SAVED_TWIN_TOTAL_CHAPTERS)
}

private fun TestScope.openCachedTwin(
    fixture: DetailsUrlOnlyRoomFixture,
    other: Manga,
    otherFirst: Chapter,
    ownerB: SavedWorkIdentity,
    response: MangaDetails,
): MangaDetails {
    fixture.source.answers[other.url] = AppResult.Success(response)
    fixture.vm.submit(DetailsIntent.OnEnterByUrl(other.api, other.url))
    advanceUntilIdle()
    assertTrue(fixture.source.requests.isEmpty())
    val cached = assertNotNull(fixture.vm.state.value.details)
    assertEquals(other.url, cached.url)
    assertEquals(listOf(otherFirst.url), cached.chapters.map { it.url })
    assertEquals(ownerB, fixture.vm.state.value.savedOwner)
    fixture.vm.submit(DetailsIntent.OnRetry)
    advanceUntilIdle()
    return cached
}

private fun assertMismatchedTwinRejected(
    fixture: DetailsUrlOnlyRoomFixture,
    manga: Manga,
    other: Manga,
    ownerB: SavedWorkIdentity,
    cached: MangaDetails,
): AppResult.Failure {
    assertEquals(
        other.url,
        fixture.vm.state.value.manga
            ?.url,
    )
    assertEquals(listOf(other.workLocator()), fixture.library.offeredParents)
    val offered = fixture.library.refreshBatches.single().single()
    assertEquals(ownerB, offered.owner)
    assertEquals(manga.url, offered.fetched.details.url, "The wrapper must preserve the actual mismatched payload")
    val rejected = assertIs<AppResult.Failure>(fixture.library.results.single())
    assertEquals("FETCHED_WORK_CHANGED", assertIs<AppError.Storage.Constraint>(rejected.error).description)
    assertEquals(rejected.error, fixture.vm.state.value.libraryError)
    assertFalse(fixture.vm.state.value.isInLibrary)
    assertNull(fixture.vm.state.value.savedOwner)
    assertEquals(cached, fixture.vm.state.value.details, "A rejected payload must not repaint cached B")
    return rejected
}

private suspend fun assertTwinRowsUnchanged(
    fixture: DetailsUrlOnlyRoomFixture,
    parents: List<SavedMangaEntity>,
    rows: List<SavedChapterEntity>,
) {
    parents.forEach { parent -> assertEquals(parent, fixture.db.mangaDao().getMangaById(parent.id)) }
    rows.forEach { row -> assertEquals(listOf(row), fixture.db.chapterDao().getChaptersByMangaIdR(row.mangaId)) }
}

private fun TestScope.refreshCorrectTwin(
    fixture: DetailsUrlOnlyRoomFixture,
    manga: Manga,
    other: Manga,
    ownerB: SavedWorkIdentity,
    chapters: List<Chapter>,
) {
    fixture.vm.submit(DetailsIntent.OnEnterByUrl(manga.api, manga.url))
    advanceUntilIdle()
    fixture.vm.submit(DetailsIntent.OnEnterByUrl(other.api, other.url))
    advanceUntilIdle()
    assertEquals(ownerB, fixture.vm.state.value.savedOwner)
    assertNull(fixture.vm.state.value.libraryError)
    fixture.source.answers[other.url] = AppResult.Success(roomDetails(other, chapters))
    fixture.vm.submit(DetailsIntent.OnRetry)
    advanceUntilIdle()
}

private fun assertCorrectTwinAccepted(
    fixture: DetailsUrlOnlyRoomFixture,
    other: Manga,
    ownerB: SavedWorkIdentity,
    rejected: AppResult.Failure,
) {
    assertEquals(2, fixture.library.results.size)
    assertEquals(rejected, fixture.library.results.first())
    val accepted =
        assertIs<AppResult.Success<List<LibraryRefreshReceipt>>>(fixture.library.results.last()).value.single()
    assertEquals(ownerB, accepted.owner)
    assertEquals(1, accepted.addedChapters)
    assertTrue(accepted.notifications.isEmpty())
    assertEquals(ownerB, fixture.vm.state.value.savedOwner)
    assertTrue(fixture.vm.state.value.isInLibrary)
    assertNull(fixture.vm.state.value.libraryError)
    assertEquals(other.url, assertNotNull(fixture.vm.state.value.details).url)
    assertEquals(listOf(other.workLocator(), other.workLocator()), fixture.library.offeredParents)
    assertEquals(listOf(false, false), fixture.library.notifyRequests)
    assertEquals(listOf(other.url, other.url), fixture.source.requests.map { it.url })
}

private suspend fun assertTwinNewRow(
    fixture: DetailsUrlOnlyRoomFixture,
    savedB: SavedChapterEntity,
    otherNew: Chapter,
) {
    val bRows = fixture.db.chapterDao().getChaptersByMangaIdR(savedB.mangaId)
    assertEquals(2, bRows.size)
    assertEquals(savedB, bRows.single { it.id == savedB.id })
    assertNewRow(bRows.single { it.url == otherNew.url }, savedB.mangaId, otherNew.url)
}
