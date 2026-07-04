package me.manga.kira.sources_repositry.en.comick_io.models.chapters

import kotlinx.serialization.Serializable

@Serializable
data class Chapter(
    val chap: String?,
    val created_at: String?,
    val down_count: Int?,
    val group_name: List<String?>?,
    val hid: String?,
    val id: Int?,
    val identities: Identities?,
    val is_the_last_chapter: Boolean?,
    val lang: String?,
    val md_chapters_groups: List<MdChaptersGroup?>?,
    val publish_at: String?,
    val title: String?,
    val up_count: Int?,
    val updated_at: String?,
    val vol: String?
)