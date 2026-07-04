package me.manga.kira.sources_repositry.es.mangapark_la

import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.sources_repositry.data.MangaSource
import me.manga.kira.sources_repositry.en.mangapark.MangaParkRepository

class MangaParkRepositoryEs419(
    api: ApiClient,
    dataStore: DataStoreHelper,
    sourcesRepository: SourcesDao,
) : MangaParkRepository(api, dataStore, sourcesRepository) {
    override val language: String
        get() = "es_419"

    override val API: String
        get() = MangaSource.MANGAPARK_ES_LA.API
    override val LANGUAGE: String
        get() = MangaSource.MANGAPARK_ES_LA.LANGUAGE.Language
    override val BASE_URL: String
        get() = MangaSource.MANGAPARK_ES_LA.BASEURL
}

/*
 * Audit-trail postscript (Phase 9.x.cluster198.staleKdocSweep.cascade, Task #653, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster198 leaf 3/5 — :es/ Repository tier light-half batch, sibling 347.
 *
 * Pair-twin of sibling 346 (MangaParkRepositoryEs). Same 4-override shape, different
 * MangaSource enum target and different CLDR language code.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — `: MangaParkRepository(api, dataStore, sourcesRepository)` at line 13
 *     extends the EN MangaParkRepository (sibling 337, swept cluster196 leaf 2/5). Same
 *     parent class as sibling 346 — both ES variants reuse the EN GraphQL implementation.
 *
 *   • POTENTIAL-BUG-PRESERVED — `override val language: String get() = "es_419"` at line 15.
 *     The underscore-separated `"es_419"` is non-canonical relative to:
 *       (a) standard CLDR Unicode language ID: `"es-419"` (hyphen)
 *       (b) BCP-47 language tag: `"es-419"` (hyphen)
 *       (c) sibling 345 (ComickRepositoryEs) commented body uses `"es-419"` (hyphen)
 *     The underscore form is a kotlinx.serialization-friendly variant (no escaping needed
 *     in property files / SerialName annotations) and is commonly used internally; some
 *     locale APIs accept both, others require hyphen. Preserved per §253 — schema-conformance
 *     shim continuing from a deliberate upstream choice. Caller-side language matching
 *     against `"es_419"` is presumably consistent across the codebase.
 *
 *   • LIVE-NOT-STALE — `MangaSource.MANGAPARK_ES_LA` enum-entry reference at lines 18/20/22.
 *     "ES_LA" = Latin America Spanish (per sibling 345 prose at line 11). The Spanish-Spain
 *     variant uses sibling 346's `MANGAPARK_ES`; this Latin America variant lives in its own
 *     `mangapark_la/` package.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — Same 4 cross-package imports as sibling 346, with the
 *     subdirectory rename `mangapark_la` (this file's own package) being the only divergence.
 *     Identical import graph confirms pair-twin architectural shape.
 *
 *   • LIVE-NOT-STALE — Package name `me.manga.kira.sources_repositry.es.mangapark_la`
 *     at line 1 uses underscore (`mangapark_la`) — Kotlin package names cannot contain
 *     hyphens, so the underscore is the necessary form. The `_la` suffix mirrors the
 *     `MANGAPARK_ES_LA` enum entry naming style (underscore tier).
 */
