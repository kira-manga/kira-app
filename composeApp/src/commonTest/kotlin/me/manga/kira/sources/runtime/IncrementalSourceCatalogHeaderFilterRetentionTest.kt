package me.manga.kira.sources.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.config.IncrementalSourceCatalogManager
import me.manga.kira.sources.contracts.ConfigSignatureMetadata
import me.manga.kira.sources.contracts.RemoteSourceCatalog
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogAcceptanceFloor
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceCatalogSignatureVerifier
import me.manga.kira.sources.contracts.SourceCatalogStore
import me.manga.kira.sources.contracts.SourceConfigValidator
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import me.manga.kira.sources.contracts.StoredSourceCatalog
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.ValidationResult
import me.manga.kira.sources.contracts.model.EndpointSpec
import me.manga.kira.sources.contracts.model.FilterDefinition
import me.manga.kira.sources.contracts.model.FilterRequestSpec
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real shipping validator + manager; fake ports isolate semantic rejection, not cryptography/Room. */
class IncrementalSourceCatalogHeaderFilterRetentionTest {
    @Test
    fun unsafe_header_revision_retains_the_entire_previously_accepted_catalog() =
        runTest {
            val fixture = Fixture()
            fixture.acceptBaseline()
            fixture.rejectAdvancingCandidate()
        }

    private class Fixture {
        private val stable = source("StableSource")
        private val changing = source("ChangingSource")
        private val baseline = catalog(2, 1, listOf(stable, changing))
        private val candidate =
            catalog(
                revision = 3,
                sourceRevision = 2,
                sources =
                    listOf(
                        stable.copy(displayName = "Valid update that must not partially activate"),
                        source("ChangingSource", headerName = "Authorization"),
                    ),
                previous = baseline.stored.manifest.metadata,
            )
        private val store = MemoryStore(SourceConfigDocument(1, revision = 1, sources = listOf(stable)))
        private val remote = FixtureRemote(listOf(baseline, candidate))
        private val validator = RecordingValidator()
        private val rejections = mutableListOf<String>()
        private val manager =
            IncrementalSourceCatalogManager(store, FixtureVerifier, validator, remote, rejections::add)

        suspend fun acceptBaseline() {
            val result = assertIs<AppResult.Success<SourceConfigDocument>>(manager.refresh())
            assertEquals(baseline.document, result.value)
            assertEquals(baseline.document, manager.activeDocument())
            assertEquals(baseline.stored, store.readActive())
            assertEquals(SourceCatalogAcceptanceFloor(2, checksum(2)), store.readAcceptanceFloor())
            assertEquals(1, store.activationCount)
            assertEquals(1, store.bundleProjectionCount)
            assertEquals(UpdateState.Active(2, UpdateState.Origin.REMOTE), manager.state.value)
            assertEquals(
                listOf("StableSource", "ChangingSource"),
                manager.diagnostics.value.activeSources.map { it.api },
            )
            assertTrue(validator.calls.single { it.first.revision == 2L }.second.isValid)
            assertTrue(rejections.isEmpty())
        }

        suspend fun rejectAdvancingCandidate() {
            val beforeDocument = manager.activeDocument()
            val beforeDiagnostics = manager.diagnostics.value
            val beforeState = manager.state.value
            val beforeCatalog = assertNotNull(store.readActive())
            val beforeFloor = assertNotNull(store.readAcceptanceFloor())
            val beforeManifest = assertNotNull(store.readAcceptedManifest())
            val beforeActivations = store.activationCount

            val result = assertIs<AppResult.Success<SourceConfigDocument>>(manager.refresh())

            assertSemanticRejection()
            assertEquals(beforeDocument, result.value)
            assertEquals(beforeDocument, manager.activeDocument())
            assertEquals(beforeDiagnostics, manager.diagnostics.value)
            assertEquals(beforeState, manager.state.value)
            assertEquals(beforeCatalog, store.readActive())
            assertEquals(beforeFloor, store.readAcceptanceFloor())
            assertEquals(beforeManifest, store.readAcceptedManifest())
            assertEquals(beforeActivations, store.activationCount)
            assertEquals(1, store.bundleProjectionCount)
        }

        private fun assertSemanticRejection() {
            val (document, validation) = validator.calls.single { it.first.revision == 3L }
            assertEquals(candidate.document, document) // Both advancing payloads reached the real validator.
            assertFalse(validation.isValid)
            assertEquals(
                listOf(
                    "source 'ChangingSource': filters: filter 'custom': request.param: " +
                        "sensitive header names are not supported for filters",
                ),
                validation.errors,
            )
            assertEquals(listOf("catalog validation failed"), rejections)
            assertEquals(listOf(1L, 2L, 2L, 3L), validator.calls.map { it.first.revision })
            assertEquals(listOf(null, checksum(2)), remote.requestedEtags)
            assertEquals(
                setOf("StableSource" to 1L, "ChangingSource" to 1L, "StableSource" to 2L, "ChangingSource" to 2L),
                remote.fetchedSources.toSet(),
            )
            assertEquals(4, remote.fetchedSources.size)
        }
    }

    private class RecordingValidator : SourceConfigValidator {
        private val actual = DefaultSourceConfigValidator(DefaultStrategyRegistry())
        val calls = mutableListOf<Pair<SourceConfigDocument, ValidationResult>>()

        override fun validate(document: SourceConfigDocument): ValidationResult =
            actual.validate(document).also { calls += document to it }
    }

    private class MemoryStore(private val bundle: SourceConfigDocument) : SourceCatalogStore {
        private var active: StoredSourceCatalog? = null
        private var floor: SourceCatalogAcceptanceFloor? = null
        var activationCount = 0
            private set
        var bundleProjectionCount = 0
            private set

