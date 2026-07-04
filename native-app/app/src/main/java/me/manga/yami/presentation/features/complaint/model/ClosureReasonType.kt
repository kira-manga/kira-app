package me.manga.yamiapk.presentation.features.complaint.model

enum class ClosureReasonType(val key: String) {
    DONE("done"),
    DONE_WAIT_UPDATE("done_and_wait_update"),
    PINNED("pinned"),
    OTHER("other");

    companion object {
        fun fromString(reason: String?): ClosureReasonType {
            if (reason.isNullOrBlank()) return OTHER

            return when {
                reason.contains("done", ignoreCase = true) && reason.contains("wait", ignoreCase = true) && reason.contains("update", ignoreCase = true) -> DONE_WAIT_UPDATE
                reason.contains("done", ignoreCase = true) -> DONE
                reason.contains("pinned", ignoreCase = true) -> PINNED
                else -> OTHER
            }
        }
    }
}