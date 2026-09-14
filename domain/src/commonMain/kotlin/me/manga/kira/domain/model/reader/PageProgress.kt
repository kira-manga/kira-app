package me.manga.kira.domain.model.reader

import kotlinx.coroutines.flow.Flow

/**
 * Opaque, identity-equal ownership of one Reader page URL. A handle is never reused, even when
 * a Reader returns to the same URL. Only handles acquired from the progress repository are live.
 * Carries no mutable service into presentation state or an image request.
 */
class PageProgressHandle(
    val url: String,
)

/**
 * A newly acquired page slot and its distinct progress stream. The acquirer must release
 * [handle] when the page leaves its feed, including pages that never started an image request.
 */
data class PageProgressObservation(
    val handle: PageProgressHandle,
    val progress: Flow<PageDownloadProgress>,
)

/**
 * Immutable authority for one image-request execution, not for a URL or a remembered request.
 * Reports after completion, failure, cancellation or owner release are ignored. Idle cancels
 * only this attempt; it must not reset another live attempt sharing the owned page slot.
 */
fun interface PageProgressAttempt {
    fun report(status: PageDownloadProgress)
}
