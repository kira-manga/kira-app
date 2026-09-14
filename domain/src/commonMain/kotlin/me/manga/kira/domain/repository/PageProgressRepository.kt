package me.manga.kira.domain.repository

import me.manga.kira.domain.model.reader.PageProgressAttempt
import me.manga.kira.domain.model.reader.PageProgressHandle
import me.manga.kira.domain.model.reader.PageProgressObservation

/**
 * Ephemeral, thread-safe progress for explicitly owned Reader pages. Storage is bounded by
 * acquired handles and live request attempts, never by historical URLs or arbitrary image loads.
 * Different Readers observing the same URL own independent slots. Reports never create slots.
 */
interface PageProgressRepository {
    /** Acquire a fresh, initially Idle slot. Repeated URLs do not share ownership. */
    fun observe(url: String): PageProgressObservation

    /**
     * Start one execution for a live handle, initially Started; null for a released/unknown handle.
     * The newest live attempt is visible. When it finishes, any older live producer survives;
     * with no live producers the last terminal status is visible. Finished attempts are discarded.
     */
    fun beginAttempt(handle: PageProgressHandle): PageProgressAttempt?

    /** Revoke every attempt for this handle and remove its slot. Synchronous and idempotent. */
    fun clear(handle: PageProgressHandle)
}
