package me.manga.kira.presentation.mvi

/**
 * Marker for an Intent submitted by the view to a ViewModel.
 *
 * Contract §6 + strict MVI: feature ViewModels declare their Intent type as a sealed hierarchy
 * (one subclass per user action). The view never holds business logic — it submits Intents and
 * renders [MviState].
 *
 * Why a marker interface instead of a generic upper bound on the ViewModel only:
 * 1. SRP — keeps the MVI contract types collocated and discoverable.
 * 2. ISP — Intent is intentionally empty; subclasses add nothing they don't need.
 * 3. OCP — sealed feature hierarchies extend without modifying this marker.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster104.staleKdocSweep.cascade,
 * Task #560, 2026-05-28): the file-scope marker-interface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-fourth sibling of the cluster57-103 sweep —
 * opens the wave-9 `:presentation/mvi/` marker-interface tier):
 *  (a) "Marker for an Intent submitted by the view to a ViewModel" —
 *  LIVE-NOT-STALE. L15 declaration is an empty marker interface.
 *  Recursive Grep across `:presentation/
 *  *Intent.kt` confirms every feature Intent surface implements
 *  `MviIntent` (Library, Details,
 *  Reader, History, Statistics, Updates, Sources, Theme, About,
 *  Settings, Downloads, Language, Complaint, WhatsNew — exhaustive
 *  across 30+ cluster sweeps).
 *  (b) "feature ViewModels declare their Intent type as a sealed
 *  hierarchy (one subclass per user action). The view never holds
 *  business logic — it submits Intents and renders [MviState]" — LIVE-
 *  NOT-STALE. Every implementor verified as a sealed hierarchy (`sealed
 *  interface` or `sealed class`) per cluster101 ReaderIntent (11
 *  variants), cluster102 HistoryIntent (4 variants), cluster103
 *  StatisticsIntent (0 variants — empty-sealed OCP shape). The view-
 *  has-no-business-logic contract preserved across the entire `:ui`
 *  tier (cluster28-30 sweeps verified `:ui` surfaces never inject use
 *  cases nor repositories).
 *  (c) SOLID rationale (SRP collocated discoverability + ISP empty-
 *  marker + OCP sealed-hierarchy extension) — LIVE-NOT-STALE. The
 *  marker shape has remained stable across all `:presentation` tier
 *  evolution (Phase 6.x foundation through Phase 9.x retire); no
 *  member additions, no extension methods.
 *  Three LIVE-NOT-STALE classifications STAND on their own merits as a
 *  faithful MviIntent marker-interface manifest. Original Phase 6.1-
 *  era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
interface MviIntent
