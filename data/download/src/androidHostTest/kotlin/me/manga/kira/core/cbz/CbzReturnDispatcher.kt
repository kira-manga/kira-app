package me.manga.kira.core.cbz

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/** One caller startup, then retain its real withContext return; no production post-commit hook. */
internal class CbzReturnDispatcher :
    CoroutineDispatcher(),
    AutoCloseable {
    private val lock = Any()
    private var started = false
    private var released = false
    private val pending = mutableListOf<Pair<CoroutineContext, Runnable>>()
    val returned = CompletableDeferred<Unit>()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        synchronized(lock) {
            if (!started) {
                started = true
            } else if (!released) {
                pending += context to block
                returned.complete(Unit)
                return
            }
        }
        Dispatchers.Default.dispatch(context, block)
    }

    override fun close() {
        val resumes =
            synchronized(lock) {
                released = true
                pending.toList().also { pending.clear() }
            }
        resumes.forEach { (context, block) -> Dispatchers.Default.dispatch(context, block) }
    }
}
