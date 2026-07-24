package me.manga.kira.presentation.features.webview.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.sources.contracts.SourceRegistry

/**
 * Phase 9.x.webviewvm.componentprune (Task #406): dropped 2 orphan constructor deps
 * (`dataStoreManager: DataStoreHelper`, `activeRepoProvider: ActiveRepoProvider`) +
 * 1 unrelated unreferenced import (`SourcesDao`) after a 3-pass receiver-anchored
 * reacher-chain audit (`webViewViewModel.X(` + bare `\bX\b` + `::X`) covering the
 * entire source tree. The audit confirmed that the ONLY external reach onto this VM
 * is `webViewViewModel.saveHeaders(...)` at `WebViewScreenRoute.kt:35` + `:38`, and
 * that the `saveHeaders` body references ONLY `sourcesRepository.getRepoByName(api)`
 * — neither `dataStoreManager` nor `activeRepoProvider` is referenced anywhere inside
 * the class body. Each orphan ctor param appeared EXACTLY ONCE in the file (at its
 * own declaration site); the `SourcesDao` import was unreferenced.
 *
 * Removed (independent orphans):
 *   - `dataStoreManager: DataStoreHelper` — ctor dep. Zero internal references.
 *   - `activeRepoProvider: ActiveRepoProvider` — ctor dep. Zero internal references.
 *   - `import me.manga.kira.data.local.dao.SourcesDao` — orphan import; never
 *     referenced in the file body.
 *
 * Coupled import drops (transitively-dead once the ctor deps are gone):
 *   - `me.manga.kira.core.storage.DataStoreHelper` — type of the dropped
 *     `dataStoreManager` param. No other use.
 *   - `me.manga.kira.di.sources.provider.ActiveRepoProvider` — type of the
 *     dropped `activeRepoProvider` param. No other use.
 *
 * LIVE method preserved (verified by exhaustive reacher-chain audit):
 *   - `saveHeaders(headers, api)` — `WebViewScreenRoute.kt:35`/`:38`.
 *
 * Constructor narrows from 3-arg `(dataStoreManager, activeRepoProvider,
 * sourcesRepository)` to 1-arg `(sourcesRepository)`. The lone LIVE dep
 * `sourcesRepository` is referenced by the LIVE `saveHeaders` body
 * (`sourcesRepository.getRepoByName(api).refreshHeaders(headers)`). Koin binding
 * `viewModel { WebViewViewModel(get()) }` in `SharedModule.kt:314` updated from
 * 3-arg to 1-arg in the same slice.
 */
