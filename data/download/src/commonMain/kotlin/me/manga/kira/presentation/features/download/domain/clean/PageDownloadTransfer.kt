package me.manga.kira.presentation.features.download.domain.clean

import io.ktor.client.HttpClient
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector

/**
 * Inputs for one bounded HTTP page-transfer boundary: an uncached client and its publication checks.
 * Engines retain their existing client guard and cancellation/temporary-file ownership. The byte
 * ceiling defaults to [PageBytePolicy] exactly as it did on each engine constructor.
 */
class PageDownloadTransfer(
    val httpClient: HttpClient,
    val mediaInspector: PageMediaInspector,
    val pageBytePolicy: PageBytePolicy = PageBytePolicy(),
)
