package me.manga.kira.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.PageProgressAttempt
import me.manga.kira.domain.model.reader.PageProgressHandle
import me.manga.kira.domain.model.reader.PageProgressObservation
import me.manga.kira.domain.repository.PageProgressRepository

/**
 * Reader-owned slots. The registry changes only on acquisition/release; byte ticks update one
 * slot, not a process-wide URL map. Slot revocation and report acceptance share the same CAS,
 * so a producer that raced with removal cannot revive its slot or a later same-URL owner.
 */
class PageProgressRepositoryImpl : PageProgressRepository {
    private val slots = MutableStateFlow<Map<PageProgressHandle, Slot>>(emptyMap())

    internal val activeSlotCount: Int get() = slots.value.size
    internal val activeAttemptCount: Int get() = slots.value.values.sumOf { it.attemptCount }

    override fun observe(url: String): PageProgressObservation {
        val handle = PageProgressHandle(url)
        val slot = Slot()
        slots.update { it + (handle to slot) }
        return PageProgressObservation(handle, slot.progress)
    }

    override fun beginAttempt(handle: PageProgressHandle): PageProgressAttempt? =
        slots.value[handle]?.beginAttempt()

    override fun clear(handle: PageProgressHandle) {
        val slot = slots.value[handle] ?: return
        // Revoke first: a reporter may already hold this Slot when the registry entry disappears.
        slot.revoke()
        slots.update { it - handle }
    }

    private class AttemptKey

    private data class SlotState(
        val active: Boolean = true,
        val attempts: Map<AttemptKey, PageDownloadProgress> = emptyMap(),
        val terminal: PageDownloadProgress = PageDownloadProgress.Idle,
    ) {
        // Kotlin's insertion-ordered maps keep the latest-started live attempt last.
        val progress: PageDownloadProgress get() = attempts.values.lastOrNull() ?: terminal
    }

    private class Slot {
        private val state = MutableStateFlow(SlotState())
        val progress = state.map { it.progress }.distinctUntilChanged()
        val attemptCount: Int get() = state.value.attempts.size

        fun beginAttempt(): PageProgressAttempt? {
            val key = AttemptKey()
            while (true) {
                val previous = state.value
                if (!previous.active) return null
                val next = previous.copy(attempts = previous.attempts + (key to PageDownloadProgress.Started))
                if (state.compareAndSet(previous, next)) break
            }
            return PageProgressAttempt { status -> report(key, status) }
        }

        fun revoke() {
            state.update { SlotState(active = false) }
        }

        private fun report(key: AttemptKey, status: PageDownloadProgress) {
            state.update { previous ->
                if (!previous.active || key !in previous.attempts) return@update previous
                when (status) {
                    PageDownloadProgress.Idle,
                    PageDownloadProgress.Complete,
                    PageDownloadProgress.Failed,
                    -> previous.copy(attempts = previous.attempts - key, terminal = status)
                    else -> previous.copy(attempts = previous.attempts + (key to status))
                }
            }
        }
    }
}
