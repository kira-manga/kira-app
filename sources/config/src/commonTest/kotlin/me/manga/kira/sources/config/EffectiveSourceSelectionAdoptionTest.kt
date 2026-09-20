package me.manga.kira.sources.config

import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.CommittedSourceSelection
import me.manga.kira.sources.contracts.PreviousHostAuthority
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.VerifiedSourceSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Controlled manager routing; fake storage/digests are not Room, artifact-liveness or native proof. */
class EffectiveSourceSelectionAdoptionTest {
    @Test
    fun cold_exact_selection_is_readopted_without_calling_a_denied_commit() =
        runTest {
            val catalog = verifiedCatalogAt(10, "https://current.test")
            val store = FakeCatalogStore(assertNotNull(catalog.stored))
            val remote = FakeRemote(SourceCatalogManifestResult.Unavailable)
            assertIs<AppResult.Success<*>>(manager(store, remote).refresh())
            val durable = assertNotNull(store.ready)
            val nextGeneration = store.nextGeneration
            val historicalReads = store.historicalReads
            store.invalidateSelection()
            var commits = 0
            store.beforeCommit = {
                commits++
                throw SourceSelectionUnavailable("migration denied")
            }
            val restarted = manager(store, remote)
            var adoptions = 0
            store.beforeAdopt = {
                adoptions++
                assertEquals(durable.payload, it.payload)
                assertEquals(BUNDLED_REVISION, restarted.activeDocument().revision)
                assertNull(store.ready)
            }

            assertIs<AppResult.Success<*>>(restarted.refresh())

            assertEquals(0, commits)
            assertEquals(1, adoptions)
            assertTrue(store.historicalReads > historicalReads, "cold preparation must revisit signed evidence")
            assertEquals(durable, store.selection)
            assertEquals(durable, store.ready)
            assertEquals(nextGeneration, store.nextGeneration)
            assertEquals(0, store.activationCount)
            assertEquals(0, store.bundleProjectionCount)
            assertEquals(catalog.document, restarted.activeDocument())
            assertEquals(UpdateState.Active(10, UpdateState.Origin.CACHE), restarted.state.value)
        }

    @Test
    fun warm_exact_selection_is_readopted_without_calling_a_denied_commit() =
        runTest {
            val store = FakeCatalogStore(null)
            val remote = FakeRemote(SourceCatalogManifestResult.Unavailable)
            val subject = manager(store, remote)
            assertIs<AppResult.Success<*>>(subject.refresh())
            val durable = assertNotNull(store.ready)
            val document = subject.activeDocument()
            val nextGeneration = store.nextGeneration
            val projections = store.bundleProjectionCount
            store.invalidateSelection()
            var commits = 0
            store.beforeCommit = {
                commits++
                throw SourceSelectionUnavailable("migration denied")
            }
            var adoptions = 0
            store.beforeAdopt = { adoptions++ }

            assertIs<AppResult.Success<*>>(subject.refresh())

            assertEquals(0, commits)
            assertEquals(1, adoptions)
            assertEquals(durable, store.selection)
            assertEquals(durable, store.ready)
            assertEquals(nextGeneration, store.nextGeneration)
            assertEquals(projections, store.bundleProjectionCount)
            assertEquals(document, subject.activeDocument())
            assertEquals(2, remote.manifestFetches)
        }

    @Test
    fun changed_proof_payload_at_same_identity_cannot_skip_a_denied_commit() =
        runTest {
            val store = FakeCatalogStore(null)
            val catalog = verifiedCatalogAt(10, "https://current.test", listOf("floor.test"))
            val stored = assertNotNull(catalog.stored)
            val artifacts = stored.sources.associateBy { it.api }
            val initialRemote = FakeRemote(SourceCatalogManifestResult.Modified(stored.manifest), artifacts)
            assertIs<AppResult.Success<*>>(manager(store, initialRemote).refresh())
            val durable = assertNotNull(store.ready)
            assertEquals(
                PreviousHostAuthority.PROVEN_COMPATIBLE_ROOT,
                durable.payload.rules
                    .single()
                    .previousHosts
                    .single()
                    .authority,
            )
            val nextGeneration = store.nextGeneration
            val floor = store.readAcceptanceFloor()
            store.bundleJson = bundledJson().replace("floor.test", "updated-bundle.test")
            store.invalidateSelection()
            var attempted: VerifiedSourceSelection? = null
            var commits = 0
            store.beforeCommit = {
                attempted = it
                commits++
                throw SourceSelectionUnavailable("migration denied")
            }
            val remote = FakeRemote(SourceCatalogManifestResult.Unavailable)
            val restarted = manager(store, remote)

            assertIs<AppResult.Failure>(restarted.refresh())

            val candidate = assertNotNull(attempted)
            assertEquals(durable.token.identity, candidate.payload.identity)
            assertNotEquals(durable.payload, candidate.payload)
            val host =
                candidate.payload.rules
                    .single()
                    .previousHosts
                    .single()
            assertEquals(PreviousHostAuthority.UNKNOWN, host.authority)
            assertNull(host.proof)
            assertEquals(1, commits)
            assertEquals(durable, store.selection)
            assertNull(store.ready)
            assertEquals(nextGeneration, store.nextGeneration)
            assertEquals(floor, store.readAcceptanceFloor())
            assertEquals(BUNDLED_REVISION, restarted.activeDocument().revision)
            assertEquals(0, remote.manifestFetches)
        }

