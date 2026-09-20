package me.manga.kira.domain.model.complaint

/** One current backend read, never a mutation receipt, deletion proof or legacy action argument. */
sealed interface ComplaintDetail {
    /** Existing closed projection; an unknown kind remains non-actionable and content-free. */
    class Owned(
        val item: ComplaintOwnerRow,
    ) : ComplaintDetail {
        override fun toString(): String = "ComplaintDetail.Owned(redacted)"
    }

    /** Immutable localization-key projection; no server prose or action tag. */
    class Notice(
        val item: ComplaintNotice,
    ) : ComplaintDetail {
        override fun toString(): String = "ComplaintDetail.Notice(redacted)"
    }

    /** The exact authenticated detail read returned NOT_FOUND; the reason is intentionally indistinguishable. */
    data object Unavailable : ComplaintDetail
}
