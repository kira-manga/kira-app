package me.manga.kira.data.download.artifacts

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.data.local.dao.ChapterArtifactDao
import me.manga.kira.data.local.dao.ChapterConversionOutcome
import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterArtifactFile
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.claimOrNull
import me.manga.kira.data.local.entity.isOwnedBy
import okio.Path

/**
 * One singleton per database, shared by download producers, backup, readers and destructive users.
 * Short transition locks exclude admission changes; file locks protect extraction/publication.
 * Native encodes and coroutine producers retain a use permit until their actual finally unwinds.
 */
class ChapterArtifacts(private val dao: ChapterArtifactDao, private val recovery: ChapterArtifactRecovery) {
    private val gates = ChapterArtifactGates()
    private val readiness = Mutex()
    private var ready = false

    /** Admission and queue insertion form one Room transaction; no network or file work here. */
    suspend fun enqueue(expected: SavedChapterEntity, requested: ChapterDownloadEntity): ChapterArtifactClaim? =
        admission(expected.mangaId, expected.id) { token -> dao.enqueue(expected, requested, token) }

    /** Prepare idle retry files before queue visibility; callback must not re-enter this coordinator. */
    suspend fun retry(expected: ChapterDownloadEntity, prepare: (String) -> Unit = {}): ChapterArtifactClaim? =
        admission(expected.mangaId, expected.chapterId, pinFiles = true) { token ->
            if (dao.retryCandidate(expected) == null) null else {
                prepare(token)
                dao.retry(expected, token)
            }
        }

    /** Restores reserve custody before creating even their private generation under chapterDir. */
    suspend fun beginRestore(expected: SavedChapterEntity, sizeBytes: Long): ChapterArtifactClaim? =
        admission(expected.mangaId, expected.id) { token ->
            dao.claimRestore(expected, token, ChapterArtifactFile(ChapterArtifactReference.restored(token), sizeBytes))
        }

    /** Bind a queue snapshot once. A newer ledger id cannot inherit this producer's authority. */
    suspend fun downloadClaim(expected: ChapterDownloadEntity): ChapterArtifactClaim? =
        admission(expected.mangaId, expected.chapterId) { token -> dao.claimExistingDownload(expected, token) }

    /** Current FAILED generation only; same gates as Retry, never producer authority. */
    suspend fun beginFailedCleanup(expected: ChapterDownloadEntity): ChapterArtifactClaim? =
        admission(expected.mangaId, expected.chapterId) { token -> dao.claimFailedCleanup(expected, token) }

    /** Capture, revoke and failure bookkeeping share the existing short publication fence. */
    internal suspend fun cancelCapturedDownload(
        expected: ChapterDownloadEntity,
        cancelled: suspend (ChapterArtifactClaim) -> Boolean,
    ): ChapterArtifactClaim? = admission(expected.mangaId, expected.chapterId) { token ->
        val claim = dao.claimCapturedCancellation(expected, token) ?: return@admission null
        if (cancelled(claim)) claim else null
    }

    /** Queue drains may await this exact refusal outside producer/file/engine locks. */
    internal suspend fun downloadAdmission(expected: ChapterDownloadEntity): ChapterArtifactAdmission {
        var parentReopen: Deferred<Unit>? = null
        val claim = admission(expected.mangaId, expected.chapterId, onParentClosed = { parentReopen = it }) { token ->
            dao.claimExistingDownload(expected, token)
        }
        return ChapterArtifactAdmission(claim, parentReopen)
    }

    suspend fun beginConversion(expected: SavedChapterEntity): ChapterArtifactClaim? =
        admission(expected.mangaId, expected.id, pinFiles = true) { token ->
            // Dispatch before Room reservation, never add a prompt-cancellable return after it.
            val roster = withContext(platformIoDispatcher) { recovery.conversionFiles.capture(expected) }
            dao.claimConversion(expected, token, roster)
        }

    /** The exact captured inputs, retained writer, archive validation and Room write share one pin. */
    suspend fun convertFiles(
        claim: ChapterArtifactClaim,
        write: suspend (List<Path>) -> Path,
        commit: suspend (Path, Long) -> Boolean,
    ): Boolean? = files(claim) {
        val archive = withContext(platformIoDispatcher) { recovery.conversionFiles.prepare(claim, write) }
        publish(claim) { commit(archive.path, archive.sizeBytes) }
    }

