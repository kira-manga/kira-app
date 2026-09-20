package me.manga.kira.data.complaint.backend

import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.InstallationTemporaryFailure

/** Storage-operation boundaries, not a second lifecycle implementation or an OS durability model. */
internal enum class InstallationStoreStep {
    CREDENTIAL_READ,
    CREATE_BEFORE,
    CREATE_STORED,
    REPLACE_BEFORE,
    REPLACE_STORED,
    MARKER_READ,
    MARKER_CREATE_BEFORE,
    MARKER_STORED,
    CLEANUP_BEFORE,
    KEY_REMOVED,
    PAYLOAD_REMOVED,
    MARKER_REMOVE_BEFORE,
    MARKER_REMOVED,
    PENDING_READ,
    PENDING_CREATE_BEFORE,
    PENDING_CREATED,
    PENDING_REPLACE_BEFORE,
    PENDING_REPLACED,
    PENDING_DELETE_BEFORE,
    PENDING_DELETED,
    PENDING_CLEAR_BEFORE,
    PENDING_SLOT_REMOVED,
    PENDING_CLEARED,
}

/** One-shot typed failures and controllable suspension at the actual fake store calls. */
internal class InstallationStoreFaults {
    val trace = mutableListOf<InstallationStoreStep>()
    var onStep: suspend (InstallationStoreStep) -> Unit = {}
    private val failures = mutableMapOf<InstallationStoreStep, ScheduledFailure>()

    val mutations: List<InstallationStoreStep>
        get() = trace.filter { it !in READ_STEPS }

    fun failAt(
        step: InstallationStoreStep,
        failure: InstallationStorageFailure = ioFailure,
        occurrence: Int = 1,
    ) {
        require(occurrence > 0)
        failures[step] = ScheduledFailure(occurrence, failure)
    }

    suspend fun visit(step: InstallationStoreStep): InstallationStorageFailure? {
        trace += step
        onStep(step)
        val scheduled = failures[step] ?: return null
        scheduled.remaining -= 1
        return if (scheduled.remaining != 0) {
            null
        } else {
            failures.remove(step)
            scheduled.failure
        }
    }

    fun clearFaults() {
        failures.clear()
        onStep = {}
    }

    private class ScheduledFailure(
        var remaining: Int,
        val failure: InstallationStorageFailure,
    )

    companion object {
        val ioFailure = InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.IO_FAILURE)
        private val READ_STEPS =
            setOf(
                InstallationStoreStep.CREDENTIAL_READ,
                InstallationStoreStep.MARKER_READ,
                InstallationStoreStep.PENDING_READ,
            )
    }
}
