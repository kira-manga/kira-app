package me.manga.kira.data.repository.selection

import me.manga.kira.data.identity.AcceptedSourceAliasRule
import me.manga.kira.data.identity.SelectionUrlRewrites
import me.manga.kira.data.local.dao.ReaderProgressDao
import me.manga.kira.data.local.dao.SelectionInspectionSize
import me.manga.kira.data.local.dao.SelectionQueueRow
import me.manga.kira.data.local.dao.SourceSelectionMigrationDao
import me.manga.kira.sources.contracts.SourceSelectionToken

/** Conservative admission limits for a complete ownership snapshot, not permission to inspect a prefix. */
object SelectionMigrationLimits {
    const val MAX_INSPECTED_ROWS = 100_000
    const val MAX_LOCATOR_BYTES = 16 * 1024 * 1024
}

/**
 * Lower transaction participant ONLY. Production composition is deliberately unbound pending the
 * real readiness/re-adoption/native lifetime decisions (B1/B2/B3), not supplied by a test fixture.
 *
 * The caller must already hold one real immediate Room writer and the actual download/native
 * exclusion through commit or rollback. QUEUED is not a no-capture or artifact-absence witness.
 * Pass only the private verified candidate's accepted rules and newly allocated token. This class
 * opens no transaction, waits on no gate, reads no committed provider, and performs no file/network IO.
 * Failures/cancellation MUST escape the outer writer; a cancellation request cannot release it early.
 * The writer must retain original caller cancellation, as RoomMangaWriteTransaction does: Room's
 * current Job alone may belong to the database instead. This is not supplied by the observer.
 *
 * Image-only write-set/exclusion proof is outside this page-alias input. The future composition
 * must read projection whole rows AFTER these writes in this same writer, never apply cached copies.
 */
