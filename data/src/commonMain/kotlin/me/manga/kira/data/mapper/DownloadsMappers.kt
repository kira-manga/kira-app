package me.manga.kira.data.mapper

import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.presentation.features.download.data.DownloadingState

/**
 * Legacy → domain mappers for the Downloads slice.
 *
 * Phase 7.x.downloads.foundation rework (data layer). Translates between
 * the legacy `:shared` Room entity [ChapterDownloadEntity] +
 * [DownloadingState] enum and the rework `:domain` [DownloadedChapter] +
 * [DownloadState]. The rework only consumes the legacy types
 * (read-direction); the rework `:data` impl never writes a
 * `ChapterDownloadEntity` directly — mutations go through legacy
 * `DownloadRepository` methods that take `SavedChapterEntity` /
 * `chapterId`. No `toEntity()` mapper needed.
 *
 * SRP (contract §6): one file owns the [ChapterDownloadEntity] →
 * [DownloadedChapter] + [DownloadingState] → [DownloadState] translations.
 * Domain types stay free of Room and legacy package imports; the entity
 * stays free of domain imports. Same posture as `HistoryMappers.kt`.
 *
 * **`mangaTitle` null handling**: legacy entity allows `null`; the
 * legacy `:ui` renders `"Ch X - null"` literally because Kotlin string
 * templates print `"null"` (see legacy `DownloadsScreen.kt:251` —
 * `"Ch ${item.number} - ${item.mangaTitle} "`). The rework lifts the
 * null handling to this mapper: substitute the empty string so the
 * `:domain` model can declare `mangaTitle: String` (non-null), keeping
 * domain consumers null-check-free. The empty-string substitution is
 * arguably cleaner than the legacy's literal "null" rendering — minor
 * observable improvement on the rework path.
 *
 * **Enum mapping is exhaustive 1:1**: all 5 [DownloadingState] variants
 * map to the same-name [DownloadState] variant. The `when (this)` block
 * is exhaustive (Kotlin compiler enforces) — adding a future variant
 * to either enum without adjusting this mapper is a compile-time error.
 *
 * Why a top-level `internal` extension (vs a class): same convention
 * the existing mappers use. Extension function on the entity makes the
 * call site read naturally (`entity.toDomain()`); `internal` visibility
 * keeps the mapping an implementation detail of `:data`.
 *
 * **Audit-trail postscript** (Phase 9.x.downloads.staleKdocSweep.cascade.peers,
 * Task #451, 2026-05-28): the "`mangaTitle` null handling" paragraph above
 * (lines 25-33) cites "legacy `DownloadsScreen.kt:251` —
 * `"Ch ${item.number} - ${item.mangaTitle} "`" as the line-anchored
 * precedent for the literal `"null"`-rendering behaviour. That file
 * (`composeApp/.../features/download/ui/screens/DownloadsScreen.kt`) was
 * retired in Phase 9.x.downloads.legacyui.retire (§352); verified by a
 * filesystem check returning zero hits. The cited line number is no longer
 * reachable by a reader. The design rationale stands on its own merits —
 * substituting `""` at the mapper boundary keeps `:domain mangaTitle: String`
 * non-null regardless of which legacy rendering precedent justified it.
 * Original §253-era prose preserved verbatim per the audit-trail-preservation
 * convention — the line-anchored citation is historical record of the
 * design lineage; the mapper continues to translate correctly through the
 * legacy retire.
 */
internal fun ChapterDownloadEntity.toDomain(): DownloadedChapter = DownloadedChapter(
    chapterId = chapterId,
    mangaId = mangaId,
    number = number,
    mangaTitle = mangaTitle ?: "",
    state = state.toDomain(),
    progress = progress,
    errorMsg = errorMsg,
    url = url,
    sizeBytes = sizeBytes,
)

internal fun DownloadingState.toDomain(): DownloadState = when (this) {
    DownloadingState.QUEUED -> DownloadState.QUEUED
    DownloadingState.RUNNING -> DownloadState.RUNNING
    DownloadingState.COMPRESSING -> DownloadState.COMPRESSING
    DownloadingState.DOWNLOADED -> DownloadState.DOWNLOADED
    DownloadingState.SUCCESS -> DownloadState.SUCCESS
    DownloadingState.FAILED -> DownloadState.FAILED
}