    @Test
    fun missing_selected_payload_cannot_publish_when_commit_is_denied() =
        runTest {
            val store = FakeCatalogStore(null)
            val remote = FakeRemote(SourceCatalogManifestResult.Unavailable)
            val subject = manager(store, remote)
            assertIs<AppResult.Success<*>>(subject.refresh())
            val document = subject.activeDocument()
            val nextGeneration = store.nextGeneration
            store.loseSelectedPayload()
            var commits = 0
            store.beforeCommit = {
                commits++
                throw SourceSelectionUnavailable("migration denied")
            }
            var adoptions = 0
            store.beforeAdopt = { adoptions++ }

            assertIs<AppResult.Failure>(subject.refresh())

            assertEquals(1, adoptions)
            assertEquals(1, commits)
            assertNull(store.selection)
            assertNull(store.ready)
            assertEquals(nextGeneration, store.nextGeneration)
            assertEquals(document, subject.activeDocument())
            assertEquals(1, remote.manifestFetches)
        }

    @Test
    fun missing_allocator_denies_adoption_before_remote_fetch() =
        runTest {
            val store = FakeCatalogStore(null)
            val remote = FakeRemote(SourceCatalogManifestResult.Unavailable)
            val subject = manager(store, remote)
            assertIs<AppResult.Success<*>>(subject.refresh())
            val durable = assertNotNull(store.ready)
            val document = subject.activeDocument()
            store.nextGeneration = null
            var commits = 0
            store.beforeCommit = {
                commits++
                throw SourceSelectionUnavailable("migration denied")
            }
            var adoptions = 0
            store.beforeAdopt = { adoptions++ }

            assertIs<AppResult.Failure>(subject.refresh())

            assertEquals(0, adoptions)
            assertEquals(0, commits)
            assertNull(store.ready)
            assertNull(store.nextGeneration)
            assertEquals(durable, store.selection)
            assertEquals(document, subject.activeDocument())
            assertEquals(1, remote.manifestFetches)
        }

    @Test
    fun adoption_failure_invalidates_readiness_without_falling_through_to_commit() =
        runTest {
            val store = FakeCatalogStore(null)
            val remote = FakeRemote(SourceCatalogManifestResult.Unavailable)
            val subject = manager(store, remote)
            assertIs<AppResult.Success<*>>(subject.refresh())
            val durable = assertNotNull(store.ready)
            val document = subject.activeDocument()
            val nextGeneration = store.nextGeneration
            var commits = 0
            store.beforeCommit = {
                commits++
                throw SourceSelectionUnavailable("migration denied")
            }
            var adoptions = 0
            store.beforeAdopt = {
                adoptions++
                // This must invalidate via pending, not the special SourceSelectionUnavailable branch.
                error("adoption unavailable")
            }

            assertIs<AppResult.Failure>(subject.refresh())

            assertEquals(1, adoptions)
            assertEquals(0, commits)
            assertNull(store.ready)
            assertEquals(durable, store.selection)
            assertEquals(nextGeneration, store.nextGeneration)
            assertEquals(document, subject.activeDocument())
            assertEquals(1, remote.manifestFetches)
            assertIs<UpdateState.Failed>(subject.state.value)
        }

    @Test
    fun a_to_b_to_a_uses_the_current_adoption_receipt_not_the_retained_generation() =
        runTest {
            val catalog = verifiedCatalogAt(10, "https://a.test")
            val store = FakeCatalogStore(assertNotNull(catalog.stored))
            val subject = manager(store, FakeRemote(SourceCatalogManifestResult.Unavailable))
            assertIs<AppResult.Success<*>>(subject.refresh())
            val first = assertNotNull(store.ready)
            val a = selectionCandidate(catalog, null, emptyList(), false)
            val b = selectionCandidate(verifiedCatalogAt(11, "https://b.test"), null, emptyList(), false)
            assertEquals(first.payload, a.payload)
            var commits = 0
            val denyCommit: suspend (VerifiedSourceSelection) -> Unit = {
                commits++
                throw SourceSelectionUnavailable("migration denied")
            }
            store.beforeCommit = denyCommit
            var current: CommittedSourceSelection? = null
            store.beforeAdopt = {
                // Interleave later durable winners after preparation, before this controlled adoption.
                store.beforeAdopt = {}
                store.beforeCommit = {}
                try {
                    store.commitSelection(b, assertNotNull(store.readSelection().expected))
                    current = store.commitSelection(a, assertNotNull(store.readSelection().expected))
                } finally {
                    store.beforeCommit = denyCommit
                }
            }

            assertIs<AppResult.Success<*>>(subject.refresh())

            val latest = assertNotNull(current)
            assertEquals(first.payload, latest.payload)
            assertEquals(first.token.generation + 2, latest.token.generation)
            assertNotEquals(first.token, latest.token)
            assertEquals(latest, store.selection)
            assertEquals(latest, store.ready)
            val historicalReads = store.historicalReads
            assertIs<AppResult.Success<*>>(subject.refresh())
            assertEquals(0, commits)
            assertEquals(
                historicalReads,
                store.historicalReads,
                "the returned receipt must support warm verified reuse",
            )
            assertEquals(latest, store.ready)
            assertEquals(catalog.document, subject.activeDocument())
        }
}
