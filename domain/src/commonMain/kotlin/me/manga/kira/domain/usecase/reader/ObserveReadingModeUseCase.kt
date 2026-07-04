package me.manga.kira.domain.usecase.reader

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.repository.ReadingModeRepository

/**
 * Observe the user's persisted reading-mode preference as a live stream.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [ReadingModeRepository.observe]". Mirrors the
 * established "one VM-callable verb per use case" shape used by [FetchChapterPagesUseCase] and
 * [me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase].
 *
 * Why a use case at all when this is a single-line delegate:
 *  - **Stable presentation-layer dependency**. The Reader VM (and any future settings VM that
 *    surfaces the same value) depends on `ObserveReadingModeUseCase`, not on
 *    `ReadingModeRepository`. Future enrichment (e.g. combining with a per-manga override) lands
 *    inside this class without forcing a VM signature change.
 *  - **Test seam**. Mocking one operator-fun is cheaper than mocking the full repository.
 *  - **Pair with [SetReadingModeUseCase]**. Splitting observe / set into two use cases gives the
 *    VM two narrow, distinctly named injection points — neither use case can accidentally do the
 *    other's job.
 *
 * Constructor-injected `ReadingModeRepository` per contract §6 DIP — Koin binds it as a `factory`
 * in `readerReworkModule`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster126.staleKdocSweep.cascade,
 * Task #582, 2026-05-28): classified as follows after recursive symbol
 * verification (eighty-fourth sibling of the cluster57-125 sweep —
 * second file of the wave-22 closer `:domain/usecase/reader/` 3-file
 * batch alongside FetchChapterPages plus SetReadingMode):
 *  (a) "SRP single-rule-delegate-to-ReadingModeRepository.observe +
 *  one-VM-callable-verb-per-use-case + mirrors FetchChapterPagesUseCase
 *  and FetchMangaDetailsUseCase" — LIVE-NOT-STALE. ReaderViewModel.kt
 *  L17 import, L227 ctor `private val observeReadingMode: ObserveReading-
 *  ModeUseCase`, L279 realization `observeReadingMode().collect { mode -> }`
 *  inside the reading-mode-collection coroutine. L30 single-line
 *  `repository.observe()` pass-through preserved.
 *  (b) "Flow-return-shape — second Flow-shaped use case in cluster126
 *  alongside FetchChapterPagesUseCase; Flow<ReadingMode> lets the
 *  rework Reader react to cross-screen reading-mode changes (e.g.
 *  ReadingModeDialog in the rework :ui layer) without polling" —
 *  LIVE-FRAMING. Intra-cluster126 sibling cross-ref to FetchChapter-
 *  PagesUseCase (83rd, just-swept) — the two Flow-shaped siblings
 *  represent the streaming-source-page-list and observed-preference
 *  axes; SetReadingModeUseCase (85th forthcoming) is the suspend-shaped
 *  counterpart that completes the Observe/Set verb-split pair.
 *  (c) "Why-use-case-at-all single-line-delegate — stable presentation-
 *  layer dependency + test seam + Pair with SetReadingModeUseCase
 *  (Observe/Set split = narrow distinctly-named VM injection points,
 *  neither can accidentally do the other's job)" — LIVE-NOT-STALE.
 *  This completes the third sibling verb-split pair in `:domain/use-
 *  case/reader/` — Start/End session (cluster125 78th+79th) + Load/Save
 *  position (cluster125 80th+81st) + Observe/Set reading-mode
 *  (cluster126 84th+85th); three-pair architectural-symmetry posture
 *  upheld.
 *  (d) "§6 DIP + Koin factory-stateless + readerReworkModule binding
 *  alongside ChapterPages + ReadingSession + ReadProgress repos" —
 *  LIVE-NOT-STALE. ReaderReworkModule.kt L101 `factory { Observe-
 *  ReadingModeUseCase(get()) }` realization; same factory-stateless +
 *  repository-state-holding-single split as the four other wave-22
 *  siblings. Four classifications STAND on their own merits. Original
 *  Phase 6.4.2+-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
class ObserveReadingModeUseCase(
    private val repository: ReadingModeRepository,
) {
    operator fun invoke(): Flow<ReadingMode> = repository.observe()
}
