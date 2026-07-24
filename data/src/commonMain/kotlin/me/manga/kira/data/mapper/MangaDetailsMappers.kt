package me.manga.kira.data.mapper

import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.ChapterItem as LegacyChapterItem
import me.manga.kira.domain.model.MangaInfo as LegacyMangaInfo

/**
 * Source-DTO ↔ domain mappers for the Details slice.
 *
 * SRP (contract §6): owns the legacy [LegacyMangaInfo] / [LegacyChapterItem] → rework
 * [MangaDetails] / [Chapter] translation and nothing else. Network policy, dispatcher wrapping
 * and error classification live in [me.manga.kira.data.repository.MangaDetailsRepositoryImpl].
 *
 * Why the legacy types are aliased to `LegacyXxx` on import:
 *  - The legacy `MangaInfo` / `ChapterItem` and the rework `MangaDetails` / `Chapter` share the
 *    same `me.manga.kira.domain.model` package across the two source-set roots (`:shared`
 *    vs. `:domain`). Without the alias the same simple-name resolves ambiguously when the file
 *    references both at once.
 *  - The alias is mapper-local: `:data`'s public surface (the impl signature) only exposes the
 *    rework types. Legacy types stay quarantined in this file.
 *
 * Field-by-field translation choices (contract §13 functionality-preservation gate — these are
 * lossless w.r.t. what the Details screen actually renders):
 *  - `imageUrl` → `coverUrl`: rework picked the more conventional name (also matches
 *    [me.manga.kira.domain.model.Manga.coverUrl]).
 *  - `MutableList<ChapterItem>` → `List<Chapter>`: rework domain models are immutable
 *    (contract §4). The mapper produces a fresh `List` so legacy code that still holds the
 *    underlying `MutableList` reference can't mutate the rework model after-the-fact.
 *  - `ChapterItem.chaptersImages: List<ChapterImage>` is dropped — chapter pages belong to the
 *    Reader slice's future `ChapterPages` model (see [Chapter] KDoc). Embedding pages in the
 *    chapter list inflated every Details payload for no benefit.
 *  - The 6 orphan fields the legacy `MangaInfo` still declares (`artist` / `ratingCount` /
 *    `favoritesCount` / `otherNames` / `yearOfProduction` / `tags`) are NOT copied — neither the
 *    rework `DetailsScreen` nor any legacy consumer reads them today. Field-prune cascaded to the
 *    rework [MangaDetails] in the same commit; the legacy `MangaInfo` gets defaults added here
 *    and the fields stripped after every source-repo writer's named-arg writes are cleaned up
 *    (Phase 9.y.mangainfo.fieldprune.cumulative — Task #417).
 *  - Everything else (`api` / `language` / `title` / `url` / `description` / `author` /
 *    `rating` / `status` / `genres` / `Chapter.{number,name,url,date,isDownloaded,isBookmarked}`)
 *    is a 1:1 copy — same nullability, same string-vs-typed shape (see [MangaDetails] KDoc on
 *    why `rating` / `status` stay as `String`).
 *
 * **Audit-trail postscript** (Phase 9.x.mangainfo.staleKdocSweep.cascade, Task #450,
 * 2026-05-28): the "6 orphan fields the legacy `MangaInfo` still declares" bullet above
 * (lines 33-38) is FACTUALLY STALE. The cited Phase 9.y.mangainfo.fieldprune.cumulative
 * (Task #417) has since landed — the legacy `MangaInfo` no longer declares the 6 fields
 * (`artist` / `ratingCount` / `favoritesCount` / `otherNames` / `yearOfProduction` /
 * `tags`); verified by reading `MangaInfo.kt` (only 11 fields remain: `api` / `language`
 * / `url` / `title` / `imageUrl` / `rating` / `description` / `author` / `genres` /
 * `status` / `chapters`). The "still declares" present-tense framing was accurate when
 * this mapper was first written (the legacy MangaInfo carried the 6 orphan fields then,
 * with defaults added in §417's prep slices); post-§417's final field-drop the fields
 * are GONE. The "NOT copied" rule continues to apply trivially — the mapper can't copy
 * fields that don't exist — and the mapper body's 10-field copy at lines 44-56 below is
 * unchanged. Original §253-era prose preserved verbatim per §253 — the "still declares"
 * framing is historical record of the design lineage; the wire continues to work
 * correctly through the field-prune retire.
 */
internal fun LegacyMangaInfo.toDomain(): MangaDetails = MangaDetails(
    api = api,
    language = language,
    title = title,
    url = url,
    coverUrl = imageUrl,
    description = description,
    author = author,
    rating = rating,
    status = status,
    genres = genres,
    chapters = chapters.map { it.toDomain() },
)

internal fun LegacyChapterItem.toDomain(): Chapter = Chapter(
    number = number,
    name = name,
    url = url,
    date = date,
    isDownloaded = isDownloaded,
    isBookmarked = isBookmarked,
)

