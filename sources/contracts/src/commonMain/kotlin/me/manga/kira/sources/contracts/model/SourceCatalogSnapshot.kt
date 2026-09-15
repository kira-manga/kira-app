package me.manga.kira.sources.contracts.model

/**
 * Display metadata from one complete accepted document, never a per-source union of revisions.
 * [descriptors] contains only active generic sources, in document/manifest order. An empty list is
 * authoritative. Operationally non-working sources remain visible so the UI can explain them.
 */
data class SourceCatalogSnapshot(
    val revision: Long,
    val descriptors: List<RuntimeSourceDescriptor>,
)
