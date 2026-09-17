package me.manga.kira.data.complaint.backend

import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.model.complaint.ComplaintNotice
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow

/** Complete snapshot only: never publish a verified prefix or merge it into older/legacy history. */
internal class ComplaintHistoryPages {
    private val notices = mutableListOf<ComplaintNotice>()
    private val items = mutableListOf<ComplaintOwnerRow>()
    private val ids = mutableSetOf<String>()
    private val cursors = mutableSetOf<String>()
    private val mismatches = mutableSetOf<ComplaintHistoryMismatch>()
    private var pages = 0
    private var populated = 0
    var cursor: String? = null
        private set
    var complete: Boolean = false
        private set

    fun accept(page: ComplaintHistoryPage): Boolean {
        if (complete || pages == MAX_REQUESTS || page.items.size > PAGE_SIZE) return false
        if (pages != 0 && page.notices.isNotEmpty()) return false
        if (page.items.isNotEmpty() && ++populated > MAX_POPULATED) return false
        if (items.size + page.items.size > MAX_ITEMS) return false
        if (page.items.isEmpty() && page.nextCursor != null) return false
        if (page.nextCursor != null && !cursors.add(page.nextCursor)) return false
        if ((page.notices.map { it.id } + page.items.map { it.id }).any { !ids.add(it) }) return false
        var previous = items.lastOrNull()
        for (row in page.items) {
            if (previous != null && !ordered(previous, row)) return false
            previous = row
        }
        mismatches += page.mismatches
        notices += page.notices
        items += page.items
        cursor = page.nextCursor
        pages++
        complete = cursor == null
        return complete || pages < MAX_REQUESTS
    }

    fun snapshot(): ComplaintHistory.Backend = ComplaintHistory.Backend(notices, items)

    fun reportMismatches() = mismatches.forEach { it.report() }

    private fun ordered(before: ComplaintOwnerRow, after: ComplaintOwnerRow): Boolean =
        before.createdAt > after.createdAt || before.createdAt == after.createdAt && before.id > after.id

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_ITEMS = 100
        const val MAX_POPULATED = 2
        const val MAX_REQUESTS = 3
    }
}
