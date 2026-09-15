package me.manga.kira.presentation.testing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.history.HistoryEntry
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.repository.ChapterBookmarkRepository
import me.manga.kira.domain.repository.ChapterPagesRepository
import me.manga.kira.domain.repository.HistoryRepository
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.repository.MarkChapterReadRepository
import me.manga.kira.domain.repository.ReadProgressRepository
import me.manga.kira.domain.repository.ReadingModeRepository
import me.manga.kira.domain.repository.ReadingSessionRepository
import me.manga.kira.domain.repository.SavedMangaDetailsRepository
import me.manga.kira.domain.usecase.reader.ClearExtractedPagesUseCase
import me.manga.kira.domain.usecase.reader.ClearPageProgressUseCase
import me.manga.kira.domain.usecase.reader.EndReadingSessionUseCase
import me.manga.kira.domain.usecase.reader.FetchChapterPagesUseCase
import me.manga.kira.domain.usecase.reader.ListChaptersUseCase
import me.manga.kira.domain.usecase.reader.LoadPagePositionUseCase
import me.manga.kira.domain.usecase.reader.MarkChapterReadUseCase
import me.manga.kira.domain.usecase.reader.ObserveChapterBookmarkUseCase
import me.manga.kira.domain.usecase.reader.ObservePageProgressUseCase
import me.manga.kira.domain.usecase.reader.ObserveReadingModeUseCase
import me.manga.kira.domain.usecase.reader.RecordHistoryUseCase
import me.manga.kira.domain.usecase.reader.SavePagePositionUseCase
import me.manga.kira.domain.usecase.reader.SetReadingModeUseCase
import me.manga.kira.domain.usecase.reader.StartReadingSessionUseCase
import me.manga.kira.domain.usecase.reader.ToggleChapterBookmarkUseCase
import me.manga.kira.presentation.reader.ReaderViewModel

/**
 * Reusable [ReaderViewModel] test harness. The VM has many collaborators; all but the page-fetch
 * are benign no-ops here. [ReaderTestEnv.pages] is the controllable [ChapterPagesRepository] a test
 * drives; [ReaderTestEnv.chapterList] seeds the chapter list (for Next/Prev / append tests).
 */
class FakeChapterPagesRepository : ChapterPagesRepository {
    /** The flow returned for the next fetch. Default: a single empty Success. */
    var result: Flow<AppResult<List<Page>>> = flowOf(AppResult.Success(emptyList()))
    val fetched = mutableListOf<Pair<Manga, Chapter>>()

    override fun fetchPages(
        manga: Manga,
        chapter: Chapter,
    ): Flow<AppResult<List<Page>>> {
        fetched += manga to chapter
        return result
    }

    val cleared = mutableListOf<Pair<Manga, Chapter>>()

    override fun clearExtractedPages(
        manga: Manga,
        chapter: Chapter,
    ) {
        cleared += manga to chapter
    }
}

private class FakeReadingModeRepository : ReadingModeRepository {
    override fun observe(): Flow<ReadingMode> = flowOf(ReadingMode.WEBTOON)

    override suspend fun set(mode: ReadingMode) = Unit
}

private class FakeReaderMangaDetailsRepository(
    private val details: MangaDetails,
) : MangaDetailsRepository {
    override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> = AppResult.Success(details)
}

private class FakeSavedDetailsRepository : SavedMangaDetailsRepository {
    override fun observeSavedDetails(
        api: String,
        title: String,
    ): Flow<MangaDetails?> = flowOf(null)
}

/**
 * Counting [ReadingSessionRepository] (#7) — records begin/end calls so a test can assert the
 * reader coalesces duplicate lifecycle callbacks into begin/end edges. A single shared instance is
 * wired into BOTH the start and end use cases in [readerTestEnv] (mirrors the production single
 * binding), so the counts reflect the same session.
 */
class RecordingReadingSessionRepository : ReadingSessionRepository {
    val calls = mutableListOf<String>()
    /** Optional persistence barrier; record the end before waiting, matching capture-before-write order. */
    var endCompletion: CompletableDeferred<Unit>? = null
    var beginCount = 0
        private set
    var endCount = 0
        private set
    var completedEndCount = 0
        private set

    override fun begin() {
        beginCount++
        calls += "begin"
    }

    override suspend fun end() {
        endCount++
        calls += "end"
        endCompletion?.await()
        completedEndCount++
    }
}

class RecordingReadProgressRepository : ReadProgressRepository {
    /** Every (chapterUrl, withinChapterPageIndex) save, in order — for resume-position assertions. */
    val saved = mutableListOf<Pair<String, Int>>()

    /** Value returned by [load] (the resume seed); a test can set it before OnEnter. */
    var loadValue: Int? = null
    var loadGate: CompletableDeferred<Unit>? = null

    override suspend fun save(
        chapterUrl: String,
        pageIndex: Int,
    ) {
        saved += chapterUrl to pageIndex
    }

    override suspend fun load(chapterUrl: String): Int? {
        loadGate?.await()
        return loadValue
    }

    override suspend fun clear(chapterUrl: String) = Unit
}

class RecordingChapterBookmarkRepository : ChapterBookmarkRepository {
    /** Owner and chapter URL subscribed to, in order — `last()` is the active observed chapter. */
    val observed = mutableListOf<Pair<Manga, String>>()

