package me.manga.kira.sources.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.identity.SourceAliasReadiness
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SourceSelectionLimits
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** New coordinator/provider contracts only; schema4/P3b retain their separate migration/anchor matrices. */
class EffectiveSourceSelectionRoomTest {
    @Test
    fun fresh_execution_bootstrap_has_no_identity_authority_until_commit_and_adoption() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            val manager = f.manager()
            assertTrue(manager.activeDocument().sources.isNotEmpty())
            assertEquals(SourceAliasReadiness.NotReady, f.provider.readiness.first())
            assertNull(f.store.readSelection().selection)
            assertFailsWith<SourceSelectionUnavailable> { f.writer { f.provider.readInTransaction() } }
            assertIs<AppResult.Success<*>>(manager.refresh())
            val selected = assertNotNull(f.store.readSelection().selection)
            assertEquals(selected.token, f.writer { f.provider.readInTransaction().token })
            assertEquals(manager.activeDocument().sources.map { it.api }, selected.payload.rules.map { it.api })
        }
    }

    @Test
    fun queued_preparation_freezes_caller_owned_collections_before_entering_the_writer() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            assertIs<AppResult.Success<*>>(f.manager().refresh())
            val original = f.prepared.last()
            val sources = original.document.sources.toMutableList()
            val proofs = original.proofs.toMutableList()
            val hosts = original.document.sources.first().previousHosts.toMutableList()
            sources[0] = sources[0].copy(previousHosts = hosts)
            val candidate = original.copy(document = original.document.copy(sources = sources), proofs = proofs)
            f.migration.beforeWriter = { frozen ->
                sources.clear()
                proofs.clear()
                hosts += "not-verified.test"
                assertEquals(original.document, frozen.document)
                assertEquals(original.proofs, frozen.proofs)
            }
            val before = assertNotNull(f.store.readSelection().selection)
            assertEquals(before, f.store.commitSelection(candidate, assertNotNull(f.store.readSelection().expected)))
            var published = false
            assertEquals(before, f.store.adoptSelection(original) { published = true })
            assertTrue(published)
        }
    }

    @Test
    fun reopen_reverifies_real_signed_bytes_not_mutable_entries_and_lost_pins_select_bundle_without_lowering_floor() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            assertIs<AppResult.Success<*>>(f.manager(BootstrapCatalogRemote(BootstrapSignedCatalogFixture.load())).refresh())
            val selected = assertNotNull(f.store.readSelection().selection)
            assertEquals(SelectedCatalogKind.SIGNED, selected.token.identity.kind)
            f.execute("DELETE FROM source_catalog_entries")
            f.reopen()
            assertFailsWith<SourceSelectionUnavailable> { f.writer { f.provider.readInTransaction() } }
            assertIs<AppResult.Success<*>>(f.manager().refresh())
            assertEquals(selected, f.store.readSelection().selection)
            assertEquals(selected.token, f.writer { f.provider.readInTransaction().token })
            val floor = f.store.readAcceptanceFloor()
            f.keys = emptyMap()
            f.reopen()
            assertIs<AppResult.Success<*>>(f.manager().refresh())
            val fallback = assertNotNull(f.store.readSelection().selection)
            assertEquals(SelectedCatalogKind.BUNDLED, fallback.token.identity.kind)
            assertTrue(fallback.token.generation > selected.token.generation)
            assertEquals(floor, f.store.readAcceptanceFloor())
        }
    }

    @Test
    fun verified_warm_evidence_survives_cache_damage_but_reopen_cannot_authenticate_the_damaged_artifact() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            val remote = BootstrapCatalogRemote(BootstrapSignedCatalogFixture.load())
            val manager = f.manager(remote)
            assertIs<AppResult.Success<*>>(manager.refresh())
            val selected = assertNotNull(f.store.readSelection().selection)
            f.execute("UPDATE source_revision_artifacts SET rawPayload = rawPayload || ' '")
            remote.deliverManifest = false
            // A separate no-network manager would be cold; the same manager retains exact verified evidence.
            assertIs<AppResult.Success<*>>(manager.refresh())
            assertEquals(selected, f.store.readSelection().selection)
            assertEquals(selected.token, f.writer { f.provider.readInTransaction().token })
            f.reopen()
            assertIs<AppResult.Success<*>>(f.manager().refresh())
            val fallback = assertNotNull(f.store.readSelection().selection)
            assertEquals(SelectedCatalogKind.BUNDLED, fallback.token.identity.kind)
            assertTrue(fallback.token.generation > selected.token.generation)
            assertEquals(selected.token.identity.revision, f.store.readAcceptanceFloor()?.catalogRevision)
        }
    }

    @Test
    fun durable_evidence_metadata_bounds_count_bytes_after_embedded_nuls_before_materialization() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            assertIs<AppResult.Success<*>>(f.manager(BootstrapCatalogRemote(BootstrapSignedCatalogFixture.load())).refresh())
            val artifact = assertNotNull(f.prepared.last().catalog).sources.first()
            f.execute("UPDATE source_revision_artifacts SET canonVersion = 'kcj-1' || char(0) || hex(zeroblob(1024))")
            assertNull(f.store.findSource(artifact.api, artifact.sourceRevision, artifact.checksum))
            f.execute("UPDATE source_catalog_manifests SET signingKeyId = 'test' || char(0) || hex(zeroblob(1024))")
            assertNull(f.store.readAcceptedManifest())
            assertNotNull(f.store.readAcceptanceFloor())
        }
    }

    @Test
    fun authenticated_empty_is_ready_and_a_to_b_to_a_never_reuses_a_generation_or_stale_preparation() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            assertIs<AppResult.Success<*>>(f.manager().refresh())
            val a = f.prepared.last()
            val first = assertNotNull(f.store.readSelection().selection)
            val stale = assertNotNull(f.store.readSelection().expected)
            assertIs<AppResult.Success<*>>(f.manager(emptySelectionRemote()).refresh())
            val b = f.prepared.last()
            val empty = assertNotNull(f.store.readSelection().selection)
            assertTrue(empty.payload.rules.isEmpty())
            assertEquals(empty.token, f.writer { f.provider.readInTransaction().token })
            val third = f.store.commitSelection(a, assertNotNull(f.store.readSelection().expected))
            assertEquals(first.payload, third.payload)
            assertNotEquals(first.token, third.token)
            assertEquals(first.token.generation + 2, third.token.generation)
            assertFailsWith<SourceSelectionUnavailable> { f.store.commitSelection(b, stale) }
            assertFailsWith<SourceSelectionUnavailable> { f.writer { f.provider.readInTransaction() } }
            assertEquals(third, f.store.adoptSelection(a) {})
            assertEquals(third.token, f.writer { f.provider.readInTransaction().token })
        }
    }

    @Test
    fun even_an_empty_signed_candidate_rejects_a_conflicting_immutable_accepted_manifest() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            assertIs<AppResult.Success<*>>(f.manager().refresh())
            val a = f.prepared.last()
            assertIs<AppResult.Success<*>>(f.manager(emptySelectionRemote()).refresh())
            val empty = f.prepared.last()
            f.store.commitSelection(a, assertNotNull(f.store.readSelection().expected))
            f.execute("UPDATE source_catalog_manifests SET rawPayload = rawPayload || ' '")
            val before = f.state()
            assertFailsWith<IllegalArgumentException> { f.store.commitSelection(empty, assertNotNull(f.store.readSelection().expected)) }
            assertEquals(before, f.state())
        }
    }

    @Test
    fun a_late_selected_record_failure_rolls_back_artifacts_manifest_floor_projection_and_allocator_together() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            assertIs<AppResult.Success<*>>(f.manager().refresh())
            f.db.sourcesDao().insert(SourcesEntity("fixture-sentinel", priority = 99, language = "en", imageBaseUrl = "", imageUrlVersion = 0))
            val before = f.state()
            f.execute("CREATE TRIGGER fixture_fail_selection BEFORE INSERT ON effective_source_selection " +
                "WHEN NEW.generation = 2 BEGIN SELECT RAISE(ABORT, 'fixture late failure'); END")
            assertIs<AppResult.Failure>(f.manager(BootstrapCatalogRemote(BootstrapSignedCatalogFixture.load())).refresh())
            assertEquals(before, f.state())
            assertFailsWith<SourceSelectionUnavailable> { f.writer { f.provider.readInTransaction() } }
        }
    }

    @Test
    fun strict_participant_failure_rolls_back_its_own_prior_writes_and_never_adopts_a_candidate() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            assertIs<AppResult.Success<*>>(f.manager().refresh())
            val before = f.state()
            f.migration.insideWriter = { candidate ->
                if (candidate.advancesSignedFloor) {
                    f.execute("DELETE FROM sources")
                    error("fixture strict rejection")
                }
            }
            assertIs<AppResult.Failure>(f.manager(emptySelectionRemote()).refresh())
            assertEquals(before, f.state())
            assertFailsWith<SourceSelectionUnavailable> { f.writer { f.provider.readInTransaction() } }
        }
    }

    @Test
    fun original_caller_cancellation_inside_selection_writer_rolls_back_before_publication() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            assertIs<AppResult.Success<*>>(f.manager().refresh())
            val before = f.state()
            var enteredWriter = false
            val refresh = launch {
                val caller = currentCoroutineContext().job
                f.migration.insideWriter = { candidate ->
                    if (candidate.advancesSignedFloor) {
                        enteredWriter = true
                        f.execute("DELETE FROM sources")
                        caller.cancel(CancellationException("original selection caller cancelled"))
                    }
                }
                f.manager(emptySelectionRemote()).refresh()
            }
            refresh.join()
            assertTrue(enteredWriter)
            assertTrue(refresh.isCancelled)
            assertEquals(before, f.state())
            f.reopen()
            assertEquals(before, f.state())
        }
    }

    @Test
    fun cancellation_after_the_real_commit_reconciles_the_durable_candidate_without_old_state_restoration() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            val manager = f.manager(emptySelectionRemote())
            f.migration.afterCommit = { if (it.advancesSignedFloor) throw CancellationException("after real commit") }
            val cancelled = assertFailsWith<CancellationException> {
                // Reconciliation's bounded timeout must use real time, like Room's Default I/O;
                // runTest otherwise advances its virtual deadline while that I/O is still pending.
                withContext(Dispatchers.Default) { manager.refresh() }
            }
            assertEquals("after real commit", cancelled.message)
            val selected = assertNotNull(f.store.readSelection().selection)
            assertEquals(SelectedCatalogKind.SIGNED, selected.token.identity.kind)
            assertTrue(manager.activeDocument().sources.isEmpty())
            assertEquals(selected.token, f.writer { f.provider.readInTransaction().token })
            assertEquals(selected.token.identity.revision, f.store.readAcceptanceFloor()?.catalogRevision)
        }
    }

    @Test
    fun provider_reads_caller_writer_state_but_adoption_cannot_publish_from_a_rollbackable_outer_transaction() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            assertIs<AppResult.Success<*>>(f.manager().refresh())
            val before = f.state()
            assertFailsWith<FixtureRollback> {
                f.writer {
                    val dao = f.db.effectiveSourceSelectionDao()
                    assertEquals(1, dao.advance(2, 3))
                    dao.setSelection(assertNotNull(before.selected).copy(generation = 2))
                    assertFailsWith<SourceSelectionUnavailable> { f.provider.readInTransaction() }
                    var published = false
                    assertFailsWith<SourceSelectionUnavailable> { f.store.adoptSelection(f.prepared.last()) { published = true } }
                    assertFalse(published)
                    throw FixtureRollback()
                }
            }
            assertEquals(before, f.state())
            assertEquals(1L, f.writer { f.provider.readInTransaction().token.generation })
        }
    }

    @Test
    fun readiness_stream_invalidates_for_loss_same_token_readoption_and_uncoupled_allocator_changes() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            assertIs<AppResult.Success<*>>(f.manager().refresh())
            val candidate = f.prepared.last()
            val ready = SourceAliasReadiness.Ready(assertNotNull(f.store.readSelection().selection).token)
            val events = Channel<SourceAliasReadiness>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { f.provider.readiness.collect { events.send(it) } }
            try {
                events.await(ready)
                f.store.invalidateSelection()
                events.await(SourceAliasReadiness.NotReady)
                f.store.adoptSelection(candidate) {}
                events.await(ready)
                f.writer { assertEquals(1, f.db.effectiveSourceSelectionDao().advance(2, 3)) }
                events.await(SourceAliasReadiness.NotReady)
                assertFailsWith<SourceSelectionUnavailable> { f.writer { f.provider.readInTransaction() } }
            } finally { collector.cancelAndJoin(); events.close() }
        }
    }

    @Test
    fun corrupt_or_lost_payload_repairs_with_a_fresh_generation_but_a_lost_allocator_never_reseeds() = runTest {
        EffectiveSourceSelectionRoomFixture().use { f ->
            val manager = f.manager()
            assertIs<AppResult.Success<*>>(manager.refresh())
            f.execute("UPDATE effective_source_selection SET payload = '{}' ")
            assertNotNull(f.db.effectiveSourceSelectionDao().boundedSelection(SourceSelectionLimits.PAYLOAD_BYTES))
            assertNull(f.store.readSelection().selection)
            assertFailsWith<SourceSelectionUnavailable> { f.writer { f.provider.readInTransaction() } }
            assertIs<AppResult.Success<*>>(manager.refresh())
            assertEquals(2L, f.writer { f.provider.readInTransaction().token.generation })
            f.execute("DELETE FROM effective_source_selection")
            assertIs<AppResult.Success<*>>(manager.refresh())
            assertEquals(3L, f.writer { f.provider.readInTransaction().token.generation })
            f.execute("DELETE FROM source_selection_generation")
            assertIs<AppResult.Failure>(manager.refresh())
            assertFailsWith<SourceSelectionUnavailable> { f.writer { f.provider.readInTransaction() } }
            assertEquals(0L, f.number("SELECT count(*) FROM source_selection_generation"))
        }
    }
}

private class FixtureRollback : IllegalStateException()

private suspend fun ReceiveChannel<SourceAliasReadiness>.await(expected: SourceAliasReadiness) = withContext(Dispatchers.Default) {
    withTimeout(5_000) { while (receive() != expected) Unit }
}
