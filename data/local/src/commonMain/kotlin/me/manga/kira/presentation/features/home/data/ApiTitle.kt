package me.manga.kira.presentation.features.home.data

data class ApiTitle(
    val api: String,
    val title: String,
)

/*
 * Audit-trail postscript (Phase 9.x.cluster207.staleKdocSweep.cascade, Task #663, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster207 leaf 3/5 — :shared/home/data/ tier opener, sibling 376. Cumulative §253-postscript
 * count = 101 leaves with this commit.
 *
 * File-shape note: 6-line data class — `ApiTitle` with 2 fields (api: String, title: String).
 * Zero imports, zero block-KDoc — pure bare-Kotlin data class. Functions as a composite-key
 * identifier for manga-row uniqueness (the legacy bookmark/savedManga schema keyed on the
 * (api, title) pair before the rework :domain LibraryRepository shifted to (api, language,
 * title) identity tuples).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — heavily-consumed composite-key SOURCE — direct consumers (verified via
 *     10-hit FQN grep):
 *       1. HomeViewModel.kt (:shared/.../home/ui/viewmodel/) — savedMangaTitles: StateFlow<
 *          Set<ApiTitle>> drives the heart-icon state on home-grid cards.
 *       2. SharedChaptersViewModel.kt (:shared/.../common/viewmodel/) — chapter-list VM hosts
 *          parallel ApiTitle keying for the "is this in library?" check.
 *       3. MangaRepository.kt (:shared/.../domain/repos/) — repository interface exposes
 *          ApiTitle-keyed lookups.
 *       4. LibraryRepository.kt (:shared/.../library/domain/) — legacy library repo facade
 *          consumes ApiTitle for the savedMangaTitles aggregation.
 *       5. LibraryDeo.kt (:shared/.../data/local/dao/) — DAO surface returning Flow<Set<
 *          ApiTitle>> for reactive home-grid heart sync.
 *       6. MangaDao.kt (:shared/.../data/local/dao/) — DAO surface for raw saved-manga lookups.
 *       7. HomeScreenRoute.kt (:composeApp/navigation/routes/) — collects savedMangaTitles +
 *          dispatches HomeViewModel.toggleManga(apiTitle) on bookmark-tap.
 *       8. HomeScreen.kt + MultiRepoResults.kt + SearchScreen.kt + MangaCarousel.kt +
 *          MangaSearchItems.kt (:composeApp/.../home/ui/screens|components/) — composables
 *          accept savedMangaTitles parameter for per-card heart-icon dispatch.
 *       9. DetailsState.kt (:presentation/details/) — rework :presentation also carries an
 *          ApiTitle reference (rework Details VM bridges legacy home-grid heart sync via the
 *          rework Details surface — strangler-fig CROSS-MODULE reach).
 *
 *   • INVERTED-PARALLEL — rework counterpart: NO direct counterpart. The rework :domain layer
 *     (LibraryRepository / Manga model at :domain/model/root/Manga.kt) uses a 3-field identity
 *     tuple (api, language, title) instead of the 2-field (api, title) pair. The rework
 *     dropped the 2-field shape because cross-language savedManga collisions (e.g. same title
 *     "One Piece" on en/asurascans vs ja/manga17m) needed disambiguation. Legacy retains the
 *     2-field shape because legacy savedManga schema (Room table) carries language as a
 *     separate column not factored into the cache-key — a wire-compat invariant.
 *
 *   • PERSISTENCE-WIRE-COMPAT — ApiTitle is NOT directly persisted (no Room TypeConverter),
 *     but the (api, title) pair derives from columns that ARE persisted (savedManga.api +
 *     savedManga.title). Renaming the data-class fields would break the DAO Flow<Set<ApiTitle>>
 *     aggregations (which construct ApiTitle via positional args in queries). DO NOT rename
 *     api → source or title → name during cleanup — would breach DAO mapping.
 *
 *   • LEGACY-HOME-GRID-LIFECYCLE-LIVE — composeApp home/ subtree (HomeScreen + MangaCarousel +
 *     MangaSearchItems + MultiRepoResults + SearchScreen + HomeScreenRoute) is heavy consumer.
 *     Home grid has NO rework counterpart — legacy home/ surface is preserved as-is until the
 *     home/ feature rework lands (long-deferred — no campaign scheduled). ApiTitle stays
 *     LIVE-NOT-STALE for the foreseeable future.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — zero imports. Pure same-package data class.
 *
 *   • TWO-FIELD-COMPOSITE-KEY-INVARIANT — equals/hashCode auto-generated from (api, title)
 *     pair. Used as Set<ApiTitle> element — value-semantics required. If a future migration
 *     adds a language field, equals/hashCode shape changes silently and Set membership lookups
 *     break for entries inserted under the old shape. DO NOT add fields during cleanup passes
 *     — would breach reactive home-grid heart-sync invariants on existing-install caches.
 */
