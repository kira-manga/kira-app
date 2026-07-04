package me.manga.kira.sources.config

import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/**
 * Merges several config documents into one effective document. Documents are supplied in ASCENDING
 * precedence (bundled first, then cache, then remote) — a later document overrides an earlier one for
 * the same source api, EXCEPT a source pinned with a higher [SourceConfig.priority] in a lower-
 * precedence document resists being overridden by a lower-priority one. This is what lets a remote
 * update re-point or disable most sources while the bundled binary can still pin a critical source.
 *
 * Pure and deterministic (no clock/randomness), so the resolution is fully unit-testable.
 */
internal object ConfigMerger {

    fun merge(documentsAscendingPrecedence: List<SourceConfigDocument>): SourceConfigDocument {
        if (documentsAscendingPrecedence.isEmpty()) {
            return SourceConfigDocument(schemaVersion = 1, revision = -1)
        }

        // rank = (priority, precedenceIndex); higher wins. Tie on priority → later document wins.
        val chosen = LinkedHashMap<String, Ranked>()
        documentsAscendingPrecedence.forEachIndexed { precedence, document ->
            for (source in document.sources) {
                val candidate = Ranked(source, source.priority, precedence)
                val existing = chosen[source.api]
                if (existing == null || candidate.beats(existing)) {
                    chosen[source.api] = candidate
                }
            }
        }

        return SourceConfigDocument(
            schemaVersion = documentsAscendingPrecedence.last().schemaVersion,
            revision = documentsAscendingPrecedence.maxOf { it.revision },
            sources = chosen.values.map { it.source },
        )
    }

    private data class Ranked(val source: SourceConfig, val priority: Int, val precedence: Int) {
        fun beats(other: Ranked): Boolean =
            priority > other.priority || (priority == other.priority && precedence >= other.precedence)
    }
}
