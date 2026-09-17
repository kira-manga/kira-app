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
        var stopped: ReportFailure? = null
        for (slot in inventory.value.snapshot.entries()) {
            val admitted = coordinator.beginReportAction(work, ReportStart.Retained(slot))
            stopped =
                when (admitted) {
                    is Outcome.Success -> {
                        val result = execution.status(admitted.value, retryLive = false)
                        observations += ReportRecoveryItem(slot, result.attempt)
                        // Release checks the exact successor, never a newly captured inventory.
                        val released = coordinator.releaseReportAction(result.binding)
                        if (released is Outcome.Success) null else reportLocalFailure(released)
                    }
                    else -> reportLocalFailure(admitted)
                }
            if (stopped != null) break
        }
        return ReportRecovery(observations, stopped)
    }
}
