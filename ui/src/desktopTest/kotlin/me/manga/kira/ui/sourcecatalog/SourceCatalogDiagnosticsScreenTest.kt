package me.manga.kira.ui.sourcecatalog

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.source_catalog_origin_backend
import me.manga.kira.ui.generated.resources.source_catalog_origin_bundled
import me.manga.kira.ui.generated.resources.source_catalog_title
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SourceCatalogDiagnosticsScreenTest {
    @Test
    fun bundled_catalog_renders_provenance_and_handles_back() =
        runComposeUiTest {
            var title = ""
            var origin = ""
            var back = ""
            var backTaps = 0
            setContent {
                KiraTheme(darkTheme = false) {
                    title = stringResource(Res.string.source_catalog_title)
                    origin = stringResource(Res.string.source_catalog_origin_bundled)
                    back = stringResource(Res.string.back)
                    SourceCatalogDiagnosticsScreen(
                        model = bundledModel(),
                        onBack = { backTaps++ },
                    )
                }
            }

            onNodeWithText(title).assertIsDisplayed()
            onNodeWithText(origin).assertIsDisplayed()
            onNodeWithText("Azora").performScrollTo().assertIsDisplayed()
            onNodeWithContentDescription(back).performClick()
            assertEquals(1, backTaps)
        }

    @Test
    fun backend_catalog_renders_verified_manifest_metadata() =
        runComposeUiTest {
            var origin = ""
            val checksum = "a".repeat(64)
            val previousChecksum = "b".repeat(64)
            setContent {
                KiraTheme(darkTheme = true) {
                    origin = stringResource(Res.string.source_catalog_origin_backend)
                    SourceCatalogDiagnosticsScreen(
                        model = backendModel(checksum, previousChecksum),
                        onBack = {},
                    )
                }
            }

            onNodeWithText(origin).assertIsDisplayed()
            onNodeWithText(checksum).performScrollTo().assertIsDisplayed()
            onNodeWithText(previousChecksum).performScrollTo().assertIsDisplayed()
            onNodeWithText("prod-2026-01").performScrollTo().assertIsDisplayed()
        }

    private fun bundledModel(): SourceCatalogDiagnosticsUiModel =
        SourceCatalogDiagnosticsUiModel(
            origin = SourceCatalogOriginUi.BUNDLED,
            syncStatus = SourceCatalogSyncStatusUi.ACTIVE,
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
            activeSources = listOf(source(sourceRevision = null, checksum = null)),
        )

    private fun backendModel(
        checksum: String,
        previousChecksum: String,
    ): SourceCatalogDiagnosticsUiModel =
        SourceCatalogDiagnosticsUiModel(
            origin = SourceCatalogOriginUi.BACKEND,
            syncStatus = SourceCatalogSyncStatusUi.ACTIVE,
            catalogRevision = 120,
            catalogSchemaVersion = 1,
            sourceSchemaVersion = 1,
            generatedAt = "2026-07-23T00:00:00Z",
            manifestChecksum = checksum,
            manifestSigningKeyId = "prod-2026-01",
            signatureAlgorithm = "Ed25519",
            signatureFormat = "kira-source-catalog-manifest-v1",
            previousCatalogRevision = 119,
            previousCatalogChecksum = previousChecksum,
            removedSourceCount = 33,
            inactiveSourceCount = 0,
            activeSources = listOf(source(sourceRevision = 9, checksum = "c".repeat(64))),
        )

    private fun source(
        sourceRevision: Long?,
        checksum: String?,
    ): SourceCatalogSourceUiModel =
        SourceCatalogSourceUiModel(
            api = "azora-api",
            displayName = "Azora",
            language = "(AR)",
            baseUrl = "https://azoramoon.com",
            engine = "generic",
            lifecycle = "active",
            order = 0,
            sourceRevision = sourceRevision,
            checksum = checksum,
            signingKeyId = sourceRevision?.let { "prod-2026-01" },
        )
}
