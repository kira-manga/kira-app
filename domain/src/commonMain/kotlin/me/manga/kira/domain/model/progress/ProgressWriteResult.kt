package me.manga.kira.domain.model.progress

/** A stale Reader handle is rejected without opening a replacement session or recreating anchors. */
enum class ProgressWriteResult {
    WRITTEN,
    STALE,
}
