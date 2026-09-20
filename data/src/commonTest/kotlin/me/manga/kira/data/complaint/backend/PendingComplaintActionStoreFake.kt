package me.manga.kira.data.complaint.backend

import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationValueIssue
import me.manga.kira.platform.storage.InstallationValueResult
import me.manga.kira.platform.storage.PendingClearResult
import me.manga.kira.platform.storage.PendingComplaintActionStore
import me.manga.kira.platform.storage.PendingComplaintSlot
import me.manga.kira.platform.storage.PendingComplaintSnapshot
import me.manga.kira.platform.storage.PendingCreateResult
import me.manga.kira.platform.storage.PendingDeleteResult
import me.manga.kira.platform.storage.PendingReadResult
import me.manga.kira.platform.storage.PendingReplaceResult
import me.manga.kira.data.complaint.backend.InstallationStoreStep as Step

/** Retained physical slots; clear can fail after one removal, without simulating semantic records. */
internal class PendingComplaintActionStoreFake(
    private val faults: InstallationStoreFaults,
) : PendingComplaintActionStore {
    val slots = mutableListOf<PendingComplaintSlot>()
    var readFailure: InstallationStorageFailure? = null

    // Deliberately dishonest adapter success for independent inventory-readback discriminators.
    var clearReply: PendingClearResult? = null

    override suspend fun read(): PendingReadResult {
        faults.visit(Step.PENDING_READ)?.let { return it }
        return readFailure ?: when (val checked = PendingComplaintSnapshot.checked(slots)) {
            is InstallationValueResult.Valid -> PendingReadResult.Verified(checked.value)
            is InstallationValueResult.Invalid -> inventoryFailure(checked.issue)
        }
    }

    override suspend fun createIfMissing(slot: PendingComplaintSlot): PendingCreateResult {
        faults.visit(Step.PENDING_CREATE_BEFORE)?.let { return it }
        return readFailure ?: when {
            slots.any { it.id == slot.id } -> PendingCreateResult.AlreadyPresent
            PendingComplaintSnapshot.checked(slots + slot) is InstallationValueResult.Invalid -> oversized()
            else -> {
                slots += slot
                faults.visit(Step.PENDING_CREATED) ?: PendingCreateResult.Stored
            }
        }
    }

    override suspend fun replace(
        expected: PendingComplaintSlot,
        replacement: PendingComplaintSlot,
    ): PendingReplaceResult {
        val failure = faults.visit(Step.PENDING_REPLACE_BEFORE) ?: readFailure
        if (failure != null) return failure
        val index = slots.indexOfFirst { it.id == expected.id }
        return when {
            index < 0 -> PendingReplaceResult.Missing
            !slots[index].sameAs(expected) || expected.id != replacement.id -> PendingReplaceResult.Stale
            else -> {
                slots[index] = replacement
                faults.visit(Step.PENDING_REPLACED) ?: PendingReplaceResult.Stored
            }
        }
    }

    override suspend fun delete(expected: PendingComplaintSlot): PendingDeleteResult {
        val failure = faults.visit(Step.PENDING_DELETE_BEFORE) ?: readFailure
        if (failure != null) return failure
        val index = slots.indexOfFirst { it.id == expected.id }
        return when {
            index < 0 -> PendingDeleteResult.Missing
            !slots[index].sameAs(expected) -> PendingDeleteResult.Stale
            else -> {
                slots.removeAt(index)
                faults.visit(Step.PENDING_DELETED) ?: PendingDeleteResult.Deleted
            }
        }
    }

    override suspend fun clearForConfirmedRecovery(): PendingClearResult =
        faults.visit(Step.PENDING_CLEAR_BEFORE) ?: clearReply ?: run {
            val failure = readFailure
            when {
                failure is InstallationStorageFailure.TemporarilyUnavailable -> failure
                failure is InstallationStorageFailure.PermanentFailure &&
                    failure.reason == InstallationPermanentFailure.UNSUPPORTED -> failure
                else -> clearRetainedSlots()
            }
        }

    private suspend fun clearRetainedSlots(): PendingClearResult {
        while (slots.isNotEmpty()) {
            slots.removeAt(0)
            faults.visit(Step.PENDING_SLOT_REMOVED)?.let { return it }
        }
        readFailure = null
        return faults.visit(Step.PENDING_CLEARED) ?: PendingClearResult.Cleared
    }

    private fun oversized(): InstallationStorageFailure =
        InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.TOO_LARGE)

    private fun inventoryFailure(issue: InstallationValueIssue): InstallationStorageFailure =
        when (issue) {
            InstallationValueIssue.SLOT_COUNT, InstallationValueIssue.TOTAL_SLOT_SIZE -> oversized()
            else -> InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.CORRUPT)
        }
}
