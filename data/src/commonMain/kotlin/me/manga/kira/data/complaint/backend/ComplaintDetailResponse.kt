package me.manga.kira.data.complaint.backend

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow

/** Not published until the shared reader's final credential/work check succeeds. */
internal class ComplaintDetailRead(
    val detail: ComplaintDetail,
    mismatches: Set<ComplaintHistoryMismatch> = emptySet(),
) {
    private val mismatches = mismatches.toSet()

    fun reportMismatches() = mismatches.forEach { it.report() }

    override fun toString(): String = "ComplaintDetailRead(redacted)"
}

/** The exact same closed row grammar as list, with an independent root limit and HTTP/id agreement. */
internal object ComplaintDetailResponse {
    const val MAX_BYTES = 32 * 1_024

    fun decode(
        text: String,
        request: ComplaintDetailRequest,
        etag: String?,
    ): AppResult<ComplaintDetailRead> =
        try {
            val root = ComplaintHistoryJson(text, maximumBytes = MAX_BYTES).read()
            if (root.historyString("id") != request.id) invalidHistory()
            val result = if (root.historyString("kind") == "NOTICE") notice(root, etag) else owner(root, etag)
            AppResult.Success(result)
        } catch (_: InvalidComplaintHistory) {
            malformedHistory()
        } catch (_: SerializationException) {
            malformedHistory()
        } catch (_: IllegalArgumentException) {
            malformedHistory()
        }

    private fun notice(
        root: JsonObject,
        etag: String?,
    ): ComplaintDetailRead {
        if (etag != null) invalidHistory()
        val item = ComplaintHistoryResponse.notice(root)
        return ComplaintDetailRead(
            ComplaintDetail.Notice(item),
            ComplaintHistoryResponse.mismatches(listOf(item), emptyList()),
        )
    }

    private fun owner(
        root: JsonObject,
        etag: String?,
    ): ComplaintDetailRead {
        val item = ComplaintHistoryResponse.owner(root)
        if (item is ComplaintOwnerRow.Content && etag != item.fields.actionTag) invalidHistory()
        // A future kind discards HTTP/body tags together with prose and parent/navigation fields.
        return ComplaintDetailRead(
            ComplaintDetail.Owned(item),
            ComplaintHistoryResponse.mismatches(emptyList(), listOf(item)),
        )
    }
}