    suspend fun settleConversion(claim: ChapterArtifactClaim): ChapterConversionOutcome =
        recovery.settleConversion(this, claim)

    /** Explicit retry is bounded to retiring conversions; never revoke or wait on a live writer. */
    suspend fun recoverConversions() {
        ensureReady()
        for (record in dao.getUnsettled()) {
            if (record.retiring && record.operation == ChapterArtifactOperation.CONVERT) {
                record.claimOrNull()?.let { recovery.settleConversion(this, it) }
            }
        }
    }

    /** Read current custody without manufacturing authority for an asynchronous producer. */
    suspend fun currentClaim(chapterId: Long): ChapterArtifactClaim? = dao.get(chapterId)?.claimOrNull()

    /** Checks and metadata writes cannot race revoke/release through another coordinator user. */
    suspend fun <T> publish(claim: ChapterArtifactClaim, action: suspend () -> T): T? {
        val gate = gates.chapter(claim.owner.chapterId)
        return gate.transition.withLock { if (dao.canPublish(claim)) action() else null }
    }

    /** Keep this permit across the whole producer, including cancellation/cleanup finally blocks. */
    suspend fun <T> producing(claim: ChapterArtifactClaim, action: suspend () -> T): T? {
        val gate = gates.chapter(claim.owner.chapterId)
        val admitted = gate.transition.withLock {
            if (!dao.canPublish(claim)) false else true.also { gate.acquire(claim.token) }
        }
        if (!admitted) return null
        return try {
            action()
        } finally {
            withContext(NonCancellable) { gate.transition.withLock { gate.release(claim.token) } }
        }
    }

    /** Serialize only live file work, never the producer's network waits. */
    suspend fun <T> files(claim: ChapterArtifactClaim, action: suspend () -> T): T? = producing(claim) {
        val gate = gates.chapter(claim.owner.chapterId)
        gate.files.withLock {
            val admitted = gate.transition.withLock { dao.canPublish(claim) }
            if (admitted) action() else null
        }
    }

    /** Pins a committed CBZ only through its existing extraction/export copy, not reader lifetime. */
    suspend fun <T> read(chapterId: Long, action: suspend (ChapterArtifactEntity?) -> T): T {
        ensureReady()
        val gate = gates.chapter(chapterId)
        return gate.files.withLock {
            val record = gate.transition.withLock { dao.get(chapterId) }
            action(record)
        }
    }

    /** Stops new publications immediately but keeps file custody until [settle] drains producers. */
    suspend fun revoke(claim: ChapterArtifactClaim): Boolean {
        val gate = gates.chapter(claim.owner.chapterId)
        return gate.transition.withLock {
            val record = dao.get(claim.owner.chapterId)
            record?.isOwnedBy(claim) == true && dao.revoke(claim.owner.chapterId, claim.token) == 1
        }
    }

    /**
     * Request a stop only for actual users of this retiring token, not historical cleanup custody.
     * The signal is synchronous under the existing transition fence: do not await or re-enter here.
     * In particular, Android's shared worker must not stop for an unrelated settled FAILED row.
     */
    suspend fun requestStopIfProducing(claim: ChapterArtifactClaim, stop: () -> Unit): Boolean {
        val gate = gates.chapter(claim.owner.chapterId)
        return gate.transition.withLock {
            val record = dao.get(claim.owner.chapterId) ?: return@withLock false
            if (!record.isOwnedBy(claim) || !record.retiring || gate.drained(claim.token) == null) {
                false
            } else {
                stop()
                true
            }
        }
    }

    /** Revocation and cancellation bookkeeping share the same publication fence. */
    suspend fun <T> invalidate(claim: ChapterArtifactClaim, action: suspend () -> T): T? {
        val gate = gates.chapter(claim.owner.chapterId)
        return gate.transition.withLock {
            if (dao.get(claim.owner.chapterId)?.isOwnedBy(claim) != true) return@withLock null
            check(dao.revoke(claim.owner.chapterId, claim.token) == 1)
            action()
        }
    }