        override fun readBundled(): String = Json.encodeToString(SourceConfigDocument.serializer(), bundle)

        override suspend fun projectBundled(document: SourceConfigDocument) {
            assertEquals(bundle, document)
            bundleProjectionCount++
        }

        override suspend fun readActive(): StoredSourceCatalog? = active

        override suspend fun readAcceptanceFloor(): SourceCatalogAcceptanceFloor? = floor

        override suspend fun readAcceptedManifest(): SignedSourceCatalogManifest? = active?.manifest

        override suspend fun findSource(api: String, sourceRevision: Long, checksum: String): SourceRevisionArtifact? =
            active?.sources?.singleOrNull {
                it.api == api && it.sourceRevision == sourceRevision && it.checksum == checksum
            }

        override suspend fun activate(catalog: StoredSourceCatalog) {
            activationCount++
            active = catalog
            floor = SourceCatalogAcceptanceFloor(catalog.manifest.metadata.revision, catalog.manifest.metadata.checksum)
        }
    }

    private class FixtureRemote(private val catalogs: List<Catalog>) : RemoteSourceCatalog {
        private val artifacts = catalogs.flatMap { it.stored.sources }
        val requestedEtags = mutableListOf<String?>()
        val fetchedSources = mutableListOf<Pair<String, Long>>()

        override suspend fun fetchManifest(etag: String?): SourceCatalogManifestResult {
            val catalog = catalogs[requestedEtags.size]
            requestedEtags += etag
            return SourceCatalogManifestResult.Modified(catalog.stored.manifest)
        }

        override suspend fun fetchSource(entry: SourceCatalogEntry): SourceRevisionArtifact {
            fetchedSources += entry.api to entry.sourceRevision
            return artifacts.single {
                it.api == entry.api && it.sourceRevision == entry.sourceRevision && it.checksum == entry.checksum
            }
        }
    }

    private object FixtureVerifier : SourceCatalogSignatureVerifier {
        override fun verifyManifest(manifest: SignedSourceCatalogManifest): Boolean =
            manifest.metadata.signatureBase64 == SYNTHETIC_SIGNATURE

        override fun verifySource(entry: SourceCatalogEntry, artifact: SourceRevisionArtifact): Boolean =
            entry.api == artifact.api && entry.sourceRevision == artifact.sourceRevision &&
                entry.checksum == artifact.checksum
    }

    private data class Catalog(val document: SourceConfigDocument, val stored: StoredSourceCatalog)

    private companion object {
        const val GENERATED_AT = "2026-09-10T00:00:00Z"
        // Base64 for 64 zero bytes: Ed25519-shaped fixture metadata, NOT a valid signature.
        val SYNTHETIC_SIGNATURE = "A".repeat(86) + "=="

        fun source(api: String, headerName: String = "X-Lang"): SourceConfig =
            SourceConfig(
                api = api,
                language = "en",
                baseUrl = "https://fixture.example",
                engine = "generic",
                endpoints =
                    mapOf(
                        "home" to EndpointSpec(url = "{baseUrl}/latest", listSelector = "div.item"),
                        "search" to EndpointSpec(url = "{baseUrl}/search?q={queryEncoded}", listSelector = "div.item"),
                    ),
                filters =
                    listOf(
                        FilterDefinition("custom", "Custom", "text", request = FilterRequestSpec("header", headerName)),
                    ),
            )

        fun catalog(
            revision: Long,
            sourceRevision: Long,
            sources: List<SourceConfig>,
            previous: ConfigSignatureMetadata? = null,
        ): Catalog {
            val artifacts =
                sources.mapIndexed { index, source ->
                    SourceRevisionArtifact(
                        source.api,
                        sourceRevision,
                        checksum(revision * 100 + index),
                        "kcj-1",
                        Json.encodeToString(SourceConfig.serializer(), source),
                    )
                }
            val entries = artifacts.mapIndexed(::manifestEntry)
            val document =
                SourceConfigDocument(
                    schemaVersion = 1,
                    revision = revision,
                    generatedAt = GENERATED_AT,
                    sources = sources.mapIndexed { index, source -> source.copy(priority = index) },
                )
            return Catalog(document, StoredSourceCatalog(signedManifest(revision, entries, previous), artifacts))
        }

        fun manifestEntry(order: Int, artifact: SourceRevisionArtifact): SourceCatalogEntry =
            SourceCatalogEntry(
                artifact.api,
                artifact.sourceRevision,
                artifact.checksum,
                order,
                "active",
                "generic",
                "test-key",
                SYNTHETIC_SIGNATURE,
            )

        // These shape-valid checksums/signatures are synthetic; this is not canonical-byte/signature evidence.
        fun signedManifest(
            revision: Long,
            entries: List<SourceCatalogEntry>,
            previous: ConfigSignatureMetadata?,
        ): SignedSourceCatalogManifest =
            SignedSourceCatalogManifest(
                Json.encodeToString(
                    SourceCatalogManifest.serializer(),
                    SourceCatalogManifest(1, 1, revision, GENERATED_AT, entries),
                ),
                ConfigSignatureMetadata(
                    format = "kira-source-catalog-manifest-v1",
                    algorithm = "Ed25519",
                    keyId = "test-key",
                    signatureBase64 = SYNTHETIC_SIGNATURE,
                    revision = revision,
                    checksum = checksum(revision),
                    createdAt = GENERATED_AT,
                    previousRevision = previous?.revision,
                    previousChecksum = previous?.checksum,
                ),
            )

        fun checksum(value: Long): String = value.toString(16).padStart(64, '0')
    }
}
