package me.manga.kira.data.repository.selection

import kotlinx.coroutines.yield
import me.manga.kira.data.local.ensureMangaWriteActive

/** Actual work observations, not an admission limit, transaction owner or exclusion capability. */
internal enum class SelectionVisit { ROW, LOOKUP, CLASSIFY, OWNER, RELATION, GLOBAL_READ, APPLY, WRITE }

internal data class SelectionPlanningSnapshot(
    private val counts: List<Long>,
) {
    val total: Long get() = counts.sum()

    operator fun get(visit: SelectionVisit): Long = counts[visit.ordinal]
}

/** The observer supplies no validity authority; checks observe real current and writer-caller Jobs. */
internal class SelectionPlanningProgress(
    private val observe: ((SelectionPlanningSnapshot) -> Unit)? = null,
) {
    private val counts = LongArray(SelectionVisit.entries.size)
    private var pending = 0

    suspend fun visit(kind: SelectionVisit) {
        ensureMangaWriteActive()
        counts[kind.ordinal]++
        pending++
        if (pending == CHECKPOINT_VISITS) checkpoint()
    }

    suspend fun checkpoint() {
        ensureMangaWriteActive()
        observe?.invoke(snapshot())
        ensureMangaWriteActive()
        pending = 0
        yield()
        ensureMangaWriteActive()
    }

    /** Check both sides of existing SQL/parser primitives without claiming they are preemptible. */
    suspend fun <T> around(block: suspend () -> T): T {
        checkpoint()
        val result = block()
        checkpoint()
        return result
    }

    fun snapshot(): SelectionPlanningSnapshot = SelectionPlanningSnapshot(counts.toList())

    companion object {
        const val CHECKPOINT_VISITS = 128
    }
}
