package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.repository.PageProgressRepository

/**
 * In-memory [PageProgressRepository] backed by a [MutableStateFlow] of URL → progress.
 *
 * SRP (contract §6): owns ONE rule — "hold the latest progress state for every observed page
 * URL in a thread-safe, observable map and project a per-URL Flow on demand". Where the progress
 * events come from (Coil listener on `:ui` / OkHttp body wrap on `:platform/androidMain`) and
 * how they're attached to image requests are concerns of those higher layers.
 *
 * DIP: depends only on [PageProgressRepository] (`:domain`) and `kotlinx.coroutines.flow`. No
 * `:platform`, no `:shared`, no Coil, no Android. The `:platform` reporters call [report]
 * through the interface, never the concrete impl.
 *
 * Storage model: ephemeral, in-memory, process-singleton. The slice's plan deliberately rejects
 * persistence (see `PageProgressRepository` KDoc) — a fresh process re-fetches all pages, so the
 * cache survives only as long as Coil's own cache. This impl is a `MutableStateFlow<Map<...>>`
 * rather than a `ConcurrentHashMap` so the [observe] projection is a vanilla `.map` over the
 * single source of truth (no separate per-URL flows to invalidate).
 *
 * Thread-safety: [MutableStateFlow.update] is atomic under contention — Android's OkHttp body
 * wrap MAY emit [report] from background threads concurrent with the Reader VM's main-thread
 * observers. The state-flow's compare-and-set ensures map mutations don't tear.
 *
 * Memory footprint: monotonic per process (grows as new URLs are reported), but the upper bound
 * is bounded by the number of distinct page URLs the user opens in a session. A heavy reader
 * (1000 pages in a single session) costs ~1000 entries × (~80 bytes URL + ~24 bytes
 * [PageDownloadProgress] reference) ≈ 100 KB. Acceptable. The optional [clear] method is
 * exposed for callers that want explicit pruning (the Reader VM does NOT call it today — see
 * follow-on slice).
 *
 * Why a Map rather than a ConcurrentMap from kotlinx-collections-immutable:
 *  - The Map is read-only at the publication boundary (the state-flow holds an `immutable
 *    Map<String, PageDownloadProgress>` copy on each [update]). Adding a `kotlinx-collections-immutable`
 *    dep for one in-memory cache would be over-investment when a vanilla copy-on-write per
 *    update is sufficient at this scale.
 *  - On a typical reader page-tick cadence (≥50ms gap per emission), the per-update Map copy
 *    cost (~O(n) where n is the chapter's page count, ≤200 typically) is negligible.
 *
 * Why no `Idle` is ever stored in the map:
 *  - [observe] defaults to [PageDownloadProgress.Idle] for URLs absent from the map. Storing
 *    `Idle` would be redundant. [report] of `Idle` is treated as [clear] — an explicit
 *    transition back to the default state.
 *
 * [distinctUntilChanged] on the [observe] projection:
 *  - Without it, every change to ANY URL's state would trigger a re-projection for EVERY
 *    observed URL (the state-flow's emission notifies all collectors). With it, only the URL
 *    that actually changed re-emits to its observer. Critical for keeping the Reader VM's
 *    per-page-progress collectors from spamming the UI on every neighbor's emission.
 */
class PageProgressRepositoryImpl : PageProgressRepository {

    private val state = MutableStateFlow<Map<String, PageDownloadProgress>>(emptyMap())

    override fun observe(url: String): Flow<PageDownloadProgress> =
        state
            .map { it[url] ?: PageDownloadProgress.Idle }
            .distinctUntilChanged()

    override fun report(url: String, status: PageDownloadProgress) {
        if (status is PageDownloadProgress.Idle) {
            clear(url)
            return
        }
        state.update { it + (url to status) }
    }

