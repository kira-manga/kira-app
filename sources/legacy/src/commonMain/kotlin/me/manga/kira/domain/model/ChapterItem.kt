package me.manga.kira.domain.model

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.serialization.Serializable

// Migration notes (Phase 4 batch 4.2):
//   - @Parcelize + Parcelable dropped — Android-only API; replaced with @Serializable.
//   - java.time.LocalDate -> kotlinx.datetime.LocalDate (KMP-portable; locked stack).
//   - LocalDate.now() -> Clock.System.todayIn(TimeZone.currentSystemDefault()) preserves behavior:
//     constructs an instance with today's system-local date as the default. Same observable value.
//   - kotlinx-datetime 0.8.0 moved Clock to kotlin.time.Clock (it's @ExperimentalTime in stdlib;
//     opting in here is required at the data-class declaration site).
//
// Phase 9.y.chapteritem.fieldprune.cumulative (Task #418): dropped the orphan
// `chaptersImages: List<ChapterImage> = listOf()` field per the §245 model-layer audit and the
// §246 next-candidate block. The field was a pure writer-only orphan (7 sites: 5 source-repo
// constructors + 2 HandelDataClasses factory sites) with zero `.chaptersImages` accessor reads
// in the codebase. The rework `:domain` type `Chapter` already drops chapter-page state by
// design (pages belong to the separate fetch concern via `:domain` `ReadChapterPagesUseCase`),
// and the `:data` mapper `LegacyChapterItem.toDomain()` already filtered the field out.
// Slice 1 cleared the 5 source-repo writes; Slice 2 (this commit) drops the 2 HandelDataClasses
// writes + this declaration.
@OptIn(ExperimentalTime::class)
@Serializable
data class ChapterItem(
    val number: String,
    val name: String = "",
    val url: String,
    val date: LocalDate? = Clock.System.todayIn(TimeZone.currentSystemDefault()),
    val isDownloaded: Boolean = false,
    val isBookmarked: Boolean = false,
)

/*
 * §253 audit-trail postscript — cluster281 §253 sweep (2026-05-29)
 * Classification: LIVE / LEGACY (pre-rework :shared commonMain domain model, still wired).
 *
 * LIVE evidence (writers + readers + mapper outflow, all verified by grep this sweep):
 *   - WRITERS (constructor call sites): the per-source repositories build this type directly —
 *     e.g. WebtoontrRepository.kt:137 + :237, WebtoonhattiRepository.kt:134 + :234,
 *     TimenaightRepository.kt:136 + :210, MangahubRepository.kt:270, SenkuroRepository.kt:641
 *     (factory MangaTachiyomiChaptersDto.ChaptersMessage.BookDto.toChapterItem at :637-:641).
 *   - ABSTRACT-CONTRACT readers: SeparatedDetailsSites.kt:246 (abstract parseChapters):
 *     List ChapterItem), SeparatedDetailsSitesv2.kt:262, NormalSites/NormalSitesv2 chapter flows
 *     (Flow State List ChapterItem) feed the MangaInfo.chapters field at combine time.
 *   - UI consumer: HomeScreen.kt:201/:294/:458 onChapterClick (ChapterItem, MangaItem,
 *     List ChapterItem) -> Unit — the legacy chapter row still passes this type to the reader.
 *   - MAPPER outflow: MangaDetailsMappers.kt:74 internal fun LegacyChapterItem.toDomain():
 *     Chapter (import alias at MangaDetailsMappers.kt:5) is the strangler-fig boundary into the
 *     rework :domain Chapter type (Chapter.kt KDoc names this file as its legacy mirror).
 *   No Koin binding applies — this is a plain data class instantiated inline, not an injectable.
 *
 * Delta-axes (this legacy model vs. the rework :domain Chapter it strangler-figs into):
 *   1. Platform API — @Parcelize + Parcelable (Android-only) dropped Phase 4 batch 4.2, replaced
 *      by kotlinx.serialization @Serializable (KMP-portable); see top-of-file migration note.
 *   2. Date type — java.time.LocalDate -> kotlinx.datetime.LocalDate; LocalDate.now() ->
 *      Clock.System.todayIn(TimeZone.currentSystemDefault()); Clock now under kotlin.time
 *      (@ExperimentalTime), hence the @OptIn at the declaration site.
 *   3. Field-prune lineage — Phase 9.y (Task #418) dropped the orphan chaptersImages field
 *      (writer-only, zero accessor reads); the rework Chapter never carried chapter-page state
 *      (pages live behind ReadChapterPagesUseCase), so the mapper at L74 filters it out already.
 *   4. Mutability/threading — no dispatcher concern here (pure value type); threading lives in the
 *      repositories that build it (Flow State on IODispatcher inside each source repo).
 *   5. DI binding mechanism — none; value type, constructed by source-repo parsers, never injected.
 *
 * Nested-comment hazard check: this file has 0 pre-existing KDoc/comment openers (the head uses
 * line comments with the double-slash form, not block comments); this appended block adds exactly
 * one opener and one closer, with zero interior comment delimiters in the prose. Balanced.
 */
