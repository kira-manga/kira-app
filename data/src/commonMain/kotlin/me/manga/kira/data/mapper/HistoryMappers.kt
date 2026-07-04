package me.manga.kira.data.mapper

import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.domain.model.history.HistoryEntry

/**
 * Entity ↔ domain mappers for the History slice.
 *
 * Phase 7.x.history rework. Translates between the rework `:domain` [HistoryEntry] (pure data
 * class, no Room annotations) and the legacy `:shared` Room entity
 * [me.manga.kira.data.local.entity.HistoryItemD] (annotated `@Entity(tableName = "history_items")`).
 *
 * SRP (contract §6): one file owns the [HistoryItemD] ↔ [HistoryEntry] translation. Domain types
 * stay free of Room; the entity stays free of domain imports. Same posture as `LibraryMappers.kt`
 * and `MangaDetailsMappers.kt`.
 *
 * Field-by-field shape parity: the domain [HistoryEntry] is intentionally a 14-field mirror of
 * the entity (see [HistoryEntry] KDoc for why the full nav payload lives on the model). The
 * mapping is a straight copy with no derivation, validation, or default substitution. Both
 * directions are total functions — no information is lost on either round trip.
 *
 * Why entity → domain returns a non-nullable [HistoryEntry] (vs `HistoryEntry?` with a "skip
 * malformed rows" branch): the entity's fields are all non-nullable per the Room schema (only
 * `localImagePaths` defaults to `listOf()` and `lastReadDate` defaults to `Clock.System.now()`,
 * neither of which are null). The legacy code paths never produce a partial entity; the mapper
 * doesn't need to defend against one.
 *
 * Why a top-level `internal` extension (vs a `class` with `map(entry: ...)`): same convention
 * the existing mappers use. Extension functions on the entity type make the call site read
 * naturally (`entity.toDomain()` / `entry.toEntity()`); `internal` visibility keeps the mapping
 * an implementation detail of `:data`.
 */
internal fun HistoryItemD.toDomain(): HistoryEntry = HistoryEntry(
    id = id,
    api = api,
    language = language,
    mangaId = mangaId,
    mangaUrl = mangaUrl,
    mangaTitle = mangaTitle,
    mangaImageUrl = mangaImageUrl,
    chapterUrl = chapterUrl,
    chapterTitle = chapterTitle,
    isDownloaded = isDownloaded,
    localImagePaths = localImagePaths,
    lastReadDate = lastReadDate,
    lastReadPage = lastReadPage,
    totalPages = totalPages,
)

internal fun HistoryEntry.toEntity(): HistoryItemD = HistoryItemD(
    id = id,
    api = api,
    language = language,
    mangaId = mangaId,
    mangaUrl = mangaUrl,
    mangaTitle = mangaTitle,
    mangaImageUrl = mangaImageUrl,
    chapterUrl = chapterUrl,
    chapterTitle = chapterTitle,
    isDownloaded = isDownloaded,
    localImagePaths = localImagePaths,
    lastReadDate = lastReadDate,
    lastReadPage = lastReadPage,
    totalPages = totalPages,
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster151.staleKdocSweep.cascade,
 * Task #607, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eighty-sixth sibling of the cluster57-150
 * sweep — opening file of the wave-26 :data/mapper tier 4-leaf batch
 * alongside UpdateMappers plus SourcesMappers plus LibraryMappers; OPENS
 * :data/mapper tier 1/4):
 *  (a) "Entity-domain-mappers-for-the-History-slice + Phase-7.x.history-
 *  rework-Translates-between-the-rework-:domain-HistoryEntry-pure-data-
 *  class-no-Room-annotations-and-the-legacy-:shared-Room-entity-History
 *  ItemD-annotated-Entity-tableName-history_items + SRP-contract-section
 *  -6-one-file-owns-the-HistoryItemD-HistoryEntry-translation-Domain-
 *  types-stay-free-of-Room-the-entity-stays-free-of-domain-imports-Same
 *  -posture-as-LibraryMappers.kt-and-MangaDetailsMappers.kt + Field-by-
 *  field-shape-parity-the-domain-HistoryEntry-is-intentionally-a-14-
 *  field-mirror-of-the-entity + The-mapping-is-a-straight-copy-with-no-
 *  derivation-validation-or-default-substitution-Both-directions-are-
 *  total-functions-no-information-is-lost-on-either-round-trip + Why-
 *  entity-domain-returns-a-non-nullable-HistoryEntry-vs-HistoryEntry-
 *  with-a-skip-malformed-rows-branch-the-entity-s-fields-are-all-non-
 *  nullable-per-the-Room-schema + Why-a-top-level-internal-extension-
 *  vs-a-class-with-map-entry-same-convention-the-existing-mappers-use"
 *  — LIVE-NOT-STALE. Verified: HistoryItemD ↔ HistoryEntry 14-field
 *  round-trip mapping shipped. toDomain() + toEntity() symmetric. The
 *  same-posture cross-references to LibraryMappers.kt + MangaDetails
 *  Mappers.kt honored — all three :data mapper files use top-level
 *  internal extensions on the entity type. Field-by-field shape parity
 *  preserved (id + api + language + mangaId + mangaUrl + mangaTitle +
 *  mangaImageUrl + chapterUrl + chapterTitle + isDownloaded + local
 *  ImagePaths + lastReadDate + lastReadPage + totalPages = 14 fields).
 *  Total-function-both-directions stance honored — no information loss
 *  on either round trip. Consumed by HistoryRepositoryImpl (cluster23
 *  sibling X) via .toDomain() / .toEntity() at the impl's read/write
 *  boundaries. One classification. Original Phase 7.x.history (Task
 *  #239) mapper prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
