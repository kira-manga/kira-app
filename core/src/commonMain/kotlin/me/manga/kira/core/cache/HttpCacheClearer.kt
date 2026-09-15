package me.manga.kira.core.cache

/**
 * Invalidates the live HTTP response cache and its owned persistent records together.
 *
 * The composition root must inject the actual cache owner's implementation. This deliberately
 * exposes no transport or filesystem types to consumers such as Settings. A successful return
 * means the clear completed; I/O failures and cancellation must not be reported as success.
 * Concurrent cache operations are serialized with the clear; later responses may populate it again.
 */
fun interface HttpCacheClearer {
    suspend fun clear()
}
