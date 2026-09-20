package me.manga.kira.domain.model.identity

/** Raw chapter address scoped to its work; a chapter URL alone never identifies its owner. */
data class ChapterLocator(
    val work: WorkLocator,
    val chapterUrl: String,
)
