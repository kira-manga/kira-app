package me.manga.kira.presentation.reader

import me.manga.kira.presentation.cloudflare.CloudflareRecovery
import me.manga.kira.presentation.cloudflare.CloudflareRecoveryBudget

internal data class ReaderChallengeOperation(
    val chapterUrl: String,
    val append: Boolean,
)

/** Anchor and appended-chapter failure streaks have independent, bounded current identities. */
internal class ReaderChallengeRecovery {
    private val solver = CloudflareRecovery<ReaderChallengeOperation>()
    private val anchorBudget = CloudflareRecoveryBudget<String>()
    private val appendBudget = CloudflareRecoveryBudget<String>()

    fun clearOwner() {
        solver.clear()
        anchorBudget.clear()
        appendBudget.clear()
    }

    fun requestId(chapterUrl: String): String? =
        solver.requestId.takeIf { solver.operation?.chapterUrl == chapterUrl }

    fun consume(requestId: String): ReaderChallengeOperation? = solver.consume(requestId)

    fun begin(operation: ReaderChallengeOperation) {
        solver.operation?.takeIf { it.append == operation.append }?.let(solver::invalidate)
    }

    fun request(operation: ReaderChallengeOperation): Boolean =
        solver.request(operation) { budget(operation).acquire(operation.chapterUrl) }

    fun recovered(operation: ReaderChallengeOperation) {
        budget(operation).recovered(operation.chapterUrl)
        solver.invalidate(operation)
    }

    private fun budget(operation: ReaderChallengeOperation): CloudflareRecoveryBudget<String> =
        if (operation.append) appendBudget else anchorBudget
}
