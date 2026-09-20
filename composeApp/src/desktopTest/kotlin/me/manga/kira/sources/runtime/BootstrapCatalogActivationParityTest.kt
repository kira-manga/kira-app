package me.manga.kira.sources.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.sources.config.IncrementalSourceCatalogManager
import me.manga.kira.sources.contracts.SourceCatalogAcceptanceFloor
import me.manga.kira.sources.contracts.SourceCatalogManifest
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.SourceHttpMethod
import me.manga.kira.sources.contracts.StoredSourceCatalog
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry
import me.manga.kira.sources.engine.GenericSourceClient
import me.manga.kira.sources.runtime.BootstrapSignedCatalogFixture.Companion.BUNDLED_REVISION
import me.manga.kira.sources.runtime.BootstrapSignedCatalogFixture.Companion.CATALOG_REVISION
import me.manga.kira.sources.runtime.BootstrapSignedCatalogFixture.Companion.CREATED_AT
import me.manga.kira.sources.runtime.BootstrapSignedCatalogFixture.Companion.KEY_ID
import me.manga.kira.sources.runtime.BootstrapSignedCatalogFixture.Companion.PINNED_KEYS
import me.manga.kira.sources.runtime.BootstrapSignedCatalogFixture.Companion.SOURCE_COUNT
import me.manga.kira.sources.runtime.BootstrapSignedCatalogFixture.Companion.SOURCE_REVISION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real producer signatures -> shipping validation/activation -> activated Azora engine behavior. */
class BootstrapCatalogActivationParityTest {
    private val bundle =
        assertIs<AppResult.Success<SourceConfigDocument>>(
            SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON),
        ).value
    private val verifier = Ed25519ConfigSignatureVerifier(PINNED_KEYS)
    private val validator = DefaultSourceConfigValidator(DefaultStrategyRegistry())

    @Test
    fun backendBootstrapActivatesAllTwelveVerifiedMembersAndAzoraChapters() =
        runTest {
            val fixture = BootstrapSignedCatalogFixture.load()
            val manifest = assertApprovedFixture(fixture)
            val session = session(fixture)

            val result = assertIs<AppResult.Success<SourceConfigDocument>>(session.manager.refresh())

            assertActivatedCatalog(session, fixture, manifest, result.value)
            assertActivatedAzoraDetails(session.manager)
        }

    @Test
    fun tamperedLastMemberFailsWithoutPartialActivationOrReplacingBundle() =
        runTest {
            val fixture = BootstrapSignedCatalogFixture.load()
            val manifest = assertApprovedFixture(fixture)
            val tampered = fixture.sources.last().let { it.copy(payload = it.payload + " ") }
            assertEquals(
                bundle.sources.last(),
                assertIs<AppResult.Success<SourceConfig>>(SourceConfigParser.parseSource(tampered.payload)).value,
            )
            assertFalse(verifier.verifySource(manifest.sources.last(), tampered))
            val session = session(fixture.copy(sources = fixture.sources.dropLast(1) + tampered))
            val beforeDiagnostics = session.manager.diagnostics.value

            assertIs<AppResult.Failure>(session.manager.refresh())

            assertIs<UpdateState.Failed>(session.manager.state.value)
            assertEquals(bundle, session.manager.activeDocument())
            assertEquals(beforeDiagnostics, session.manager.diagnostics.value)
            assertEquals(bundle, session.store.bundledProjection)
            assertEquals(session.store.selection, session.store.ready)
            assertEquals(BUNDLED_REVISION, session.store.ready?.token?.identity?.revision)
            assertEquals(0, session.store.activations)
            assertNull(session.store.readActive())
            assertNull(session.store.readAcceptanceFloor())
            assertNull(session.store.readAcceptedManifest())
            assertTrue(session.remote.fetchedEntries.contains(manifest.sources.first()))
            assertTrue(session.remote.fetchedEntries.contains(manifest.sources.last()))
            assertTrue(session.rejections.isNotEmpty())
        }

    private fun assertApprovedFixture(fixture: BootstrapSignedCatalogFixture): SourceCatalogManifest {
        assertEquals(BootstrapSignedCatalogFixture.VERSION, fixture.fixtureVersion)
        assertEquals(PINNED_KEYS, fixture.verificationKeys, "fixture must retain the explicit public test-vector pin")
        assertManifestMetadata(fixture)
        assertTrue(verifier.verifyManifest(fixture.manifest), "the actual backend manifest signature must verify")
        val manifest =
            assertIs<AppResult.Success<SourceCatalogManifest>>(
                SourceConfigParser.parseManifest(fixture.manifest.payload),
            ).value
        assertEquals(BUNDLED_REVISION, bundle.revision)
        assertEquals(SOURCE_COUNT, bundle.sources.size)
        assertEquals(1, manifest.schemaVersion)
        assertEquals(bundle.schemaVersion, manifest.sourceSchemaVersion)
        assertEquals(CATALOG_REVISION, manifest.catalogRevision)
        assertEquals(CREATED_AT, manifest.generatedAt)
        assertEquals(bundle.sources.map { it.api }, manifest.sources.map { it.api })
        assertTrue(manifest.removedSources.isEmpty())
        assertSourceParity(fixture, manifest)
        return manifest
    }

    private fun assertManifestMetadata(fixture: BootstrapSignedCatalogFixture) {
        val metadata = fixture.manifest.metadata
        assertEquals("kira-source-catalog-manifest-v1", metadata.format)
        assertEquals("Ed25519", metadata.algorithm)
        assertEquals(KEY_ID, metadata.keyId)
        assertEquals(CATALOG_REVISION, metadata.revision)
        assertTrue(metadata.revision > bundle.revision)
        assertEquals(CREATED_AT, metadata.createdAt)
        assertNull(metadata.previousRevision)
        assertNull(metadata.previousChecksum)
    }

    private fun assertSourceParity(
        fixture: BootstrapSignedCatalogFixture,
        manifest: SourceCatalogManifest,
    ) {
        assertEquals(SOURCE_COUNT, fixture.sources.size)
        assertEquals(manifest.sources.map { it.api }, fixture.sources.map { it.api })
        manifest.sources.zip(fixture.sources).forEachIndexed { index, (entry, artifact) ->
            assertEquals(index, entry.order)
            assertEquals("active", entry.lifecycle)
            assertEquals("generic", entry.engine)
            assertEquals(SOURCE_REVISION, entry.sourceRevision)
            assertEquals(KEY_ID, entry.sourceSigningKeyId)
            assertEquals(entry.api, artifact.api)
            assertEquals(entry.sourceRevision, artifact.sourceRevision)
            assertEquals(entry.checksum, artifact.checksum)
            assertEquals("kcj-1", artifact.canonVersion)
            assertTrue(verifier.verifySource(entry, artifact), "actual source signature: ${entry.api}")
            val source =
                assertIs<AppResult.Success<SourceConfig>>(
                    SourceConfigParser.parseSource(artifact.payload),
                ).value
            assertEquals(bundle.sources[index], source, "all default-expanded source fields: ${entry.api}")
        }
    }

    private suspend fun assertActivatedCatalog(
        session: Session,
        fixture: BootstrapSignedCatalogFixture,
        manifest: SourceCatalogManifest,
        actual: SourceConfigDocument,
    ) {
        val expected = bundle.copy(
            revision = CATALOG_REVISION, generatedAt = CREATED_AT,
            sources = bundle.sources.mapIndexed { index, source -> source.copy(lifecycle = "active", priority = index) },
        )
        assertEquals(expected, actual, "only manifest lifecycle/order/revision/time project over full source models")
        assertEquals(expected, session.manager.activeDocument())
        assertEquals(UpdateState.Active(CATALOG_REVISION, UpdateState.Origin.REMOTE), session.manager.state.value)
        assertEquals(1, session.store.activations)
        assertEquals(bundle, session.store.bundledProjection)
        assertEquals(session.store.selection, session.store.ready)
        assertEquals(CATALOG_REVISION, session.store.ready?.token?.identity?.revision)
        assertEquals(StoredSourceCatalog(fixture.manifest, fixture.sources), session.store.readActive())
        assertEquals(fixture.manifest, session.store.readAcceptedManifest())
        assertEquals(
            SourceCatalogAcceptanceFloor(CATALOG_REVISION, fixture.manifest.metadata.checksum),
            session.store.readAcceptanceFloor(),
        )
        assertEquals(listOf<String?>(null), session.remote.requestedEtags)
        assertEquals(SOURCE_COUNT, session.remote.fetchedEntries.size)
        assertEquals(manifest.sources.toSet(), session.remote.fetchedEntries.toSet())
        assertTrue(session.rejections.isEmpty())
        assertRemoteDiagnostics(session.manager, fixture, manifest)
    }

    private fun assertRemoteDiagnostics(
        manager: IncrementalSourceCatalogManager,
        fixture: BootstrapSignedCatalogFixture,
        manifest: SourceCatalogManifest,
    ) {
        val diagnostics = manager.diagnostics.value
        assertEquals(UpdateState.Origin.REMOTE, diagnostics.origin)
        assertEquals(CATALOG_REVISION, diagnostics.catalogRevision)
        assertEquals(CREATED_AT, diagnostics.generatedAt)
        assertEquals(fixture.manifest.metadata.checksum, diagnostics.manifestChecksum)
        assertEquals(KEY_ID, diagnostics.manifestSigningKeyId)
        assertEquals(manifest.sources.map { it.api }, diagnostics.activeSources.map { it.api })
        assertEquals(manifest.sources.map { it.order }, diagnostics.activeSources.map { it.order })
        assertEquals(manifest.sources.map { it.sourceRevision }, diagnostics.activeSources.map { it.sourceRevision })
        assertEquals(manifest.sources.map { it.checksum }, diagnostics.activeSources.map { it.checksum })
        assertEquals(0, diagnostics.inactiveSourceCount)
        assertEquals(0, diagnostics.removedSourceCount)
    }

    private suspend fun assertActivatedAzoraDetails(manager: IncrementalSourceCatalogManager) {
        val azora = manager.activeDocument().sources.single { it.api == "Azora" }
        val http = BootstrapAzoraDetailsHttp(azora.baseUrl)
        val manga = Manga(azora.api, azora.language, "One Piece", http.itemUrl, "", null, emptyList())
        val client = GenericSourceClient(azora, http, NoopHeaderStore())

        val details = assertIs<AppResult.Success<MangaDetails>>(client.details(manga)).value

        assertEquals(listOf(http.expectedUrl), http.requests.map { it.url })
        assertEquals(listOf(SourceHttpMethod.GET), http.requests.map { it.method })
        assertEquals("One Piece", details.title)
        assertEquals("Pirates & adventure", details.description)
        assertEquals("8.0", details.rating)
        assertEquals(expectedChapters(azora.baseUrl), details.chapters)
    }

    private fun expectedChapters(base: String): List<Chapter> =
        listOf(
            Chapter(
                "Chapter 1",
                "Romance Dawn",
                "$base/api/chapter?chapterId=85027",
                LocalDate.parse("2024-01-15"),
                false,
                false,
            ),
            Chapter(
                "Chapter 2",
                "Chapter 2",
                "$base/api/chapter?chapterId=85028",
                LocalDate.parse("2024-01-22"),
                false,
                false,
            ),
            Chapter(
                "Chapter 3",
                "Chapter 3",
                "$base/api/chapter?chapterId=85029",
                LocalDate.parse("2024-02-01"),
                false,
                false,
            ),
        )

    private fun session(fixture: BootstrapSignedCatalogFixture): Session {
        val store = BootstrapCatalogMemoryStore()
        val remote = BootstrapCatalogRemote(fixture)
        val rejections = mutableListOf<String>()
        val manager = IncrementalSourceCatalogManager(store, verifier, validator, remote, rejections::add)
        return Session(store, remote, manager, rejections)
    }

    private data class Session(
        val store: BootstrapCatalogMemoryStore,
        val remote: BootstrapCatalogRemote,
        val manager: IncrementalSourceCatalogManager,
        val rejections: List<String>,
    )
}
