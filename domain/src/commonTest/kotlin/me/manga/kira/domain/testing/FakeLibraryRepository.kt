package me.manga.kira.domain.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.repository.MangaKey

/**
 * Hand-written in-memory fake of [LibraryRepository] for `:domain` unit tests.
 *
 * The rework test suite uses hand fakes rather than a mocking framework (no Mockito/MockK on the
 * KMP classpath, and the contract favours explicit doubles). [observeLibrary] is driven by a
 * [MutableStateFlow] the test mutates via [emitLibrary]; every suspend mutator records its call in
 * [calls] so a test can assert delegation/read-only-ness, and returns [AppResult.Success] by
 * default.
 *
 * Tests seed real [LibraryManga] rows through [emitLibrary] using the `sampleLibraryManga`
 * test-factory (see `LibraryMangaFactory.kt` in this package); the flow starts empty.
 */
class FakeLibraryRepository : LibraryRepository {

    private val library = MutableStateFlow<List<LibraryManga>>(emptyList())
    private val inLibrary = MutableStateFlow(false)

    /** Recorded mutator/read-op calls in invocation order, for delegation assertions. */
    val calls: MutableList<String> = mutableListOf()

    /** Drive the [observeLibrary] flow from a test. */
    fun emitLibrary(value: List<LibraryManga>) {
        library.value = value
    }

    /** Drive the [observeIsInLibrary] flow from a test. */
    fun emitInLibrary(value: Boolean) {
        inLibrary.value = value
    }

    override fun observeLibrary(): Flow<List<LibraryManga>> = library.asStateFlow()

    override fun observeIsInLibrary(api: String, language: String, title: String): Flow<Boolean> =
        inLibrary.asStateFlow()

    override suspend fun get(api: String, language: String, title: String): AppResult<LibraryManga?> {
        calls += "get($api,$language,$title)"
        return AppResult.Success(
            library.value.firstOrNull {
                it.manga.api == api && it.manga.language == language && it.manga.title == title
            },
        )
    }

    /** Complete payload of the most recent [addToLibrary] call. */
    var lastAddedDetails: MangaDetails? = null
        private set

    override suspend fun addToLibrary(details: MangaDetails): AppResult<Unit> {
        calls += "addToLibrary(${details.api},${details.language},${details.title},chapters=${details.chapters.size})"
        lastAddedDetails = details
        return AppResult.Success(Unit)
    }

    override suspend fun updateCoverIfChanged(
        api: String,
        language: String,
        title: String,
        newCoverUrl: String,
    ): AppResult<Unit> {
        calls += "updateCoverIfChanged($api,$language,$title,$newCoverUrl)"
        return AppResult.Success(Unit)
    }

    override suspend fun removeFromLibrary(api: String, language: String, title: String): AppResult<Unit> {
        calls += "removeFromLibrary($api,$language,$title)"
        return AppResult.Success(Unit)
    }

    /** #21: settable purged-count the bulk-remove use case forwards; defaults to keys.size. */
    var removeAllPurgedCount: Int? = null

    override suspend fun removeAllFromLibrary(keys: List<MangaKey>): AppResult<Int> {
        calls += "removeAllFromLibrary(${keys.size})"
        return AppResult.Success(removeAllPurgedCount ?: keys.size)
    }

    override suspend fun toggleLiked(key: MangaKey): AppResult<Unit> {
        calls += "toggleLiked(${key.title})"
        return AppResult.Success(Unit)
    }

    override suspend fun toggleWatchingNow(key: MangaKey): AppResult<Unit> {
        calls += "toggleWatchingNow(${key.title})"
        return AppResult.Success(Unit)
    }

    override suspend fun markOpened(api: String, language: String, title: String): AppResult<Unit> {
        calls += "markOpened($api,$language,$title)"
        return AppResult.Success(Unit)
    }

    /** Count returned by [persistNewChapters]/[persistNewChaptersAndNotify]; a test can override it. */
    var persistedCount: Int = 0

    override suspend fun persistNewChapters(
        api: String,
        language: String,
        title: String,
        fetched: List<Chapter>,
    ): AppResult<Int> {
        calls += "persistNewChapters($api,$language,$title,fetched=${fetched.size})"
        return AppResult.Success(persistedCount)
    }

    override suspend fun persistNewChaptersAndNotify(manga: Manga, fetched: List<Chapter>): AppResult<Int> {
        calls += "persistNewChaptersAndNotify(${manga.api},${manga.language},${manga.title},fetched=${fetched.size})"
        return AppResult.Success(persistedCount)
    }
}