    /** Owner and chapter URL toggled, in order — for asserting the toggle targets the active chapter. */
    val toggled = mutableListOf<Pair<Manga, String>>()

    /**
     * Whether [toggleBookmark] reports the chapter as in-library (#15). `true` (default) mimics a
     * chapter with a `saved_chapters` row (toggle succeeds); set `false` to exercise the
     * not-in-library no-op path that drives `ReaderEffect.ShowNotInLibrary`.
     */
    var inLibrary: Boolean = true

    override fun observeBookmark(
        manga: Manga,
        chapterUrl: String,
    ): Flow<Boolean> {
        observed += manga to chapterUrl
        return flowOf(false)
    }

    override suspend fun toggleBookmark(
        manga: Manga,
        chapterUrl: String,
    ): Boolean {
        toggled += manga to chapterUrl
        return inLibrary
    }

    override suspend fun toggleBookmark(
        manga: Manga,
        chapterUrls: List<String>,
    ) {
        toggled += chapterUrls.map { manga to it }
    }
}

class RecordingHistoryRepository : HistoryRepository {
    /** (manga.title, chapter.url) recorded, in order — for asserting history follows the active chapter. */
    val recorded = mutableListOf<Pair<String, String>>()

    override fun observeHistory(): Flow<List<HistoryEntry>> = flowOf(emptyList())

    override suspend fun deleteEntry(entry: HistoryEntry) = Unit

    override suspend fun deleteAll() = Unit

    override suspend fun record(
        manga: Manga,
        chapter: Chapter,
    ) {
        recorded += manga.title to chapter.url
    }
}

class RecordingMarkChapterReadRepository : MarkChapterReadRepository {
    val marked = mutableListOf<Pair<Manga, String>>()

    override suspend fun markRead(
        manga: Manga,
        chapterUrl: String,
    ) {
        marked += manga to chapterUrl
    }

    override suspend fun toggleRead(
        manga: Manga,
        chapterUrl: String,
    ) = Unit

    override suspend fun markRead(
        manga: Manga,
        chapterUrls: List<String>,
    ) {
        marked += chapterUrls.map { manga to it }
    }
}

/**
 * Owns a [ReaderViewModel] and the fakes a test drives. [chapterList] seeds the chapter list the VM lists
 * on enter (used by Next/Prev/append tests); the manga details fetch returns those chapters.
 *
 * Resume/bookmark/history use RECORDING fakes (shared per concern) so tests can assert that those
 * actions follow the active visible chapter (#5). A single [RecordingChapterBookmarkRepository] backs
 * both the observe and toggle use cases so observed/toggled URLs line up.
 */
class ReaderTestEnv(
    chapterList: List<Chapter>,
) {
    val pages = FakeChapterPagesRepository()
    val markRead = RecordingMarkChapterReadRepository()
    val readProgress = RecordingReadProgressRepository()
    val bookmark = RecordingChapterBookmarkRepository()
    val history = RecordingHistoryRepository()

    // #7: ONE shared reading-session recorder wired into both use cases (mirrors prod single binding).
    val readingSession = RecordingReadingSessionRepository()
    val pageProgress = RecordingPageProgressRepository()
    private val details =
        MangaDetails(
            api = "src",
            language = "en",
            title = "Naruto",
            url = "https://x/naruto",
            coverUrl = "",
            description = "",
            author = "",
            rating = "",
            status = "",
            genres = emptyList(),
            chapters = chapterList,
        )
    val vm =
        ReaderViewModel(
            fetchPages = FetchChapterPagesUseCase(pages),
            observeReadingMode = ObserveReadingModeUseCase(FakeReadingModeRepository()),
            setReadingMode = SetReadingModeUseCase(FakeReadingModeRepository()),
            listChapters = ListChaptersUseCase(FakeReaderMangaDetailsRepository(details), FakeSavedDetailsRepository()),
            startReadingSession = StartReadingSessionUseCase(readingSession),
            endReadingSession = EndReadingSessionUseCase(readingSession),
            loadPagePosition = LoadPagePositionUseCase(readProgress),
            savePagePosition = SavePagePositionUseCase(readProgress),
            observePageProgress = ObservePageProgressUseCase(pageProgress),
            observeChapterBookmark = ObserveChapterBookmarkUseCase(bookmark),
            toggleChapterBookmark = ToggleChapterBookmarkUseCase(bookmark),
            recordHistory = RecordHistoryUseCase(history, FakeSettingsRepository()),
            markChapterRead = MarkChapterReadUseCase(markRead),
            clearExtractedPages = ClearExtractedPagesUseCase(pages),
            clearPageProgress = ClearPageProgressUseCase(pageProgress),
        )
}

fun readerTestEnv(chapterList: List<Chapter> = emptyList()): ReaderTestEnv = ReaderTestEnv(chapterList)

fun readerChapter(n: String): Chapter =
    Chapter(number = n, name = "Ch $n", url = "ch/$n", date = null, isDownloaded = false, isBookmarked = false)

fun readerManga(): Manga =
    Manga(
        api = "src",
        language = "en",
        title = "Naruto",
        url = "https://x/naruto",
        coverUrl = "",
        rating = null,
        genres = emptyList(),
    )

fun readerPage(url: String): Page = Page(url = url, headers = emptyMap())
