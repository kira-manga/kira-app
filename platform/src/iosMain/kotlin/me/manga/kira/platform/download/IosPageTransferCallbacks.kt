package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.PageByteLimitExceeded
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaException
import me.manga.kira.platform.media.PageMediaInspector
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask

/** Per-task outcomes owned exclusively by the background session's serial delegate queue. */
@OptIn(ExperimentalForeignApi::class)
internal class IosPageTransferCallbacks(
    appFileSystem: AppFileSystem,
    mediaInspector: PageMediaInspector,
    private val pageBytePolicy: PageBytePolicy,
    private val listener: () -> TransferListener?,
    private val admitEvent: () -> (() -> Unit),
) {
    private val pages = IosDownloadedPageStore(appFileSystem, mediaInspector, pageBytePolicy)
    private val outcomes = mutableMapOf<ULong, PageOutcome>()

    fun prepareStaging() = pages.prepareStaging()

    fun handleWroteData(
        task: NSURLSessionTask,
        bytesWritten: Long,
        totalBytesWritten: Long,
        totalExpected: Long,
        operation: DownloadOperationExclusion.Operation,
    ) {
        val d = IosTransferIdentity.decode(task.taskDescription) ?: return
        val outcome = outcomes.getOrPut(task.taskIdentifier) { PageOutcome() }
        if (totalBytesWritten > pageBytePolicy.maxEncodedBytes || totalExpected > pageBytePolicy.maxEncodedBytes) {
            // OS progress is coarse, not a hard temporary-disk bound. Keep the policy reason even
            // when the terminal NSError is only NSURLErrorCancelled.
            outcome.failure =
                PageByteLimitExceeded(pageBytePolicy.maxEncodedBytes, maxOf(totalBytesWritten, totalExpected)).message
            withEvent(operation) { event ->
                reportFailureOnce(task, d, requireNotNull(outcome.failure), event)
                task.cancel()
            }
        } else if (totalBytesWritten == bytesWritten) {
            BgDownloadLog.log(
                "task.didWriteData.started",
                "chapterId" to d.chapterId,
                "pageIndex" to d.pageIndex,
                "bytesExpected" to totalExpected,
            )
        }
    }

    fun handleFinishedDownload(
        task: NSURLSessionDownloadTask,
        location: NSURL,
        response: NSHTTPURLResponse?,
        operation: DownloadOperationExclusion.Operation,
    ) {
        val d = IosTransferIdentity.decode(task.taskDescription) ?: return
        val outcome = outcomes.getOrPut(task.taskIdentifier) { PageOutcome() }
        if (outcome.reported) return
        withEvent(operation) { event ->
            val failure = outcome.failure
            if (failure == null) {
                finishUnreportedDownload(task, location, response, d, outcome, event)
            } else {
                reportFailureOnce(task, d, failure, event)
            }
        }
    }

    private fun finishUnreportedDownload(
        task: NSURLSessionDownloadTask,
        location: NSURL,
        response: NSHTTPURLResponse?,
        d: IosTransferIdentity,
        outcome: PageOutcome,
        event: IosTransferEvent,
    ) {
        val status = response?.statusCode?.toInt()
        BgDownloadLog.log(
            "task.didFinishDownloading",
            "chapterId" to d.chapterId,
            "pageIndex" to d.pageIndex,
            "taskId" to task.taskIdentifier,
            "httpStatus" to status,
        )
        if (status == null || status !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) {
            reportHttpFailure(task, d, status, event)
        } else {
            publishAndReport(task, location, response, d, outcome, event)
        }
    }

    private fun reportHttpFailure(
        task: NSURLSessionTask,
        d: IosTransferIdentity,
        status: Int?,
        event: IosTransferEvent,
    ) {
        BgDownloadLog.warn(
            "task.httpError",
            "chapterId" to d.chapterId,
            "pageIndex" to d.pageIndex,
            "httpStatus" to status,
        )
        reportFailureOnce(task, d, if (status == null) "Missing HTTP response" else "HTTP $status", event)
    }

    private fun publishAndReport(
        task: NSURLSessionDownloadTask,
        location: NSURL,
        response: NSHTTPURLResponse?,
        d: IosTransferIdentity,
        outcome: PageOutcome,
        event: IosTransferEvent,
    ) {
        val publication = pages.beginPublication()
        try {
            val page = publication.stage(location, d, response?.expectedContentLength)
            if (!deliverPage(d, page, event)) return
            publication.handOff()
            outcome.reported = true
            BgDownloadLog.log("file.move.success", "chapterId" to d.chapterId, "pageIndex" to d.pageIndex)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportFailureOnce(task, d, pageFailureReason(failure), event)
        } finally {
            publication.discardTemporary()
        }
    }

    private fun deliverPage(d: IosTransferIdentity, page: StagedDownloadPage, event: IosTransferEvent): Boolean {
        val receiver = listener() ?: return false
        event.deliver { operation, acknowledge ->
            receiver.onPageComplete(d.mangaId, d.chapterId, d.pageIndex, d.attemptToken, page, operation, acknowledge)
        }
        return true
    }

    private fun pageFailureReason(failure: Exception): String =
        when (failure) {
            is PageByteLimitExceeded, is PageMediaException -> failure.message ?: "Page validation failed"
            else -> "Downloaded page could not be saved"
        }

    fun handleCompleted(
        task: NSURLSessionTask,
        error: NSError?,
        operation: DownloadOperationExclusion.Operation,
    ) {
        val d = IosTransferIdentity.decode(task.taskDescription) ?: return
        withEvent(operation) { event ->
            try {
                val outcome = outcomes.getOrPut(task.taskIdentifier) { PageOutcome() }
                if (!outcome.reported) completeUnreported(task, d, outcome, error, event)
            } finally {
                outcomes.remove(task.taskIdentifier)
            }
        }
    }

    private fun completeUnreported(
        task: NSURLSessionTask,
        d: IosTransferIdentity,
        outcome: PageOutcome,
        error: NSError?,
        event: IosTransferEvent,
    ) {
        val failure = outcome.failure
        when {
            failure != null -> reportFailureOnce(task, d, failure, event)
            error?.code == NSURLErrorCancelled ->
                // User/engine cancellation is silent; an earlier policy failure takes precedence.
                BgDownloadLog.log("task.didComplete.cancelled", "chapterId" to d.chapterId, "pageIndex" to d.pageIndex)
            else -> reportFailureOnce(task, d, error?.localizedDescription ?: "Download completed without a page", event)
        }
    }

    private fun reportFailureOnce(
        task: NSURLSessionTask,
        d: IosTransferIdentity,
        reason: String,
        event: IosTransferEvent,
    ) {
        val outcome = outcomes.getOrPut(task.taskIdentifier) { PageOutcome() }
        outcome.failure = reason
        if (outcome.reported) return
        outcome.reported = true
        val receiver = listener() ?: return
        event.deliver { operation, acknowledge ->
            receiver.onPageFailed(d.mangaId, d.chapterId, d.pageIndex, d.attemptToken, reason, operation, acknowledge)
        }
    }

    private inline fun withEvent(operation: DownloadOperationExclusion.Operation, action: (IosTransferEvent) -> Unit) {
        val event = IosTransferEvent(operation, admitEvent())
        try {
            action(event)
        } finally {
            event.finishDelivery()
        }
    }

    private data class PageOutcome(
        var failure: String? = null,
        var reported: Boolean = false,
    )

    private companion object {
        const val HTTP_SUCCESS_MIN = 200
        const val HTTP_SUCCESS_MAX = 299
    }
}
