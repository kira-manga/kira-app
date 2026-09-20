package me.manga.kira.data.repository.selection

import me.manga.kira.data.identity.SelectionAliasKey
import me.manga.kira.data.identity.SelectionUrlRewrites
import me.manga.kira.data.local.dao.SelectionQueueRow
import me.manga.kira.data.local.dao.SelectionRelatedRow
import me.manga.kira.data.local.dao.SelectionSavedChapter
import me.manga.kira.data.local.entity.ReaderChapterStateEntity
import me.manga.kira.domain.model.identity.WorkLocator

internal data class SelectionUrlMove<T>(
    val expected: T,
    val destination: String,
)

internal data class SelectionFamilyPlan(
    val family: SelectionMigrationFamily,
    val savedChapters: SelectionChapterPlans<SelectionSavedChapter>,
    val readerChapters: SelectionChapterPlans<ReaderChapterStateEntity>,
)

internal data class SelectionRelatedMove(
    val expected: SelectionRelatedRow,
    val workUrl: String,
    val chapterUrl: String,
)

/** Entire before-image/destination plan is complete before the first URL update. */
internal data class SelectionMigrationPlan(
    val families: List<SelectionFamilyPlan>,
    val queues: List<SelectionUrlMove<SelectionQueueRow>>,
    val history: List<SelectionRelatedMove>,
    val notifications: List<SelectionRelatedMove>,
)

/** Complete members are enumerated once; lookup/cardinality never rescans a large parent bucket. */
internal class SelectionChapterPlans<T>(
    private val progress: SelectionPlanningProgress,
) {
    val moves = mutableListOf<SelectionUrlMove<T>>()
    val byId = mutableMapOf<Long, SelectionUrlMove<T>>()
    val byKey = mutableMapOf<SelectionAliasKey, SelectionUrlMove<T>>()
    private val originals = mutableSetOf<String>()
    private val destinations = mutableSetOf<String>()
    private val addresses = mutableListOf<Pair<String, String>>()

    suspend fun add(
        id: Long,
        key: SelectionAliasKey,
        original: String,
        move: SelectionUrlMove<T>,
    ) {
        progress.visit(SelectionVisit.RELATION)
        requireSelection(id > 0 && byId.put(id, move) == null, "invalid chapter identity")
        requireSelection(byKey.put(key, move) == null, "chapter alias conflict within its parent")
        requireSelection(destinations.add(move.destination), "chapter destinations collide within their parent")
        originals += original
        addresses += original to move.destination
        moves += move
    }

    suspend fun checkOccupancy() {
        for ((original, destination) in addresses) {
            progress.visit(SelectionVisit.RELATION)
            requireSelection(
                original == destination || destination !in originals,
                "chapter destination is already occupied",
            )
        }
    }
}

