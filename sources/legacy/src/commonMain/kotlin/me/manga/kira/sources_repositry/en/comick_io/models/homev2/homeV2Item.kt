package me.manga.kira.sources_repositry.en.comick_io.models.homev2

import kotlinx.serialization.Serializable

@Serializable

data class homeV2Item(
    val chap: String? = "",
    val count: Int? = 0,
    val created_at: String? = "",
    val down_count: Int? = 0,
    val group_name: List<String?>? = listOf(),
    val hid: String? = "",
    val id: Int? = 0,
    val lang: String? = "",
    val last_at: String? = "",
    val md_comics: MdComics? = MdComics(),
    val publish_at: String? = "",
    val status: String? = "",
    val up_count: Int? = 0,
    val updated_at: String? = "",
    val vol: String? = ""
)