/**
 * Room saved-chapter row → domain [Chapter] for the offline/local Details path (regression fix,
 * 2026-05-31). Unlike [LegacyChapterItem.toDomain] (the network fetch), this carries the locally
 * persisted `isRead` / `isDownloaded` / `isBookmarked` flags so a Library-opened manga renders its
 * read/unread + downloaded marks immediately, without a network round-trip.
 */
internal fun SavedChapterEntity.toDomainChapter(): Chapter = Chapter(
    number = number,
    name = name,
    url = url,
    date = date,
    isDownloaded = isDownloaded,
    isBookmarked = isBookmarked,
    isRead = isRead,
    // Carries the Room `saved_chapters.isNew` flag (set by a Library refresh-insert, native parity)
    // so the Details chapter row can render the red "NEW" badge for chapters added since the last
    // refresh. Cleared on chapter open via the mark-read path (see MarkChapterReadRepositoryImpl).
    isNew = isNew,
    // Discovery timestamp driving the badge's 4-day read-time expiry (presentation layer).
    fetchedAt = fetchedAt,
)

/**
 * Room saved-manga row + its saved chapters → domain [MangaDetails], the offline projection the
 * [me.manga.kira.data.repository.SavedMangaDetailsRepositoryImpl] emits for a manga already in
 * the library. All source metadata maps 1:1 (cover ← `imageUrl`, rating ← nullable `rating`
 * coalesced to empty for rows imported from older backups).
 */
internal fun SavedMangaEntity.toDomainDetails(chapters: List<SavedChapterEntity>): MangaDetails =
    MangaDetails(
        api = api,
        language = language,
        title = title,
        url = url,
        coverUrl = imageUrl,
        description = description,
        author = author,
        rating = rating ?: "",
        status = status,
        genres = genres,
        chapters = chapters.map { it.toDomainChapter() },
    )

/**
 * Domain [Chapter] → Room [SavedChapterEntity] for the add-to-library persist path (native parity:
 * `ChapterItem.toSavedEntity` in `HandelDataClasses.kt`). Mirrors native's field mapping 1:1:
 *  - `id = 0` so Room auto-generates the surrogate key.
 *  - `mangaId` is a caller-supplied placeholder — [me.manga.kira.data.local.dao.LibraryDeo
 *    .saveMangaWithChapters] re-stamps it with the resolved/created manga id inside its
 *    transaction (`.copy(mangaId = mangaId)`), so the value passed here is overwritten; default 0.
 *  - `date` coalesces a null source date to *today* (native: `date ?: LocalDate.now()`).
 *  - `name` / `number` / `url` / `isDownloaded` / `isBookmarked` copy straight across.
 *  - `isRead` is intentionally NOT carried: the network chapter list has no read history (matches
 *    native, whose `toSavedEntity` omits it), so it defaults `false`. Read marks are owned by the
 *    saved/Room path and merged back via the Details overlay.
 *
 * Insertion order: native reverses the list at the call site before persisting
 * (`...toSavedEntities(1).reversed()`) so the source's newest-first list lands oldest-first in
 * Room (autoincrement `id` then ascends with chapter recency, matching `getChaptersByMangaId`'s
 * `ORDER BY id ASC`). This mapper is per-element; the `.reversed()` is applied by the caller (the
 * `:data` repository impl) to preserve that ordering detail.
 */
@OptIn(ExperimentalTime::class)
internal fun Chapter.toSavedChapterEntity(mangaId: Long = 0L): SavedChapterEntity = SavedChapterEntity(
    id = 0L,
    mangaId = mangaId,
    name = name,
    number = number,
    url = url,
    date = date ?: Clock.System.todayIn(TimeZone.currentSystemDefault()),
    isDownloaded = isDownloaded,
    isBookmarked = isBookmarked,
)

/**
 * Domain [Chapter] → Room [SavedChapterEntity] for the refresh-discovered NEW-chapter persist path
 * (native parity: `LibraryDetailsViewModel.refreshChapters` / `LibraryRefreshWorker` build the
 * entity with `isNew = true`). Identical to [toSavedChapterEntity] except it stamps `isNew = true`
 * and a [fetchedAt] discovery timestamp (epoch-millis) so the badge's 4-day expiry can be evaluated.
 */
@OptIn(ExperimentalTime::class)
internal fun Chapter.toNewSavedChapterEntity(mangaId: Long, fetchedAt: Long): SavedChapterEntity =
    SavedChapterEntity(
        id = 0L,
        mangaId = mangaId,
        name = name,
        number = number,
        url = url,
        date = date ?: Clock.System.todayIn(TimeZone.currentSystemDefault()),
        isDownloaded = isDownloaded,
        isBookmarked = isBookmarked,
        isNew = true,
        fetchedAt = fetchedAt,
    )
