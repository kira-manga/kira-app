package me.manga.kira.data.repository.progress

import me.manga.kira.data.local.entity.ReaderLegacyCleanupEntity
import me.manga.kira.data.local.entity.ReaderLegacyCleanupState
import me.manga.kira.domain.model.progress.LegacyProgressCleanup
import me.manga.kira.domain.model.progress.LegacyProgressReconciliation

/** Each receipt gets at most one cleanup attempt per pass, including ACKED and CONFLICT tombstones. */
internal class LegacyProgressReconciler(
    private val owners: ProgressOwnerTransactions,
    private val settings: LegacyProgressSettings,
) {
    suspend fun finish(receipt: ReaderLegacyCleanupEntity): LegacyProgressCleanup {
        val observed = settings.cleanup(CapturedLegacyProgress(receipt.legacyKey, receipt.capturedPayload))
        val state = when (observed) {
            LegacyProgressCleanup.ACKNOWLEDGED -> ReaderLegacyCleanupState.ACKED
            LegacyProgressCleanup.CONFLICT -> ReaderLegacyCleanupState.CONFLICT
            else -> return observed
        }
        owners.write {
            // A stale receipt-state CAS must not overwrite another attempt's newer observation.
            // Never retry a copy, delete a receipt, or downgrade it to PENDING on a failed CAS.
            storage.cleanup.markCleanup(receipt.legacyKey, receipt.capturedPayload, receipt.state, state)
        }
        return observed
    }

    suspend fun reconcile(): LegacyProgressReconciliation {
        val receipts = owners.write { storage.cleanup.allReceipts() }
        var acknowledged = 0
        var conflicts = 0
        var deferred = 0
        for (receipt in receipts) {
            when (finish(receipt)) {
                LegacyProgressCleanup.ACKNOWLEDGED -> acknowledged++
                LegacyProgressCleanup.CONFLICT -> conflicts++
                else -> deferred++
            }
        }
        return LegacyProgressReconciliation(acknowledged, conflicts, deferred)
    }
}
