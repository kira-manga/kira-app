package me.manga.kira.data.local

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Room may use its database Job rather than the writer caller's Job. Observe both without
 * replacing either Job or releasing the writer before Room finishes commit/rollback.
 */
suspend fun ensureMangaWriteActive() {
    val context = currentCoroutineContext()
    context.ensureActive()
    context[MangaWriteCancellation]?.ensureActive()
}

/**
 * Capture before Room changes context; nested writers retain their outer cancellation origin.
 * Composition-owned transactions must call [ensureMangaWriteActive] inside the writer before and
 * after their work. This context element neither opens a transaction nor guarantees rollback for
 * a cancellation racing the database commit after the final check.
 */
suspend fun <T> withMangaWriteCancellation(block: suspend () -> T): T {
    val context = currentCoroutineContext()
    ensureMangaWriteActive()
    val cancellation = MangaWriteCancellation(context[Job], context[MangaWriteCancellation])
    return withContext(cancellation) { block() }
}

private class MangaWriteCancellation(
    private val caller: Job?,
    private val outer: MangaWriteCancellation?,
) : AbstractCoroutineContextElement(Key) {
    fun ensureActive() {
        caller?.ensureActive()
        outer?.ensureActive()
    }

    companion object Key : CoroutineContext.Key<MangaWriteCancellation>
}
