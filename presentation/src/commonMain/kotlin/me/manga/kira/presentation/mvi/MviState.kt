package me.manga.kira.presentation.mvi

/**
 * Marker for the immutable view-state produced by a ViewModel.
 *
 * Strict MVI rule: implementations MUST be immutable `data class` instances with `val`
 * properties only. The view derives every UI element it shows from the current State —
 * no view-side mutable state allowed beyond ephemeral UI primitives (focus, scroll).
 *
 * The empty-marker shape lets us write a base [MviViewModel] generic over `S : MviState`
 * without forcing a particular set of fields on every feature.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster104.staleKdocSweep.cascade,
 * Task #560, 2026-05-28): the file-scope marker-interface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-fourth sibling of the cluster57-103 sweep —
 * sibling of cluster104 MviIntent.kt):
 *  (a) "Marker for the immutable view-state produced by a ViewModel" —
 *  LIVE-NOT-STALE. Recursive Grep across `:presentation/
 *  *State.kt` confirms every feature State surface implements
 *  `MviState`. Cluster-
 *  102 HistoryState plus cluster103 StatisticsState plus cluster101
 *  ReaderState all data-class instances with `val`-only properties.
 *  (b) "Strict MVI rule: implementations MUST be immutable `data class`
 *  instances with `val` properties only" — LIVE-NOT-STALE. Every
 *  feature State verified as `data class` with `val`-only fields
 *  across the cluster31 `:presentation State tier survey` (Task #487)
 *  plus subsequent State-touching cluster sweeps (no `var`, no mutable
 *  collections in public surface).
 *  (c) "The empty-marker shape lets us write a base [MviViewModel]
 *  generic over `S : MviState` without forcing a particular set of
 *  fields on every feature" — LIVE-NOT-STALE. MviViewModel.kt L45 type
 *  parameter `S : MviState` realization preserved; no member additions
 *  to the marker since Phase 6.1 foundation.
 *  Three LIVE-NOT-STALE classifications STAND on their own merits as a
 *  faithful MviState marker-interface manifest. Original Phase 6.1-era
 *  prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
interface MviState
