package me.manga.kira.sources.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.RoomMangaWriteTransaction
import me.manga.kira.data.local.dao.SelectionInspectionSize
import me.manga.kira.data.local.dao.SourceSelectionMigrationDao
import me.manga.kira.data.repository.selection.StrictSourceSelectionMigration
import me.manga.kira.platform.download.DownloadOperationExclusion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Real adapter/strict participant boundary; no claim about nonempty migration or native lifetime. */
class DownloadSourceCatalogSelectionMigrationTest {
    @Test
    fun aRoomWriterCannotInspectWithoutThisGraphsActualExclusiveContext() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            // Reuse verified preparation; the fixture's empty-family adapter is NOT the adapter under test.
            assertIs<AppResult.Success<*>>(f.manager().refresh())
            val candidate = f.prepared.last()
            val token = assertNotNull(f.store.readSelection().selection).token
            val before = f.state()
            val operations = DownloadOperationExclusion()
            val inspections = AtomicInteger()
            val adapter = DownloadSourceCatalogSelectionMigration(operations, observedPlanner(f, inspections))
            val writer = RoomMangaWriteTransaction(f.db)
            val migrate: suspend () -> Unit = { writer.write { adapter.migrateInTransaction(candidate, token) } }

            assertFailsWith<IllegalStateException> { migrate() }
            operations.withOperation { assertFailsWith<IllegalStateException> { migrate() } }
            DownloadOperationExclusion().withExclusive {
                assertFailsWith<IllegalStateException> { migrate() }
            }
            assertEquals(0, inspections.get(), "Unheld/shared/foreign contexts must refuse before any planner capture")
            assertEquals(before, f.state())

            adapter.withQuiescentSelection(candidate) { migrate() }
            assertEquals(1, inspections.get(), "The real participant is reached under the correct exclusive and writer")
            assertEquals(before, f.state(), "Empty ownership families require no row or selection writes")
        }
    }

    private fun observedPlanner(f: EffectiveSourceSelectionRoomFixture, inspections: AtomicInteger): StrictSourceSelectionMigration {
        val delegate = f.db.sourceSelectionMigrationDao()
        val observed = object : SourceSelectionMigrationDao by delegate {
            override suspend fun inspectionSize(): SelectionInspectionSize {
                inspections.incrementAndGet()
                return delegate.inspectionSize()
            }
        }
        return StrictSourceSelectionMigration(observed, f.db.readerProgressDao())
    }
}
