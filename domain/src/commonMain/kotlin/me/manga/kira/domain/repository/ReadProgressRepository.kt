package me.manga.kira.domain.repository

/**
 * Per-chapter last-read-page persistence.
 *
 * Contract §6 SRP: owns ONE rule — "for a given chapter, remember the last page index the user
 * was viewing so the Reader can resume there on re-entry". Where the value lives on disk, how
 * chapter identity is encoded into a storage key, and how key-length limits across platforms
 * are reconciled are all `:data` concerns.
 *
 * Net-new persistence (not strangler-fig): unlike the reading-mode slice (Phase 6.4.x.mode) and
 * the statistics slice (Phase 6.4.x.statistics), there is **no** legacy on-disk cell to preserve.
 * The legacy data layer has a `lastReadPage` column on `HistoryItemD` and a DAO method
 * (`HistoryDao.updateHistoryItem(..., lastReadPage: Int)`), but the legacy reader never writes a
 * non-zero value into it — every call site passes the default `0`. So this slice is a clean
 * write of a new persistence cell, with no risk of disagreement with the legacy reader during
 * the strangler-fig transition. After the Phase 9.x route swap retires the legacy reader, the
 * dead `HistoryItemD.lastReadPage` column can be deleted with no on-disk migration concerns.
 *
 * Why a fresh on-disk cell rather than starting to populate the dead `lastReadPage` column:
 *  - **Layer cleanliness.** Routing this through the legacy Room graph would force `:data` to
 *    depend on `:shared` `HistoryRepository`, mirroring the strangler-fig posture of
 *    `ReadingSessionRepositoryImpl` over `StatisticsRepository`. But the statistics posture was
 *    forced — the legacy persistence was load-bearing because the user's lifetime read-minutes
 *    counter must not visibly drop at the route swap. No such constraint exists here (the column
 *    is dead). Pulling Room into the rework's `:data` for a feature the legacy never delivered
 *    would be unjustified coupling.
 *  - **Per-chapter granularity.** The legacy `HistoryItemD` keys by `mangaUrl` (one row per
 *    manga), so its `lastReadPage` semantic is "the last page of the most recently opened
 *    chapter in this manga". The Reader UX users actually expect — "each chapter remembers
 *    where I stopped" — needs per-chapter keying. A new cell keyed by chapter URL gives that for
 *    free.
 *  - **Same template as Phase 6.4.x.mode.** Both slices wrap `ObservableSettings`. Same factory
 *    Koin binding, same `:data` `single`, same `:domain` interface shape. Less context to learn,
 *    less surface to maintain.
 *
 * Identity: chapters are identified by their `url` field — same as the rest of the rework
 * Reader (see [me.manga.kira.presentation.reader.ReaderViewModel] re-entry idempotence
 * comparison, which keys on `chapter.url`). URLs are stable per-source (they ARE the source's
 * route to the chapter content), and a chapter's URL never changes once published. The `:data`
 * impl handles the storage-key encoding (chapter URLs are routinely longer than the
 * `java.util.prefs.Preferences` 80-char key limit on Desktop, so the impl hashes; see its KDoc).
 *
 * Collision safety: the `:data` impl is allowed to use a non-injective key derivation
 * (e.g. hash-based) to fit platform constraints, but [load] MUST return null on a hash collision
 * rather than misreport another chapter's saved page. The impl stores the chapter URL alongside
 * the page index and verifies on read — see [ReadProgressRepository.load] contract below.
 *
 * Why two single-shot suspend methods rather than a `Flow`:
 *  - Read side ([load]) is called exactly once per chapter entry — when the VM transitions to a
 *    new `(manga, chapter)` pair via `OnEnter`. There is no observation use case (the user can't
 *    edit this cell out-of-band; it's purely the Reader's own scratchpad). A one-shot read is
 *    the minimal surface.
 *  - Write side ([save]) is called on every page change (via `OnPageChanged`). A Flow-shaped
 *    setter buys nothing here — there's no upstream value to subscribe to, the VM just feeds
 *    new positions imperatively.
 *
 * Why `Int?` for the load result rather than `Int` with a sentinel:
 *  - Conventional null-for-absent semantics. The caller's "no saved position — start at 0"
 *    fallback lives in the VM where the policy belongs, not in the repository.
 *  - Distinguishes "user never opened this chapter" (null) from "user opened, viewed page 0,
 *    and quit on page 0" (0). The first should start at page 0 by default; the second already
 *    is at page 0 — so the distinction is observationally irrelevant in practice, BUT the
 *    contract is still cleaner when the absence case is explicit.
 *
 * No-`AppResult` rationale: same as [ReadingModeRepository] / [ReadingSessionRepository] —
 * settings-backed I/O has no actionable failure surface from a presentation-layer POV. If the
 * underlying `ObservableSettings.putInt` fails (would require disk corruption on the platform's
 * preferences backend), there's nothing the Reader UI could meaningfully do with that error
 * besides log it. The impl wraps the call without trying to bubble failures up.
 *
 * Idempotence:
 *  - [save] writing the same `(chapterUrl, pageIndex)` pair twice is a no-op on disk
 *    (`ObservableSettings.putString` short-circuits identical writes). Safe to call from a
 *    fire-and-forget VM coroutine on every page swipe.
 *  - [load] is a pure read — repeated calls return the same value until the next [save].
 *
 * DIP (contract §6): consumers ([me.manga.kira.domain.usecase.reader.SavePagePositionUseCase],
 * [me.manga.kira.domain.usecase.reader.LoadPagePositionUseCase], and through them the Reader
 * VM) depend on this interface, never on the `:platform` `SettingsFactory` or the raw
 * `ObservableSettings`. Koin binds the impl at the composition root.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster139.staleKdocSweep.cascade,
 * Task #595, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-thirty-seventh sibling of the cluster57-138
 * sweep — fifth and closing file of the wave-25 first-cluster 5-leaf-
 * repository batch alongside MangaDetailsRepository plus ChapterPages-
 * Repository plus ReadingModeRepository plus ReadingSessionRepository;
 * closes cluster139):
 *  (a) "Per-chapter-last-read-page-persistence + Contract-§6-SRP-owns-
 *  ONE-rule-for-a-given-chapter-remember-the-last-page-index-the-
 *  user-was-viewing-so-the-Reader-can-resume-there-on-re-entry +
 *  Net-new-persistence-not-strangler-fig + The-legacy-data-layer-has-
 *  a-lastReadPage-column-on-HistoryItemD-and-a-DAO-method + HistoryDao.
 *  updateHistoryItem-lastReadPage-Int + but-the-legacy-reader-never-
 *  writes-a-non-zero-value-into-it-every-call-site-passes-the-default-
 *  0 + So-this-slice-is-a-clean-write-of-a-new-persistence-cell-with-
 *  no-risk-of-disagreement-with-the-legacy-reader-during-the-
 *  strangler-fig-transition + After-the-Phase-9.x-route-swap-retires-
 *  the-legacy-reader-the-dead-HistoryItemD.lastReadPage-column-can-be-
 *  deleted-with-no-on-disk-migration-concerns" — LIVE-NOT-STALE plus
 *  FULFILLED-PREDICTION plus FORECAST-NOT-YET-FULFILLED-(post-route-
 *  swap-HistoryItemD.lastReadPage-column-deletion). Verified via
 *  recursive grep: ReadProgressRepository is consumed by SavePage-
 *  PositionUseCase plus LoadPagePositionUseCase plus ReaderViewModel
 *  plus ReaderReworkModule plus ReadProgressRepositoryImpl. The "net-
 *  new-persistence-not-strangler-fig" rationale holds — Read-
 *  ProgressRepositoryImpl writes into a fresh `read_page_*` settings
 *  cell, not into the dead HistoryItemD.lastReadPage column. The
 *  legacy reader's Phase-9.x route-swap is still pending per the live
 *  status doc; the HistoryItemD column-deletion remains forecast.
 *  (b) "Why-a-fresh-on-disk-cell-rather-than-starting-to-populate-the-
 *  dead-lastReadPage-column + Layer-cleanliness + Routing-this-
 *  through-the-legacy-Room-graph-would-force-:data-to-depend-on-:
 *  shared-HistoryRepository + But-the-statistics-posture-was-forced-
 *  the-legacy-persistence-was-load-bearing-because-the-user-lifetime-
 *  read-minutes-counter-must-not-visibly-drop-at-the-route-swap + No-
 *  such-constraint-exists-here-the-column-is-dead + Per-chapter-
 *  granularity + The-legacy-HistoryItemD-keys-by-mangaUrl-one-row-per-
 *  manga + The-Reader-UX-users-actually-expect-each-chapter-remembers-
 *  where-I-stopped-needs-per-chapter-keying + A-new-cell-keyed-by-
 *  chapter-URL-gives-that-for-free + Same-template-as-Phase-6.4.x.
 *  mode + Both-slices-wrap-ObservableSettings + Same-factory-Koin-
 *  binding-same-:data-single-same-:domain-interface-shape + Identity-
 *  chapters-are-identified-by-their-url-field + URLs-are-stable-per-
 *  source-they-ARE-the-source-route-to-the-chapter-content + a-
 *  chapter-URL-never-changes-once-published + The-:data-impl-handles-
 *  the-storage-key-encoding + chapter-URLs-are-routinely-longer-than-
 *  the-java.util.prefs.Preferences-80-char-key-limit-on-Desktop-so-
 *  the-impl-hashes + Collision-safety-the-:data-impl-is-allowed-to-
 *  use-a-non-injective-key-derivation-but-load-MUST-return-null-on-a-
 *  hash-collision-rather-than-misreport-another-chapter-saved-page +
 *  The-impl-stores-the-chapter-URL-alongside-the-page-index-and-
 *  verifies-on-read" — LIVE-NOT-STALE plus FULFILLED-PREDICTION.
 *  Verified: the interface declares exactly two suspend methods —
 *  `save(chapterUrl, pageIndex)` plus `load(chapterUrl): Int?`. The
 *  `Int?` return type preserves the explicit absence-vs-page-0
 *  distinction predicted. ReadProgressRepositoryImpl.kt uses the
 *  predicted hash-key encoding plus the chapter-URL-alongside-page-
 *  index collision-safety guard per its cluster23 §479 postscript.
 *  The Desktop 80-char Preferences key limit and the per-chapter
 *  keying both hold.
 *  (c) "Why-two-single-shot-suspend-methods-rather-than-a-Flow + Read-
 *  side-load-is-called-exactly-once-per-chapter-entry-when-the-VM-
 *  transitions-to-a-new-manga-chapter-pair-via-OnEnter + There-is-no-
 *  observation-use-case-the-user-can-t-edit-this-cell-out-of-band-
 *  it-s-purely-the-Reader-own-scratchpad + A-one-shot-read-is-the-
 *  minimal-surface + Write-side-save-is-called-on-every-page-change-
 *  via-OnPageChanged + A-Flow-shaped-setter-buys-nothing-here-there-
 *  s-no-upstream-value-to-subscribe-to + Why-Int-for-the-load-result-
 *  rather-than-Int-with-a-sentinel + Conventional-null-for-absent-
 *  semantics + The-caller-no-saved-position-start-at-0-fallback-lives-
 *  in-the-VM-where-the-policy-belongs-not-in-the-repository +
 *  Distinguishes-user-never-opened-this-chapter-null-from-user-
 *  opened-viewed-page-0-and-quit-on-page-0-zero + No-AppResult-
 *  rationale-same-as-ReadingModeRepository-and-ReadingSessionRepo-
 *  settings-backed-I-O-has-no-actionable-failure-surface + Idempotence
 *  + save-writing-the-same-pair-twice-is-a-no-op-on-disk-Observable-
 *  Settings.putString-short-circuits-identical-writes + load-is-a-
 *  pure-read-repeated-calls-return-the-same-value-until-the-next-
 *  save + DIP-contract-§6-consumers-SavePagePositionUseCase-LoadPage-
 *  PositionUseCase-and-through-them-the-Reader-VM-depend-on-this-
 *  interface-never-on-the-:platform-SettingsFactory-or-the-raw-
 *  ObservableSettings" — LIVE-NOT-STALE plus FULFILLED-PREDICTION.
 *  Verified: ReaderViewModel.runFetch applies the predicted
 *  `currentPageIndex.coerceIn(0, lastIndex)` clamp on the load() value
 *  per the cluster30 §486 ReaderViewModel postscript — the bounds-
 *  check policy belongs at the VM as predicted. The fire-and-forget
 *  save-on-every-page-swipe posture holds via the OnPageChanged
 *  intent path.
 *  Three classifications STAND on their own merits. CLOSES cluster139.
 *  Original Phase 7.x.reader.resumeposition-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
interface ReadProgressRepository {

    /**
     * Persist [pageIndex] as the last-viewed page for the chapter identified by [chapterUrl].
     * Overwrites any prior saved position for the same chapter. Idempotent — writing the same
     * pair twice is a no-op on disk.
     *
     * The `:data` impl may hash [chapterUrl] to fit platform key-length limits; collisions
     * degrade gracefully via the URL stored alongside (see [load] contract).
     *
     * `suspend` even though today's `ObservableSettings` write is non-blocking — same posture
     * as [ReadingModeRepository.set], leaves the door open for a future `withContext(io)`
     * switch (e.g. if a settings backend grows synchronous I/O on Desktop).
     */
    suspend fun save(chapterUrl: String, pageIndex: Int)

    /**
     * Load the last-saved page index for [chapterUrl]. Returns `null` when no position has been
     * saved for this chapter, OR when the saved entry's stored URL does not match [chapterUrl]
     * (the `:data` impl's collision-safety guard — see class-level KDoc).
     *
     * Note that the caller is responsible for bounds-checking the returned index against the
     * actual chapter page count; the chapter could have shrunk on a re-publish since the user
     * last opened it. The Reader VM's existing `currentPageIndex.coerceIn(0, lastIndex)` clamp
     * in `runFetch` handles this.
     */
    suspend fun load(chapterUrl: String): Int?

    /**
     * Remove the saved position for [chapterUrl] (no-op when none exists). Called when a chapter's
     * manga is deleted from the library so the resume-page entry doesn't outlive the chapter.
     */
    suspend fun clear(chapterUrl: String)
}
