package me.manga.kira.data.repository.selection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.identity.SelectionUrlRewrites
import me.manga.kira.data.identity.SelectionWorkOwnerIndex
import me.manga.kira.data.identity.WorkOwnerResolution
import me.manga.kira.data.identity.WorkOwnerResolver
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Structural visits and real Job cancellation, never a wall-clock benchmark or native/gate proof. */
class StrictSelectionPlanningCostTest {
    @Test
    fun supportedBatchMatchesCompleteScalarOwnersAndKeepsItsStrictRequestBoundary() =
        runTest {
            val progress = SelectionPlanningProgress()
            val rewrites = SelectionUrlRewrites.create(strictToken(), listOf(strictRule()), progress)
            val candidates = differentialOwners()
            val reference = candidates[0].locator
            val scalar = WorkOwnerResolver(rewrites.policy)
            val indexed = SelectionWorkOwnerIndex.prepare(reference, candidates, rewrites, progress)
            for ((request, exact) in differentialRequests(candidates)) {
                assertEquals(scalar.resolve(request, exact, candidates), indexed.resolve(request, exact), request.url)
            }
            assertCompleteResult(candidates + owner(EXTRA_OWNER_ID, "$STRICT_NEW/work"), rewrites, progress)
            assertCompleteResult(candidates + owner(ALIAS_OWNER_ID, "$STRICT_OLD/changed"), rewrites, progress)
            val foreign = owner(EXTRA_OWNER_ID, "$STRICT_NEW/foreign", "other")
            assertCompleteResult(candidates + foreign, rewrites, progress)
            val unsupported = candidates[2]
            assertIs<WorkOwnerResolution.Found>(scalar.resolve(unsupported.locator, unsupported, candidates))
            assertFailsWith<SourceSelectionUnavailable> {
                SelectionWorkOwnerIndex.prepare(unsupported.locator, candidates, rewrites, progress)
            }
        }

    @Test
    fun highCardinalitySameSourceAndSharedChapterUrlsHaveOnlyLinearRecordedWork() =
        runTest {
            StrictSelectionMigrationFixture().use { f ->
                f.seedHighCardinality()
                val size = f.db.sourceSelectionMigrationDao().inspectionSize()
                assertEquals(STRICT_MANY_ROWS.toLong(), size.rowCount)
                assertTrue(size.locatorBytes <= SelectionMigrationLimits.MAX_LOCATOR_BYTES)
                val before = f.snapshot()
                val progress = SelectionPlanningProgress()
                f.migrate(progress = progress)
                assertLinearWork(progress.snapshot())
                f.reopen()
                assertEquals(before.withMovedUrls(), f.snapshot())
            }
        }

    @Test
    fun realJobCancellationAfterIndexingStartsEndsTheWriterWithoutAnyUrlWrite() =
        runTest {
            StrictSelectionMigrationFixture().use { f ->
                f.seedHighCardinality()
                val before = f.snapshot()
                val observed = cancelDuringIndexing(f)
                f.reopen()
                val persistedUnchanged = before == f.snapshot()
                val after = assertNotNull(observed.after)
                val cancelledAt = assertNotNull(observed.cancelledAt)
                val delta = after.total - cancelledAt
                val diagnostic =
                    "cancelledAt=$cancelledAt total=${after.total} delta=$delta " +
                        "ROW=${after[SelectionVisit.ROW]} WRITE=${after[SelectionVisit.WRITE]} " +
                        "persistedUnchanged=$persistedUnchanged jobs=${observed.jobs}"
                println("APP32_CALLER_CANCELLATION $diagnostic")
                assertTrue(after[SelectionVisit.ROW] >= CANCEL_AFTER_ROWS, diagnostic)
                assertTrue(delta <= SelectionPlanningProgress.CHECKPOINT_VISITS, diagnostic)
                assertEquals(0L, after[SelectionVisit.WRITE], diagnostic)
                assertTrue(persistedUnchanged, diagnostic)
            }
        }
}

private fun differentialOwners(): List<SavedWorkIdentity> =
    listOf(
        owner(ALIAS_OWNER_ID, "$STRICT_OLD/work"),
        owner(EXACT_OWNER_ID, "$STRICT_NEW/second"),
        owner(INVALID_LOCATOR_OWNER_ID, "https://outside.test/bad%zz"),
        owner(OTHER_SCHEME_OWNER_ID, "http://old.test/other-scheme"),
        owner(UNDECLARED_OWNER_ID, "https://outside.test/undeclared"),
    )

private fun differentialRequests(candidates: List<SavedWorkIdentity>): List<Pair<WorkLocator, SavedWorkIdentity?>> {
    val crossApi = owner(CROSS_API_OWNER_ID, "$STRICT_NEW/missing", "other")
    return listOf(
        candidates[0].locator to candidates[0],
        WorkLocator(STRICT_API, "$STRICT_NEW/work") to null,
        candidates[1].locator to candidates[1],
        WorkLocator(STRICT_API, "$STRICT_OLD/second") to null,
        WorkLocator(STRICT_API, "$STRICT_NEW/missing") to null,
        WorkLocator(STRICT_API, "$STRICT_NEW/missing") to crossApi,
        WorkLocator(STRICT_API, "$STRICT_NEW/work") to candidates[0],
        candidates[0].locator to null,
    )
}

