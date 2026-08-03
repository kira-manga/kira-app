@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package me.manga.kira.ui.sourcecatalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import me.manga.kira.ui.components.KiraIconButton
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.source_catalog_manifest_details
import me.manga.kira.ui.generated.resources.source_catalog_overview
import me.manga.kira.ui.generated.resources.source_catalog_sources
import me.manga.kira.ui.generated.resources.source_catalog_title
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceCatalogDiagnosticsScreen(
    model: SourceCatalogDiagnosticsUiModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.source_catalog_title),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    KiraIconButton(
                        icon = KiraIcons.Back,
                        contentDescription = stringResource(Res.string.back),
                        onClick = onBack,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { scaffoldPadding ->
        SourceCatalogDiagnosticsContent(model = model, contentPadding = scaffoldPadding)
    }
}

@Composable
private fun SourceCatalogDiagnosticsContent(
    model: SourceCatalogDiagnosticsUiModel,
    contentPadding: PaddingValues,
) {
    val spacing = LocalSpacing.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = spacing.lg,
                top = contentPadding.calculateTopPadding() + spacing.md,
                end = spacing.lg,
                bottom = contentPadding.calculateBottomPadding() + spacing.xl,
            ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item(key = "origin") { SourceCatalogOriginCard(model) }
        overviewSection(model)
        if (model.origin != SourceCatalogOriginUi.BUNDLED) manifestSection(model)
        sourcesSection(model)
    }
}

private fun LazyListScope.overviewSection(model: SourceCatalogDiagnosticsUiModel) {
    item(key = "overview-title") {
        SourceCatalogSectionTitle(stringResource(Res.string.source_catalog_overview))
    }
    item(key = "overview") { SourceCatalogOverviewCard(model) }
}

private fun LazyListScope.manifestSection(model: SourceCatalogDiagnosticsUiModel) {
    item(key = "manifest-title") {
        SourceCatalogSectionTitle(stringResource(Res.string.source_catalog_manifest_details))
    }
    item(key = "manifest") { SourceCatalogManifestCard(model) }
}

private fun LazyListScope.sourcesSection(model: SourceCatalogDiagnosticsUiModel) {
    item(key = "sources-title") {
        SourceCatalogSectionTitle(stringResource(Res.string.source_catalog_sources))
    }
    items(items = model.activeSources, key = { it.api }) { source ->
        SourceCatalogSourceCard(source = source, bundledCatalogRevision = model.catalogRevision)
    }
}
