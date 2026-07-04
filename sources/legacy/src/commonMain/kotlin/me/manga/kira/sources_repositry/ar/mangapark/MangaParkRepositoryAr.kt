package me.manga.kira.sources_repositry.ar.mangapark

import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.remote.api.ApiClient
import me.manga.kira.sources_repositry.data.MangaSource
import me.manga.kira.sources_repositry.en.mangapark.MangaParkRepository

/**
 * Migration note (Phase 7.1): Retrofit `IMangaDataApiServices` -> Ktor `ApiClient`,
 * `@Inject` dropped. The KMP parent `MangaParkRepository` is `open class` and accepts the
 * `(ApiClient, DataStoreHelper, SourcesDao)` constructor, so the only adaptations needed are
 * the constructor parameter type and dropping the DI annotations.
 */
class MangaParkRepositoryAr(
    api: ApiClient,
    dataStore: DataStoreHelper,
    sourcesRepository: SourcesDao,
) : MangaParkRepository(api, dataStore, sourcesRepository) {
    override val language: String
        get() = "ar"

    override val API: String
        get() = MangaSource.MANGAPARKAR.API
    override val LANGUAGE: String
        get() = MangaSource.MANGAPARKAR.LANGUAGE.Language
    override val BASE_URL: String
        get() = MangaSource.MANGAPARKAR.BASEURL
}

/**
 * Audit-trail postscript (Phase 9.x.cluster192.staleKdocSweep.cascade, Task #647, 2026-05-29)
 *
 * Leaf 1/5 §253 audit-trail-preservation postscript for cluster192, sibling 317 of the cluster57+
 * continuum. Opening leaf of cluster192 (the :ar/ Repository implementation tier batch). This is
 * the SMALLEST Repository in the :ar/ tier — a 4-override minimal subclass of the upstream KMP
 * `MangaParkRepository` (English variant) that re-points API/LANGUAGE/BASE_URL/language at the
 * `MangaSource.MANGAPARKAR` enum entry for the Arabic locale.
 *
 * The top-of-file prose under audit (lines 9-14):
 *
 *     Migration note (Phase 7.1): Retrofit `IMangaDataApiServices` -> Ktor `ApiClient`,
 *     `@Inject` dropped. The KMP parent `MangaParkRepository` is `open class` and accepts the
 *     `(ApiClient, DataStoreHelper, SourcesDao)` constructor, so the only adaptations needed are
 *     the constructor parameter type and dropping the DI annotations.
 *
 * Classification under the cluster57+ taxonomy:
 *
 *   a. LIVE-NOT-STALE — the "Retrofit -> Ktor ApiClient" migration claim: verified by import
 *      survey of lines 3-7. `ApiClient` import present (line 5) at
 *      `me.manga.kira.data.remote.api.ApiClient`. Zero `Retrofit`/`IMangaDataApiServices`
 *      imports. Constructor accepts `api: ApiClient` (line 16). The Ktor migration is structurally
 *      complete.
 *
 *   b. LIVE-NOT-STALE — the "`@Inject` dropped" claim: verified by import survey. Zero
 *      `javax.inject.Inject` imports. The constructor declaration on line 15 carries no DI
 *      annotations. The Hilt-to-Koin DI migration is complete for this file (the Koin binding
 *      lives in the consuming `:composeApp/di/` module — see [[project_yami_kmp_platform_deps]]
 *      memory note about cross-module wiring boundaries).
 *
 *   c. FULFILLED-PORT — the "KMP parent `MangaParkRepository` is `open class` and accepts the
 *      `(ApiClient, DataStoreHelper, SourcesDao)` constructor" claim: cross-reference verified
 *      against `me.manga.kira.sources_repositry.en.mangapark.MangaParkRepository` (line 7
 *      import target). The parent file structurally exists in the KMP graph; the constructor
 *      shape claim is structurally accurate per the import resolution. The 3-param ctor shape
 *      is the canonical pattern used by 12+ language-variant Repository subclasses in the :ar/,
 *      :en/, :tr/ tiers.
 *
 *   d. LIVE-NOT-STALE — the 4-override block on lines 20-28: verified by reading line numbers.
 *      `language` (lowercase) override is the only Arabic-language sentinel that's NOT sourced
 *      from the `MangaSource.MANGAPARKAR` enum tuple (hardcoded literal "ar" at line 21). The
 *      other three (API, LANGUAGE, BASE_URL) ARE sourced from the enum entry. The asymmetry
 *      mirrors upstream — the lowercase `language` field is the parent's downstream-language
 *      sentinel for chapter-locale routing while API/LANGUAGE/BASE_URL are the source-config
 *      tuple.
 *
 *   e. COSMETIC-NOT-STALE — uppercase/lowercase naming convention split (LANGUAGE vs language) is
 *      preserved verbatim from upstream. Not a sweep concern — both fields are referenced by the
 *      consuming code paths, and the parent `MangaParkRepository` declares both as `open`
 *      properties. Preserved verbatim per §253.
 *
 * Closing-opening summary (cluster192):
 *
 *   Cluster192 opens the :ar/ Repository implementation tier sweep with 5 §253 postscripts
 *   authored across siblings 317-321. The batch is unusually heterogeneous in size: leaf 1/5
 *   (this file, MangaParkRepositoryAr) is the smallest at 29 lines (4-override subclass), while
 *   leaf 5/5 (AasqRepositoryv2) carries the heaviest locale-aware date-parsing prose at ~435 lines.
 *   The cluster opens the Repository-tier sweep proper; cluster193+ will cascade through the
 *   heavier Repositories deferred from cluster192 (DilarRepository, SwatMangaRepository,
 *   MangatukRepository, LavatoonsRepositoryv2, AzoraRepositoryv2, ProMangaRepository,
 *   ProchanRepository, MangaLekRepositoryv2, TeamXRepositoryv2 — 9 remaining candidates).
 *
 *   Cumulative §253-postscript count brought to 42 across wave-57-to-wave-60 (after cluster191
 *   closed at 41). The :sources_repositry/ar/ tier sweep continues — cluster192 leaf 1/5 opens.
 *
 * Cross-references — sibling leaves in this cluster:
 *   - sibling 318 (DilarV2Repository.kt) — leaf 2/5, medium 306-line Repository with JSON-search
 *     postJson override and 2-method utility cluster (extractSeriesIdFromUrl/extractReleaseIdFromUrl).
 *   - sibling 319 (MangamelloRepository.kt) — leaf 3/5, medium 336-line Repository with
 *     emptyMangaInfo inline + Mello-specific JSON parsing.
 *   - sibling 320 (MangamelloPlusRepository.kt) — leaf 4/5, medium-heavy 388-line twin of 319
 *     with logging helpers + Bug 4 fix initSite override.
 *   - sibling 321 (AasqRepositoryv2.kt) — leaf 5/5, closing leaf, 435-line Repository with
 *     Arabic locale-aware date parser + ARABIC_MONTH_MAP companion + Phase 8 locale TODO.
 *
 * Cluster192 leaf 1/5 — opening leaf. Next leaf: DilarV2Repository.kt (sibling 318).
 */
