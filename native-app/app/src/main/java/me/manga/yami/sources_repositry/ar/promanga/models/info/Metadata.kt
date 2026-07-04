package me.manga.yamiapk.sources_repositry.ar.promanga.models.info

import kotlinx.serialization.Serializable
@Serializable
data class Metadata(
    val closeHours: String? = "",
    val coinsTeamId: Int? = 0,
    val coinsTeamMembers: List<Int?>? = listOf(),
    val driveFileId: String? = "",
    val driveFileName: String? = "",
    val driveSourceLink: String? = "",
    val images: List<String>? = null,
    val lockDurationBase: String? = "",
    val lockDurationHours: String? = "",
    val lockDurationStart: String? = "",
    val maps: List<Map>? = null
)