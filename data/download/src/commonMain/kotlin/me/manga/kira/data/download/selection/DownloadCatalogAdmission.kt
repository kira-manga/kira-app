package me.manga.kira.data.download.selection

import me.manga.kira.core.result.AppResult
import me.manga.kira.platform.download.DownloadOperationExclusion

/** Current durable/process selection could not admit a new capture; not a chapter download failure. */
class DownloadCatalogNotReady : IllegalStateException("Download catalog selection is not ready")

/**
 * Preparation and ordinary admission are separate. No result, cached readiness or caller-supplied
 * token grants ownership. Composition must supply the actual same-graph implementation.
 */
interface DownloadCatalogAdmission {
    /**
     * Call before acquiring operation ownership, a Room transaction or an engine mutex. This local
     * preparation outcome is only a hint; it grants no lease and supplies no retry/scheduler policy.
     * Never bootstrap from an existing operation's terminal/cancellation cleanup.
     */
    suspend fun prepareLocal(): AppResult<Unit>

    /**
     * Acquire before the first mutable locator/row/manifest/file capture. Compare the actual
     * durable and process selection under the owning writer, then retain operation ownership until
     * [block] and its structured cleanup finish. The writer must end before caller I/O begins.
     *
     * The supplied handle is borrowed. Retain a distinct handle BEFORE detached/native handoff;
     * that child releases only its own handle after actual completion, not after a cancelled join.
     * Already-owned callbacks settle through their retained ownership, not fresh readiness admission.
     *
     * Admission is a point-in-time selection check, not a process-readiness lease. Later process
     * invalidation does not cancel admitted cleanup; subsequent admissions must check again.
     * [DownloadCatalogNotReady] must not be translated into chapter FAILED/SUCCESS.
     */
    suspend fun <T> withAdmittedOperation(block: suspend (DownloadOperationExclusion.Operation) -> T): T
}
