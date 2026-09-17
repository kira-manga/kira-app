package me.manga.kira.domain.model.complaint

import kotlin.time.Instant

/** Source provenance is explicit: backend rows can never become legacy mutation arguments. */
sealed interface ComplaintHistory {
    class Legacy(
        items: List<ComplaintSummary>,
    ) : ComplaintHistory {
        val items: List<ComplaintSummary> = items.toList()

        override fun toString(): String = "ComplaintHistory.Legacy(redacted)"
    }

    class Backend(
        notices: List<ComplaintNotice>,
        items: List<ComplaintOwnerRow>,
    ) : ComplaintHistory {
        val notices: List<ComplaintNotice> = notices.toList()
        val items: List<ComplaintOwnerRow> = items.toList()

        override fun toString(): String = "ComplaintHistory.Backend(redacted)"
    }
}

/** Notices carry a localization key, never server-controlled subject/body or action authority. */
class ComplaintNotice(
    val id: String,
    val noticeKey: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    override fun toString(): String = "ComplaintNotice(redacted)"
}

/** Literal [ComplaintStatus.UNKNOWN] is Known; a future wire value is not that legacy status. */
sealed interface ComplaintHistoryStatus {
    class Known(
        val value: ComplaintStatus,
    ) : ComplaintHistoryStatus {
        override fun toString(): String = "ComplaintHistoryStatus.Known"
    }

    data object Unrecognized : ComplaintHistoryStatus {
        override fun toString(): String = "ComplaintHistoryStatus.UNRECOGNIZED"
    }
}

/** Future types retain no raw token and convey no compatible mutation contract. */
sealed interface ComplaintHistoryType {
    class Known(
        val value: ComplaintType,
    ) : ComplaintHistoryType {
        override fun toString(): String = "ComplaintHistoryType.Known"
    }

    data object Unrecognized : ComplaintHistoryType {
        override fun toString(): String = "ComplaintHistoryType.UNRECOGNIZED"
    }
}

/** Server-owned display/concurrency fields; intentionally no installation/user/moderator identifier. */
// A validated immutable row is constructed atomically, not through a mutable builder/options bag.
@Suppress("LongParameterList")
class ComplaintOwnerFields(
    val id: String,
    val body: String,
    val status: ComplaintHistoryStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    val actionTag: String,
    val appVersion: String?,
    val platform: ComplaintHistoryPlatform,
    val osVersion: String?,
    val manufacturer: String?,
    val deviceModel: String?,
    val closureReason: String?,
) {
    override fun toString(): String = "ComplaintOwnerFields(redacted)"
}

enum class ComplaintHistoryPlatform { ANDROID, IOS }

/** Closed read projection. Contract recognition is not mutation authority: this slice is read-only. */
sealed interface ComplaintOwnerRow {
    val id: String
    val createdAt: Instant
    val updatedAt: Instant
    val isContractRecognized: Boolean

    sealed interface Content : ComplaintOwnerRow {
        val fields: ComplaintOwnerFields
        val type: ComplaintHistoryType
        override val id: String get() = fields.id
        override val createdAt: Instant get() = fields.createdAt
        override val updatedAt: Instant get() = fields.updatedAt
        override val isContractRecognized: Boolean
            get() = fields.status is ComplaintHistoryStatus.Known && type is ComplaintHistoryType.Known
    }

    class Report(
        override val fields: ComplaintOwnerFields,
        override val type: ComplaintHistoryType,
        val subject: String,
    ) : Content {
        override fun toString(): String = "ComplaintOwnerRow.Report(redacted)"
    }

    class Reply(
        override val fields: ComplaintOwnerFields,
        override val type: ComplaintHistoryType,
        val subject: String,
        val replyToId: String,
    ) : Content {
        override fun toString(): String = "ComplaintOwnerRow.Reply(redacted)"
    }

    class NoticeReply(
        override val fields: ComplaintOwnerFields,
        val noticeKey: String,
        val replyToId: String,
    ) : Content {
        override val type: ComplaintHistoryType = ComplaintHistoryType.Known(ComplaintType.CUSTOM)

        override fun toString(): String = "ComplaintOwnerRow.NoticeReply(redacted)"
    }
}

/**
 * UNRECOGNIZED kind: the decoder discards every other field. In particular this has no prose,
 * status/type/actionTag, parent/navigation target, or mutation capability. Never display kindToken.
 */
class UnknownComplaintItem(
    override val id: String,
    val kindToken: String,
    override val createdAt: Instant,
    override val updatedAt: Instant,
) : ComplaintOwnerRow {
    override val isContractRecognized: Boolean = false

    override fun toString(): String = "UnknownComplaintItem(UNRECOGNIZED)"
}
