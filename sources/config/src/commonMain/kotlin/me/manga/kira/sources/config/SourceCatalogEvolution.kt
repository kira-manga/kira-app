package me.manga.kira.sources.config

import me.manga.kira.sources.contracts.ActiveSourceDiagnostics
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogAcceptanceFloor
import me.manga.kira.sources.contracts.SourceCatalogDiagnostics
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifest
import me.manga.kira.sources.contracts.SourceSelectionLimits
import me.manga.kira.sources.contracts.UpdateState
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument

internal fun manifestErrors(manifest: SourceCatalogManifest, signed: SignedSourceCatalogManifest): List<String> = buildList {
    if (manifest.schemaVersion != 1) add("unsupported manifest schema")
    if (manifest.sourceSchemaVersion != 1) add("unsupported source schema")
    if (manifest.catalogRevision <= 0) add("catalog revision must be positive")
    if (manifest.catalogRevision != signed.metadata.revision) add("catalog revision metadata mismatch")
    if (manifest.generatedAt != signed.metadata.createdAt) add("catalog timestamp metadata mismatch")
    if (manifest.sources.size > SourceSelectionLimits.SOURCES) add("catalog exceeds local source bound")
    if (manifest.removedSources.size > SourceSelectionLimits.SOURCES) add("catalog exceeds local tombstone bound")
    if (manifest.sources.map { it.api }.toSet().size != manifest.sources.size) add("duplicate source api")
    if (manifest.sources.map { it.order } != manifest.sources.indices.toList()) add("source order is not contiguous")
    if (manifest.removedSources.any { it.lifecycle != "removed" }) add("invalid removed tombstone")
    val removed = manifest.removedSources.map { it.api }
    if (removed.any(String::isBlank)) add("blank removed source api")
    if (removed.distinct().size != removed.size) add("duplicate removed source api")
    if (manifest.sources.any { it.api in removed }) add("source is both present and removed")
    manifest.sources.forEach { addAll(entryErrors(it)) }
}

private fun entryErrors(entry: SourceCatalogEntry): List<String> = buildList {
    if (entry.api.isBlank() || entry.sourceRevision <= 0) add("invalid source identity")
    if (!Regex("[0-9a-f]{64}").matches(entry.checksum)) add("invalid source checksum")
    if (entry.lifecycle !in setOf("active", "disabled", "retired")) add("invalid source lifecycle")
    if (entry.engine != "generic") add("non-generic source is forbidden")
    if (!Regex("[A-Za-z0-9._-]{1,64}").matches(entry.sourceSigningKeyId) || entry.sourceSignature.isBlank()) {
        add("invalid source signature metadata")
    }
}

internal fun catalogEvolutionErrors(
    candidate: SourceCatalogManifest,
    previous: SourceCatalogManifest?,
    previousDocument: SourceConfigDocument,
): List<String> = buildList {
    val entries = candidate.sources.associateBy { it.api }
    val removed = candidate.removedSources.mapTo(hashSetOf()) { it.api }
    val oldEntries = previous?.sources?.associateBy { it.api }.orEmpty()
    val oldApis = previous?.sources?.map { it.api } ?: previousDocument.sources.map { it.api }
    if ((oldApis.toSet() - entries.keys - removed).isNotEmpty()) add("previous source is absent without a removed tombstone")
    val oldRemoved = previous?.removedSources?.mapTo(hashSetOf()) { it.api }.orEmpty()
    if (!removed.containsAll(oldRemoved)) add("removed tombstone was discarded")
    if (entries.keys.any { it in oldRemoved }) add("removed source was reintroduced")
    oldEntries.forEach { (api, old) ->
        val next = entries[api] ?: return@forEach
        if (next.sourceRevision < old.sourceRevision) add("source revision rollback is forbidden")
        if (next.sourceRevision == old.sourceRevision && next.checksum != old.checksum) {
            add("immutable source revision checksum changed")
        }
    }
}

internal fun chainAdvances(signed: SignedSourceCatalogManifest, floor: SourceCatalogAcceptanceFloor?): Boolean {
    if (floor == null) return true
    val previous = signed.metadata.previousRevision ?: return false
    return previous >= floor.catalogRevision && (previous != floor.catalogRevision || signed.metadata.previousChecksum == floor.checksum)
}

internal fun catalogDiagnostics(catalog: VerifiedCatalog, origin: UpdateState.Origin): SourceCatalogDiagnostics {
    val document = catalog.document
    val signed = catalog.stored?.manifest
    val manifest = catalog.manifest
    return SourceCatalogDiagnostics(
        origin = origin, catalogRevision = document.revision,
        catalogSchemaVersion = manifest?.schemaVersion ?: document.schemaVersion,
        sourceSchemaVersion = document.schemaVersion, generatedAt = document.generatedAt,
        manifestChecksum = signed?.metadata?.checksum, manifestSigningKeyId = signed?.metadata?.keyId,
        signatureAlgorithm = signed?.metadata?.algorithm, signatureFormat = signed?.metadata?.format,
        previousCatalogRevision = signed?.metadata?.previousRevision,
        previousCatalogChecksum = signed?.metadata?.previousChecksum,
        removedSourceCount = manifest?.removedSources?.size ?: 0,
        inactiveSourceCount = manifest?.sources?.count { it.lifecycle != "active" } ?: 0,
        activeSources = document.sources.mapIndexed { index, config ->
            sourceDiagnostics(config, manifest?.sources?.singleOrNull { it.api == config.api }, index)
        },
    )
}

private fun sourceDiagnostics(config: SourceConfig, entry: SourceCatalogEntry?, index: Int) = ActiveSourceDiagnostics(
    api = config.api, displayName = config.displayName, language = config.language, baseUrl = config.baseUrl,
    engine = config.engine, lifecycle = config.lifecycle, order = entry?.order ?: config.priority.takeIf { it >= 0 } ?: index,
    sourceRevision = entry?.sourceRevision, checksum = entry?.checksum, signingKeyId = entry?.sourceSigningKeyId,
)
