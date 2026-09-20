package me.manga.kira.domain.model.complaint

/** Finite notice identities, without row IDs, localized copy or backend mutation authority. */
enum class BackendNoticeKey(val key: String) {
    CONTENT_POLICY("complaints.notice.content-policy"),
    SOURCE_REQUIREMENTS("complaints.notice.source-requirements"),
    ;

    companion object {
        /** Exact lookup only; unknown keys, aliases and normalized near-matches are not admitted. */
        fun fromKey(key: String): BackendNoticeKey? = entries.firstOrNull { it.key == key }
    }
}
