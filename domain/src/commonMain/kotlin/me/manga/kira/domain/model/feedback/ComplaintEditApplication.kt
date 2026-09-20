package me.manga.kira.domain.model.feedback

/** Closed terminal owner-edit receipt codes, distinct from report/reply creation rejections. */
enum class ComplaintEditReceiptRejection {
    COMPLAINT_NOT_FOUND,
    COMPLAINT_INVALID_TRANSITION,
    COMPLAINT_NO_CHANGE,
    COMPLAINT_DELETION_PENDING,
    PRECONDITION_FAILED,
}

/** Original edit receipt only: never current content, ownership, or a fresh action tag. */
sealed interface ComplaintEditApplication {
    class Applied(
        val id: String,
        val version: Long,
    ) : ComplaintEditApplication {
        override fun toString(): String = "ComplaintEditApplication.Applied(redacted)"
    }

    data class Rejected(
        val code: ComplaintEditReceiptRejection,
    ) : ComplaintEditApplication
}
