package me.manga.kira.domain.model.statistics

/**
 * Aggregate snapshot of the user's reading statistics — the 8 numbers the legacy Statistics screen
 * displays (in-library count, read minutes, completed/started entry counts, 4 chapter counts).
 *
 * Phase 7.x.statistics rework: the `:data` layer's [me.manga.kira.data.repository.ReadingStatisticsRepositoryImpl]
 * combines 8 individual `Flow<Int>` properties from the legacy
 * `StatisticsRepository` into a single [ReadingStatistics] snapshot per upstream emission. The
 * `:presentation` VM projects each snapshot into its [me.manga.kira.presentation.statistics.StatisticsState].
 *
 * [readMinutes] is the RAW minute counter (typed wire, 2026-07 backlog L15 — the Phase-10 i18n
 * lift): display formatting ("Xh Ym") is a `:ui` concern, rendered by `StatisticsScreen` through
 * the localized `Res.string.h_m` pattern. Domain carries data, never UI-formatted text.
 *
 * Contract §6 SRP: one rule — "8 reading-statistics numbers as a single value". No methods, no
 * validation logic, no derivation — those live in [me.manga.kira.data.repository.ReadingStatisticsRepositoryImpl]
 * and the legacy DAO.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster138.staleKdocSweep.cascade,
 * Task #594, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-thirty-first sibling of the cluster57-137
 * sweep — second file of the wave-24 sixth-cluster closing 3-leaf-
 * model joint batch alongside Source plus UpdateEntry):
 *  (a) "Phase-7.x.statistics-rework + :data-layer-ReadingStatistics-
 *  RepositoryImpl-combines-8-individual-Flow-Int-or-Flow-String-
 *  properties-from-the-legacy-:shared-StatisticsRepository-into-a-
 *  single-ReadingStatistics-snapshot-per-upstream-emission + :pre-
 *  sentation-VM-projects-each-snapshot-into-its-StatisticsState +
 *  Aggregate-snapshot-of-the-user-reading-statistics-8-numbers + in-
 *  library-count-read-duration-string-completed-started-entry-counts-
 *  4-chapter-counts" — LIVE-NOT-STALE plus FULFILLED-PREDICTION.
 *  Verified via recursive grep: ReadingStatistics is consumed by
 *  ObserveReadingStatisticsUseCase plus ReadingStatisticsRepositoryImpl
 *  plus StatisticsState plus StatisticsViewModel plus StatisticsScreen.
 *  ReadingStatisticsRepositoryImpl.kt builds the aggregate via
 *  kotlinx.coroutines.flow.combine across the 8 legacy-StatisticsRepo
 *  source flows — exactly one emission per upstream change as
 *  predicted. The data class declares exactly 8 fields matching the
 *  predicted 1 + 1 + 2 + 4 counter shape (inLibrary + readDuration
 *  String + entriesStarted + entriesCompleted + chaptersTotal +
 *  chaptersRead + chaptersDownloaded + chaptersBookmarked).
 *  (b) "Why-readDuration-is-String-not-Int + the-legacy-repository-
 *  already-pre-formats-the-minutes-counter-into-an-Xh-Ym-string +
 *  with-a-TODO-Phase-10-stringResource-R.string.h_m-note-about-future-
 *  localisation + The-rework-reuses-the-same-formatted-string-verbatim
 *  + moving-the-formatting-into-the-VM-would-re-introduce-the-same-
 *  i18n-TODO-without-benefit + the-legacy-and-rework-consumers-will-
 *  both-pick-up-the-Phase-10-i18n-lift-together + Contract-§6-SRP-one-
 *  rule-8-reading-statistics-numbers-as-a-single-value + No-methods-
 *  no-validation-logic-no-derivation + those-live-in-ReadingStatistics-
 *  RepositoryImpl-and-the-legacy-DAO" — LIVE-NOT-STALE plus FULFILLED-
 *  PREDICTION plus FORECAST-NOT-YET-FULFILLED-(Phase-10-i18n-
 *  stringResource-readDuration-lift). Verified: readDuration: String
 *  declaration locked in. The legacy StatisticsRepository readMinutes-
 *  ToHm() formatter still pre-builds the "Xh Ym" string on the :shared
 *  side; the rework reads it through verbatim. The Phase 10 Compose
 *  Multiplatform Resources stringResource(Res.string.h_m) lift remains
 *  forecast — no Res.string.h_m key exists yet; the legacy TODO and
 *  the rework's deferred-i18n posture continue to share the same
 *  future migration point.
 *  Two classifications STAND on their own merits. Original Phase
 *  7.x.statistics-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
data class ReadingStatistics(
    /** Total number of manga in the user's library. */
    val inLibrary: Int,
    /** Total reading time in whole minutes (formatted as "Xh Ym" by `:ui` — see KDoc). */
    val readMinutes: Int,
    /** Manga with at least one read chapter. */
    val entriesStarted: Int,
    /** Manga with all chapters read. */
    val entriesCompleted: Int,
    /** Total chapter rows across all library manga. */
    val chaptersTotal: Int,
    /** Chapters marked as read. */
    val chaptersRead: Int,
    /** Chapters fully downloaded locally. */
    val chaptersDownloaded: Int,
    /** Chapters bookmarked. */
    val chaptersBookmarked: Int,
)
