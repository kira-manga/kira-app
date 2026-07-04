package me.manga.kira.sources_repositry.en.comick_io.models.chapters

import kotlinx.serialization.Serializable

@Serializable
data class infochapters(
    val chapters: List<Chapter?>?,
    val checkVol2Chap1: Boolean?,
    val limit: Int?,
    val total: Int?
)