class StrictSourceSelectionMigration(
    private val dao: SourceSelectionMigrationDao,
    private val reader: ReaderProgressDao,
) {
    /** Complete preflight precedes every write; neither the token nor the row-state check grants exclusion. */
    suspend fun migrateInTransaction(
        token: SourceSelectionToken,
        rules: Collection<AcceptedSourceAliasRule>,
    ) {
        migrateInTransaction(token, rules, SelectionPlanningProgress())
    }

    /** Internal observation shares the exact production path, not a replacement planner or writer. */
    internal suspend fun migrateInTransaction(
        token: SourceSelectionToken,
        rules: Collection<AcceptedSourceAliasRule>,
        progress: SelectionPlanningProgress,
    ) {
        val size = progress.around { dao.inspectionSize() }
        checkBounds(size)
        val rows = readRows(progress)
        requireSelection(rows.inspectedCount() == size.rowCount, "selection ownership snapshot count changed")
        val rewrites = SelectionUrlRewrites.create(token, rules, progress)
        val indices = SelectionMigrationIndexes(rows, rewrites, progress)
        indices.prepare()
        val families = SelectionMigrationFamilies(dao, indices, rewrites, progress)
        val plan = SelectionMigrationPlanner(indices, families, rewrites, progress).build()
        preflightArtifacts(plan, progress)
        progress.checkpoint()
        apply(plan, progress)
        progress.checkpoint()
    }

    private suspend fun readRows(progress: SelectionPlanningProgress): SelectionMigrationRows =
        SelectionMigrationRows(
            SelectionWorkRows(progress.around { dao.savedWorks() }, progress.around { dao.readerWorks() }),
            SelectionChildRows(progress.around { dao.savedChapters() }, progress.around { dao.readerChapters() }),
            SelectionRelatedRows(
                progress.around { dao.queues() },
                progress.around { dao.history() },
                progress.around { dao.notifications() },
            ),
        )

    /** Complete before any URL write; an idle receipt may move, but durable custody may not. */
    private suspend fun preflightArtifacts(plan: SelectionMigrationPlan, progress: SelectionPlanningProgress) {
        for (family in plan.families) {
            for (move in family.savedChapters.moves) {
                progress.visit(SelectionVisit.RELATION)
                if (move.expected.url != move.destination) {
                    requireSelection(
                        move.expected.artifactOwnerMovable != false,
                        "selection requires a settled exact chapter artifact owner",
                    )
                }
            }
        }
    }

    private suspend fun apply(
        plan: SelectionMigrationPlan,
        progress: SelectionPlanningProgress,
    ) {
        for (family in plan.families) {
            progress.visit(SelectionVisit.APPLY)
            applyFamily(family, progress)
        }
        for (queue in plan.queues) {
            progress.visit(SelectionVisit.APPLY)
            applyQueue(queue, progress)
        }
        for (row in plan.history) {
            progress.visit(SelectionVisit.APPLY)
            applyRelated(row, notification = false, progress = progress)
        }
        for (row in plan.notifications) {
            progress.visit(SelectionVisit.APPLY)
            applyRelated(row, notification = true, progress = progress)
        }
    }

    private suspend fun applyFamily(
        plan: SelectionFamilyPlan,
        progress: SelectionPlanningProgress,
    ) {
        val family = plan.family
        val destination = family.destination
        family.saved?.let {
            if (it.url != destination) writeOne(progress) { dao.moveSavedWork(it.id, it.api, it.url, destination) }
        }
        family.reader?.let {
            if (it.workUrl != destination) {
                writeReader(progress) { reader.moveWork(it, destination) }
            }
        }
        val api = family.requested.api
        for (move in plan.savedChapters.moves) {
            progress.visit(SelectionVisit.APPLY)
            val row = move.expected
            if (row.url != move.destination) {
                writeOne(progress) { dao.moveSavedChapter(row.id, row.mangaId, api, row.url, move.destination) }
                if (row.artifactOwnerMovable == true) {
                    writeOne(progress) { dao.moveChapterArtifact(row.id, row.mangaId, row.url, move.destination) }
                }
            }
        }
        for (move in plan.readerChapters.moves) {
            progress.visit(SelectionVisit.APPLY)
            val row = move.expected
            if (row.chapterUrl != move.destination) writeReader(progress) { reader.moveChapter(row, move.destination) }
        }
    }

    private suspend fun applyQueue(
        move: SelectionUrlMove<SelectionQueueRow>,
        progress: SelectionPlanningProgress,
    ) {
        val row = move.expected.identity
        if (row.url != move.destination) {
            writeOne(progress) { dao.moveQueue(row.id, row.chapterId, row.mangaId, row.api, row.url, move.destination) }
        }
    }

    private suspend fun applyRelated(
        move: SelectionRelatedMove,
        notification: Boolean,
        progress: SelectionPlanningProgress,
    ) {
        val row = move.expected
        val work = row.work
        if (work.mangaUrl != move.workUrl) {
            writeOne(progress) {
                if (notification) {
                    dao.moveNotificationWork(row.id, work.mangaId, row.chapterId, work.api, work.mangaUrl, move.workUrl)
                } else {
                    dao.moveHistoryWork(row.id, work.mangaId, work.api, work.mangaUrl, move.workUrl)
                }
            }
        }
        val nextUrl = move.chapterUrl
        if (row.chapterUrl != nextUrl) {
            writeOne(progress) {
                if (notification) {
                    dao.moveNotificationChapter(row.id, work.mangaId, row.chapterId, work.api, row.chapterUrl, nextUrl)
                } else {
                    dao.moveHistoryChapter(row.id, work.mangaId, work.api, row.chapterUrl, nextUrl)
                }
            }
        }
    }
}

private fun checkBounds(size: SelectionInspectionSize) {
    requireSelection(
        size.rowCount in 0L..SelectionMigrationLimits.MAX_INSPECTED_ROWS.toLong(),
        "selection row limit exceeded",
    )
    requireSelection(
        size.locatorBytes in 0L..SelectionMigrationLimits.MAX_LOCATOR_BYTES.toLong(),
        "selection locator byte limit exceeded",
    )
}

private fun SelectionMigrationRows.inspectedCount(): Long =
    works.saved.size.toLong() + works.reader.size + children.saved.size + children.reader.size +
        related.queues.size + related.history.size + related.notifications.size

private suspend fun writeOne(
    progress: SelectionPlanningProgress,
    block: suspend () -> Int,
) {
    progress.visit(SelectionVisit.WRITE)
    val changed = progress.around(block)
    requireSelection(changed == 1, "selection URL write count was $changed")
}

private suspend fun writeReader(
    progress: SelectionPlanningProgress,
    block: suspend () -> Boolean,
) {
    progress.visit(SelectionVisit.WRITE)
    requireSelection(progress.around(block), "reader URL move failed")
}
