package me.manga.kira.navigation.routes

import me.manga.kira.sources.contracts.ActiveSourceDiagnostics
import me.manga.kira.sources.contracts.SourceCatalogDiagnostics
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.ui.sourcecatalog.SourceCatalogOriginUi
import me.manga.kira.ui.sourcecatalog.SourceCatalogSyncStatusUi
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceCatalogDiagnosticsScreenRouteTest {
    @Test
    fun maps_verified_remote_metadata_without_exposing_payloads_or_signatures() {
        val diagnostics =
            SourceCatalogDiagnostics(
                origin = UpdateState.Origin.REMOTE,
                catalogRevision = 101,
                catalogSchemaVersion = 1,
                sourceSchemaVersion = 1,
                generatedAt = "2026-07-23T00:00:00Z",
                manifestChecksum = "a".repeat(64),
                manifestSigningKeyId = "prod-2026-01",
                signatureAlgorithm = "Ed25519",
                signatureFormat = "kira-source-catalog-manifest-v1",
                previousCatalogRevision = 100,
                previousCatalogChecksum = "b".repeat(64),
                removedSourceCount = 33,
                inactiveSourceCount = 0,
                activeSources =
                    listOf(
                        ActiveSourceDiagnostics(
                            api = "azora",
                            displayName = "Azora",
                            language = "ar",
                            baseUrl = "https://azoramoon.com",
                            engine = "generic",
                            lifecycle = "active",
                            order = 4,
                            sourceRevision = 2,
                            checksum = "c".repeat(64),
                            signingKeyId = "prod-2026-01",
                        ),
                    ),
            )

        val model = diagnostics.toUiModel(UpdateState.Refreshing)

        assertEquals(SourceCatalogOriginUi.BACKEND, model.origin)
        assertEquals(SourceCatalogSyncStatusUi.REFRESHING, model.syncStatus)
        assertEquals(101, model.catalogRevision)
        assertEquals(33, model.removedSourceCount)
        assertEquals("b".repeat(64), model.previousCatalogChecksum)
        assertEquals(2, model.activeSources.single().sourceRevision)
        assertEquals("c".repeat(64), model.activeSources.single().checksum)
    }

    @Test
    fun maps_every_catalog_origin_and_update_state() {
        val bundled =
            diagnostics(UpdateState.Origin.BUNDLED).toUiModel(
                UpdateState.Active(6, UpdateState.Origin.BUNDLED),
            )
        val cache = diagnostics(UpdateState.Origin.CACHE).toUiModel(UpdateState.Refreshing)
        val backend = diagnostics(UpdateState.Origin.REMOTE).toUiModel(UpdateState.Failed("offline"))

        assertEquals(SourceCatalogOriginUi.BUNDLED, bundled.origin)
        assertEquals(SourceCatalogSyncStatusUi.ACTIVE, bundled.syncStatus)
        assertEquals(SourceCatalogOriginUi.VERIFIED_CACHE, cache.origin)
        assertEquals(SourceCatalogSyncStatusUi.REFRESHING, cache.syncStatus)
        assertEquals(SourceCatalogOriginUi.BACKEND, backend.origin)
        assertEquals(SourceCatalogSyncStatusUi.FAILED, backend.syncStatus)
    }

    private fun diagnostics(origin: UpdateState.Origin): SourceCatalogDiagnostics =
        SourceCatalogDiagnostics(
            origin = origin,
            catalogRevision = 6,
            catalogSchemaVersion = 1,
            sourceSchemaVersion = 1,
            generatedAt = null,
            manifestChecksum = null,
            manifestSigningKeyId = null,
            signatureAlgorithm = null,
            signatureFormat = null,
            previousCatalogRevision = null,
            previousCatalogChecksum = null,
            removedSourceCount = 0,
            inactiveSourceCount = 0,
            activeSources = emptyList(),
        )
}
