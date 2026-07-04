package me.manga.yamiapk.sources_repositry.ar.dilar.models.images

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class ReleaseInfo (
    val pages: List<String> = emptyList(),

    @SerialName("webp_pages")
    val webpPages: List<String> = emptyList(),

    @SerialName("storage_key")
    val storageKey: String = "",

    @SerialName("file_link")
    val fileLink: String = ""
    )

@Serializable
data class ReaderData(
    val release: ReleaseInfo
    // (you can omit all the other fields, thanks to ignoreUnknownKeys)
)

@Serializable
data class ReaderDataAction(
    val readerData: ReaderData
)

@Serializable
data class Root(
    val readerDataAction: ReaderDataAction
)