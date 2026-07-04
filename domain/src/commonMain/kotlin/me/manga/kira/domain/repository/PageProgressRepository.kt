package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.reader.PageDownloadProgress

/**
 * Per-page download/decode progress facade.
 *
 * Phase 7.x.reader.modelayout.pageprogress slice's `:domain` boundary. Owns ONE rule —
 * "for a given page URL, surface a cold Flow of [PageDownloadProgress] emissions reflecting that
 * page's current position in the Coil fetch/decode pipeline; accept reporter calls from the
 * `:platform` interceptors that drive those emissions." Where the underlying state lives (a
 * `MutableStateFlow<Map<String, PageDownloadProgress>>` in `:data`), how the OkHttp body wrap
 * reports bytes (Android `:platform/androidMain`), and how the Coil listener attaches to the
 * per-page `ImageRequest.Builder` (`:ui` site, calling into a `:platform` attacher) are all
 * out-of-domain concerns.
 *
 * Why a single repository with both a read side and a reporter side:
 *  - The reporter calls are pure side-effects from the `:platform` layer — they're not a use-case
 *    surface that the `:presentation` layer would call. Splitting "reader" and "writer" into two
 *    interfaces (an `Observable<...>` for `:presentation` and a `Reporter<...>` for `:platform`)
 *    would force a parallel binding shape with no observability benefit. One repository, one
 *    Koin `single`, one in-memory state-flow source.
 *  - Mirrors the established `:data`-layer pattern of state-flow-backed in-memory caches (see
 *    `ReadingModeRepository`'s `ObservableSettings` posture — same shape, different backing store).
 *  - The reporter-call surface is narrow enough ([report]) that keeping it on the same interface
 *    doesn't create an ISP violation — `:presentation` callers ignore [report]; `:platform`
 *    callers ignore [observe]. Each layer uses the half of the surface relevant to it.
 *
 * Net-new persistence model: this repository is purely in-memory; no `ObservableSettings`, no
 * Room, no on-disk cell. Progress state is ephemeral — it resets to [PageDownloadProgress.Idle]
 * on process restart, which is correct (a fresh process re-fetches every page anyway). This
 * differs from [ReadProgressRepository] (Phase 7.x.reader.resumeposition) which persists across
 * restarts because resume-position must survive process death; progress state has no analogous
 * requirement.
 *
 * Why no `AppResult` wrapping on [observe] or [report]:
 *  - [observe] is a cold Flow that can't synchronously fail — there's no I/O to error out on.
 *    A consumer who observes a never-reported URL gets a Flow that emits [PageDownloadProgress.Idle]
 *    once and stays subscribed; that's not a failure case.
 *  - [report] is a fire-and-forget state update. The only conceivable failure is "the underlying
 *    MutableStateFlow is closed", but the impl never closes it (lives for the App's lifetime).
 *    Same posture as [ReadingModeRepository.set] / [ReadProgressRepository.save] — settings-like
 *    writes don't surface failures upward.
 *
 * Lifecycle / scope:
 *  - The repository is a process-singleton (Koin `single`). State accumulates across multiple
 *    chapter loads in the same process — a chapter the user already viewed contributes
 *    [PageDownloadProgress.Complete] entries that linger in the map. The map's memory footprint
 *    grows monotonically per process, but `:data` impls MAY prune entries on chapter exit
 *    (driven by an explicit caller) — see [clear].
 *
 * Idempotence:
 *  - [observe] is a pure-read projection over the underlying state-flow; repeated subscriptions
 *    for the same URL share the same upstream emissions.
 *  - [report] is idempotent on identical `(url, status)` pairs by virtue of the underlying
 *    `MutableStateFlow.update` short-circuiting equal-value writes.
 *  - [clear] is idempotent — clearing an empty entry is a no-op.
 *
 * Thread-safety: `:data` impls MUST be safe for concurrent calls. The Android OkHttp body wrap
 * may emit [report] from background threads while the Reader VM observes from the main
 * dispatcher; the `MutableStateFlow` backing handles the cross-thread handoff atomically.
 *
 * DIP (contract §6): consumers (the Reader VM via observe, the `:platform` interceptors via
 * report) depend on this interface, never on the underlying `MutableStateFlow` or any
 * platform-specific reporter. Koin binds the impl at the composition root.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster140.staleKdocSweep.cascade,
 * Task #596, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-thirty-eighth sibling of the cluster57-139
 * sweep — first file of the wave-25 second-cluster 5-leaf-repository
 * batch opening cluster140; opens the middle batch of the :domain/
 * repository/ tier survey):
 *  (a) "Per-page-download-decode-progress-facade + Phase-7.x.reader.
 *  modelayout.pageprogress-slice-:domain-boundary + Owns-ONE-rule-for-a-
 *  given-page-URL-surface-a-cold-Flow-of-PageDownloadProgress-emissions-
 *  reflecting-that-page-current-position-in-the-Coil-fetch-decode-
 *  pipeline-accept-reporter-calls-from-the-:platform-interceptors-that-
 *  drive-those-emissions + Where-the-underlying-state-lives-a-Mutable-
 *  StateFlow-Map-String-PageDownloadProgress-in-:data-how-the-OkHttp-
 *  body-wrap-reports-bytes-Android-:platform-androidMain-and-how-the-
 *  Coil-listener-attaches-to-the-per-page-ImageRequest.Builder-:ui-
 *  site-calling-into-a-:platform-attacher-are-all-out-of-domain-concerns
 *  + Why-a-single-repository-with-both-a-read-side-and-a-reporter-side +
 *  The-reporter-calls-are-pure-side-effects-from-the-:platform-layer +
 *  Splitting-reader-and-writer-into-two-interfaces-would-force-a-
 *  parallel-binding-shape-with-no-observability-benefit + One-
 *  repository-one-Koin-single-one-in-memory-state-flow-source +
 *  Mirrors-the-established-:data-layer-pattern-of-state-flow-backed-in-
 *  memory-caches + The-reporter-call-surface-is-narrow-enough-report-
 *  that-keeping-it-on-the-same-interface-does-not-create-an-ISP-
 *  violation + :presentation-callers-ignore-report + :platform-callers-
 *  ignore-observe" — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified
 *  via recursive grep: PageProgressRepository is consumed by Reader-
 *  ViewModel plus ReaderState plus ReaderScreen (per-URL pageProgress
 *  map projection) plus :platform Android OkHttp body-wrap interceptor
 *  plus the cross-platform Coil per-request listener attacher plus
 *  PageProgressRepositoryImpl (the :data MutableStateFlow-backed impl).
 *  The dual-half ISP carve holds: ReaderViewModel reaches `observe`
 *  only — never `report` or `clear`; the :platform reporters reach
 *  `report` only — never `observe`. One interface, two consumer halves,
 *  zero ISP fattening.
 *  (b) "Net-new-persistence-model-this-repository-is-purely-in-memory-
 *  no-ObservableSettings-no-Room-no-on-disk-cell + Progress-state-is-
 *  ephemeral-it-resets-to-PageDownloadProgress.Idle-on-process-restart-
 *  which-is-correct-a-fresh-process-re-fetches-every-page-anyway +
 *  This-differs-from-ReadProgressRepository-Phase-7.x.reader.
 *  resumeposition-which-persists-across-restarts-because-resume-
 *  position-must-survive-process-death-progress-state-has-no-analogous-
 *  requirement + Why-no-AppResult-wrapping-on-observe-or-report +
 *  observe-is-a-cold-Flow-that-cannot-synchronously-fail-there-is-no-
 *  I/O-to-error-out-on + report-is-a-fire-and-forget-state-update + The-
 *  only-conceivable-failure-is-the-underlying-MutableStateFlow-is-
 *  closed-but-the-impl-never-closes-it-lives-for-the-App-lifetime +
 *  Lifecycle-or-scope + The-repository-is-a-process-singleton-Koin-
 *  single + State-accumulates-across-multiple-chapter-loads-in-the-same-
 *  process + The-map-memory-footprint-grows-monotonically-per-process-
 *  but-:data-impls-MAY-prune-entries-on-chapter-exit-driven-by-an-
 *  explicit-caller-see-clear + Idempotence + observe-is-a-pure-read-
 *  projection-over-the-underlying-state-flow-repeated-subscriptions-
 *  for-the-same-URL-share-the-same-upstream-emissions + report-is-
 *  idempotent-on-identical-url-status-pairs-by-virtue-of-the-underlying-
 *  MutableStateFlow.update-short-circuiting-equal-value-writes + clear-
 *  is-idempotent-clearing-an-empty-entry-is-a-no-op + Thread-safety +
 *  :data-impls-MUST-be-safe-for-concurrent-calls" — LIVE-NOT-STALE plus
 *  FULFILLED-PREDICTION. Verified: PageProgressRepositoryImpl in :data
 *  wraps a single process-scope MutableStateFlow<Map<String,
 *  PageDownloadProgress>>; reads via .map { it[url] ?: Idle }.
 *  distinctUntilChanged(); writes via .update { it + (url to status) };
 *  clear via .update { it - url }. The Koin binding is `single` (not
 *  `factory`) per the predicted lifecycle. The three-method surface
 *  declared here matches the impl's 1:1 method body count — no surface
 *  drift.
 *  Two classifications STAND on their own merits. Original Phase 7.x.
 *  reader.modelayout.pageprogress-era prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
interface PageProgressRepository {

    /**
     * Observe a cold Flow of [PageDownloadProgress] for the page at [url].
     *
     * Emits [PageDownloadProgress.Idle] when the repository has no entry for [url] — including
     * during the brief window between when the chapter's page list is loaded and when Coil
     * actually starts fetching. The Flow stays subscribed for the lifetime of the collector; new
     * emissions arrive as the `:platform` reporters call [report] for this URL.
     *
     * The impl SHOULD `distinctUntilChanged` so consumers only recompose on real state
     * transitions. The Reader VM observes per-page progress for the chapter's pages on entry and
     * surfaces the per-URL value into `ReaderState.pageProgress[url]`.
     */
    fun observe(url: String): Flow<PageDownloadProgress>

    /**
     * Update the progress state for [url] to [status]. Called by the `:platform` interceptors:
     *  - Android OkHttp body wrap: emits [PageDownloadProgress.InProgress] with a computed
     *    fraction on each meaningful byte tick (throttled to ≥1% advancement or ≥50ms gap).
     *  - Coil-level per-request listener (cross-platform, attached at the `:ui`
     *    `ImageRequest.Builder` site via a `:platform` attacher): emits
     *    [PageDownloadProgress.Started] on `onStart`, [PageDownloadProgress.Complete] on
     *    `onSuccess`, [PageDownloadProgress.Failed] on `onError`.
     *
     * No-op if the new status equals the existing entry (MutableStateFlow short-circuits).
     */
    fun report(url: String, status: PageDownloadProgress)

    /**
     * Remove the entry for [url] (subsequent [observe] for this URL emits
     * [PageDownloadProgress.Idle] until the next [report]). Caller-driven cleanup — the Reader VM
     * calls this (via `ClearPageProgressUseCase`) in `onCleared` for its loaded chapters' page URLs
     * to keep the in-memory map from growing without bound across long sessions. No-op if no entry
     * exists.
     */
    fun clear(url: String)
}
