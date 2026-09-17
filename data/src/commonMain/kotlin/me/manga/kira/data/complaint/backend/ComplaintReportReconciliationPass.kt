package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import me.manga.kira.platform.storage.PendingComplaintSlot

/** Bounded bookkeeping under the credential mutex, scoped to one registered work and consumed by a new action. */
internal class ComplaintReportReconciliationPass {
    private var anchor: ReconciliationPermit? = null
    private val slots = mutableMapOf<String, PendingComplaintSlot>()

    fun clear() {
        anchor = null
        slots.clear()
    }

    fun consume(permit: ReconciliationPermit) {
        if (!permit.snapshot.isEmpty &&
            (
                !sameScope(permit) ||
                    permit.snapshot.entries().any { slots[it.id]?.sameAs(it) != true }
            )
        ) {
            refuse(Block.RECONCILIATION_REQUIRED)
        }
        clear()
    }

    /** A later failed request must not inherit a successful older observation of the same slot. */
    fun forget(binding: ReportActionBinding) {
        binding.slot?.let { slots.remove(it.id) }
    }

    fun record(binding: ReportActionBinding) {
        if (!sameScope(binding.permit)) {
            clear()
            anchor = binding.permit
        }
        val inventory = binding.permit.snapshot.entries()
        slots.entries.removeAll { (_, observed) -> inventory.none { it.sameAs(observed) } }
        val slot = binding.slot ?: refuse(Block.STALE_BINDING)
        slots[slot.id] = slot
    }

    private fun sameScope(permit: ReconciliationPermit): Boolean {
        val previous = anchor ?: return false
        return previous.issuer === permit.issuer && previous.record.sameAs(permit.record)
    }
}
