package me.manga.kira.presentation.details

import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.presentation.cloudflare.CloudflareRecovery
import me.manga.kira.presentation.cloudflare.CloudflareRecoveryBudget

internal enum class DetailsChallengeOperation {
    Metadata,
    Downloads,
}

internal val DOWNLOAD_CHALLENGE_ERROR = AppError.Network.Http(statusCode = 403)

/** One unresolved download batch for this owner, independent of successful metadata refreshes. */
internal class DetailsChallengeRecovery {
    private val solver = CloudflareRecovery<DetailsChallengeOperation>()
    private val metadataBudget = CloudflareRecoveryBudget<Unit>()
    private val downloadBudget = CloudflareRecoveryBudget<Unit>()
    private val unresolvedDownloads = mutableSetOf<String>()
    private var failedDownloads = emptySet<String>()

    val requestId: String? get() = solver.requestId
    val failedDownloadUrls: List<String> get() = failedDownloads.toList()

    fun clearOwner() {
        solver.clear()
        metadataBudget.clear()
        downloadBudget.clear()
        unresolvedDownloads.clear()
        failedDownloads = emptySet()
    }

    fun begin(operation: DetailsChallengeOperation) = solver.invalidate(operation)

    fun consume(requestId: String): DetailsChallengeOperation? = solver.consume(requestId)

    fun request(operation: DetailsChallengeOperation): Boolean =
        solver.request(operation) {
            when (operation) {
                DetailsChallengeOperation.Metadata -> metadataBudget.acquire(Unit)
                DetailsChallengeOperation.Downloads -> downloadBudget.acquire(Unit)
            }
        }

    fun metadataRecovered() {
        metadataBudget.clear()
        solver.invalidate(DetailsChallengeOperation.Metadata)
    }

    /** QUEUED/RUNNING are retries, not recovery; rotating failed chapters share the same budget. */
    fun observeDownloads(
        rows: List<DownloadedChapter>,
        displayed: Set<String>,
    ): Boolean {
        // A retry may temporarily remove its row. Only completion or removal from this owner's
        // displayed operation ends the streak; the retained set is bounded by displayed chapters.
        unresolvedDownloads.retainAll(displayed)
        rows.filter { it.state == DownloadState.SUCCESS }.forEach { unresolvedDownloads.remove(it.url) }
        resetRecoveredDownloads()
        val failed =
            rows.asSequence()
                .filter { it.url in displayed && it.state == DownloadState.FAILED }
                .filter { it.errorMsg == DownloadedChapter.CLOUDFLARE_CHALLENGE_SENTINEL }
                .map { it.url }.toSet()
        val freshBatch = failed.isNotEmpty() && failedDownloads.none { it in failed }
        failedDownloads = failed
        unresolvedDownloads.addAll(failed)
        return freshBatch
    }

    fun downloadRecovered(url: String) {
        failedDownloads = failedDownloads - url
        unresolvedDownloads.remove(url)
        resetRecoveredDownloads()
    }

    private fun resetRecoveredDownloads() {
        if (unresolvedDownloads.isNotEmpty()) return
        downloadBudget.clear()
        solver.invalidate(DetailsChallengeOperation.Downloads)
    }
}
