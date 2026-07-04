package me.manga.kira.sources_repositry.en.comick_io.models.info

data class Recommendation(
    val down: Int,
    val relates: Relates,
    val total: Int,
    val up: Int
)