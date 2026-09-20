package me.manga.kira.data.complaint.backend

/** Distinct edit application in the one write/recovery owner; no fresh content or action authority. */
internal sealed interface ComplaintEditActionState {
    class Applied(
        val id: String,
        val version: Long,
    ) : ComplaintEditActionState {
        override fun toString(): String = "ComplaintEditActionState.Applied(redacted)"
    }

    class Rejected(
        val code: ComplaintEditRejection,
    ) : ComplaintEditActionState
}

internal fun ComplaintEditActionState.sameAs(other: ComplaintEditActionState): Boolean =
    when {
        this is ComplaintEditActionState.Applied && other is ComplaintEditActionState.Applied ->
            id == other.id && version == other.version
        this is ComplaintEditActionState.Rejected && other is ComplaintEditActionState.Rejected -> code == other.code
        else -> false
    }

internal fun ComplaintEditAcknowledgement.application(): ReportActionState.Edit =
    ReportActionState.Edit(ComplaintEditActionState.Applied(id, version))
