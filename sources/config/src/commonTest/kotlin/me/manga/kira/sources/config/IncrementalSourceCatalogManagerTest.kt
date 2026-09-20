package me.manga.kira.sources.config

import kotlin.test.assertSame
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.SourceCatalogAcceptanceFloor
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Existing tier/delta cases; warm/cancellation cases moved intact in meaning to the lifecycle test. */
@OptIn(ExperimentalCoroutinesApi::class)
class IncrementalSourceCatalogManagerTest {
    @Test
    fun accepted_document_publishes_verified_cache_before_remote_finishes() = runTest {
        listOf(SourceCatalogManifestResult.Unavailable, SourceCatalogManifestResult.NotModified).forEach { result ->
            val store = FakeCatalogStore(storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1))))
            val releaseRemote = CompletableDeferred<Unit>()
            val remote = FakeRemote(result).apply { beforeManifestFetch = { releaseRemote.await() } }
            val manager = manager(store, remote)
            val observed = mutableListOf<SourceConfigDocument>()
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                manager.acceptedDocument.toList(observed)
            }
            val refresh = launch { assertTrue(manager.refresh() is AppResult.Success) }
            runCurrent()
            assertEquals(listOf(BUNDLED_REVISION, 10L), observed.map { it.revision })
            assertEquals(listOf("a"), observed.last().sources.map { it.api })
            assertTrue(refresh.isActive)
            assertEquals(UpdateState.Refreshing, manager.state.value)
            assertEquals(0, store.bundleProjectionCount)
            assertEquals(0, store.activationCount)
            releaseRemote.complete(Unit)
            refresh.join()
            assertEquals(listOf(BUNDLED_REVISION, 10L), observed.map { it.revision })
            assertSame(manager.activeDocument(), manager.acceptedDocument.value)
            observer.cancel()
        }
    }


    @Test
    fun diagnostics_start_on_the_bundled_catalog_without_inventing_source_revisions() {
        val store = FakeCatalogStore(active = null)
        val manager = manager(store, FakeRemote(SourceCatalogManifestResult.Unavailable))
        val diagnostics = manager.diagnostics.value
        assertEquals(UpdateState.Origin.BUNDLED, diagnostics.origin)
        assertEquals(BUNDLED_REVISION, diagnostics.catalogRevision)
        assertEquals(listOf("floor"), diagnostics.activeSources.map { it.api })
        assertEquals(null, diagnostics.activeSources.single().sourceRevision)
        assertEquals(null, diagnostics.manifestChecksum)
        assertEquals(0, diagnostics.removedSourceCount)
        assertEquals(0, diagnostics.inactiveSourceCount)
        assertEquals(null, store.ready, "synchronous execution bootstrap is not identity readiness")
    }

    @Test
    fun unavailable_remote_atomically_projects_the_complete_bundle() = runTest {
        val store = FakeCatalogStore(active = null)
        val manager = manager(store, FakeRemote(SourceCatalogManifestResult.Unavailable))
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(1, store.bundleProjectionCount)
        assertEquals(BUNDLED_REVISION, manager.activeDocument().revision)
        assertEquals(1L, assertNotNull(store.ready).token.generation)
    }

    @Test
    fun notModified_downloads_no_source_payloads() = runTest {
        val store = FakeCatalogStore(storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1))))
        val remote = FakeRemote(SourceCatalogManifestResult.NotModified)
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(0, remote.sourceFetches)
        assertEquals(0, store.activationCount)
        assertEquals(10, manager.activeDocument().revision)
        assertEquals(UpdateState.Origin.CACHE, manager.diagnostics.value.origin)
        assertEquals(10, manager.diagnostics.value.catalogRevision)
        val source = manager.diagnostics.value.activeSources.single()
        assertEquals(1, source.sourceRevision)
        assertEquals(checksum(1), source.checksum)
    }

    @Test
    fun changed_manifest_downloads_only_missing_revision_and_activates_once() = runTest {
        val stored = storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1), entry("b", 1) to artifact("b", 1)))
        val store = FakeCatalogStore(stored)
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(
            signedManifest(11, listOf(entry("a", 1), entry("b", 2)), previousRevision = 10)), mapOf("b" to artifact("b", 2)))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(listOf("b"), remote.fetchedApis)
        assertEquals(1, store.activationCount)
        assertEquals(11, manager.activeDocument().revision)
        assertEquals(UpdateState.Origin.REMOTE, manager.diagnostics.value.origin)
        assertEquals(11, manager.diagnostics.value.catalogRevision)
        assertEquals(listOf(1L, 2L), manager.diagnostics.value.activeSources.map { it.sourceRevision })
        assertEquals("test-key", manager.diagnostics.value.manifestSigningKeyId)
        assertEquals("Ed25519", manager.diagnostics.value.signatureAlgorithm)
        assertEquals(10, manager.diagnostics.value.previousCatalogRevision)
        assertEquals(checksum(10), manager.diagnostics.value.previousCatalogChecksum)
    }

    @Test
    fun maintenance_site_state_is_preserved_in_the_active_document() = runTest {
        val store = FakeCatalogStore(storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1))))
        val next = signedManifest(11, listOf(entry("a", 2)), previousRevision = 10)
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(next), mapOf("a" to artifact("a", 2, "UNDER_MAINTENANCE")))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals("UNDER_MAINTENANCE", manager.activeDocument().sources.single().siteState)
        assertEquals("active", manager.activeDocument().sources.single().lifecycle)
    }

    @Test
    fun non_generic_manifest_entry_is_rejected_without_fetch_or_activation() = runTest {
        val store = FakeCatalogStore(storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1))))
        val next = signedManifest(11, listOf(entry("a", 2).copy(engine = "legacy")), previousRevision = 10)
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(next))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(0, remote.sourceFetches)
        assertEquals(0, store.activationCount)
        assertEquals(10, manager.activeDocument().revision)
    }

    @Test
    fun previously_known_source_requires_an_explicit_removed_tombstone() = runTest {
        val stored = storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1), entry("b", 1) to artifact("b", 1)))
        val store = FakeCatalogStore(stored)
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(signedManifest(11, listOf(entry("a", 1)), previousRevision = 10)))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(0, remote.sourceFetches)
        assertEquals(0, store.activationCount)
        assertEquals(10, manager.activeDocument().revision)
    }

    @Test
    fun explicit_removed_tombstone_activates_without_downloading_payloads() = runTest {
        val store = FakeCatalogStore(storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1))))
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(signedManifest(11, emptyList(), 10, listOf("a"))))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(0, remote.sourceFetches)
        assertEquals(1, store.activationCount)
        assertTrue(manager.activeDocument().sources.isEmpty())
        assertTrue(assertNotNull(store.ready).payload.rules.isEmpty(), "authenticated empty is ready, not unavailable")
    }

    @Test
    fun lower_per_source_revision_is_rejected_as_a_rollback() = runTest {
        val store = FakeCatalogStore(storedCatalog(10, listOf(entry("a", 2) to artifact("a", 2))))
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(signedManifest(11, listOf(entry("a", 1)), 10)))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(0, remote.sourceFetches)
        assertEquals(0, store.activationCount)
        assertEquals(10, manager.activeDocument().revision)
    }

    @Test
    fun failed_required_download_never_activates_partial_catalog() = runTest {
        val store = FakeCatalogStore(storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1))))
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(signedManifest(11, listOf(entry("a", 2)), 10)))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Failure)
        assertEquals(0, store.activationCount)
        assertEquals(10, manager.activeDocument().revision)
        assertEquals(UpdateState.Origin.CACHE, manager.diagnostics.value.origin)
        assertEquals(10, manager.diagnostics.value.catalogRevision)
        assertTrue(manager.state.value is UpdateState.Failed)
    }

    @Test
    fun durable_floor_blocks_replay_even_when_cached_catalog_is_unreadable() = runTest {
        val store = FakeCatalogStore(null, SourceCatalogAcceptanceFloor(20, checksum(20)))
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(signedManifest(19, listOf(entry("a", 1)), 18)))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(0, remote.sourceFetches)
        assertEquals(0, store.activationCount)
        assertEquals(BUNDLED_REVISION, manager.activeDocument().revision)
    }

    @Test
    fun matching_floor_revision_repairs_an_unreadable_cached_catalog() = runTest {
        val store = FakeCatalogStore(null, SourceCatalogAcceptanceFloor(20, checksum(20)))
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(signedManifest(20, listOf(entry("a", 2)), 19)),
            mapOf("a" to artifact("a", 2)))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(listOf("a"), remote.fetchedApis)
        assertEquals(1, store.activationCount)
        assertEquals(20, manager.activeDocument().revision)
    }

    @Test
    fun higher_revision_fails_closed_when_durable_manifest_baseline_is_unreadable() = runTest {
        val store = FakeCatalogStore(null, SourceCatalogAcceptanceFloor(20, checksum(20)))
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(signedManifest(21, listOf(entry("a", 3)), 20)))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(0, remote.sourceFetches)
        assertEquals(0, store.activationCount)
        assertEquals(BUNDLED_REVISION, manager.activeDocument().revision)
    }

    @Test
    fun previously_removed_source_cannot_be_reintroduced() = runTest {
        val store = FakeCatalogStore(storedCatalog(10, emptyList(), removedApis = listOf("a")))
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(signedManifest(11, listOf(entry("a", 2)), 10)))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(0, remote.sourceFetches)
        assertEquals(0, store.activationCount)
        assertTrue(manager.activeDocument().sources.isEmpty())
    }

    @Test
    fun activation_failure_keeps_complete_last_known_good_catalog() = runTest {
        val store = FakeCatalogStore(storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1))), failActivation = true)
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(signedManifest(11, listOf(entry("a", 2)), 10)),
            mapOf("a" to artifact("a", 2)))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Failure)
        assertEquals(10, manager.activeDocument().revision)
        assertEquals(UpdateState.Origin.CACHE, manager.diagnostics.value.origin)
        assertEquals(10, manager.diagnostics.value.catalogRevision)
        assertTrue(manager.state.value is UpdateState.Failed)
    }

    @Test
    fun explicit_lifecycle_updates_activate_an_empty_catalog_without_fetching_payloads() = runTest {
        val stored = storedCatalog(10, listOf(entry("disabled", 1) to artifact("disabled", 1), entry("retired", 1) to artifact("retired", 1)))
        val lifecycleOnly = signedManifest(11, listOf(entry("disabled", 1, "disabled"), entry("retired", 1, "retired")), 10, listOf("removed"))
        val store = FakeCatalogStore(stored)
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(lifecycleOnly))
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(0, remote.sourceFetches)
        assertEquals(1, store.activationCount)
        assertEquals(11, manager.activeDocument().revision)
        assertTrue(manager.activeDocument().sources.isEmpty())
        assertEquals(UpdateState.Origin.REMOTE, manager.diagnostics.value.origin)
        assertEquals(2, manager.diagnostics.value.inactiveSourceCount)
        assertEquals(1, manager.diagnostics.value.removedSourceCount)
        assertTrue(manager.diagnostics.value.activeSources.isEmpty())
    }
}
