package me.manga.kira.presentation.features.settings.data

const val ONE_MB = 1024 * 1024L

/*
 * Audit-trail postscript (Phase 9.x.cluster207.staleKdocSweep.cascade, Task #663, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster207 leaf 5/5 — :shared/settings/data/ tier SINGLE-LEAF (single .kt file in subdir),
 * sibling 378. CLUSTER207 CLOSER. Cumulative §253-postscript count = 103 leaves with this
 * commit (was 98 post-cluster206).
 *
 * File-shape note: 3-line top-level `const val ONE_MB = 1024 * 1024L` — single bytes-per-MB
 * constant. Zero imports, zero KDoc. Smallest cluster205-207 leaf by far.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE-BARELY — strangler-fig SOURCE with EXACTLY ONE direct consumer (verified
 *     via FQN grep):
 *       1. SettingsRepository.kt (:shared/.../settings/domain/) — legacy SettingsRepository
 *          impl calls `fs.clearCacheLargerThan(ONE_MB)` in clearImageCache(). The 1MB threshold
 *          is the "clear images larger than 1 megabyte from the cache" upper bound — used by
 *          the user-facing "Clear image cache" Settings menu action.
 *     The rework :domain ClearCacheUseCase.kt + :domain SettingsRepository.kt have KDoc
 *     references to the legacy `clearCacheLargerThan(ONE_MB)` site for port-lineage tracking
 *     but DO NOT import this constant — they implement their own cache-clear semantics via the
 *     :platform AppFileSystem rework facade (which has its own 1MB threshold inlined as a
 *     bare literal, not a constant). The rework deliberately did NOT lift ONE_MB to a shared
 *     constant — the literal is short and the constant has only one consumer.
 *
 *   • INVERTED-PARALLEL — rework counterpart: NO direct counterpart (constant duplicated
 *     inline as bare `1024 * 1024L` in :platform AppFileSystem rework facade). DO NOT lift to
 *     a shared :core constant during cleanup — the rework deliberately chose duplicate-bare-
 *     literal over a shared constant (per the "Don't add features, refactor, or introduce
 *     abstractions beyond what the task requires" principle; the constant has 1 consumer in
 *     legacy + 1 inline literal in rework — promoting to :core would breach §253 "no
 *     observable-behavior changes during audit pass" by introducing a new cross-module
 *     dependency edge for trivial value-equality).
 *
 *   • SHADOW-ORPHAN-CANDIDATE-NOT-RETIRE — ONE_MB has exactly 1 consumer (legacy
 *     SettingsRepository.clearImageCache). If that legacy SettingsRepository becomes orphan
 *     after a future rework-settings-feature swap, ONE_MB.kt becomes coupled-orphan. DO NOT
 *     pre-emptively retire — wait for the SettingsRepository orphan-retire campaign (currently
 *     blocked behind Task #422 — Phase 9.x.coreshadow.retire pending user direction). Forward-
 *     work pointer: if Task #422 resolves "legacy direction", retire ONE_MB.kt alongside
 *     SettingsRepository.kt in a sibling commit.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — zero imports. Pure same-package top-level const val.
 *
 *   • TYPE-IS-LONG-NOT-INT-INVARIANT — the `L` suffix on `1024 * 1024L` is LOAD-BEARING. The
 *     downstream `clearCacheLargerThan(thresholdBytes: Long)` signature accepts Long; without
 *     the `L` suffix the literal would be Int (1048576) which fits but would force implicit
 *     widening at the call site. Coil image-cache file sizes can exceed Int.MAX_VALUE (~2.1GB)
 *     on extreme webtoon volumes — Long is the correct width. DO NOT drop the `L` suffix
 *     during literal-cleanup passes.
 *
 * --------------------------------------------------------------------------------------------
 * Cross-cluster cluster207 CLOSER register (cumulative across leaves 1-5):
 *
 *   • Cluster207 cohort scoped 5 leaves across 3 :shared/.../presentation/features/ data-shape
 *     subdirs: download/data/ (2 files) + home/data/ (2 files) + settings/data/ (1 file).
 *     All 5 leaves swept in this commit. Cumulative §253-postscript count after commit = 103
 *     (was 98 post-cluster206).
 *
 *   • Naming-axis posture across cluster207 cohort (5 leaves):
 *       - DownloadState (sibling 374) — INVERTED-PARALLEL: 4-state sealed (legacy lifecycle) vs
 *         5-state sealed (rework orchestrator).
 *       - DownloadingState (sibling 375) — INVERTED-PARALLEL with PERSISTENCE-WIRE-COMPAT pin:
 *         5-variant flat enum (Room-persisted) vs 5-variant sealed (rework :domain).
 *       - ApiTitle (sibling 376) — INVERTED-PARALLEL with PERSISTENCE-WIRE-COMPAT pin: 2-field
 *         composite-key (legacy) vs 3-field identity-tuple (rework).
 *       - SearchType (sibling 377) — INVERTED-PARALLEL with NO-REWORK-COUNTERPART:
 *         sources_repositry/ stays in :shared, search-dispatch never migrates.
 *       - ONE_MB (sibling 378 — this leaf) — INVERTED-PARALLEL with NO-REWORK-COUNTERPART:
 *         constant duplicated as bare inline literal in :platform rework facade.
 *     UNIFORM POSTURE — all 5 leaves are INVERTED-PARALLEL. Cluster207 demonstrates a CLEAN
 *     STRANGLER-FIG inverted-parallel cohort: NONE of the legacy data shapes have direct
 *     identity-matching rework counterparts. The rework layers consume the same domains (
 *     download lifecycle, manga identity, settings threshold) via DIFFERENT shapes adapted to
 *     each layer's orchestrator (coroutine vs WorkManager; rework :domain identity vs Room
 *     savedManga schema; bare literal vs constant).
 *
 *   • Subdir closer status:
 *       - download/data/ — FULLY SWEPT (2-of-2, post-cluster207).
 *       - home/data/ — FULLY SWEPT (2-of-2, post-cluster207).
 *       - settings/data/ — FULLY SWEPT (1-of-1, post-cluster207).
 *     Three subdir-closers in one commit.
 *
 *   • Doc-lacuna ratio across cluster207: 1-of-5 retains prose (SearchType — 3-line port-
 *     lineage line-comment). 4-of-5 are pure data-shapes with zero pre-existing KDoc. LOWEST
 *     doc-retention ratio in the §253-sweep so far — reflects the bare-data-class nature of
 *     the cluster (data classes / enums / single const val are not typically prose-bearing).
 *
 *   • Wave-64 (cluster207) maintains the LIVE-NOT-STALE posture across all 5 leaves — zero
 *     orphans, zero drifted prose, zero dead code. One SHADOW-ORPHAN-CANDIDATE-NOT-RETIRE
 *     marker (ONE_MB — sibling 378) preserved as load-bearing forward-work reference linked
 *     to blocked Task #422.
 *
 *   • Cluster208+ scout: remaining :shared/.../presentation/features/ unswept prose-bearing
 *     files include the larger settings/domain/ subdir (SettingsRepository + StatisticsRepository
 *     pair) — likely cluster208 target. Followed by library/data/+library/domain/ tier and any
 *     remaining home/domain/ + sources/domain/ leaves.
 */
