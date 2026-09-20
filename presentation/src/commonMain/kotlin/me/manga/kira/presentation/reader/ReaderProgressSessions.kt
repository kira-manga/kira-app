package me.manga.kira.presentation.reader

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.ChapterLocator
import me.manga.kira.domain.model.identity.ProgressHandle
import me.manga.kira.domain.model.identity.ProgressSnapshot
import me.manga.kira.domain.model.progress.ProgressWriteResult
import me.manga.kira.domain.usecase.reader.BeginReadProgressSessionUseCase
import me.manga.kira.domain.usecase.reader.PrepareLegacyReadProgressUseCase
import me.manga.kira.domain.usecase.reader.SaveScopedPagePositionUseCase

/**
 * One Reader's progress sessions, confined to its reducer dispatcher and lifetime.
 *
 * Requested locators key the feed; the data layer's returned handle may retain a proven alias.
 * Entry generations fence suspended work locally, independently of the durable handle generations.
 * Writes keep captured handles across navigation and run in page-event order, never max-page order.
 */
class ReaderProgressSessions(
    private val prepareLegacy: PrepareLegacyReadProgressUseCase,
    private val beginSession: BeginReadProgressSessionUseCase,
    private val savePosition: SaveScopedPagePositionUseCase,
) {
    /** Local feed generation, not a durable persistence fence. */
    var generation: Long = 0
        private set

    private var closed = false
    private val sessions = mutableMapOf<ChapterLocator, Session>()
    private val writes = Channel<PendingWrite>(Channel.UNLIMITED)

    /** Deliberate replacement forgets feed handles, but does not retarget or drop queued writes. */
    fun replaceFeed(): Long {
        generation++
        sessions.clear()
        return generation
    }

    /** Whether an asynchronous result still belongs to the visible Reader entry. */
    fun isCurrent(entry: Long): Boolean = !closed && entry == generation

    /**
     * Prepare the captured raw locator, then acquire once for this feed's deliberate entry/append.
     * Null means superseded or already pending, NOT an absent saved position. Failures are cached
     * too: retrying page fetches must not silently reacquire a failed or stale progress session.
     */
    suspend fun open(entry: Long, chapter: ChapterLocator): AppResult<ProgressSnapshot>? {
        if (!isCurrent(entry)) return null
        sessions[chapter]?.let { return it.result }
        val session = Session()
        sessions[chapter] = session
        val prepared = prepareLegacy(chapter)
        currentCoroutineContext().ensureActive()
        if (!isCurrent(entry)) return null
        val result = when (prepared) {
            is AppResult.Failure -> prepared
            is AppResult.Success -> beginSession(chapter)
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrent(entry)) return null
        session.result = result
        return result
    }

    /** Capture a real page change synchronously; displaying an absent seed at page zero is not one. */
    fun pageChanged(entry: Long, chapter: ChapterLocator, pageIndex: Int) {
        if (!isCurrent(entry)) return
        val session = sessions[chapter] ?: return
        val result = session.result
        if (result !is AppResult.Success) return
        if (session.writable) {
            writes.trySend(PendingWrite(entry, session, result.value.handle, pageIndex))
        }
    }

    /**
     * Run exactly once in the VM's cancellable scope. A stale result disables that acquired session,
     * including already queued events; it never opens another handle. Typed failures do not retry.
     */
    suspend fun writeQueued(onFailure: suspend (Long, AppError) -> Unit) {
        for (write in writes) {
            if (!write.session.writable) continue
            when (val result = savePosition(write.handle, write.pageIndex)) {
                is AppResult.Failure -> onFailure(write.entry, result.error)
                is AppResult.Success -> {
                    if (result.value == ProgressWriteResult.STALE) write.session.writable = false
                }
            }
        }
    }

    /** Teardown invalidates local work only; it neither clears durable progress nor promises a flush. */
    fun close() {
        closed = true
        sessions.clear()
        writes.cancel()
    }

    private class Session {
        var result: AppResult<ProgressSnapshot>? = null
        var writable = true
    }

    private data class PendingWrite(
        val entry: Long,
        val session: Session,
        val handle: ProgressHandle,
        val pageIndex: Int,
    )
}
