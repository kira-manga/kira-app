package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.service.FileService
import me.manga.kira.domain.usecase.reader.ListChaptersUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tests mobile persistence on real Room/SQLite, not the desktop product or a DAO list fake. */
class SavedChapterOrderTest : ChapterOwnershipFixture() {
    @Test
    fun sourceOrderSurvivesLibrarySaveRefreshAndReopen() =
        runTest {
            val original = details(listOf(chapter("18"), chapter("17"), chapter("16")))
            var networkCalls = 0
            val network =
                object : MangaDetailsRepository {
                    override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> {
                        assertEquals(mangaA, manga)
                        networkCalls++
                        return AppResult.Success(original)
                    }
                }
            fun saved() = SavedMangaDetailsRepositoryImpl(db.mangaDao(), db.chapterDao(), dispatchers)
            fun reader() = ListChaptersUseCase(network, saved())
            fun library() =
                LibraryRepositoryImpl(
                    db.mangaDao(), db.libraryDeo(), db.chapterDao(), db.notificationDao(),
                    db.historyDao(), db.chapterDownloadingDao(), FakeDownloadRepository(),
                    FileService(appFs), RecordingReadProgressRepository(), dispatchers,
                )

            val before = assertIs<AppResult.Success<List<Chapter>>>(reader()(mangaA)).value
            assertEquals(1, networkCalls)
            assertIs<AppResult.Success<Unit>>(library().addToLibrary(original))
            val after = assertIs<AppResult.Success<List<Chapter>>>(reader()(mangaA)).value
            assertEquals(before.map(Chapter::url), after.map(Chapter::url))
            assertEquals(1, networkCalls, "The saved Reader result must actually come from Room")
            val savedDetails = assertNotNull(saved().observeSavedDetails(mangaA.api, mangaA.title).first())
            assertEquals(original.chapters.map(Chapter::url), savedDetails.chapters.map(Chapter::url))

            val parent = assertNotNull(db.libraryDeo().getMangaIdByUrl(mangaA.url))
            val oldestFirst = db.chapterDao().getChaptersByMangaId(parent).first()
            assertEquals(listOf("16", "17", "18"), oldestFirst.map { it.number })
            val middle = oldestFirst.single { it.number == "17" }
            db.chapterDao().markChapterAsRead(middle.id, 1234)
            db.chapterDao().toggleChapterBookmark(middle.id)

            val refreshed = listOf(chapter("20"), chapter("19")) + original.chapters
            assertEquals(2, assertIs<AppResult.Success<Int>>(
                library().persistNewChaptersAndNotify(mangaA, refreshed),
            ).value)
            val projected = assertIs<AppResult.Success<List<Chapter>>>(reader()(mangaA)).value
            assertEquals(refreshed.map(Chapter::url), projected.map(Chapter::url))
            assertTrue(projected.single { it.number == "17" }.isRead)
            assertTrue(projected.single { it.number == "17" }.isBookmarked)
            assertEquals(1234L, projected.single { it.number == "17" }.lastReadAtEpochMillis)
            assertEquals(1, networkCalls)

            db.close()
            db = openDatabase()
            assertEquals(projected, assertIs<AppResult.Success<List<Chapter>>>(reader()(mangaA)).value)
            assertEquals(1, networkCalls)
        }

    private fun chapter(number: String) =
        Chapter(number, "Chapter $number", "chapter/$number", null, false, false)

    private fun details(chapters: List<Chapter>) =
        MangaDetails(
            mangaA.api, mangaA.language, mangaA.title, mangaA.url, mangaA.coverUrl,
            "", "", "", "", emptyList(), chapters,
        )
}
