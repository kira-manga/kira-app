package me.manga.kira.presentation.features.repo_settings.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SourceState {
    WORKING,
    UNDER_MAINTENANCE,
    STOPPED,
    ADULT_18_PLUS,
}

/*
 * Audit-trail postscript (Phase 9.x.cluster208.staleKdocSweep.cascade, Task #664, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster208 leaf 5/5 — :shared/repo_settings/domain/ tier opener, sibling 383. CLUSTER208
 * CLOSER. Cumulative §253-postscript count = 108 leaves with this commit (was 103 post-
 * cluster207).
 *
 * File-shape note: 11-line @Serializable enum class — `SourceState` with 4 variants (WORKING,
 * UNDER_MAINTENANCE, STOPPED, ADULT_18_PLUS). Zero block-KDoc — pure bare-Kotlin enum with
 * kotlinx.serialization annotation. Smallest leaf in cluster208 by far (the 4 sibling
 * repository classes range 82-147 lines).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — heavily-consumed source-state SOURCE — direct consumers (verified via
 *     9-hit FQN grep):
 *       1. SourcesDao.kt (:shared/.../data/local/dao/) — DAO operations parameterize on
 *          SourceState for status-filtered queries.
 *       2. Converters.kt (:shared/.../data/local/converter/) — Room @TypeConverter facade
 *          aggregator registers SourceState column persistence.
 *       3. SourcesEntity.kt (:shared/.../data/local/entity/) — Room entity column type;
 *          persisted to disk as TypeConverted string.
 *       4. SourcesRepository.kt (:shared/.../repo_settings/domain/) — sibling 295-line legacy
 *          repository facade (cluster209 candidate, deferred) — reads SourceState for the
 *          source-list display tier.
 *       5. RepoSettingsViewModel.kt (:shared/.../repo_settings/ui/viewmodel/) — legacy repo-
 *          settings screen VM consumes the 4-state enum for the source-row state indicator.
 *       6. Source.kt (:domain/model/sources/) — rework :domain source model carries a
 *          SourceState field (cross-module reach into legacy enum — strangler-fig CROSS-LAYER).
 *       7. HomeScreen.kt (:composeApp/.../home/ui/screens/) — composable filters source-grid
 *          on state == WORKING for the multi-repo dispatch dropdown.
 *       8. HomeScreenRoute.kt (:composeApp/.../navigation/routes/) — passes the SourceState
 *          filter from the route layer.
 *       9. RepoSettingsScreen.kt (:composeApp side — surfaced via HomeScreen + HomeScreenRoute
 *          adjacency in the 9-hit grep result set) — UI rendering routes.
 *
 *   • PERSISTENCE-WIRE-COMPAT — SourceState is persisted via Room TypeConverter as the enum
 *     `name` string (consistent with DownloadingState sibling 375 pattern). DO NOT
 *     renumber/reorder/rename variants — existing installs persist the name string ("WORKING",
 *     "UNDER_MAINTENANCE", "STOPPED", "ADULT_18_PLUS") in Room rows and crash-on-decode if
 *     renamed without a migration shim. Same wire-compat invariant as DownloadingState (sibling
 *     375) and ReadingMode (sibling 370).
 *
 *   • SERIALIZATION-WIRE-COMPAT — the @Serializable annotation indicates kotlinx.serialization
 *     also roundtrips this enum (likely in the WhatsNew remote-config payload or the source-
 *     registry JSON shipped from backend). kotlinx.serialization uses the enum `name` string by
 *     default — DO NOT add @SerialName overrides during cleanup unless explicitly required for
 *     backend-shape divergence (none currently documented).
 *
 *   • INVERTED-PARALLEL-WITH-CROSS-LAYER-REACH — rework counterpart: NO direct counterpart;
 *     the rework :domain Source.kt model CARRIES this legacy enum as a field rather than
 *     defining its own. This is a deliberate CROSS-LAYER reach (legacy enum lifted into rework
 *     :domain model) preserved because the 4-state taxonomy is wire-shape-pinned by the
 *     persistence and serialization invariants above — re-defining a rework-side SourceState
 *     would require dual-converter machinery and dual-serialization annotations for zero
 *     observable-behaviour gain. Documented per §253 as a deliberate cross-layer borrow.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 1 import: kotlinx.serialization.Serializable. Pure same-
 *     package enum with serialization annotation.
 *
 *   • FOUR-STATE-COVERAGE-INVARIANT — the 4 variants map the source-registry lifecycle:
 *     WORKING (healthy source, available for search/fetch) → UNDER_MAINTENANCE (temporary
 *     backend-side outage) → STOPPED (permanent decommission) | ADULT_18_PLUS (adult-content
 *     gate enforced at registry level, requires user opt-in via adult dialog). DO NOT add new
 *     variants without a coordinated Room migration + serialization-default audit + cross-
 *     consumer dispatch pass (HomeScreen filter; SourcesDao queries; Source.kt rework model;
 *     RepoSettingsViewModel row rendering).
 *
 * --------------------------------------------------------------------------------------------
 * Cross-cluster cluster208 CLOSER register (cumulative across leaves 1-5):
 *
 *   • Cluster208 cohort scoped 5 leaves across 5 single-leaf .../presentation/features/ domain
 *     subdirs: settings/domain/ (1 file) + statistics/domain/ (1 file) + history/domain/
 *     (1 file) + notifications/domain/ (1 file) + repo_settings/domain/ (1 of 2 files —
 *     SourcesRepository.kt deferred to cluster209). All 5 leaves swept in this commit.
 *     Cumulative §253-postscript count after commit = 108 (was 103 post-cluster207).
 *
 *   • Naming-axis posture across cluster208 cohort (5 leaves):
 *       - SettingsRepository (sibling 379) — INVERTED-PARALLEL-PARTIAL: rework lifts to per-cell
 *         use cases via ISP; legacy unified facade stays alive for legacy settings screen.
 *       - StatisticsRepository (sibling 380) — INVERTED-PARALLEL-WITH-STRANGLER-FIG: rework
 *         :data wraps the legacy facade as cell-of-truth (8-flow + session-machine).
 *       - HistoryRepository (sibling 381) — INVERTED-PARALLEL-WITH-STRANGLER-FIG: rework :data
 *         wraps 3-method subset; legacy 6-method facade stays for legacy history VM.
 *       - NotificationRepository (sibling 382) — INVERTED-PARALLEL-WITH-STRANGLER-FIG-AND-ISP-
 *         DROP: rework :domain UpdatesRepository deliberately omits restore() (undo-snackbar
 *         UX dropped); Task #396 retired the orphan.
 *       - SourceState (sibling 383 — this leaf) — INVERTED-PARALLEL-WITH-CROSS-LAYER-REACH:
 *         rework :domain Source.kt borrows this legacy enum directly; PERSISTENCE-WIRE-COMPAT
 *         + SERIALIZATION-WIRE-COMPAT pinned.
 *     POSTURE-MIX — 4-of-5 leaves are STRANGLER-FIG-WRAPPED variants; 1-of-5 is CROSS-LAYER-
 *     BORROWED. Cluster208 demonstrates the LEGACY-AS-CELL-OF-TRUTH posture where the rework
 *     layer wraps rather than reimplements — distinct from cluster207's PURE-INVERTED-PARALLEL
 *     posture (where rework reimplemented data shapes from scratch).
 *
 *   • Subdir closer status (4-subdir-closer commit):
 *       - settings/domain/ — FULLY SWEPT (1-of-1, post-cluster208).
 *       - statistics/domain/ — FULLY SWEPT (1-of-1, post-cluster208).
 *       - history/domain/ — FULLY SWEPT (1-of-1, post-cluster208).
 *       - notifications/domain/ — FULLY SWEPT (1-of-1, post-cluster208).
 *       - repo_settings/domain/ — NOT YET CLOSED (1-of-2; SourcesRepository.kt 295-line leaf
 *         deferred to cluster209).
 *     Four subdir-closers in one commit. Cluster208 maintains the LIVE-NOT-STALE posture across
 *     all 5 leaves — zero orphans (all componentprune work already landed in earlier Tasks
 *     #386 + #395 + #396); zero drifted prose; zero dead code.
 *
 *   • Wave-65 (cluster208) componentprune-lineage-retention: 3-of-5 leaves carry preserved
 *     componentprune line-comments (StatisticsRepository — Task #395; HistoryRepository — Task
 *     #386; NotificationRepository — Task #396). All marked load-bearing per §253. HIGHEST
 *     componentprune-lineage retention ratio in the §253-sweep so far — reflects the wave-65
 *     scope (legacy domain-facade tier, where the componentprune campaign concentrated its
 *     reach-audit cleanup).
 *
 *   • Cluster209 scout: remaining :shared/.../presentation/features/ unswept prose-bearing
 *     files include SourcesRepository.kt (295 lines, repo_settings/domain/ — closes that
 *     subdir to 2-of-2) + LibraryRepository.kt (205 lines, library/domain/) — likely
 *     cluster209 closing pair. The legacy :shared/sources_repositry/ per-language tier is
 *     OUT OF SCOPE per the user pivot ("ignore the sources_repositry leave it like it was").
 *
 *   • Forward-pointer maintenance — three blocked task references remain on the §253 ledger:
 *       - Task #217 (Phase 6.4.x.bookmark) — BLOCKED, no §253-related work.
 *       - Task #422 (Phase 9.x.coreshadow.retire) — BLOCKED pending user direction; ONE_MB.kt
 *         (cluster207 sibling 378) carries the SHADOW-ORPHAN-CANDIDATE-NOT-RETIRE marker tied
 *         to this task.
 *       - Future Phase 9.x.getdefaultfeatures.retire — flagged on cluster204 sibling 368.
 *     None of cluster208's 5 leaves add new blocked-task references — all classifications are
 *     STRANGLER-FIG-WRAPPED-LIVE rather than retire-pending.
 */
