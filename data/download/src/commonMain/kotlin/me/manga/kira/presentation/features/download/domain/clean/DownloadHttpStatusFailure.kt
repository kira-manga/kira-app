package me.manga.kira.presentation.features.download.domain.clean

/** HTTP status retained across the page-provider/transport exception boundary, when available. */
interface DownloadHttpStatusFailure {
    val httpStatusCode: Int?
}
