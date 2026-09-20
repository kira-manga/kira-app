package me.manga.kira.data.repository.progress

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.data.mapper.savedIdentity
import me.manga.kira.data.repository.LIBRARY_CURRENT_URL
import me.manga.kira.data.repository.LIBRARY_PREVIOUS_URL
import me.manga.kira.data.repository.LIBRARY_TEST_API
import me.manga.kira.data.repository.libraryParent
import me.manga.kira.data.repository.librarySavedChapter
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.SavedProgressOwner
import me.manga.kira.domain.model.identity.WorkLocator
import kotlin.test.fail

internal val PROGRESS_WORK = WorkLocator(LIBRARY_TEST_API, LIBRARY_CURRENT_URL)
internal val PROGRESS_CHAPTER = ChapterLocator(PROGRESS_WORK, "$LIBRARY_CURRENT_URL/chapter/one")
internal val PROGRESS_PREVIOUS = ChapterLocator(
    WorkLocator(LIBRARY_TEST_API, LIBRARY_PREVIOUS_URL), "$LIBRARY_PREVIOUS_URL/chapter/one",
)

internal data class ProgressSavedFamily(val work: SavedMangaEntity, val chapter: SavedChapterEntity) {
    val locator get() = ChapterLocator(work.savedIdentity().locator, chapter.url)
    val owner get() = SavedProgressOwner(work.savedIdentity(), chapter.id)
}

internal suspend fun ProgressRuntimeFixture.family(
    draft: SavedMangaEntity = libraryParent(),
    chapterUrl: String = "${draft.url}/chapter/one",
): ProgressSavedFamily {
    val work = parent(draft)
    return ProgressSavedFamily(work, chapter(librarySavedChapter(work, chapterUrl)))
}

internal fun ProgressRuntimeFixture.seedLegacy(
    locator: ChapterLocator = PROGRESS_CHAPTER,
    pageText: String = "6",
): CapturedLegacyProgress {
    val captured = CapturedLegacyProgress(legacyProgressKey(locator.chapterUrl), "${locator.chapterUrl}|$pageText")
    settings.putString(captured.key, captured.payload)
    return captured
}

internal fun <T> AppResult<T>.progressValue(): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> fail("Expected progress success, received $error")
}

/** The competitor holds a real Room writer; policy changes only after the operation has attempted entry. */
internal suspend fun <T> ProgressRuntimeFixture.afterWriterWait(
    operation: suspend () -> T,
    change: suspend () -> Unit,
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
