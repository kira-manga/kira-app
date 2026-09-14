package me.manga.kira.presentation.reader

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.repository.ChapterPagesRepository
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.repository.ReadProgressRepository
import me.manga.kira.domain.repository.ReadingModeRepository
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
import me.manga.kira.presentation.testing.FakeSettingsRepository
import me.manga.kira.presentation.testing.RecordingChapterBookmarkRepository
import me.manga.kira.presentation.testing.RecordingHistoryRepository
import me.manga.kira.presentation.testing.RecordingMarkChapterReadRepository
import me.manga.kira.presentation.testing.RecordingPageProgressRepository
import me.manga.kira.presentation.testing.RecordingReadingSessionRepository
import me.manga.kira.presentation.testing.readerChapter
import me.manga.kira.presentation.testing.readerManga
import me.manga.kira.presentation.testing.readerPage

@OptIn(ExperimentalCoroutinesApi::class)
internal class ReaderActiveActionFixture(
    private val scheduler: TestCoroutineScheduler,
    mode: ReadingMode = ReadingMode.WEBTOON,
) {
    val manga = readerManga()
    val chapters = listOf(readerChapter("1"), readerChapter("2"), readerChapter("3"))
    val pages = ActiveActionPages(chapters)
    val progress = ActiveActionProgress()
    val resume = ActiveActionResume()
    val details = ActiveActionDetails(manga, chapters)
    val bookmark = RecordingChapterBookmarkRepository()
    val history = RecordingHistoryRepository()
    val markRead = RecordingMarkChapterReadRepository()
    private val sessions = RecordingReadingSessionRepository()
    private val readingMode = ActiveActionReadingMode(mode)

    val vm =
        ReaderViewModel(
            fetchPages = FetchChapterPagesUseCase(pages),
            observeReadingMode = ObserveReadingModeUseCase(readingMode),
            setReadingMode = SetReadingModeUseCase(readingMode),
            listChapters = ListChaptersUseCase(details, ActiveActionSavedDetails()),
            startReadingSession = StartReadingSessionUseCase(sessions),
            endReadingSession = EndReadingSessionUseCase(sessions),
            loadPagePosition = LoadPagePositionUseCase(resume),
            savePagePosition = SavePagePositionUseCase(resume),
            observePageProgress = ObservePageProgressUseCase(progress),
            observeChapterBookmark = ObserveChapterBookmarkUseCase(bookmark),
            toggleChapterBookmark = ToggleChapterBookmarkUseCase(bookmark),
            recordHistory = RecordHistoryUseCase(history, FakeSettingsRepository()),
            markChapterRead = MarkChapterReadUseCase(markRead),
            clearExtractedPages = ClearExtractedPagesUseCase(pages),
            clearPageProgress = ClearPageProgressUseCase(progress),
        )

    fun dispatch(intent: ReaderIntent) {
        vm.submit(intent)
        scheduler.runCurrent()
    }

    fun enterAndAppend(count: Int = 1) {
        dispatch(ReaderIntent.OnEnter(manga, chapters.first()))
        repeat(count) { dispatch(ReaderIntent.OnAppendNextChapter) }
    }

    fun close() {
        vm.viewModelScope.cancel()
        scheduler.runCurrent()
    }

    fun callCounts(): Map<String, Int> =
        mapOf(
            "fetch" to pages.requested.size,
            "cleanup" to pages.cleared.size,
            "list" to details.requested.size,
            "resumeLoad" to resume.loaded.size,
            "resumeSave" to resume.saved.size,
            "bookmark" to bookmark.observed.size,
            "history" to history.recorded.size,
            "markRead" to markRead.marked.size,
            "progressCancellation" to progress.cancelled.size,
        )
}

internal fun activeActionPages(chapter: Chapter): List<Page> =
    listOf(
        readerPage("${chapter.url}/a"),
        readerPage("${chapter.url}/b"),
    )

internal class ActiveActionPages(
    chapters: List<Chapter>,
) : ChapterPagesRepository {
    val results =
        chapters
            .associate { chapter ->
                chapter.url to flowOf<AppResult<List<Page>>>(AppResult.Success(activeActionPages(chapter)))
            }.toMutableMap()
    val requested = mutableListOf<Pair<Manga, Chapter>>()
    val cleared = mutableListOf<Chapter>()

    override fun fetchPages(
        manga: Manga,
        chapter: Chapter,
    ): Flow<AppResult<List<Page>>> {
        requested += manga to chapter
        return results.getValue(chapter.url)
    }

    override fun clearExtractedPages(chapter: Chapter) {
        cleared += chapter
    }
}

internal typealias ActiveActionProgress = RecordingPageProgressRepository

internal class ActiveActionResume : ReadProgressRepository {
    val positions = mutableMapOf<String, Int>()
    val loaded = mutableListOf<String>()
    val saved = mutableListOf<Pair<String, Int>>()

    override suspend fun save(
        chapterUrl: String,
        pageIndex: Int,
    ) {
        saved += chapterUrl to pageIndex
        positions[chapterUrl] = pageIndex
    }

    override suspend fun load(chapterUrl: String): Int? {
        loaded += chapterUrl
        return positions[chapterUrl]
    }

    override suspend fun clear(chapterUrl: String) {
        positions.remove(chapterUrl)
    }
}

internal class ActiveActionDetails(
    manga: Manga,
    chapters: List<Chapter>,
) : MangaDetailsRepository {
    val lists = mutableMapOf(manga to chapters)
    val gates = mutableMapOf<Manga, CompletableDeferred<Unit>>()
    val requested = mutableListOf<Manga>()

    override suspend fun fetchDetails(manga: Manga): AppResult<MangaDetails> {
        requested += manga
        gates[manga]?.await()
        return AppResult.Success(
            MangaDetails(
                api = manga.api,
                language = manga.language,
                title = manga.title,
                url = manga.url,
                coverUrl = "",
                description = "",
                author = "",
                rating = "",
                status = "",
                genres = emptyList(),
                chapters = lists.getValue(manga),
            ),
        )
    }
}

private class ActiveActionSavedDetails : SavedMangaDetailsRepository {
    override fun observeSavedDetails(
        api: String,
        title: String,
    ): Flow<MangaDetails?> = flowOf(null)
}

private class ActiveActionReadingMode(
    mode: ReadingMode,
) : ReadingModeRepository {
    private val value = MutableStateFlow(mode)

    override fun observe(): Flow<ReadingMode> = value

    override suspend fun set(mode: ReadingMode) {
        value.value = mode
    }
}