internal class SelectionMigrationPlanner(
    private val rows: SelectionMigrationIndexes,
    private val families: SelectionMigrationFamilies,
    private val rewrites: SelectionUrlRewrites,
    private val progress: SelectionPlanningProgress,
) {
    private val plans = SelectionPlanIndex()

    suspend fun build(): SelectionMigrationPlan {
        for (family in families.discover()) {
            progress.visit(SelectionVisit.RELATION)
            plans.add(planFamily(family), rewrites, progress)
        }
        val queues = mutableListOf<SelectionUrlMove<SelectionQueueRow>>()
        for (row in rows.rows.related.queues) {
            progress.visit(SelectionVisit.RELATION)
            queue(row)?.let { queues += it }
        }
        val history = relatedRows(rows.rows.related.history, notification = false)
        val notifications = relatedRows(rows.rows.related.notifications, notification = true)
        families.checkPlannedOccupancy()
        return SelectionMigrationPlan(plans.ordered, queues, history, notifications)
    }

    private suspend fun planFamily(family: SelectionMigrationFamily): SelectionFamilyPlan {
        val api = family.requested.api
        val saved = SelectionChapterPlans<SelectionSavedChapter>(progress)
        for (row in family.saved?.let { rows.savedChildren[it.id] }.orEmpty()) {
            val locator = WorkLocator(api, row.url)
            saved.add(row.id, rewrites.key(locator), row.url, SelectionUrlMove(row, rewrites.destination(locator)))
        }
        saved.checkOccupancy()
        val reader = SelectionChapterPlans<ReaderChapterStateEntity>(progress)
        for (row in family.reader?.let { rows.readerChildren[it.workId] }.orEmpty()) {
            val locator = WorkLocator(api, row.chapterUrl)
            val key = rewrites.key(locator)
            val destination = saved.byKey[key]?.destination ?: rewrites.destination(locator)
            val move = SelectionUrlMove(row, rewrites.checked(locator, destination))
            reader.add(row.chapterId, key, row.chapterUrl, move)
        }
        reader.checkOccupancy()
        return SelectionFamilyPlan(family, saved, reader)
    }

    private suspend fun queue(row: SelectionQueueRow): SelectionUrlMove<SelectionQueueRow>? {
        val original = row.identity
        val parent = rows.savedById[original.mangaId]
        val child = rows.savedChildById[original.chapterId]
        val parentPlan = plans.bySaved[original.mangaId]
        val childPlan = plans.childParent(original.chapterId, rows)
        val validLinks = parent != null && parent.api == original.api && child?.mangaId == parent.id
        if (!validLinks) {
            val read = rewrites.read(WorkLocator(original.api, original.url))
            val locatorMatch = read.key?.let { it in plans.savedChapterKeys } == true
            val relevant = parentPlan != null || childPlan != null || locatorMatch || read.affected
            requireSelection(!relevant, "queue has invalid saved parent or chapter links")
            return null
        }
        return if (parentPlan == null && childPlan == null) null else plannedQueue(row, parentPlan, childPlan)
    }

    private suspend fun plannedQueue(
        row: SelectionQueueRow,
        parentPlan: SelectionFamilyPlan?,
        childPlan: SelectionFamilyPlan?,
    ): SelectionUrlMove<SelectionQueueRow> {
        val original = row.identity
        requireSelection(parentPlan != null && parentPlan === childPlan, "queue links disagree about their parent")
        val plan = checkNotNull(parentPlan)
        requireSelection(row.stationary, "selection requires queue recovery before migration")
        requireSelection(original.id > 0, "invalid queue identity")
        val move = plan.savedChapters.byId[original.chapterId] ?: unavailable("queue has no matching saved chapter")
        requireSelection(move.expected.url == original.url, "queue and saved chapter raw URLs disagree")
        return SelectionUrlMove(row, rewrites.checked(WorkLocator(original.api, original.url), move.destination))
    }

    private suspend fun relatedRows(
        rows: List<SelectionRelatedRow>,
        notification: Boolean,
    ): List<SelectionRelatedMove> {
        val moves = mutableListOf<SelectionRelatedMove>()
        for (row in rows) {
            progress.visit(SelectionVisit.RELATION)
            related(row, notification)?.let { moves += it }
        }
        return moves
    }

    private suspend fun related(
        row: SelectionRelatedRow,
        notification: Boolean,
    ): SelectionRelatedMove? {
        val work = WorkLocator(row.work.api, row.work.mangaUrl)
        val byId = plans.bySaved[row.work.mangaId]
        val byChild = if (notification) plans.childParent(row.chapterId, rows) else null
        val byUrl = rewrites.read(work).key?.let { plans.byKey[it] }
        val plan = byId ?: byChild ?: byUrl ?: return null
        // Reference identity avoids hashing/comparing a plan's entire child lists for each related row.
        requireSelection(
            (byId == null || byId === plan) &&
                (byChild == null || byChild === plan) &&
                (byUrl == null || byUrl === plan),
            "related row has conflicting ID or locator owners",
        )
        checkRelated(row, plan, notification)
        val destination = rewrites.destination(work)
        families.checkOccupancy(plan.family, work.url, destination)
        return SelectionRelatedMove(row, destination, rewrites.destination(WorkLocator(work.api, row.chapterUrl)))
    }

    private suspend fun checkRelated(
        row: SelectionRelatedRow,
        plan: SelectionFamilyPlan,
        notification: Boolean,
    ) {
        val family = plan.family
        val work = WorkLocator(row.work.api, row.work.mangaUrl)
        requireSelection(row.id > 0 && row.work.mangaId >= 0 && row.chapterId >= 0, "invalid related row identity")
        requireSelection(
            row.work.mangaId == 0L || family.saved?.id == row.work.mangaId,
            "related row saved link changed",
        )
        requireSelection(rewrites.key(work) == rewrites.key(family.requested), "related row source or work changed")
        val key = rewrites.key(WorkLocator(work.api, row.chapterUrl))
        val saved = plan.savedChapters.byKey[key]
        val reader = plan.readerChapters.byKey[key]
        requireSelection(saved != null || reader != null, "related chapter has no scoped saved or reader owner")
        if (notification && row.chapterId != 0L) {
            requireSelection(saved?.expected?.id == row.chapterId, "notification saved chapter link changed")
        }
    }
}
