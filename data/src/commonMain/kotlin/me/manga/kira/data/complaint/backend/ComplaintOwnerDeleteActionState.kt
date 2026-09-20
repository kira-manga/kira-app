package me.manga.kira.data.complaint.backend

/** Single-target resource outcome only, never installation deletion or a refreshed action tag. */
internal sealed interface ComplaintOwnerDeleteActionState {
    data object Applied : ComplaintOwnerDeleteActionState

    class Rejected(
        val code: ComplaintOwnerDeleteRejection,
    ) : ComplaintOwnerDeleteActionState
}

internal fun ComplaintOwnerDeleteActionState.sameAs(other: ComplaintOwnerDeleteActionState): Boolean =
    when {
        this === ComplaintOwnerDeleteActionState.Applied && other === ComplaintOwnerDeleteActionState.Applied -> true
        this is ComplaintOwnerDeleteActionState.Rejected && other is ComplaintOwnerDeleteActionState.Rejected ->
            code == other.code
        else -> false
    }
