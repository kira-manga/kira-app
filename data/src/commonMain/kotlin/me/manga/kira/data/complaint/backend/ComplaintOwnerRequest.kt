package me.manga.kira.data.complaint.backend

/** Closed live values for the existing durable write lane. Creation wire types remain creation-only. */
internal sealed interface ComplaintOwnerRequest

internal fun ComplaintOwnerRequest.operationKey(): String =
    when (this) {
        is ComplaintCreationRequest -> identity.key.canonical
        is ComplaintEditRequest -> key.canonical
        is ComplaintOwnerDeleteRequest -> key.canonical
    }
