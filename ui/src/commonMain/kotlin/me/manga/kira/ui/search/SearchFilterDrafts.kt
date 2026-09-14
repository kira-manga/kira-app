package me.manga.kira.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterControlType.NUMBER
import me.manga.kira.domain.model.filters.FilterControlType.TEXT
import me.manga.kira.domain.model.filters.SourceFilter

/** Sheet-session input only: consuming a draft must precede the callback and its delayed echo. */
internal class SearchFilterDrafts {
    private var fields by mutableStateOf(emptyMap<String, Field>())

    fun reconcile(
        filters: List<SourceFilter>,
        selections: Map<String, List<String>>,
    ) {
        fields =
            buildMap {
                for (filter in filters.filter(::isTextInput)) {
                    val committed = (selections[filter.id] ?: filter.defaultValues).firstOrNull().orEmpty()
                    val held = fields[filter.id]?.takeIf { it.type == filter.type }
                    put(filter.id, held?.reconcile(committed) ?: Field.initial(filter.type, committed))
                }
            }
    }

    fun value(filterId: String): String = fields[filterId]?.value.orEmpty()

    fun edit(
        filterId: String,
        value: String,
    ) {
        val field = fields[filterId] ?: return
        fields = fields + (filterId to field.copy(value = value))
    }

    /** A single id is IME Done; null consumes every remaining declared input, including hidden ones. */
    fun consume(filterId: String? = null): Map<String, String> {
        val pending = fields.filter { (id, field) -> (filterId == null || id == filterId) && field.isPending }
        if (pending.isEmpty()) return emptyMap()
        fields = fields.mapValues { (id, field) -> if (id in pending) field.consume() else field }
        return pending.mapValues { (_, field) -> field.value }
    }

    fun reset(filters: List<SourceFilter>) {
        fields =
            filters.filter(::isTextInput).associate { filter ->
                val initial = Field.initial(filter.type, filter.defaultValues.firstOrNull().orEmpty())
                filter.id to
                    initial.copy(
                        observed = fields[filter.id]?.observed ?: initial.observed,
                        awaitingEcho = initial.baseline,
                    )
            }
    }

    private data class Field(
        val type: FilterControlType,
        val value: String,
        val baseline: String,
        val observed: String,
        val awaitingEcho: String? = null,
    ) {
        val isPending: Boolean get() = normalized(value) != baseline

        fun consume(): Field = copy(baseline = normalized(value), awaitingEcho = normalized(value))

        fun reconcile(committed: String): Field {
            val incoming = normalized(committed)
            return when {
                incoming == observed -> this
                // An older echo must not replace a locally consumed value or a newer edit.
                awaitingEcho != null && incoming != awaitingEcho -> copy(observed = incoming)
                else ->
                    copy(
                        value = if (isPending) value else committed,
                        baseline = incoming,
                        observed = incoming,
                        awaitingEcho = null,
                    )
            }
        }

        companion object {
            fun initial(
                type: FilterControlType,
                value: String,
            ): Field = Field(type, value, normalized(value), normalized(value))
        }
    }
}

private fun isTextInput(filter: SourceFilter): Boolean = filter.type == TEXT || filter.type == NUMBER

// Match existing selection pruning without trimming nonblank text or imposing numeric grammar.
private fun normalized(value: String): String = value.takeUnless { it.isBlank() }.orEmpty()
