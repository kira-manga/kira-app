package me.manga.kira.sources.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.PreviousHostAuthority
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import me.manga.kira.sources.contracts.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lifecycle windows use the controlled port; actual Room atomicity is tested in the composition root. */
class EffectiveSourceSelectionLifecycleTest {
    @Test
    fun later_refresh_never_downgrades_a_valid_in_memory_catalog_to_bundle() = runTest {
        val store = FakeCatalogStore(active = null)
        val next = signedManifest(10, listOf(entry("floor", 1)), previousRevision = 5)
        val firstRemote = FakeRemote(SourceCatalogManifestResult.Modified(next), mapOf("floor" to artifact("floor", 1)))
        val manager = manager(store, firstRemote)
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(10, manager.activeDocument().revision)
        val before = assertNotNull(store.ready)
        store.simulateUnreadableActiveCatalog()
        firstRemote.manifestResult = SourceCatalogManifestResult.Unavailable
        assertTrue(manager.refresh() is AppResult.Success)
        assertEquals(10, manager.activeDocument().revision)
        assertEquals(before, store.ready)
    }

    @Test
    fun caller_cancellation_during_required_fetch_preserves_the_warm_catalog_and_floor() = runTest {
        val stored = storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1)))
        val store = FakeCatalogStore(active = stored)
        val remote = FakeRemote(SourceCatalogManifestResult.NotModified)
        val manager = manager(store, remote)
        assertTrue(manager.refresh() is AppResult.Success)
        val state = UpdateState.Active(10, UpdateState.Origin.CACHE)
        assertEquals(state, manager.state.value)
        val document = manager.activeDocument()
        val diagnostics = manager.diagnostics.value
        val floor = store.readAcceptanceFloor()
        val ready = store.ready
        val fetching = CompletableDeferred<Unit>()
        remote.manifestResult = SourceCatalogManifestResult.Modified(signedManifest(11, listOf(entry("a", 2)), 10))
        remote.beforeSourceFetch = { fetching.complete(Unit); awaitCancellation() }
        cancelRequiredFetch(manager, fetching)
        assertEquals(document, manager.activeDocument())
        assertEquals(diagnostics, manager.diagnostics.value)
        assertEquals(state, manager.state.value)
        assertEquals(stored, store.readActive())
        assertEquals(stored.manifest, store.readAcceptedManifest())
        assertEquals(floor, store.readAcceptanceFloor())
        assertEquals(0, store.activationCount)
        assertEquals(listOf("a"), remote.fetchedApis)
        assertEquals(ready, store.ready)
    }

    @Test
    fun missing_allocator_invalidates_a_warm_binding_without_fetching_or_fabricating_empty() = runTest {
        val store = FakeCatalogStore(null)
        val remote = FakeRemote(SourceCatalogManifestResult.Unavailable)
        val manager = manager(store, remote)
        assertIs<AppResult.Success<*>>(manager.refresh())
        val document = manager.activeDocument()
        store.nextGeneration = null
        assertIs<AppResult.Failure>(manager.refresh())
        assertNull(store.ready)
        assertEquals(document, manager.activeDocument())
        assertTrue(document.sources.isNotEmpty())
        assertEquals(1, remote.manifestFetches)
    }

    @Test
    fun changed_embedded_bundle_downgrades_lost_unsigned_proof_under_a_new_generation() = runTest {
        val store = FakeCatalogStore(null)
        val catalog = verifiedCatalogAt(10, "https://current.test", listOf("floor.test"))
        val stored = assertNotNull(catalog.stored)
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(stored.manifest), stored.sources.associateBy { it.api })
        assertIs<AppResult.Success<*>>(manager(store, remote).refresh())
        val before = assertNotNull(store.ready)
        assertEquals(PreviousHostAuthority.PROVEN_COMPATIBLE_ROOT, before.payload.rules.single().previousHosts.single().authority)
        val floor = store.readAcceptanceFloor()
        store.bundleJson = bundledJson().replace("floor.test", "updated-bundle.test")
        store.invalidateSelection() // A new process has no inherited process-verification binding.
        store.beforeCommit = { assertNull(store.ready) }
        val restarted = manager(store, FakeRemote(SourceCatalogManifestResult.Unavailable))
        assertIs<AppResult.Success<*>>(restarted.refresh())
        val after = assertNotNull(store.ready)
        assertEquals(before.token.identity, after.token.identity)
        assertTrue(after.token.generation > before.token.generation)
        assertNotEquals(before.token.payloadDigest, after.token.payloadDigest)
        assertEquals(PreviousHostAuthority.UNKNOWN, after.payload.rules.single().previousHosts.single().authority)
        assertEquals(floor, store.readAcceptanceFloor())
    }

    @Test
    fun cancellation_before_commit_never_publishes_the_private_candidate_and_later_refresh_recovers() = runTest {
        val store = FakeCatalogStore(null)
        val catalog = assertNotNull(verifiedCatalogAt(10, "https://current.test").stored)
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(catalog.manifest), catalog.sources.associateBy { it.api })
        val manager = manager(store, remote)
        store.beforeCommit = { if (it.advancesSignedFloor) throw CancellationException("before commit") }
        assertFailsWith<CancellationException> { manager.refresh() }
        assertEquals(BUNDLED_REVISION, manager.activeDocument().revision)
        assertEquals(BUNDLED_REVISION, assertNotNull(store.selection).token.identity.revision)
        assertNull(store.readAcceptanceFloor())
        assertNull(store.ready)
        store.beforeCommit = {}
        assertIs<AppResult.Success<*>>(manager.refresh())
        assertEquals(10, manager.activeDocument().revision)
        assertEquals(store.selection, store.ready)
    }

    @Test
    fun cancellation_after_commit_adopts_only_the_matching_verified_durable_candidate() = runTest {
        val store = FakeCatalogStore(null)
        val catalog = assertNotNull(verifiedCatalogAt(10, "https://current.test").stored)
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(catalog.manifest), catalog.sources.associateBy { it.api })
        val manager = manager(store, remote)
        store.afterCommit = { if (it.advancesSignedFloor) throw CancellationException("after commit") }
        assertEquals("after commit", assertFailsWith<CancellationException> { manager.refresh() }.message)
        assertEquals(10, manager.activeDocument().revision)
        assertEquals(UpdateState.Active(10, UpdateState.Origin.REMOTE), manager.state.value)
        assertEquals(store.selection, store.ready)
        assertEquals(2L, assertNotNull(store.ready).token.generation)
        assertEquals(10, store.readAcceptanceFloor()?.catalogRevision)
    }

    @Test
    fun later_winner_prevents_stale_cancellation_publication_and_is_adopted_on_retry() = runTest {
        val store = FakeCatalogStore(null)
        val catalog = assertNotNull(verifiedCatalogAt(10, "https://middle.test").stored)
        val remote = FakeRemote(SourceCatalogManifestResult.Modified(catalog.manifest), catalog.sources.associateBy { it.api })
        val manager = manager(store, remote)
        store.afterCommit = { candidate ->
            if (candidate.advancesSignedFloor) {
                store.afterCommit = {}
                val winner = selectionCandidate(verifiedCatalogAt(11, "https://latest.test"), candidate.payload, candidate.proofs, true)
                store.commitSelection(winner, assertNotNull(store.readSelection().expected))
                throw CancellationException("later winner")
            }
        }
        assertFailsWith<CancellationException> { manager.refresh() }
        assertEquals(11, assertNotNull(store.selection).token.identity.revision)
        assertEquals(BUNDLED_REVISION, manager.activeDocument().revision)
        assertNull(store.ready)
        assertIs<UpdateState.Failed>(manager.state.value)
        remote.manifestResult = SourceCatalogManifestResult.Unavailable
        assertIs<AppResult.Success<*>>(manager.refresh())
        assertEquals(11, manager.activeDocument().revision)
        assertEquals(store.selection, store.ready)
    }

    @Test
    fun selection_loss_uses_a_fresh_generation_instead_of_replaying_a_warm_token() = runTest {
        val store = FakeCatalogStore(storedCatalog(10, listOf(entry("floor", 1) to artifact("floor", 1))))
        val manager = manager(store, FakeRemote(SourceCatalogManifestResult.Unavailable))
        assertIs<AppResult.Success<*>>(manager.refresh())
        val before = assertNotNull(store.ready)
        store.loseSelectedPayload()
        assertIs<AppResult.Success<*>>(manager.refresh())
        val after = assertNotNull(store.ready)
        assertEquals(before.payload, after.payload)
        assertEquals(before.token.generation + 1, after.token.generation)
    }

    @Test
    fun a_newer_shipped_bundle_replaces_an_older_selected_signed_tier_without_lowering_the_floor() = runTest {
        val store = FakeCatalogStore(storedCatalog(10, listOf(entry("floor", 1) to artifact("floor", 1))))
        assertIs<AppResult.Success<*>>(manager(store, FakeRemote(SourceCatalogManifestResult.Unavailable)).refresh())
        val floor = store.readAcceptanceFloor()
        store.bundleJson = bundledJson().replace("\"revision\":5", "\"revision\":12")
        store.invalidateSelection()
        val restarted = manager(store, FakeRemote(SourceCatalogManifestResult.Unavailable))
        assertIs<AppResult.Success<*>>(restarted.refresh())
        assertEquals(12, restarted.activeDocument().revision)
        assertEquals(floor, store.readAcceptanceFloor())
        assertEquals(12, assertNotNull(store.ready).token.identity.revision)
    }

    @Test
    fun stale_preparation_cannot_reuse_the_first_generation_after_a_to_b_to_a() = runTest {
        val store = FakeCatalogStore(null)
        val bundle = SourceCatalogVerifier(store, FakeCatalogVerifier, SchemaOnlyValidator, {}).bundle()
        val a = selectionCandidate(bundle, null, emptyList(), false)
        val first = store.commitSelection(a, assertNotNull(store.readSelection().expected))
        val stale = assertNotNull(store.readSelection().expected)
        val b = selectionCandidate(verifiedCatalogAt(10, "https://other.test"), a.payload, a.proofs, true)
        store.commitSelection(b, stale)
        val third = store.commitSelection(a, assertNotNull(store.readSelection().expected))
        assertEquals(first.payload, third.payload)
        assertNotEquals(first.token, third.token)
        assertEquals(3L, third.token.generation)
        assertFailsWith<SourceSelectionUnavailable> { store.commitSelection(b, stale) }
        assertEquals(third, store.selection)
    }
}
