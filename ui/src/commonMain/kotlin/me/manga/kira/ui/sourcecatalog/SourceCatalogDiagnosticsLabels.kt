package me.manga.kira.ui.sourcecatalog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.source_catalog_not_available
import me.manga.kira.ui.generated.resources.source_catalog_origin_backend
import me.manga.kira.ui.generated.resources.source_catalog_origin_backend_description
import me.manga.kira.ui.generated.resources.source_catalog_origin_bundled
import me.manga.kira.ui.generated.resources.source_catalog_origin_bundled_description
import me.manga.kira.ui.generated.resources.source_catalog_origin_cache
import me.manga.kira.ui.generated.resources.source_catalog_origin_cache_description
import me.manga.kira.ui.generated.resources.source_catalog_sync_active
import me.manga.kira.ui.generated.resources.source_catalog_sync_failed
import me.manga.kira.ui.generated.resources.source_catalog_sync_refreshing
import me.manga.kira.ui.generated.resources.source_catalog_trust_bundle
import me.manga.kira.ui.generated.resources.source_catalog_trust_signed
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun trustLabel(origin: SourceCatalogOriginUi): String =
    if (origin == SourceCatalogOriginUi.BUNDLED) {
        stringResource(Res.string.source_catalog_trust_bundle)
    } else {
        stringResource(Res.string.source_catalog_trust_signed)
    }

@Composable
internal fun originTitle(origin: SourceCatalogOriginUi): String =
    when (origin) {
        SourceCatalogOriginUi.BUNDLED -> stringResource(Res.string.source_catalog_origin_bundled)
        SourceCatalogOriginUi.VERIFIED_CACHE -> stringResource(Res.string.source_catalog_origin_cache)
        SourceCatalogOriginUi.BACKEND -> stringResource(Res.string.source_catalog_origin_backend)
    }

@Composable
internal fun originDescription(origin: SourceCatalogOriginUi): String =
    when (origin) {
        SourceCatalogOriginUi.BUNDLED -> stringResource(Res.string.source_catalog_origin_bundled_description)
        SourceCatalogOriginUi.VERIFIED_CACHE -> stringResource(Res.string.source_catalog_origin_cache_description)
        SourceCatalogOriginUi.BACKEND -> stringResource(Res.string.source_catalog_origin_backend_description)
    }

@Composable
internal fun syncStatus(status: SourceCatalogSyncStatusUi): String =
    when (status) {
        SourceCatalogSyncStatusUi.ACTIVE -> stringResource(Res.string.source_catalog_sync_active)
        SourceCatalogSyncStatusUi.REFRESHING -> stringResource(Res.string.source_catalog_sync_refreshing)
        SourceCatalogSyncStatusUi.FAILED -> stringResource(Res.string.source_catalog_sync_failed)
    }

internal fun SourceCatalogOriginUi.icon(): ImageVector =
    when (this) {
        SourceCatalogOriginUi.BUNDLED -> Icons.Outlined.Inventory2
        SourceCatalogOriginUi.VERIFIED_CACHE -> Icons.Outlined.Storage
        SourceCatalogOriginUi.BACKEND -> Icons.Outlined.CloudDone
    }

@Composable
internal fun String?.orUnavailable(): String =
    this?.takeIf(String::isNotBlank)
        ?: stringResource(Res.string.source_catalog_not_available)
