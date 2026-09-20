package me.manga.kira.domain.model.feedback

/** Closed historical single-delete receipt codes, distinct from edit and creation rejections. */
enum class ComplaintOwnerDeleteReceiptRejection {
    COMPLAINT_NOT_FOUND,
    COMPLAINT_DELETION_PENDING,
    PRECONDITION_FAILED,
}

/** Original resource-deletion outcome only; no current row/version or installation-deletion claim. */
sealed interface ComplaintOwnerDeleteApplication {
    data object Applied : ComplaintOwnerDeleteApplication

    data class Rejected(
        val code: ComplaintOwnerDeleteReceiptRejection,
    ) : ComplaintOwnerDeleteApplication
}
