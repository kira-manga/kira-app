@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.sources.contracts.SourceCatalogDiagnostics
import me.manga.kira.sources.contracts.SourceCatalogDiagnosticsProvider
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.ui.sourcecatalog.SourceCatalogDiagnosticsScreen
import me.manga.kira.ui.sourcecatalog.SourceCatalogDiagnosticsUiModel
import me.manga.kira.ui.sourcecatalog.SourceCatalogOriginUi
import me.manga.kira.ui.sourcecatalog.SourceCatalogSourceUiModel
import me.manga.kira.ui.sourcecatalog.SourceCatalogSyncStatusUi
import org.koin.compose.koinInject

@Composable
fun SourceCatalogDiagnosticsScreenRoute(navController: NavController) {
    val diagnosticsProvider: SourceCatalogDiagnosticsProvider = koinInject()
    val updateManager: SourceUpdateManager = koinInject()
    val diagnostics by diagnosticsProvider.diagnostics.collectAsState()
    val updateState by updateManager.state.collectAsState()

    SourceCatalogDiagnosticsScreen(
        model = diagnostics.toUiModel(updateState),
        onBack = { navController.safePopBackStack() },
    )
}

internal fun SourceCatalogDiagnostics.toUiModel(updateState: UpdateState): SourceCatalogDiagnosticsUiModel =
    SourceCatalogDiagnosticsUiModel(
        origin =
            when (origin) {
                UpdateState.Origin.BUNDLED -> SourceCatalogOriginUi.BUNDLED
                UpdateState.Origin.CACHE -> SourceCatalogOriginUi.VERIFIED_CACHE
                UpdateState.Origin.REMOTE -> SourceCatalogOriginUi.BACKEND
            },
        syncStatus =
            when (updateState) {
                is UpdateState.Active -> SourceCatalogSyncStatusUi.ACTIVE
                UpdateState.Refreshing -> SourceCatalogSyncStatusUi.REFRESHING
                is UpdateState.Failed -> SourceCatalogSyncStatusUi.FAILED
            },
        catalogRevision = catalogRevision,
        catalogSchemaVersion = catalogSchemaVersion,
        sourceSchemaVersion = sourceSchemaVersion,
        generatedAt = generatedAt,
        manifestChecksum = manifestChecksum,
        manifestSigningKeyId = manifestSigningKeyId,
        signatureAlgorithm = signatureAlgorithm,
        signatureFormat = signatureFormat,
        previousCatalogRevision = previousCatalogRevision,
        previousCatalogChecksum = previousCatalogChecksum,
        removedSourceCount = removedSourceCount,
        inactiveSourceCount = inactiveSourceCount,
        activeSources =
            activeSources.map { source ->
                SourceCatalogSourceUiModel(
                    api = source.api,
                    displayName = source.displayName,
                    language = source.language,
                    baseUrl = source.baseUrl,
                    engine = source.engine,
                    lifecycle = source.lifecycle,
                    order = source.order,
                    sourceRevision = source.sourceRevision,
                    checksum = source.checksum,
                    signingKeyId = source.signingKeyId,
                )
            },
    )