private fun owner(
    id: Long,
    url: String,
    api: String = STRICT_API,
): SavedWorkIdentity = SavedWorkIdentity(id, WorkLocator(api, url))

private suspend fun assertCompleteResult(
    candidates: List<SavedWorkIdentity>,
    rewrites: SelectionUrlRewrites,
    progress: SelectionPlanningProgress,
) {
    val reference = candidates[0].locator
    val index = SelectionWorkOwnerIndex.prepare(reference, candidates, rewrites, progress)
    val scalar = WorkOwnerResolver(rewrites.policy).resolve(reference, candidates[0], candidates)
    assertEquals(scalar, index.resolve(reference, candidates[0]))
}

private fun assertLinearWork(counts: SelectionPlanningSnapshot) {
    val families = STRICT_MANY_FAMILIES.toLong()
    val children = families * STRICT_MANY_CHILDREN
    val rows = STRICT_MANY_ROWS.toLong()
    val unique = families + STRICT_MANY_CHILDREN
    // Full saved/reader scans once, then one owner resolution per distinct family; one API batch.
    assertEquals(OWNER_VISITS_PER_FAMILY * families + 1, counts[SelectionVisit.OWNER])
    assertEquals(unique, counts[SelectionVisit.CLASSIFY])
    // Each absent new raw work target is really cached, despite many reader/related references.
    assertEquals(2 * families, counts[SelectionVisit.GLOBAL_READ])
    assertEquals(2 * families + WRITE_VISITS_PER_CHILD * children, counts[SelectionVisit.WRITE])
    assertTrue(counts[SelectionVisit.ROW] <= 2 * rows + 2)
    assertTrue(counts[SelectionVisit.RELATION] <= RELATION_VISITS_PER_ROW_BOUND * rows)
    assertTrue(counts[SelectionVisit.LOOKUP] <= LOOKUP_VISITS_PER_ROW_BOUND * rows)
    assertTrue(counts[SelectionVisit.APPLY] <= rows)
    // Counts are incremented at actual loops/lookups, not synthesized from this expected formula.
    assertTrue(counts.total <= TOTAL_VISITS_PER_ROW_BOUND * rows + TOTAL_VISITS_PER_URL_BOUND * unique)
}

private data class StrictCancellationObservation(
    var cancelledAt: Long? = null,
    var after: SelectionPlanningSnapshot? = null,
    var jobs: StrictCancellationJobs? = null,
)

private data class StrictCancellationJobs(
    val targetWasActive: Boolean,
    val targetIsCancelled: Boolean,
    val writerIsTarget: Boolean,
    val writerWasActive: Boolean,
    val writerIsActiveAfterCancel: Boolean,
)

private suspend fun cancelDuringIndexing(fixture: StrictSelectionMigrationFixture): StrictCancellationObservation =
    coroutineScope {
        val observed = StrictCancellationObservation()
        val attempt =
            async {
                val job = currentCoroutineContext().job
                var writerJob: Job? = null
                val progress =
                    SelectionPlanningProgress { snapshot ->
                        if (observed.cancelledAt == null && snapshot[SelectionVisit.ROW] >= CANCEL_AFTER_ROWS) {
                            observed.cancelledAt = snapshot.total
                            val writer = checkNotNull(writerJob)
                            val targetWasActive = job.isActive
                            val writerWasActive = writer.isActive
                            job.cancel()
                            observed.jobs =
                                StrictCancellationJobs(
                                    targetWasActive,
                                    job.isCancelled,
                                    writer === job,
                                    writerWasActive,
                                    writer.isActive,
                                )
                        }
                    }
                try {
                    fixture.migrate(progress = progress, onWriterEntered = { writerJob = it })
                } finally {
                    observed.after = progress.snapshot()
                }
            }
        assertFailsWith<CancellationException> { attempt.await() }
        attempt.join()
        observed
    }

private const val CANCEL_AFTER_ROWS = 4_096L
private const val ALIAS_OWNER_ID = 1L
private const val EXACT_OWNER_ID = 2L
private const val INVALID_LOCATOR_OWNER_ID = 3L
private const val OTHER_SCHEME_OWNER_ID = 4L
private const val UNDECLARED_OWNER_ID = 5L
private const val EXTRA_OWNER_ID = 6L
private const val CROSS_API_OWNER_ID = 9L
private const val OWNER_VISITS_PER_FAMILY = 3
private const val WRITE_VISITS_PER_CHILD = 7
private const val RELATION_VISITS_PER_ROW_BOUND = 4
private const val LOOKUP_VISITS_PER_ROW_BOUND = 12
private const val TOTAL_VISITS_PER_ROW_BOUND = 32
private const val TOTAL_VISITS_PER_URL_BOUND = 8
