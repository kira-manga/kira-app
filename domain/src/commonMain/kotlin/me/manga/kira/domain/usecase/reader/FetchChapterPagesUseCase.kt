package me.manga.kira.domain.usecase.reader

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.repository.ChapterPagesRepository

/**
 * Fetch the page list for a given [Chapter] of a given [Manga].
 *
 * Contract §6 SRP: owns ONE rule — "ask the [ChapterPagesRepository] for the page stream and
 * propagate it". Source routing, downloaded-vs-live decisioning, header attachment all live in
 * the `:data` impl behind the repository interface.
 *
 * Why a use case at all when this is a single-line delegate:
 *  - **Stable presentation-layer dependency**. The future Reader VM depends on
 *    `FetchChapterPagesUseCase`, not on `ChapterPagesRepository`. Future enrichment (e.g.
 *    chapter prefetch, transparent retry policy, page deduplication for streaming sources) lands
 *    inside this class without forcing a VM signature change.
 *  - **Test seam**. Mocking one operator-fun is cheaper than mocking the full repository.
 *  - **Consistent with Library / Details slices**. Mirrors `ObserveLibraryUseCase`,
 *    `FetchMangaDetailsUseCase`, etc. — one use case per VM-callable verb.
 *
 * Constructor-injected `ChapterPagesRepository` per contract §6 DIP — Koin binds it as a factory
 * in the future `ReaderReworkModule` (Phase 6.4.2+).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster126.staleKdocSweep.cascade,
 * Task #582, 2026-05-28): classified as follows after recursive symbol
 * verification (eighty-third sibling of the cluster57-125 sweep — first
 * and opening file of the wave-22 closer `:domain/usecase/reader/` 3-
 * file batch alongside ObserveReadingMode plus SetReadingMode; closes
 * the ≤5-file-cap-with-followup convention's third consecutive split
 * after wave-20 downloads/ 5+1 and wave-21 complaint/ 5+4):
 *  (a) "Phase 6.4.2+ ReaderReworkModule binding-target + thin pass-
 *  through over ChapterPagesRepository.fetchPages + source-routing-
 *  downloaded-vs-live-decisioning-header-attachment-live-in-:data" —
 *  LIVE-NOT-STALE + FULFILLED-PREDICTION. The Phase 6.4.2+ forecast
 *  has fully landed: ReaderViewModel.kt L14 import, L226 ctor `private
 *  val fetchPages: FetchChapterPagesUseCase` (alphabetically first of
 *  the nine collaborators per ReaderViewModel L132-133 KDoc),
 *  L413 realization `fetchPages(manga, chapter).collect { result -> }`
 *  inside the runFetch chapter-pages-collect block; ReaderViewModel L39
 *  KDoc explicitly cites this use case as "the only collaborator" for
 *  source routing. ReaderReworkModule.kt L92 `factory { FetchChapter-
 *  PagesUseCase(get()) }` realization confirms the binding-target
 *  forecast.
 *  (b) "Flow-return-shape distinction — first Flow<AppResult<List<Page>>>
 *  use case in the wave-22 cluster, distinct from the suspend-only
 *  session/position siblings (StartReadingSession plus EndReading-
 *  Session plus LoadPagePosition plus SavePagePosition plus List-
 *  Chapters)" — LIVE-FRAMING. Intra-cluster126 sibling cross-ref to
 *  ObserveReadingModeUseCase (84th sibling forthcoming) — together the
 *  two Flow-shaped use cases in the closer batch represent the
 *  streaming-source-page-list (per-chapter pages) and observed-
 *  preference (cross-cutting setting) axes of Flow propagation;
 *  ReaderViewModel L62 KDoc preserves the "streaming-source page-list
 *  semantics" framing verbatim. Flow shape lets the rework Reader
 *  observe page-progress emissions over the chapter's lifetime rather
 *  than buffering a one-shot suspend result.
 *  (c) "Why-use-case-at-all single-line-delegate — stable presentation-
 *  layer dependency + test seam + consistent with Library/Details
 *  slices (mirrors ObserveLibraryUseCase + FetchMangaDetailsUseCase)" —
 *  LIVE-NOT-STALE. ReaderViewModel ctor surface preserves the narrow-
 *  use-case-injection posture; the broader ChapterPagesRepository
 *  handle never reaches `:presentation`. §6 SRP single-rule-delegate +
 *  §6 DIP constructor-injection upheld; the cross-package consistency
 *  cross-ref to ObserveLibraryUseCase + FetchMangaDetailsUseCase
 *  (cluster119 FULLY SWEPT) stands. Three classifications STAND on
 *  their own merits. Original Phase 6.4.2+-era prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
class FetchChapterPagesUseCase(
    private val repository: ChapterPagesRepository,
) {
    operator fun invoke(manga: Manga, chapter: Chapter): Flow<AppResult<List<Page>>> =
        repository.fetchPages(manga, chapter)
}
