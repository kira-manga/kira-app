package me.manga.yamiapk.sources_repositry.en.comick_io.models.info

import kotlinx.serialization.Serializable

@Serializable
data class MuComicCategory(
    val mu_categories: MuCategories?,
    val negative_vote: Int?,
    val positive_vote: Int?
)