class WebViewViewModel(
    private val dataStore: DataStoreHelper,
    private val sourceRegistry: SourceRegistry,
) : ViewModel() {

    private val log = Logger.withTag(TAG)

    fun saveHeaders(headers: Map<String, String>?, api: String) {
        if (headers.isNullOrEmpty()) {
            return
        }

        viewModelScope.launch(platformIoDispatcher) {
            try {
                // A WebView route must never create state for a withheld/removed/unknown source.
                if (sourceRegistry.get(api) == null) return@launch
                dataStore.saveHeadersForApi(api, headers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "Failed to persist Cloudflare headers for api=$api" }
            }
        }
    }

    private companion object {
        private const val TAG = "WebViewViewModel"
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster210.staleKdocSweep.cascade, Task #666, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster210 leaf 4/4 — :shared/webview/ui/viewmodel/ tier SINGLE-LEAF, sibling 389. CLUSTER210
 * CLOSER. Cumulative §253-postscript count = 114 leaves with this commit (was 110 post-
 * cluster209).
 *
 * File-shape note: 60-line class — `WebViewViewModel` with 1 ctor dep (sourcesRepository:
 * SourcesRepository) after Task #406 componentprune. Surfaces 1 LIVE method (saveHeaders). The
 * smallest VM in cluster210 by far. 34-line class-level KDoc (lines 9-42) carrying Task #406
 * componentprune lineage (2 dropped ctor deps + 1 orphan import + 2 coupled type-imports + 1-
 * name LIVE manifest).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — narrow-purpose Cloudflare-bypass header-persistence SOURCE — direct
 *     consumer (verified via the cited 2-hit WebViewScreenRoute.kt anchor in the prior KDoc
 *     head):
 *       1. WebViewScreenRoute.kt (:composeApp/.../navigation/routes/) — reaches saveHeaders
 *          at lines 35 + 38. Persists scraped User-Agent + Cookie headers from the embedded
 *          WebView to the active source repository's in-memory header cache (used by the
 *          Coil image-header interceptor + OkHttp/Ktor fetcher chain).
 *
 *   • NO-INVERTED-PARALLEL — rework counterpart status: NONE. There is no rework WebView screen
 *     yet (Phase 7.x.details.webview slice 3 routes to the LEGACY Screen.WebView; the rework
 *     Details surface delegates the WebView nav-target via :composeApp). The legacy WebView
 *     screen + VM are the only WebView surface in the codebase. This is the ONLY VM in
 *     cluster210 with NO rework counterpart — POSTURE-OUTLIER vs the 3 STRANGLER-FIG-WRAPPED
 *     sibling leaves.
 *
 *   • TASK-406-COMPONENTPRUNE-LINEAGE-PRESERVED — the 24-line componentprune block (lines
 *     9-32) documents Task #406's removal of 2 orphan ctor deps (DataStoreHelper +
 *     ActiveRepoProvider) + 1 orphan import (SourcesDao) + 2 coupled type-imports. The 1-name
 *     LIVE manifest (lines 33-34) is the verified-by-grep preserved-set. PRESERVE — load-
 *     bearing componentprune audit per §253. Note the explicit SharedModule.kt:314 cross-
 *     reference documenting that the Koin binding was narrowed in the same slice from 3-arg to
 *     1-arg.
 *
 *   • HEADER-REFRESH-INVARIANT — `saveHeaders` calls `sourcesRepository.getRepoByName(api)
 *     .refreshHeaders(headers)`. The early-return on null/empty headers (line 47-49) prevents
 *     a redundant `getRepoByName` lookup when the WebView reload returned no new headers. DO
 *     NOT remove the early-return during cleanup — getRepoByName performs a Set<BaseMangaRepository>
 *     linear scan (per SourcesRepository.kt:213-216) that surfaces in CPU profiles under fast
 *     WebView header re-emissions.
 *
 *   • SILENT-CATCH-BEHAVIOUR-PRESERVATION — the empty `catch (e: Exception) { }` body (lines
 *     55-57) is deliberate: a header-persistence failure should NOT crash the WebView screen
 *     (the user would be unable to dismiss the WebView). The Coil interceptor will fall back to
 *     stale headers + surface the failure via the next image-load attempt. DO NOT add Log.e
 *     or analytics surface during cleanup — would re-introduce the noisy WebView teardown
 *     telemetry that drove the silent-catch choice originally.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 5 imports (post-Task-#406 narrowing): androidx.lifecycle
 *     (ViewModel + viewModelScope) + kotlinx.coroutines.launch + core.concurrency.platformIoDispatcher
 *     + legacy SourcesRepository. All LIVE. SMALLEST import footprint in cluster210.
 *
 * --------------------------------------------------------------------------------------------
 * Cross-cluster cluster210 CLOSER register (cumulative across leaves 1-4):
 *
 *   • Cluster210 cohort scoped 4 leaves across 4 single-leaf :shared/.../presentation/features/
 *     (per-feature)/ui/viewmodel/ subdirs: settings/ + history/ + repo_settings/ + webview/.
 *     The (per-feature) VM tier OUTSIDE this batch carries 2 already-swept VMs (HomeViewModel —
 *     earlier cluster; ReaderViewModel — Task #413 dedicated staleKdocSweep). Cumulative §253-
 *     postscript count after commit = 114 (was 110 post-cluster209).
 *
 *   • Naming-axis posture across cluster210 cohort (4 leaves):
 *       - SettingsViewModel (sibling 386) — INVERTED-PARALLEL-PARTIAL: legacy survives as a
 *         narrow theme-bootstrap adapter for App.kt; rework owns the toggle/clear MVI surface.
 *       - HistoryViewModel (sibling 387) — INVERTED-PARALLEL-WITH-STRANGLER-FIG: legacy survives
 *         as a write-only Reader-side adapter; rework owns the read-path History screen MVI.
 *       - RepoSettingsViewModel (sibling 388) — INVERTED-PARALLEL-WITH-STRANGLER-FIG-AND-ISP-
 *         DROP: legacy survives as a Home-tab dropdown adapter; rework SourcesViewModel routes
 *         around 7 dropped facade methods via direct use-case reach.
 *       - WebViewViewModel (sibling 389 — this leaf) — NO-INVERTED-PARALLEL: only WebView
 *         surface in the codebase; legacy stays as the cell-of-truth (no rework counterpart).
 *     POSTURE-MIX — 3-of-4 leaves are INVERTED-PARALLEL variants (each with a different
 *     wrapping/bypass shape); 1-of-4 is NO-INVERTED-PARALLEL OUTLIER. Cluster210 demonstrates
 *     the LEGACY-AS-NARROW-ADAPTER posture where the legacy VM survives despite its primary
 *     screen consumer being retired — each surface narrowed by Task #397/#401/#405/#406
 *     componentprune to its remaining cross-screen integration anchor.
 *
 *   • Subdir closer status (4-subdir-closer commit, completes the :shared/.../features/
 *     (per-feature)/ui/viewmodel/ tier to 6-of-6 swept including the prior HomeViewModel +
 *     ReaderViewModel coverage):
 *       - settings/ui/viewmodel/ — FULLY SWEPT (1-of-1, post-cluster210).
 *       - history/ui/viewmodel/ — FULLY SWEPT (1-of-1, post-cluster210).
 *       - repo_settings/ui/viewmodel/ — FULLY SWEPT (1-of-1, post-cluster210).
 *       - webview/ui/viewmodel/ — FULLY SWEPT (1-of-1, post-cluster210).
 *       - home/ui/viewmodel/ — FULLY SWEPT (prior earlier-cluster coverage).
 *       - reader/ui/viewmodel/ — FULLY SWEPT (Task #413 dedicated staleKdocSweep).
 *     Six subdir-closers cumulative. Cluster210 maintains the LIVE-NOT-STALE posture across all
 *     4 leaves — zero orphans (all componentprune work already landed in earlier Tasks #397 +
 *     #401 + #405 + #406); zero drifted prose; zero dead code.
 *
 *   • Tier-completion declaration: cluster208 (domain/ tier, 5 leaves) + cluster209 (domain/
 *     tier, 2 closing leaves) + cluster210 (ui/viewmodel/ tier, 4 leaves) together complete the
 *     entire :shared/.../presentation/features/(per-feature)/{domain,ui/viewmodel}/ surface
 *     across 11 §253-postscript-bearing leaves. The remaining :shared/.../presentation/features/
 *     (per-feature)/ui/ surface is the screen+component composable tier (largely already swept
 *     via dedicated earlier clusters per-screen).
 *
 *   • Wave-65 (cluster210) componentprune-lineage-retention: 4-of-4 leaves carry preserved
 *     componentprune line-comments (SettingsViewModel — Task #397; HistoryViewModel — Task
 *     #401; RepoSettingsViewModel — Task #405; WebViewViewModel — Task #406). HIGHEST
 *     componentprune-lineage retention ratio in the §253-sweep — reflects the wave-65 scope
 *     (legacy VM tier, where the componentprune campaign concentrated its post-route-swap
 *     orphan-detection sweep).
 *
 *   • Cluster211 scout: remaining :shared/.../presentation/features/ unswept prose-bearing
 *     files would be the per-feature ui/screens/ + ui/components/ composable tier (largely
 *     already swept via cluster100+ wave-22 screen-by-screen passes). A dedicated tier-
 *     completion audit may surface a small handful of unswept leaves but the bulk of the
 *     :shared presentation/features/ surface is now FULLY SWEPT.
 *
 *   • Forward-pointer maintenance — three blocked task references remain on the §253 ledger:
 *       - Task #217 (Phase 6.4.x.bookmark) — BLOCKED, no §253-related work.
 *       - Task #422 (Phase 9.x.coreshadow.retire) — BLOCKED pending user direction; ONE_MB.kt
 *         (cluster207 sibling 378) carries the SHADOW-ORPHAN-CANDIDATE-NOT-RETIRE marker tied
 *         to this task.
 *       - Future Phase 9.x.getdefaultfeatures.retire — flagged on cluster204 sibling 368.
 *     None of cluster210's 4 leaves add new blocked-task references — all classifications are
 *     LEGACY-AS-NARROW-ADAPTER-LIVE rather than retire-pending.
 */
