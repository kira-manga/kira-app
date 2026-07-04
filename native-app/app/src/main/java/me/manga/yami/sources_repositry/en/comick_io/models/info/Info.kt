package me.manga.yamiapk.sources_repositry.en.comick_io.models.info

import kotlinx.serialization.Serializable

@Serializable
data class Info(
    val artists: List<Artist?>? = null,
    val authors: List<Author?>? = null,
    val checkVol2Chap1: Boolean? = null,
    val comic: Comic? = null,
    val demographic: String? = null,
    val englishLink: String? = null,

    val firstChap: FirstChap? = null,
    val langList: List<String>? = null,
    val matureContent: Boolean? = null,
    val recommendable: Boolean? = null
)