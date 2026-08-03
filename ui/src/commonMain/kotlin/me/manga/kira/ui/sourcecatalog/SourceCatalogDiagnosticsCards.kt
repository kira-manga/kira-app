@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package me.manga.kira.ui.sourcecatalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.source_catalog_active_count
import me.manga.kira.ui.generated.resources.source_catalog_algorithm
import me.manga.kira.ui.generated.resources.source_catalog_api
import me.manga.kira.ui.generated.resources.source_catalog_base_url
import me.manga.kira.ui.generated.resources.source_catalog_bundled_revision
import me.manga.kira.ui.generated.resources.source_catalog_catalog_schema
import me.manga.kira.ui.generated.resources.source_catalog_checksum
import me.manga.kira.ui.generated.resources.source_catalog_engine
import me.manga.kira.ui.generated.resources.source_catalog_format
import me.manga.kira.ui.generated.resources.source_catalog_generated_at
import me.manga.kira.ui.generated.resources.source_catalog_inactive_count
import me.manga.kira.ui.generated.resources.source_catalog_language
import me.manga.kira.ui.generated.resources.source_catalog_lifecycle
import me.manga.kira.ui.generated.resources.source_catalog_order
import me.manga.kira.ui.generated.resources.source_catalog_previous_checksum
import me.manga.kira.ui.generated.resources.source_catalog_previous_revision
import me.manga.kira.ui.generated.resources.source_catalog_removed_count
import me.manga.kira.ui.generated.resources.source_catalog_revision
import me.manga.kira.ui.generated.resources.source_catalog_signing_key
import me.manga.kira.ui.generated.resources.source_catalog_source_revision
import me.manga.kira.ui.generated.resources.source_catalog_source_schema
import me.manga.kira.ui.generated.resources.source_catalog_sync_status
import me.manga.kira.ui.generated.resources.source_catalog_trust
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SourceCatalogOverviewCard(model: SourceCatalogDiagnosticsUiModel) {
    SourceCatalogDetailsCard {
        SourceCatalogDetailRow(stringResource(Res.string.source_catalog_revision), model.catalogRevision.toString())
        SourceCatalogDetailDivider()
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_active_count),
            model.activeSources.size.toString(),
        )
        SourceCatalogDetailDivider()
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_catalog_schema),
            model.catalogSchemaVersion.toString(),
        )
        SourceCatalogDetailDivider()
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_source_schema),
            model.sourceSchemaVersion.toString(),
        )
        SourceCatalogDetailDivider()
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_generated_at),
            model.generatedAt.orUnavailable(),
        )
        SourceCatalogDetailDivider()
        SourceCatalogDetailRow(stringResource(Res.string.source_catalog_trust), trustLabel(model.origin))
    }
}

@Composable
internal fun SourceCatalogManifestCard(model: SourceCatalogDiagnosticsUiModel) {
    SourceCatalogDetailsCard {
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_checksum),
            model.manifestChecksum.orUnavailable(),
            true,
        )
        SourceCatalogDetailDivider()
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_signing_key),
            model.manifestSigningKeyId.orUnavailable(),
            true,
        )
        SourceCatalogDetailDivider()
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_algorithm),
            model.signatureAlgorithm.orUnavailable(),
        )
        SourceCatalogDetailDivider()
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_format),
            model.signatureFormat.orUnavailable(),
            true,
        )
        SourceCatalogDetailDivider()
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_previous_revision),
            model.previousCatalogRevision?.toString().orUnavailable(),
        )
        SourceCatalogDetailDivider()
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_previous_checksum),
            model.previousCatalogChecksum.orUnavailable(),
            true,
        )
        SourceCatalogDetailDivider()
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_removed_count),
            model.removedSourceCount.toString(),
        )
        SourceCatalogDetailDivider()
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_inactive_count),
            model.inactiveSourceCount.toString(),
        )
    }
}

@Composable
internal fun SourceCatalogOriginCard(model: SourceCatalogDiagnosticsUiModel) {
    val spacing = LocalSpacing.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = model.origin.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = originTitle(model.origin),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = originDescription(model.origin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(spacing.xs))
                Text(
                    text = stringResource(Res.string.source_catalog_sync_status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = syncStatus(model.syncStatus),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
internal fun SourceCatalogSourceCard(
    source: SourceCatalogSourceUiModel,
    bundledCatalogRevision: Long,
) {
    val spacing = LocalSpacing.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            SourceHeader(source)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SourceDetails(source, bundledCatalogRevision)
        }
    }
}

@Composable
private fun SourceHeader(source: SourceCatalogSourceUiModel) {
    val spacing = LocalSpacing.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.VerifiedUser,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(source.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = source.api,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceDetails(
    source: SourceCatalogSourceUiModel,
    bundledCatalogRevision: Long,
) {
    SourceCatalogDetailRow(
        stringResource(Res.string.source_catalog_source_revision),
        source.sourceRevision?.toString().orUnavailable(),
    )
    if (source.sourceRevision == null) {
        SourceCatalogDetailRow(
            stringResource(Res.string.source_catalog_bundled_revision),
            bundledCatalogRevision.toString(),
        )
    }
    SourceCatalogDetailRow(stringResource(Res.string.source_catalog_api), source.api, true)
    SourceCatalogDetailRow(stringResource(Res.string.source_catalog_language), source.language)
    SourceCatalogDetailRow(stringResource(Res.string.source_catalog_order), source.order.toString())
    SourceCatalogDetailRow(stringResource(Res.string.source_catalog_lifecycle), source.lifecycle)
    SourceCatalogDetailRow(stringResource(Res.string.source_catalog_engine), source.engine)
    SourceCatalogDetailRow(stringResource(Res.string.source_catalog_base_url), source.baseUrl, true)
    source.checksum?.let { SourceCatalogDetailRow(stringResource(Res.string.source_catalog_checksum), it, true) }
    source.signingKeyId?.let { SourceCatalogDetailRow(stringResource(Res.string.source_catalog_signing_key), it, true) }
}

@Composable
internal fun SourceCatalogSectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun SourceCatalogDetailsCard(content: @Composable ColumnScope.() -> Unit) {
    val spacing = LocalSpacing.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.lg, vertical = spacing.sm),
            content = content,
        )
    }
}

@Composable
private fun SourceCatalogDetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

@Composable
private fun SourceCatalogDetailDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
