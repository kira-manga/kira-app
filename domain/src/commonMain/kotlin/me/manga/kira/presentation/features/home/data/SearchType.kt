package me.manga.kira.presentation.features.home.data

// Migration note (Phase 4 batch 4.5): subclass identity preserved verbatim including the
// non-idiomatic UPPERCASE SORT/GENRES names — used by source-repo dispatch and re-exposed via
// BaseManga.fetchSearchDataF(when ...).
sealed class SearchType {
    data class Normal(val query: String) : SearchType()
    data class SORT(val query: String, val sortType: String, val genres: String) : SearchType()
    data class GENRES(val query: String, val genres: String) : SearchType()

    fun toNormalQuery(): String = if (this is Normal) query else ""
}

/*
 * Audit-trail postscript (Phase 9.x.cluster207.staleKdocSweep.cascade, Task #663, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster207 leaf 4/5 — :shared/home/data/ tier closer, sibling 377. CLOSES home/data/
 * 2-of-2 with sibling 376 (ApiTitle.kt). Cumulative §253-postscript count = 102 leaves with
 * this commit.
 *
 * File-shape note: 13-line sealed class — `SearchType` with 3 subclasses (Normal with query;
 * SORT with query+sortType+genres; GENRES with query+genres) + 1 helper extension method
 * (toNormalQuery returning query for Normal else ""). 3-line block-line-comment carries Phase 4
 * batch 4.5 port-lineage note. Zero imports — pure same-package sealed.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — MASSIVELY-consumed search-dispatch SOURCE — direct consumers (verified
 *     via 52-hit grep, breakdown):
 *       - 5 home/ui composables (HomeScreen + SearchScreen + MultiRepoResults + MangaSearchItems +
 *         MangaCarousel) + 1 nav route (HomeScreenRoute) — composeApp UI dispatch.
 *       - HomeViewModel + MangaViewModel (:shared/.../presentation/.../viewmodel/) — VM state
 *         carries searchType: StateFlow<SearchType> for query-mode driven page refresh.
 *       - 41 sources_repositry per-language files (ar, en, es, fr, it, in, pt, ru, tr, common
 *         subdirs) — every per-language source repository's fetchSearchDataF() handler
 *         dispatches on SearchType.Normal/SORT/GENRES sub-identity to choose the per-source
 *         search-URL shape (some sources support filter/sort, some support genres, some only
 *         do plain text-query).
 *       - BaseMangaRepository.kt + EmptyMangaRepository.kt (:shared/.../sources_repositry/) —
 *         interface root + null-object default for the source dispatch system.
 *
 *   • PORT-LINEAGE-PRESERVED — `// Migration note (Phase 4 batch 4.5): subclass identity
 *     preserved verbatim including the non-idiomatic UPPERCASE SORT/GENRES names — used by
 *     source-repo dispatch and re-exposed via BaseManga.fetchSearchDataF(when ...).` —
 *     PRESERVE this 3-line line-comment during cleanup passes. Documents the deliberate
 *     non-idiomatic naming choice (SORT/GENRES are ALL-CAPS instead of PascalCase) so future
 *     auto-formatters or readers don't "fix" the names. Per §253 — line-comment preserved
 *     (load-bearing port-lineage marker).
 *
 *   • INVERTED-PARALLEL — rework counterpart: NO direct counterpart. The rework :presentation
 *     layer has NOT lifted the source-search dispatch — sources_repositry/ remains in :shared
 *     under the legacy umbrella (per user direction: "ignore the sources_repositry leave it
 *     like it was i will change the arctecther any way contain in the rest of the app").
 *     SearchType STAYS in :shared as long as sources_repositry/ stays in :shared. No rework
 *     migration scheduled.
 *
 *   • SUBCLASS-IDENTITY-WIRE-CONTRACT-LOAD-BEARING — the (when this is SearchType.Normal →
 *     ... is SORT → ... is GENRES → ...) dispatch pattern is re-implemented in EVERY ONE OF
 *     THE 41 per-language source repositories. Adding a new subclass (e.g. `data class
 *     LATEST` or `data class TAG_FILTERED`) WITHOUT updating all 41 repos would silently fall
 *     to else-branch behavior in each repo (often returning empty results or crashing on
 *     unhandled-case). Mass exhaustive `when` is impractical (no sealed-exhaustiveness check
 *     across compilation-unit boundaries when the sealed parent is sealed in commonMain and
 *     dispatched in commonMain branches per-source). DO NOT add subclasses without a
 *     coordinated 41-repo dispatch pass.
 *
 *   • TONORMALQUERY-EXT-LIVE — `fun toNormalQuery()` is a per-instance method that returns the
 *     raw query for Normal subclass else empty string. Used by search-box state-management in
 *     HomeScreen to re-render the search text when switching subclass identity (e.g. flip
 *     Normal → SORT pre-fills the search box with the prior text). DO NOT inline-collapse to
 *     `(this as? Normal)?.query ?: ""` during cleanup — the existing form matches upstream
 *     verbatim (per Phase 4 batch 4.5 lineage).
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — zero imports. Pure same-package sealed.
 *
 * Cross-cluster :shared/home/data/ subdirectory closer status:
 *
 *   • home/data/ tier is FULLY SWEPT post-this-commit (2-of-2 files: ApiTitle + SearchType).
 *     Remaining home/ subtree (home/ui/ + home/domain/) lives in composeApp (UI composables)
 *     and :shared/sources_repositry/ (the search-dispatch consumers) — out of scope per
 *     user direction. The home/ feature subdir is thus considered FULLY SWEPT for commonMain
 *     prose-bearing audit purposes within the strangler-fig scope.
 *
 *   • Naming-axis posture across cluster207 leaves 3+4 (home/data/):
 *       - ApiTitle (sibling 376) — INVERTED-PARALLEL with PERSISTENCE-WIRE-COMPAT pin: 2-field
 *         composite-key (legacy) vs 3-field identity-tuple (rework).
 *       - SearchType (sibling 377 — this leaf) — INVERTED-PARALLEL with NO-REWORK-COUNTERPART:
 *         sources_repositry/ stays in :shared, search-dispatch never migrates to rework.
 */
