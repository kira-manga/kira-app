package me.manga.kira.data.mapper

import kotlin.time.Instant
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga

/**
 * Entity ↔ domain mappers for the Library slice.
 *
 * SRP (contract §6): one file owns the SavedMangaEntity ↔ Manga / LibraryManga translation.
 * Domain types stay free of Room/Compose, and the SavedMangaEntity stays free of domain types.
 *
 * Migration note (Phase 4 + Task #324): SavedMangaEntity carries Android-leaning fields the
 * domain intentionally does NOT expose (id, savedTimestamp, lastOpenTimestamp, description,
 * status, imageUrl). Those remain available to the legacy :shared code paths until each
 * downstream feature migrates to a domain-shaped equivalent. The `isLiked` / `isWatchingNow`
 * columns USED to be on that not-exposed list — Task #324's category-tabs foundation lifted
 * both into [LibraryManga] as pass-throughs, since the rework Library's `LibraryCategory`
 * axis filters by per-manga affinity. Mutation of the two flags is still owned by the legacy
 * code paths (Details-screen heart toggle, "watching now" mark) until a later slice ports
 * those toggles into `:domain`.
 */
internal fun SavedMangaEntity.toDomainManga(): Manga = Manga(
    api = api,
    language = language,
    title = title,
    url = url,
    coverUrl = imageUrl,
    rating = rating?.toIntOrNull(),
    genres = genres,
)

/**
 * Build a [LibraryManga] from the legacy entity + the per-manga chapter metrics already computed
 * by [me.manga.kira.data.local.dao.MangaDao.getAllChapterMetricsFlow]. Mirrors the legacy
 * two-Flow aggregate pattern (see baseline §11 "Bug 3 / Desktop InvalidationTracker" note) so
 * observable behavior is preserved. The legacy `LibraryRepository.getDisplayItemsFlow` that
 * originated the pattern was retired in Phase 9.x.mangadisplayitem.retire — this mapper is the
 * rework's owner of the same combine-and-project shape, consumed by
 * [me.manga.kira.data.repository.LibraryRepositoryImpl.observeLibrary].
 *
 * [LibraryManga.unreadCount] = `totalChapters - readCount`.
 * [LibraryManga.hasDownloads] = `downloadedCount > 0`.
 * [LibraryManga.totalChapters] = `totalChapters` (pass-through; feeds the TOTAL_CHAPTERS sort).
 * [LibraryManga.lastReadAt] = `Instant.fromEpochMilliseconds(lastReadTs)` when `lastReadTs > 0`,
 *   else `null` (a `0L` from `MAX(lastReadDate)` means "manga has chapters but none read";
 *   `null` means "no rows" — both surface as "never read" semantically, so coalesce here).
 * [LibraryManga.bookmarkedCount] = `bookmarkedCount` (pass-through; feeds the BOOKMARKED filter
 *   axis predicate `bookmarkedCount > 0`).
 * [LibraryManga.downloadedCount] = `downloadedCount` (pass-through; feeds the §150-rung-17 per-card
 *   "↓ N" caption). Same column the `hasDownloads = downloadedCount > 0` boolean is derived from
 *   — both the boolean (DOWNLOADED filter predicate) and the raw count (richer card caption)
 *   coexist with no schema migration.
 * [LibraryManga.isLiked] = `isLiked` (pass-through; feeds the `LibraryCategory.LIKED` tab
 *   predicate, Task #324). No schema migration — `SavedMangaEntity.isLiked` has been on the
 *   `saved_manga` table since the original APK.
 * [LibraryManga.isWatchingNow] = `isWatchingNow` (pass-through; feeds the
 *   `LibraryCategory.WATCHING_NOW` tab predicate, Task #324). Same no-migration posture.
 */
