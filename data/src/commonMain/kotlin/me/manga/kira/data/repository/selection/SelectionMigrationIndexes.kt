package me.manga.kira.data.repository.selection

import me.manga.kira.data.identity.SelectionAliasKey
import me.manga.kira.data.identity.SelectionUrlRewrites
import me.manga.kira.data.local.dao.SelectionQueueRow
import me.manga.kira.data.local.dao.SelectionRelatedRow
import me.manga.kira.data.local.dao.SelectionSavedChapter
import me.manga.kira.data.local.dao.SelectionSavedWork
import me.manga.kira.data.local.entity.ReaderChapterStateEntity
import me.manga.kira.data.local.entity.ReaderWorkStateEntity
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator

internal data class SelectionWorkRows(
    val saved: List<SelectionSavedWork>,
    val reader: List<ReaderWorkStateEntity>,
)

internal data class SelectionChildRows(
    val saved: List<SelectionSavedChapter>,
    val reader: List<ReaderChapterStateEntity>,
)

internal data class SelectionRelatedRows(
    val queues: List<SelectionQueueRow>,
    val history: List<SelectionRelatedRow>,
    val notifications: List<SelectionRelatedRow>,
)

internal data class SelectionMigrationRows(
    val works: SelectionWorkRows,
    val children: SelectionChildRows,
    val related: SelectionRelatedRows,
)

/** Every row is indexed before seeding; unsupported/unaffected rows retain all their ID relations. */
internal class SelectionMigrationIndexes(
    val rows: SelectionMigrationRows,
    private val rewrites: SelectionUrlRewrites,
    private val progress: SelectionPlanningProgress,
) {
    val savedById = mutableMapOf<Long, SelectionSavedWork>()
    val readerById = mutableMapOf<Long, ReaderWorkStateEntity>()
    val savedByApi = mutableMapOf<String, MutableList<SavedWorkIdentity>>()
    val readerByApi = mutableMapOf<String, MutableList<ReaderWorkStateEntity>>()
    val savedChildById = mutableMapOf<Long, SelectionSavedChapter>()
    val savedChildren = mutableMapOf<Long, MutableList<SelectionSavedChapter>>()
    val readerChildren = mutableMapOf<Long, MutableList<ReaderChapterStateEntity>>()
    val requests = linkedMapOf<WorkLocator, SelectionAliasKey>()
    val references = linkedMapOf<String, WorkLocator>()

    suspend fun prepare() {
        indexWorks()
        indexChildren()
        seedWorks()
        seedChildren()
        seedQueues()
        seedRelated(rows.related.history)
        seedRelated(rows.related.notifications)
    }

    private suspend fun indexWorks() {
        for (row in rows.works.saved) {
            progress.visit(SelectionVisit.ROW)
            requireSelection(row.id > 0 && savedById.put(row.id, row) == null, "invalid saved work identity")
            savedByApi.getOrPut(row.api) { mutableListOf() }.add(row.identity())
        }
        for (row in rows.works.reader) {
            progress.visit(SelectionVisit.ROW)
            requireSelection(readerById.put(row.workId, row) == null, "duplicate reader work identity")
            readerByApi.getOrPut(row.api) { mutableListOf() }.add(row)
        }
    }

    private suspend fun indexChildren() {
        for (row in rows.children.saved) {
            progress.visit(SelectionVisit.ROW)
            requireSelection(savedChildById.put(row.id, row) == null, "duplicate saved chapter identity")
            savedChildren.getOrPut(row.mangaId) { mutableListOf() }.add(row)
        }
        for (row in rows.children.reader) {
            progress.visit(SelectionVisit.ROW)
            readerChildren.getOrPut(row.workId) { mutableListOf() }.add(row)
        }
    }

    private suspend fun seedWorks() {
        for (row in rows.works.saved) {
            progress.visit(SelectionVisit.ROW)
            if (rewrites.read(row.locator()).affected) seed(row.locator())
        }
        for (row in rows.works.reader) {
            progress.visit(SelectionVisit.ROW)
            if (rewrites.read(row.locator()).affected) seed(row.locator())
        }
    }

    private suspend fun seedChildren() {
        for (row in rows.children.saved) {
            progress.visit(SelectionVisit.ROW)
            val parent = savedById[row.mangaId]
            if (rewrites.read(WorkLocator(parent?.api.orEmpty(), row.url)).affected) {
                seed(savedParent(row.mangaId).locator())
            }
        }
        for (row in rows.children.reader) {
            progress.visit(SelectionVisit.ROW)
            val parent = readerById[row.workId]
            if (rewrites.read(WorkLocator(parent?.api.orEmpty(), row.chapterUrl)).affected) {
                seed((parent ?: unavailable("affected reader chapter has no work anchor")).locator())
            }
        }
    }

    private suspend fun seedQueues() {
        for (row in rows.related.queues) {
            progress.visit(SelectionVisit.ROW)
            if (rewrites.read(WorkLocator(row.identity.api, row.identity.url)).affected) {
                seed(savedParent(row.identity.mangaId).locator())
            }
        }
    }

    private suspend fun seedRelated(rows: List<SelectionRelatedRow>) {
        for (row in rows) {
            progress.visit(SelectionVisit.ROW)
            val work = WorkLocator(row.work.api, row.work.mangaUrl)
            val workRead = rewrites.read(work)
            val chapterRead = rewrites.read(WorkLocator(row.work.api, row.chapterUrl))
            if (workRead.affected || chapterRead.affected) seed(work)
        }
    }

    private suspend fun seed(locator: WorkLocator) {
        val key = rewrites.key(locator)
        requests[locator] = key
        if (locator.api !in references) references[locator.api] = locator
    }

    fun savedParent(id: Long): SelectionSavedWork = savedById[id] ?: unavailable("affected row has no saved parent")
}

/** Direct row-to-plan lookup replaces full family scans; child keys here are membership, NOT owners. */
internal class SelectionPlanIndex {
    val ordered = mutableListOf<SelectionFamilyPlan>()
    val bySaved = mutableMapOf<Long, SelectionFamilyPlan>()
    val byKey = mutableMapOf<SelectionAliasKey, SelectionFamilyPlan>()
    val savedChapterKeys = mutableSetOf<SelectionAliasKey>()

    suspend fun add(
        plan: SelectionFamilyPlan,
        rewrites: SelectionUrlRewrites,
        progress: SelectionPlanningProgress,
    ) {
        progress.visit(SelectionVisit.RELATION)
        ordered += plan
        plan.family.saved?.let { bySaved[it.id] = plan }
        byKey[rewrites.key(plan.family.requested)] = plan
        for (key in plan.savedChapters.byKey.keys) {
            progress.visit(SelectionVisit.RELATION)
            savedChapterKeys += key
        }
    }

    fun childParent(
        id: Long,
        rows: SelectionMigrationIndexes,
    ): SelectionFamilyPlan? = rows.savedChildById[id]?.let { bySaved[it.mangaId] }
}
