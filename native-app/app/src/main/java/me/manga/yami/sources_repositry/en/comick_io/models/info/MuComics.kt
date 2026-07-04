package me.manga.yamiapk.sources_repositry.en.comick_io.models.info

import kotlinx.serialization.Serializable

@Serializable
data class MuComics(
    val licensed_in_english: Boolean?,
    val mu_comic_categories: List<MuComicCategory?>,
    val mu_comic_publishers: List<MuComicPublisher?>
)