package me.manga.kira.data.mapper

import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.domain.model.updates.UpdateEntry

/**
 * Entity ↔ domain mappers for the Updates slice.
 *
 * Phase 7.x.updates rework. Translates between the rework `:domain` [UpdateEntry] (pure data
 * class, no Room annotations) and the legacy `:shared` Room entity
 * [me.manga.kira.data.local.entity.ChapterNotification] (annotated
 * `@Entity(tableName = "notifications")`).
 *
 * SRP (contract §6): one file owns the [ChapterNotification] ↔ [UpdateEntry] translation. Domain
 * types stay free of Room; the entity stays free of domain imports. Same posture as
 * `HistoryMappers.kt` and `LibraryMappers.kt`.
 *
 * Field-by-field shape parity: the domain [UpdateEntry] is intentionally a 14-field mirror of
 * the entity (see [UpdateEntry] KDoc for why the full nav payload lives on the model). The
 * mapping is a straight copy with no derivation, validation, or default substitution. Both
 * directions are total functions — no information is lost on either round trip.
 *
 * Why entity → domain returns a non-nullable [UpdateEntry] (vs `UpdateEntry?` with a "skip
 * malformed rows" branch): the entity's fields are all non-nullable per the Room schema (the
 * `id` defaults to `0` for autogenerate, `notificationDate` defaults to today, `isRead` /
 * `isDownloaded` default to `false`, `localImagePaths` defaults to `emptyList()`). The legacy
 * code paths never produce a partial entity; the mapper doesn't need to defend against one.
 *
 * Why a top-level `internal` extension (vs a `class` with `map(entry: ...)`): same convention
 * the existing mappers use. Extension functions on the entity type make the call site read
 * naturally (`entity.toDomain()` / `entry.toEntity()`); `internal` visibility keeps the
 * mapping an implementation detail of `:data`.
 */
internal fun ChapterNotification.toDomain(): UpdateEntry = UpdateEntry(
    id = id,
    api = api,
    language = language,
    mangaId = mangaId,
    mangaTitle = mangaTitle,
    mangaImageUrl = mangaImageUrl,
    mangaUrl = mangaUrl,
    chapterId = chapterId,
    chapterNumber = chapterNumber,
    chapterUrl = chapterUrl,
    notificationDate = notificationDate,
    isRead = isRead,
    isDownloaded = isDownloaded,
    localImagePaths = localImagePaths,
)

internal fun UpdateEntry.toEntity(): ChapterNotification = ChapterNotification(
    id = id,
    api = api,
    language = language,
    mangaId = mangaId,
    mangaTitle = mangaTitle,
    mangaImageUrl = mangaImageUrl,
    mangaUrl = mangaUrl,
    chapterId = chapterId,
    chapterNumber = chapterNumber,
    chapterUrl = chapterUrl,
    notificationDate = notificationDate,
    isRead = isRead,
    isDownloaded = isDownloaded,
    localImagePaths = localImagePaths,
)

/**
 * **Audit-trail postscript** (Phase 9.x.cluster151.staleKdocSweep.cascade,
 * Task #607, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eighty-seventh sibling of the cluster57-150
 * sweep — second file of the wave-26 :data/mapper tier 4-leaf batch
 * alongside HistoryMappers plus SourcesMappers plus LibraryMappers):
 *  (a) "Entity-domain-mappers-for-the-Updates-slice + Phase-7.x.updates-
 *  rework-Translates-between-the-rework-:domain-UpdateEntry-pure-data-
 *  class-no-Room-annotations-and-the-legacy-:shared-Room-entity-Chapter
 *  Notification-annotated-Entity-tableName-notifications + SRP-contract
 *  -section-6-one-file-owns-the-ChapterNotification-UpdateEntry-
 *  translation-Domain-types-stay-free-of-Room-the-entity-stays-free-of-
 *  domain-imports-Same-posture-as-HistoryMappers.kt-and-LibraryMappers.
 *  kt + Field-by-field-shape-parity-the-domain-UpdateEntry-is-
 *  intentionally-a-14-field-mirror-of-the-entity + The-mapping-is-a-
 *  straight-copy-with-no-derivation-validation-or-default-substitution-
 *  Both-directions-are-total-functions-no-information-is-lost-on-either
 *  -round-trip + Why-entity-domain-returns-a-non-nullable-UpdateEntry-
 *  vs-UpdateEntry-with-a-skip-malformed-rows-branch-the-entity-s-
 *  fields-are-all-non-nullable-per-the-Room-schema + Why-a-top-level-
 *  internal-extension-vs-a-class-with-map-entry-same-convention-the-
 *  existing-mappers-use" — LIVE-NOT-STALE. Verified: ChapterNotification
 *  ↔ UpdateEntry 14-field round-trip mapping shipped. toDomain() +
 *  toEntity() symmetric. The same-posture cross-references to History
 *  Mappers + LibraryMappers honored. Field-by-field shape parity
 *  preserved (id + api + language + mangaId + mangaTitle + mangaImage
 *  Url + mangaUrl + chapterId + chapterNumber + chapterUrl +
 *  notificationDate + isRead + isDownloaded + localImagePaths = 14
 *  fields). Total-function-both-directions stance honored. Consumed by
 *  UpdatesRepositoryImpl (cluster184 :composeApp/di sibling) via legacy
 *  NotificationRepository strangler-fig — the :shared Notification
 *  Repository surface is the cell-of-truth, the rework :data UpdatesRepo
 *  delegates writes through it and re-maps reads via .toDomain() at
 *  the impl boundary. One classification. Original Phase 7.x.updates
 *  (Task #240) mapper prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
