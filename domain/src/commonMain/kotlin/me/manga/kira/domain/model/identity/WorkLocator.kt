package me.manga.kira.domain.model.identity

/**
 * Lossless source-scoped work address. Titles and language are metadata, never identity.
 *
 * Keep raw values: accepted alias policy, not this value type, decides whether different URLs
 * identify the same work. Local exact identity does not require an active source client.
 */
data class WorkLocator(
    val api: String,
    val url: String,
)
