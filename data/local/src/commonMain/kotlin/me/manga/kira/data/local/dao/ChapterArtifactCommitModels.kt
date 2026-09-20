package me.manga.kira.data.local.dao

/** Partial updates leave metadata, reading progress and notification identity untouched. */
data class ArtifactReadableUpdate(val id: Long, val isDownloaded: Boolean, val localImagePaths: List<String>)

/** Durable readback after the writer has unwound; a failed query is also UNKNOWN at the caller. */
enum class ChapterRestoreOutcome { COMMITTED, NOT_COMMITTED, UNKNOWN }

enum class ChapterDownloadOutcome { COMPLETE, INCOMPLETE, UNKNOWN }
