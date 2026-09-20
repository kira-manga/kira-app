package me.manga.kira.sources.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.manga.kira.sources.contracts.CommittedSourceSelection

/** Process evidence only. Every consumer must still compare the caller-writer durable binding. */
class SourceSelectionReadiness {
    private val current = MutableStateFlow(ProcessSourceSelectionTransition(null))
    internal val transitions = current.asStateFlow()
    internal fun invalidate() { current.value = ProcessSourceSelectionTransition(null) }
    internal fun publish(receipt: CommittedSourceSelection, raw: String) {
        current.value = ProcessSourceSelectionTransition(ProcessVerifiedSourceSelection(receipt, raw))
    }
}

/**
 * Fresh reference identity is deliberate: a deferred collector must recheck after A -> unavailable -> A.
 * One StateFlow assignment publishes binding and signal together; intermediate transitions may coalesce.
 * This is neither a durable token nor an event journal. Do not make it equality-based on the binding.
 */
internal class ProcessSourceSelectionTransition(val binding: ProcessVerifiedSourceSelection?)

internal data class ProcessVerifiedSourceSelection(val receipt: CommittedSourceSelection, val raw: String)
