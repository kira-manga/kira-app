package me.manga.kira.presentation.complaint

import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.presentation.mvi.MviState

/** Memory-only read observation. Neither a selected ID nor an Owned result grants mutation authority. */
data class ComplaintDetailState(
    val selectedId: String? = null,
    val detail: ComplaintDetail? = null,
    val isLoading: Boolean = false,
    val error: AppError? = null,
) : MviState {
    val hasContent: Boolean get() = detail is ComplaintDetail.Owned || detail is ComplaintDetail.Notice
    val isStale: Boolean get() = hasContent && error != null
    val isUnavailable: Boolean get() = detail == ComplaintDetail.Unavailable

    override fun toString(): String = "ComplaintDetailState(redacted)"
}