    override fun clear(url: String) {
        state.update { if (it.containsKey(url)) it - url else it }
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster152.staleKdocSweep.cascade,
 * Task #608, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-ninety-fourth sibling of the cluster57-151
 * sweep — CLOSING file of the wave-26 :data/repository reader-state tier
 * 5-leaf batch alongside ChapterPagesRepositoryImpl plus ReadingMode
 * RepositoryImpl plus ReadingSessionRepositoryImpl plus ReadProgressRepository
 * Impl; CLOSES :data/repository reader-state tier 5/5):
 *  (a) "In-memory-PageProgressRepository-backed-by-a-MutableStateFlow-of-
 *  URL-to-progress + SRP-contract-section-6-owns-ONE-rule-hold-the-latest-
 *  progress-state-for-every-observed-page-URL-in-a-thread-safe-observable-
 *  map-and-project-a-per-URL-Flow-on-demand + Where-the-progress-events-
 *  come-from-Coil-listener-on-:ui-OkHttp-body-wrap-on-:platform-androidMain
 *  -and-how-they-re-attached-to-image-requests-are-concerns-of-those-higher
 *  -layers + DIP-depends-only-on-PageProgressRepository-:domain-and-
 *  kotlinx.coroutines.flow-No-:platform-no-:shared-no-Coil-no-Android +
 *  The-:platform-reporters-call-report-through-the-interface-never-the-
 *  concrete-impl + Storage-model-ephemeral-in-memory-process-singleton +
 *  The-slice-s-plan-deliberately-rejects-persistence-a-fresh-process-re-
 *  fetches-all-pages-so-the-cache-survives-only-as-long-as-Coil-s-own-cache
 *  + This-impl-is-a-MutableStateFlow-Map-rather-than-a-ConcurrentHashMap-
 *  so-the-observe-projection-is-a-vanilla-.map-over-the-single-source-of-
 *  truth + Thread-safety-MutableStateFlow.update-is-atomic-under-contention
 *  -Android-s-OkHttp-body-wrap-MAY-emit-report-from-background-threads-
 *  concurrent-with-the-Reader-VM-s-main-thread-observers + The-state-flow-s
 *  -compare-and-set-ensures-map-mutations-don-t-tear + Memory-footprint-
 *  monotonic-per-process-grows-as-new-URLs-are-reported + Optional-clear-
 *  method-exposed-for-callers-that-want-explicit-pruning + Why-a-Map-rather
 *  -than-a-ConcurrentMap-from-kotlinx-collections-immutable-Adding-a-
 *  kotlinx-collections-immutable-dep-for-one-in-memory-cache-would-be-over
 *  -investment + Why-no-Idle-is-ever-stored-in-the-map-observe-defaults-to
 *  -PageDownloadProgress.Idle-for-URLs-absent-from-the-map-Storing-Idle-
 *  would-be-redundant-report-of-Idle-is-treated-as-clear + distinctUntil
 *  Changed-on-the-observe-projection-Without-it-every-change-to-ANY-URL-s-
 *  state-would-trigger-a-re-projection-for-EVERY-observed-URL + With-it-
 *  only-the-URL-that-actually-changed-re-emits-to-its-observer-Critical-
 *  for-keeping-the-Reader-VM-s-per-page-progress-collectors-from-spamming-
 *  the-UI-on-every-neighbor-s-emission" — LIVE-NOT-STALE. Verified:
 *  in-memory MutableStateFlow<Map<String, PageDownloadProgress>>-backed
 *  impl shipped. observe(url) projects state.map { it[url] ?: PageDownload
 *  Progress.Idle }.distinctUntilChanged() — the per-URL-isolation
 *  distinctUntilChanged stance honored. report(url, status) short-circuits
 *  to clear(url) when status is Idle (the "Idle is treated as clear,
 *  never stored" rule honored) otherwise state.update { it + (url to
 *  status) }. clear(url) does state.update { if (it.containsKey(url)) it -
 *  url else it } — the containsKey-guard avoids a no-op map rebuild when
 *  the URL was never reported. The "no :platform / :shared / Coil /
 *  Android imports" DIP stance honored — only kotlinx.coroutines.flow +
 *  :domain imports. The "MutableStateFlow.update atomic under contention"
 *  thread-safety stance honored. The "Map rather than ConcurrentMap"
 *  decision rationale honored (no kotlinx-collections-immutable dep
 *  added). Consumed by ObservePageProgressUseCase + ReportPageProgress
 *  UseCase (cluster93 sibling X) via the observe() / report() / clear()
 *  surface; the rework Reader VM consumes observe() per-page through the
 *  use case at its own MVI boundary while :platform reporters (Android
 *  OkHttp body wrap, iOS / Desktop fallback) call report() through the
 *  PageProgressRepository interface (never the concrete impl).
 *  CLOSING FILE of cluster152 — completes the wave-26 :data/repository
 *  reader-state tier sweep (5 of 5: ChapterPages + ReadingMode + Reading
 *  Session + ReadProgress + PageProgress). Wave-26 progress: cluster151
 *  closed :data/mapper tier (6/6 files), cluster152 closes :data/repository
 *  reader-state tier (5/5 files). Remaining unswept :data/repository
 *  surface to be picked up by cluster153 (6 files: AdultContentClassifier
 *  Impl + AboutRepositoryImpl + WhatsNewRepositoryImpl + ComplaintAction
 *  RepositoryImpl + PinnedComplaints + AdminComplaintListRepositoryImpl).
 *  One classification. Original Phase 6.4.x.pageprogress impl prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */

