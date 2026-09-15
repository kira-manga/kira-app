package me.manga.kira.data.download.artifacts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Process-local file users complement durable fencing; a cancelled Job is not a drained user. */
internal class ChapterArtifactGates {
    private val registry = Mutex()
    private val chapters = mutableMapOf<Long, ChapterGate>()
    private val parents = mutableMapOf<Long, ParentArtifactGate>()

    suspend fun chapter(chapterId: Long): ChapterGate =
        registry.withLock { chapters.getOrPut(chapterId) { ChapterGate() } }

    suspend fun parent(mangaId: Long): ParentArtifactGate =
        registry.withLock { parents.getOrPut(mangaId) { ParentArtifactGate() } }
}

/** Admission refuses a closing parent instead of parking a producer that removal must drain. */
internal class ParentArtifactGate {
    private val state = Mutex()
    private val removal = Mutex()
    private var closing = false
    private var admissions = 0
    private var drained = CompletableDeferred<Unit>().also { it.complete(Unit) }

    suspend fun <T> admit(action: suspend () -> T): T? {
        if (!state.withLock {
                if (closing) false else {
                    if (admissions++ == 0) drained = CompletableDeferred()
                    true
                }
            }
        ) return null
        return try {
            action()
        } finally {
            withContext(NonCancellable) { state.withLock { if (--admissions == 0) drained.complete(Unit) } }
        }
    }

    suspend fun <T> remove(action: suspend () -> T): T = removal.withLock {
        val wait = state.withLock { closing = true; drained }
        try {
            wait.await()
            action()
        } finally {
            withContext(NonCancellable) { state.withLock { closing = false } }
        }
    }
}

internal class ChapterGate {
    val transition = Mutex()
    val files = Mutex()
    private val uses = mutableMapOf<String, FileUsers>()

    // These three methods run under transition, including before completing the deferred.
    fun acquire(token: String) {
        uses.getOrPut(token) { FileUsers() }.count++
    }

    fun release(token: String) {
        val current = checkNotNull(uses[token])
        check(current.count > 0)
        if (--current.count == 0) {
            uses.remove(token)
            current.drained.complete(Unit)
        }
    }

    fun drained(token: String): CompletableDeferred<Unit>? = uses[token]?.drained

    private class FileUsers(var count: Int = 0, val drained: CompletableDeferred<Unit> = CompletableDeferred())
}
