package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome

/** One bounded, metadata-only pass under the caller's existing work lane and exact inventory anchor. */
internal class ComplaintOperationReconciler(
    private val coordinator: InstallationCredentialCoordinator,
    private val execution: ComplaintReportExecution,
) {
    suspend fun reconcile(work: ReportWork): ReportRecovery {
        val inventory = coordinator.reportInventory(work)
        if (inventory !is Outcome.Success) return ReportRecovery(emptyList(), reportLocalFailure(inventory))
        val observations = mutableListOf<ReportRecoveryItem>()
        for (slot in inventory.value.snapshot.entries()) {
            val admitted = coordinator.beginReportAction(work, ReportStart.Retained(slot))
            if (admitted !is Outcome.Success) return ReportRecovery(observations, reportLocalFailure(admitted))
            val result = execution.status(admitted.value, retryLive = false)
            observations += ReportRecoveryItem(slot, result.attempt)
            // Never reread arbitrary new state to manufacture a successor after uncertain application/storage.
            val released = coordinator.releaseReportAction(result.binding)
            if (released !is Outcome.Success) return ReportRecovery(observations, reportLocalFailure(released))
        }
        return ReportRecovery(observations)
    }
}
