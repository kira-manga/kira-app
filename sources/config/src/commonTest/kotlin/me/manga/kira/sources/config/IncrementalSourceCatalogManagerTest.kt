package me.manga.kira.sources.config

import kotlinx.coroutines.test.runTest
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.ConfigSignatureMetadata
import me.manga.kira.sources.contracts.RemoteSourceCatalog
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogAcceptanceFloor
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceCatalogSignatureVerifier
import me.manga.kira.sources.contracts.SourceCatalogStore
import me.manga.kira.sources.contracts.SourceConfigValidator
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import me.manga.kira.sources.contracts.StoredSourceCatalog
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.ValidationResult
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncrementalSourceCatalogManagerTest {
    @Test
    fun diagnostics_start_on_the_bundled_catalog_without_inventing_source_revisions() {
        val manager = manager(FakeCatalogStore(active = null), FakeRemote(SourceCatalogManifestResult.Unavailable))

        val diagnostics = manager.diagnostics.value

        assertEquals(UpdateState.Origin.BUNDLED, diagnostics.origin)
        assertEquals(BUNDLED_REVISION, diagnostics.catalogRevision)
        assertEquals(listOf("floor"), diagnostics.activeSources.map { it.api })
        assertEquals(null, diagnostics.activeSources.single().sourceRevision)
        assertEquals(null, diagnostics.manifestChecksum)
        assertEquals(0, diagnostics.removedSourceCount)
        assertEquals(0, diagnostics.inactiveSourceCount)
    }

    @Test
    fun unavailable_remote_atomically_projects_the_complete_bundle() =
        runTest {
            val store = FakeCatalogStore(active = null)
            val manager = manager(store, FakeRemote(SourceCatalogManifestResult.Unavailable))

            assertTrue(manager.refresh() is AppResult.Success)
            assertEquals(1, store.bundleProjectionCount)
            assertEquals(BUNDLED_REVISION, manager.activeDocument().revision)
        }

    @Test
    fun notModified_downloads_no_source_payloads() =
        runTest {
            val stored = storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1)))
            val store = FakeCatalogStore(active = stored)
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
    fun changed_manifest_downloads_only_missing_revision_and_activates_once() =
        runTest {
            val cachedA = artifact("a", 1)
            val cachedB = artifact("b", 1)
            val stored =
                storedCatalog(
                    revision = 10,
                    entries = listOf(entry("a", 1) to cachedA, entry("b", 1) to cachedB),
                )
            val nextEntries = listOf(entry("a", 1), entry("b", 2))
            val store = FakeCatalogStore(active = stored)
            val remote =
                FakeRemote(
                    manifestResult =
                        SourceCatalogManifestResult.Modified(
                            signedManifest(11, nextEntries, previousRevision = 10),
                        ),
                    artifacts = mapOf("b" to artifact("b", 2)),
                )
            val manager = manager(store, remote)

            val result = manager.refresh()

            assertTrue(result is AppResult.Success)
            assertEquals(listOf("b"), remote.fetchedApis)
            assertEquals(1, store.activationCount)
            assertEquals(11, manager.activeDocument().revision)
            assertEquals(UpdateState.Origin.REMOTE, manager.diagnostics.value.origin)
            assertEquals(11, manager.diagnostics.value.catalogRevision)
            assertEquals(
                listOf(1L, 2L),
                manager.diagnostics.value.activeSources.map { it.sourceRevision },
            )
            assertEquals("test-key", manager.diagnostics.value.manifestSigningKeyId)
            assertEquals("Ed25519", manager.diagnostics.value.signatureAlgorithm)
            assertEquals(10, manager.diagnostics.value.previousCatalogRevision)
            assertEquals(checksum(10), manager.diagnostics.value.previousCatalogChecksum)
        }

    @Test
    fun non_generic_manifest_entry_is_rejected_without_fetch_or_activation() =
        runTest {
            val stored = storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1)))
            val store = FakeCatalogStore(active = stored)
            val next = signedManifest(11, listOf(entry("a", 2).copy(engine = "legacy")), previousRevision = 10)
            val remote = FakeRemote(SourceCatalogManifestResult.Modified(next))
            val manager = manager(store, remote)

            assertTrue(manager.refresh() is AppResult.Success)
            assertEquals(0, remote.sourceFetches)
            assertEquals(0, store.activationCount)
            assertEquals(10, manager.activeDocument().revision)
        }

    @Test
    fun previously_known_source_requires_an_explicit_removed_tombstone() =
        runTest {
            val stored =
                storedCatalog(
                    10,
                    listOf(
                        entry("a", 1) to artifact("a", 1),
                        entry("b", 1) to artifact("b", 1),
                    ),
                )
            val store = FakeCatalogStore(active = stored)
            val next = signedManifest(11, listOf(entry("a", 1)), previousRevision = 10)
            val remote = FakeRemote(SourceCatalogManifestResult.Modified(next))
            val manager = manager(store, remote)

            assertTrue(manager.refresh() is AppResult.Success)
            assertEquals(0, remote.sourceFetches)
            assertEquals(0, store.activationCount)
            assertEquals(10, manager.activeDocument().revision)
        }

    @Test
    fun explicit_removed_tombstone_activates_without_downloading_payloads() =
        runTest {
            val stored = storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1)))
            val store = FakeCatalogStore(active = stored)
            val next =
                signedManifest(
                    revision = 11,
                    entries = emptyList(),
                    previousRevision = 10,
                    removedApis = listOf("a"),
                )
            val remote = FakeRemote(SourceCatalogManifestResult.Modified(next))
            val manager = manager(store, remote)

            assertTrue(manager.refresh() is AppResult.Success)
            assertEquals(0, remote.sourceFetches)
            assertEquals(1, store.activationCount)
            assertTrue(manager.activeDocument().sources.isEmpty())
        }

    @Test
    fun lower_per_source_revision_is_rejected_as_a_rollback() =
        runTest {
            val stored = storedCatalog(10, listOf(entry("a", 2) to artifact("a", 2)))
            val store = FakeCatalogStore(active = stored)
            val next = signedManifest(11, listOf(entry("a", 1)), previousRevision = 10)
            val remote = FakeRemote(SourceCatalogManifestResult.Modified(next))
            val manager = manager(store, remote)

            assertTrue(manager.refresh() is AppResult.Success)
            assertEquals(0, remote.sourceFetches)
            assertEquals(0, store.activationCount)
            assertEquals(10, manager.activeDocument().revision)
        }

    @Test
    fun failed_required_download_never_activates_partial_catalog() =
        runTest {
            val stored = storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1)))
            val store = FakeCatalogStore(active = stored)
            val next = signedManifest(11, listOf(entry("a", 2)), previousRevision = 10)
            val remote = FakeRemote(SourceCatalogManifestResult.Modified(next))
            val manager = manager(store, remote)

            assertTrue(manager.refresh() is AppResult.Failure)
            assertEquals(0, store.activationCount)
            assertEquals(10, manager.activeDocument().revision)
            assertEquals(UpdateState.Origin.CACHE, manager.diagnostics.value.origin)
            assertEquals(10, manager.diagnostics.value.catalogRevision)
            assertTrue(manager.state.value is UpdateState.Failed)
        }

    @Test
    fun durable_floor_blocks_replay_even_when_cached_catalog_is_unreadable() =
        runTest {
            val store =
                FakeCatalogStore(
                    active = null,
                    floor = SourceCatalogAcceptanceFloor(20, checksum(20)),
                )
            val replay = signedManifest(19, listOf(entry("a", 1)), previousRevision = 18)
            val remote = FakeRemote(SourceCatalogManifestResult.Modified(replay))
            val manager = manager(store, remote)

            assertTrue(manager.refresh() is AppResult.Success)
            assertEquals(0, remote.sourceFetches)
            assertEquals(0, store.activationCount)
            assertEquals(BUNDLED_REVISION, manager.activeDocument().revision)
        }

    @Test
    fun matching_floor_revision_repairs_an_unreadable_cached_catalog() =
        runTest {
            val store =
                FakeCatalogStore(
                    active = null,
                    floor = SourceCatalogAcceptanceFloor(20, checksum(20)),
                )
            val repair = signedManifest(20, listOf(entry("a", 2)), previousRevision = 19)
            val remote =
                FakeRemote(
                    SourceCatalogManifestResult.Modified(repair),
                    artifacts = mapOf("a" to artifact("a", 2)),
                )
            val manager = manager(store, remote)

            assertTrue(manager.refresh() is AppResult.Success)
            assertEquals(listOf("a"), remote.fetchedApis)
            assertEquals(1, store.activationCount)
            assertEquals(20, manager.activeDocument().revision)
        }

    @Test
    fun higher_revision_fails_closed_when_durable_manifest_baseline_is_unreadable() =
        runTest {
            val store =
                FakeCatalogStore(
                    active = null,
                    floor = SourceCatalogAcceptanceFloor(20, checksum(20)),
                )
            val next = signedManifest(21, listOf(entry("a", 3)), previousRevision = 20)
            val remote = FakeRemote(SourceCatalogManifestResult.Modified(next))
            val manager = manager(store, remote)

            assertTrue(manager.refresh() is AppResult.Success)
            assertEquals(0, remote.sourceFetches)
            assertEquals(0, store.activationCount)
            assertEquals(BUNDLED_REVISION, manager.activeDocument().revision)
        }

    @Test
    fun previously_removed_source_cannot_be_reintroduced() =
        runTest {
            val stored =
                storedCatalog(
                    revision = 10,
                    entries = emptyList(),
                    removedApis = listOf("a"),
                )
            val store = FakeCatalogStore(active = stored)
            val next = signedManifest(11, listOf(entry("a", 2)), previousRevision = 10)
            val remote = FakeRemote(SourceCatalogManifestResult.Modified(next))
            val manager = manager(store, remote)

            assertTrue(manager.refresh() is AppResult.Success)
            assertEquals(0, remote.sourceFetches)
            assertEquals(0, store.activationCount)
            assertTrue(manager.activeDocument().sources.isEmpty())
        }

    @Test
    fun later_refresh_never_downgrades_a_valid_in_memory_catalog_to_bundle() =
        runTest {
            val store = FakeCatalogStore(active = null)
            val next = signedManifest(10, listOf(entry("floor", 1)), previousRevision = 5)
            val firstRemote =
                FakeRemote(
                    SourceCatalogManifestResult.Modified(next),
                    artifacts = mapOf("floor" to artifact("floor", 1)),
                )
            val manager = manager(store, firstRemote)
            assertTrue(manager.refresh() is AppResult.Success)
            assertEquals(10, manager.activeDocument().revision)

            store.simulateUnreadableActiveCatalog()
            firstRemote.manifestResult = SourceCatalogManifestResult.Unavailable

            assertTrue(manager.refresh() is AppResult.Success)
            assertEquals(10, manager.activeDocument().revision)
        }

    @Test
    fun activation_failure_keeps_complete_last_known_good_catalog() =
        runTest {
            val stored = storedCatalog(10, listOf(entry("a", 1) to artifact("a", 1)))
            val store = FakeCatalogStore(active = stored, failActivation = true)
            val next = signedManifest(11, listOf(entry("a", 2)), previousRevision = 10)
            val remote =
                FakeRemote(
                    SourceCatalogManifestResult.Modified(next),
                    artifacts = mapOf("a" to artifact("a", 2)),
                )
            val manager = manager(store, remote)

            assertTrue(manager.refresh() is AppResult.Failure)
            assertEquals(10, manager.activeDocument().revision)
            assertEquals(UpdateState.Origin.CACHE, manager.diagnostics.value.origin)
            assertEquals(10, manager.diagnostics.value.catalogRevision)
            assertTrue(manager.state.value is UpdateState.Failed)
        }

    @Test
    fun explicit_lifecycle_updates_activate_an_empty_catalog_without_fetching_payloads() =
        runTest {
            val stored =
                storedCatalog(
                    revision = 10,
                    entries =
                        listOf(
                            entry("disabled", 1) to artifact("disabled", 1),
                            entry("retired", 1) to artifact("retired", 1),
                        ),
                )
            val lifecycleOnly =
                signedManifest(
                    revision = 11,
                    entries =
                        listOf(
                            entry("disabled", 1, lifecycle = "disabled"),
                            entry("retired", 1, lifecycle = "retired"),
                        ),
                    previousRevision = 10,
                    removedApis = listOf("removed"),
                )
            val store = FakeCatalogStore(active = stored)
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

    private fun manager(
        store: FakeCatalogStore,
        remote: FakeRemote,
    ) = IncrementalSourceCatalogManager(
        store = store,
        verifier = FakeCatalogVerifier,
        validator = SchemaOnlyValidator,
        remote = remote,
    )

    private class FakeCatalogStore(
        private var active: StoredSourceCatalog?,
        floor: SourceCatalogAcceptanceFloor? = active?.let {
            SourceCatalogAcceptanceFloor(it.manifest.metadata.revision, it.manifest.metadata.checksum)
        },
        private val failActivation: Boolean = false,
    ) : SourceCatalogStore {
        private var acceptedManifest = active?.manifest
        private val revisionArtifacts =
            active?.sources?.associateBy { Triple(it.api, it.sourceRevision, it.checksum) }?.toMutableMap()
                ?: mutableMapOf()
        private var acceptanceFloor = floor
        var activationCount = 0
            private set
        var bundleProjectionCount = 0
            private set

        override fun readBundled(): String = bundledJson()

        override suspend fun projectBundled(document: SourceConfigDocument) {
            bundleProjectionCount++
        }

        override suspend fun readActive(): StoredSourceCatalog? = active

        override suspend fun readAcceptanceFloor(): SourceCatalogAcceptanceFloor? = acceptanceFloor

        override suspend fun readAcceptedManifest(): SignedSourceCatalogManifest? = acceptedManifest

        override suspend fun findSource(
            api: String,
            sourceRevision: Long,
            checksum: String,
        ): SourceRevisionArtifact? = revisionArtifacts[Triple(api, sourceRevision, checksum)]

        override suspend fun activate(catalog: StoredSourceCatalog) {
            if (failActivation) error("simulated projection failure")
            activationCount++
            active = catalog
            acceptedManifest = catalog.manifest
            catalog.sources.forEach {
                revisionArtifacts[Triple(it.api, it.sourceRevision, it.checksum)] = it
            }
            acceptanceFloor =
                SourceCatalogAcceptanceFloor(
                    catalog.manifest.metadata.revision,
                    catalog.manifest.metadata.checksum,
                )
        }

        fun simulateUnreadableActiveCatalog() {
            active = null
        }
    }

    private class FakeRemote(
        var manifestResult: SourceCatalogManifestResult,
        private val artifacts: Map<String, SourceRevisionArtifact> = emptyMap(),
    ) : RemoteSourceCatalog {
        var sourceFetches = 0
            private set
        val fetchedApis = mutableListOf<String>()

        override suspend fun fetchManifest(etag: String?): SourceCatalogManifestResult = manifestResult

        override suspend fun fetchSource(entry: SourceCatalogEntry): SourceRevisionArtifact {
            sourceFetches++
            fetchedApis += entry.api
            return requireNotNull(artifacts[entry.api])
        }
    }

    private object FakeCatalogVerifier : SourceCatalogSignatureVerifier {
        override fun verifyManifest(manifest: SignedSourceCatalogManifest): Boolean = true

        override fun verifySource(
            entry: SourceCatalogEntry,
            artifact: SourceRevisionArtifact,
        ): Boolean =
            entry.api == artifact.api &&
                entry.sourceRevision == artifact.sourceRevision &&
                entry.checksum == artifact.checksum
    }

    private object SchemaOnlyValidator : SourceConfigValidator {
        override fun validate(document: SourceConfigDocument): ValidationResult =
            if (document.schemaVersion == 1) {
                ValidationResult.OK
            } else {
                ValidationResult.failed(listOf("bad schema"))
            }
    }

    private companion object {
        const val BUNDLED_REVISION = 5L

        fun bundledJson(): String =
            """{"schemaVersion":1,"revision":$BUNDLED_REVISION,"sources":[${sourceJson("floor")}]}"""

        fun sourceJson(api: String): String =
            """{"api":"$api","language":"en","baseUrl":"https://$api.test","engine":"generic"}"""

        fun entry(
            api: String,
            revision: Long,
            lifecycle: String = "active",
        ): SourceCatalogEntry =
            SourceCatalogEntry(
                api = api,
                sourceRevision = revision,
                checksum = checksum(revision),
                order = 0,
                lifecycle = lifecycle,
                engine = "generic",
                sourceSigningKeyId = "test-key",
                sourceSignature = "signature",
            )

        fun artifact(
            api: String,
            revision: Long,
        ): SourceRevisionArtifact =
            SourceRevisionArtifact(api, revision, checksum(revision), "kcj-1", sourceJson(api))

        fun storedCatalog(
            revision: Long,
            entries: List<Pair<SourceCatalogEntry, SourceRevisionArtifact>>,
            removedApis: List<String> = emptyList(),
        ): StoredSourceCatalog {
            val ordered = entries.mapIndexed { index, pair -> pair.first.copy(order = index) }
            return StoredSourceCatalog(
                signedManifest(revision, ordered, removedApis = removedApis),
                entries.map { it.second },
            )
        }

        fun signedManifest(
            revision: Long,
            entries: List<SourceCatalogEntry>,
            previousRevision: Long? = null,
            removedApis: List<String> = emptyList(),
        ): SignedSourceCatalogManifest {
            val ordered = entries.mapIndexed { index, entry -> entry.copy(order = index) }
            val sources =
                ordered.joinToString(",") {
                    """{"api":"${it.api}","sourceRevision":${it.sourceRevision},"checksum":"${it.checksum}",""" +
                        """"order":${it.order},"lifecycle":"${it.lifecycle}","engine":"${it.engine}",""" +
                        """"sourceSigningKeyId":"test-key","sourceSignature":"signature"}"""
                }
            val payload =
                """{"schemaVersion":1,"sourceSchemaVersion":1,"catalogRevision":$revision,""" +
                    """"generatedAt":"2026-07-23T00:00:00Z","sources":[$sources],""" +
                    """"removedSources":[${removedApis.joinToString(",") {
                        """{"api":"$it","lifecycle":"removed"}"""
                    }}]}"""
            return SignedSourceCatalogManifest(
                payload,
                ConfigSignatureMetadata(
                    format = "kira-source-catalog-manifest-v1",
                    algorithm = "Ed25519",
                    keyId = "test-key",
                    signatureBase64 = "signature",
                    revision = revision,
                    checksum = checksum(revision),
                    createdAt = "2026-07-23T00:00:00Z",
                    previousRevision = previousRevision,
                    previousChecksum = previousRevision?.let(::checksum),
                ),
            )
        }

        fun checksum(value: Long): String = value.toString(16).padStart(64, '0')
    }
}