    /**
     * Called AFTER the producer/use scope has unwound. False cleanup (including unknown commit)
     * deliberately retains the durable token. Never invoke this from inside [producing]/[files].
     * The callback runs exclusively and must not re-enter this coordinator.
     */
    suspend fun settle(claim: ChapterArtifactClaim, cleanup: suspend (ChapterArtifactEntity) -> Boolean): Boolean {
        if (!revoke(claim)) return false
        val gate = gates.chapter(claim.owner.chapterId)
        gate.transition.withLock { gate.drained(claim.token) }?.await()
        return gate.files.withLock {
            gate.transition.withLock transition@{
                val record = dao.get(claim.owner.chapterId)?.takeIf { it.isOwnedBy(claim) && it.retiring }
                    ?: return@transition false
                if (!cleanup(record)) false else if (claim.operation == ChapterArtifactOperation.DELETE) {
                    dao.finishRemoval(claim.owner.chapterId, claim.token) == 1
                } else if (claim.operation == ChapterArtifactOperation.FAILED_CLEANUP) {
                    dao.finishFailedCleanup(claim)
                } else dao.release(claim.owner.chapterId, claim.token) == 1
            }
        }
    }

    /**
     * Manga purge retains this admission barrier through revoke, drain, checked row removal AND
     * directory cleanup. Existing file users can unwind without reacquiring the parent gate.
     */
    suspend fun <T> parentRemoval(mangaId: Long, action: suspend () -> T): T {
        ensureReady()
        return gates.parent(mangaId).remove { action() }
    }

    suspend fun removeChapter(owner: ChapterArtifactOwner, stop: suspend () -> Unit = {}): Boolean =
        parentRemoval(owner.mangaId) {
            stop()
            removeChapterUnderParent(owner)
        }

    /** Enumerate no-FK receipts as well as saved rows before a manga's cascading row deletion. */
    suspend fun ownersForManga(mangaId: Long): List<ChapterArtifactOwner> {
        val owners = dao.savedForManga(mangaId).map { ChapterArtifactOwner.of(it) } +
            dao.getForManga(mangaId).map { ChapterArtifactOwner(it.chapterId, it.mangaId, it.chapterUrl) }
        val groups = owners.groupBy { it.chapterId }
        check(groups.values.all { it.distinct().size == 1 }) { "Conflicting chapter artifact owner" }
        return groups.values.map { it.first() }.sortedBy { it.chapterId }
    }

    /** Caller holds [parentRemoval]; custody survives row/FK removal and any failing cleanup. */
    suspend fun removeChapterUnderParent(owner: ChapterArtifactOwner): Boolean {
        val gate = gates.chapter(owner.chapterId)
        val previous = gate.transition.withLock {
            dao.get(owner.chapterId)?.claimOrNull()?.also { dao.revoke(owner.chapterId, it.token) }
        }
        if (previous != null) gate.transition.withLock { gate.drained(previous.token) }?.await()
        return gate.files.withLock {
            gate.transition.withLock removal@{
                val claim = dao.reserveRemoval(owner, newToken()) ?: return@removal false
                if (!recovery.clearAndDelete(claim)) return@removal false
                check(dao.finishRemoval(owner.chapterId, claim.token) == 1)
                true
            }
        }
    }

    private suspend fun admission(
        mangaId: Long,
        chapterId: Long,
        onParentClosed: (Deferred<Unit>) -> Unit = {},
        pinFiles: Boolean = false,
        action: suspend (String) -> ChapterArtifactClaim?,
    ): ChapterArtifactClaim? {
        ensureReady()
        return gates.parent(mangaId).admit(onClosed = onParentClosed) {
            val gate = gates.chapter(chapterId)
            val reserve = suspend { gate.transition.withLock { reserveToken(chapterId, action) } }
            if (pinFiles) gate.files.withLock { reserve() } else reserve()
        }
    }

    private suspend fun reserveToken(chapterId: Long, action: suspend (String) -> ChapterArtifactClaim?): ChapterArtifactClaim? {
        val token = newToken()
        return withContext(NonCancellable) {
            try {
                action(token)
            } catch (cancelled: CancellationException) {
                throw cancelled // Possibly committed custody stays durable.
            } catch (failure: Exception) {
                dao.get(chapterId)?.claimOrNull()?.takeIf { it.token == token } ?: throw failure
            }
        }
    }

    private suspend fun ensureReady() = readiness.withLock {
        if (!ready) {
            recovery.beforeAdmission(this)
            ready = true
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newToken(): String = Uuid.random().toString()
}

internal data class ChapterArtifactAdmission(val claim: ChapterArtifactClaim?, val parentReopen: Deferred<Unit>?)
