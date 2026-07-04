package me.manga.kira.sources_repositry.en.comick_io.models.info

data class Relates(
    val hid: String,
    val md_covers: List<MdCover>,
    val slug: String,
    val title: String
)