internal fun SavedMangaEntity.toLibraryManga(
    totalChapters: Int,
    readCount: Int,
    downloadedCount: Int,
    lastReadTs: Long?,
    bookmarkedCount: Int,
): LibraryManga = LibraryManga(
    manga = toDomainManga(),
    addedAt = Instant.fromEpochMilliseconds(savedTimestamp),
    unreadCount = (totalChapters - readCount).coerceAtLeast(0),
    hasDownloads = downloadedCount > 0,
    totalChapters = totalChapters,
    lastReadAt = lastReadTs?.takeIf { it > 0L }?.let(Instant::fromEpochMilliseconds),
    lastOpenedAt = Instant.fromEpochMilliseconds(lastOpenTimestamp),
    bookmarkedCount = bookmarkedCount,
    downloadedCount = downloadedCount,
    isLiked = isLiked,
    isWatchingNow = isWatchingNow,
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster151.staleKdocSweep.cascade,
 * Task #607, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eighty-ninth sibling of the cluster57-150
 * sweep — CLOSING file of the wave-26 :data/mapper tier 4-leaf batch
 * alongside HistoryMappers plus UpdateMappers plus SourcesMappers; CLOSES
 * :data/mapper tier 4/4):
 *  (a) "Entity-domain-mappers-for-the-Library-slice + SRP-contract-
 *  section-6-one-file-owns-the-SavedMangaEntity-Manga-LibraryManga-
 *  translation + Domain-types-stay-free-of-Room-Compose-and-the-Saved
 *  MangaEntity-stays-free-of-domain-types + Migration-note-Phase-4-
 *  plus-Task-324-SavedMangaEntity-carries-Android-leaning-fields-the-
 *  domain-intentionally-does-NOT-expose-id-savedTimestamp-lastOpen
 *  Timestamp-description-status-imageUrl + Those-remain-available-to-
 *  the-legacy-:shared-code-paths-until-each-downstream-feature-migrates
 *  -to-a-domain-shaped-equivalent + The-isLiked-isWatchingNow-columns-
 *  USED-to-be-on-that-not-exposed-list-Task-324-s-category-tabs-
 *  foundation-lifted-both-into-LibraryManga-as-pass-throughs-since-the
 *  -rework-Library-s-LibraryCategory-axis-filters-by-per-manga-affinity
 *  + Mutation-of-the-two-flags-is-still-owned-by-the-legacy-code-paths
 *  -Details-screen-heart-toggle-watching-now-mark-until-a-later-slice-
 *  ports-those-toggles-into-:domain" — LIVE-NOT-STALE plus
 *  PARTIALLY-FULFILLED-FORECAST. Verified: SavedMangaEntity → Manga
 *  one-way mapping (toDomainManga) + SavedMangaEntity → LibraryManga
 *  combine-and-project mapping (toLibraryManga) both shipped. Field-
 *  drop discipline honored — 6 of N entity columns (id +
 *  savedTimestamp partially via addedAt re-derivation + lastOpen
 *  Timestamp + description + status + imageUrl partially via coverUrl
 *  remap) intentionally NOT exposed on Manga; LibraryManga widens the
 *  surface only for the 4 lifted axes (isLiked + isWatchingNow +
 *  totalChapters + lastReadAt + bookmarkedCount + downloadedCount).
 *  The Task #324 category-tabs forecast is FULFILLED — isLiked +
 *  isWatchingNow now LIVE on LibraryManga; LibraryCategory.LIKED /
 *  WATCHING_NOW tab predicates consume them via the rework Library
 *  filter pipeline. The "mutation of the two flags is still owned by
 *  legacy code paths" stance is PARTIALLY-FULFILLED — the Details-
 *  screen heart toggle was MIGRATED to :domain at Task #426 (slice 1
 *  of the Phase 7.x.details.parity campaign — ObserveInLibraryUseCase
 *  + ToggleInLibraryUseCase land the bookmark toggle on the rework
 *  Details surface). The "watching now" mark mutation REMAINS owned
 *  by legacy code paths — no slice has ported it to :domain yet
 *  (deferred until a Watching-Now-rework slice is scheduled, currently
 *  not on any open task). Consumed by LibraryRepositoryImpl
 *  (cluster23 sibling X) via .toLibraryManga() in the observeLibrary
 *  two-Flow combine pipeline (MangaDao.getAllChapterMetricsFlow +
 *  SavedMangaEntity flow). The "Bug 3 / Desktop InvalidationTracker"
 *  baseline §11 reference + the retired getDisplayItemsFlow citation
 *  (Phase 9.x.mangadisplayitem.retire) honored — the rework
 *  observeLibrary path is the owner of the combine-and-project shape
 *  that the retired legacy Flow used to provide.
 *  This is the CLOSING FILE of cluster151 — completes the wave-26
 *  :data/mapper tier sweep (4 of 4: HistoryMappers + UpdateMappers +
 *  SourcesMappers + LibraryMappers). The two already-swept :data/
 *  mapper files (DownloadsMappers + MangaDetailsMappers, swept during
 *  the cluster23-25 :data/repository sweep as inline mapper coverage)
 *  bring the :data/mapper layer to FULLY-SWEPT — 6 of 6 files.
 *  One classification. Original Phase 4 + Task #324 LibraryMappers
 *  prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
