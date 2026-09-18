package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import me.manga.kira.platform.storage.PendingComplaintSlot

/** Live normalized prose is never reconstructed from a retained slot or written to platform storage. */
internal sealed interface ReportStart {
    class New(
        val report: ComplaintOwnerRequest,
    ) : ReportStart

    class Retained(
        val slot: PendingComplaintSlot,
        val liveReport: ComplaintOwnerRequest? = null,
    ) : ReportStart
}

internal enum class ReportActionStage { NEW, PREPARED, MAY_HAVE_DISPATCHED, COMPLETED }

/** Only the coordinator's current instance is recognized. Copying its fields cannot advance authority. */
internal class ReportActionBinding(
    val permit: ReconciliationPermit,
    val work: ReportWork,
    val liveReport: ComplaintOwnerRequest?,
    val observation: ReportActionObservation,
) {
    val slot: PendingComplaintSlot? get() = observation.slot
    val pendingRecord: PendingComplaintRecord? get() = observation.pendingRecord
    val stage: ReportActionStage get() = observation.stage

    override fun toString(): String = "ReportActionBinding(redacted)"
}

internal class ReportActionObservation(
    val slot: PendingComplaintSlot?,
    val pendingRecord: PendingComplaintRecord?,
    val stage: ReportActionStage,
) {
    override fun toString(): String = "ReportActionObservation(redacted)"
}

/** A token entry is separate from the action's changing, exact durable observation. */
internal class ReportSession(
    val entry: InstallationSessionEntry,
) {
    val response: ComplaintSessionResponse get() = entry.response

    override fun toString(): String = "ReportSession(redacted)"
}

internal sealed interface ReportSessionResult {
    class Ready(
        val session: ReportSession,
    ) : ReportSessionResult

    class Failed(
        val failure: ComplaintSessionResult,
    ) : ReportSessionResult
}

/** Request-bound results, produced only after the coordinator's independent post-I/O recheck. */
internal sealed interface ReportExchange {
    val binding: ReportActionBinding
    val session: ReportSession

    class Create(
        override val binding: ReportActionBinding,
        override val session: ReportSession,
        val request: ComplaintCreateHttpRequest,
        val result: ComplaintCreateHttpResult,
    ) : ReportExchange {
        override fun toString(): String = "ReportCreateExchange(redacted)"
    }

    class Status(
        override val binding: ReportActionBinding,
        override val session: ReportSession,
        val request: ComplaintCreateStatusRequest,
        val result: ComplaintCreateStatusHttpResult,
    ) : ReportExchange {
        override fun toString(): String = "ReportStatusExchange(redacted)"
    }

    class Edit(
        override val binding: ReportActionBinding,
        override val session: ReportSession,
        val request: ComplaintEditHttpRequest,
        val result: ComplaintEditHttpResult,
    ) : ReportExchange {
        override fun toString(): String = "ReportEditExchange(redacted)"
    }

    class EditStatus(
        override val binding: ReportActionBinding,
        override val session: ReportSession,
        val request: ComplaintEditStatusRequest,
        val result: ComplaintEditStatusHttpResult,
    ) : ReportExchange {
        override fun toString(): String = "ReportEditStatusExchange(redacted)"
    }
}

/** Bounded local application facts, not reconstructed server content or slot-removal authority. */
internal sealed interface ReportActionState {
    class Applied(
        val id: String,
        val version: Long,
    ) : ReportActionState {
        override fun toString(): String = "ReportActionState.Applied(redacted)"
    }

    class Rejected(
        val code: ComplaintCreationRejection,
    ) : ReportActionState

    class Edit(
        val application: ComplaintEditActionState,
    ) : ReportActionState {
        override fun toString(): String = "ReportActionState.Edit(redacted)"
    }
}

internal class ReportCompletion(
    val binding: ReportActionBinding,
    val application: ReportActionState,
) {
    override fun toString(): String = "ReportCompletion(redacted)"
}

internal fun ReportActionState.sameAs(other: ReportActionState): Boolean =
    when {
        this is ReportActionState.Applied && other is ReportActionState.Applied ->
            id == other.id && version == other.version
        this is ReportActionState.Rejected && other is ReportActionState.Rejected -> code == other.code
        this is ReportActionState.Edit && other is ReportActionState.Edit -> application.sameAs(other.application)
        else -> false
    }
