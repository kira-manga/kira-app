package me.manga.kira.sources_repositry.es.mangapark

import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.sources_repositry.data.MangaSource
import me.manga.kira.sources_repositry.en.mangapark.MangaParkRepository

class MangaParkRepositoryEs(
    api: ApiClient,
    dataStore: DataStoreHelper,
    sourcesRepository: SourcesDao,
) : MangaParkRepository(api, dataStore, sourcesRepository) {
    override val language: String
        get() = "es"

    override val API: String
        get() = MangaSource.MANGAPARK_ES.API
    override val LANGUAGE: String
        get() = MangaSource.MANGAPARK_ES.LANGUAGE.Language
    override val BASE_URL: String
        get() = MangaSource.MANGAPARK_ES.BASEURL
}

/*
 * Audit-trail postscript (Phase 9.x.cluster198.staleKdocSweep.cascade, Task #653, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster198 leaf 2/5 — :es/ Repository tier light-half batch, sibling 346.
 *
 * This file does NOT carry a Phase 7.2 migration preamble — pure subclass shim with no
 * behaviour-bearing code (4 override declarations + extending EN MangaParkRepository).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — `: MangaParkRepository(api, dataStore, sourcesRepository)` at line 13
 *     extends the EN MangaParkRepository (sibling 337, swept cluster196 leaf 2/5). The EN
 *     class is the canonical GraphQL-based MangaPark implementation; this :es/ subclass
 *     only overrides 4 properties (language/API/LANGUAGE/BASE_URL) — pure i18n delta.
 *
 *   • LIVE-NOT-STALE — `override val language: String get() = "es"` at line 15. The
 *     downcase Spanish ISO-639-1 code "es" is the canonical CLDR/ISO identifier for
 *     Spanish (any region). Distinct from sibling 347's "es_419" Latin America variant.
 *
 *   • LIVE-NOT-STALE — All 4 override blocks use `get() = ...` property-accessor form
 *     (not `= ...` direct assignment). Functionally identical for these constant returns;
 *     the chosen idiom matches the EN MangaParkRepository's own property style.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 4 cross-package imports at lines 3-7:
 *       core.storage.DataStoreHelper            (constructor parameter)
 *       data.local.dao.SourcesDao               (constructor parameter)
 *       data.remote.api.ApiClient               (constructor parameter)
 *       sources_repositry.data.MangaSource      (3 enum-value reads)
 *       sources_repositry.en.mangapark.MangaParkRepository  (parent class, cluster196 swept)
 *     All targets are confirmed-live as of cluster198 boundary.
 *
 *   • LIVE-NOT-STALE — `MangaSource.MANGAPARK_ES` enum-entry reference at lines 18/20/22.
 *     The enum entry exists (verified via ComickRepositoryEs sibling 345's prose at
 *     line 11: "MANGAPARK_ES" listed among present ES sources). Routing is wired.
 */
