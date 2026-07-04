package me.manga.kira.presentation.statistics

import me.manga.kira.presentation.mvi.MviEffect

/**
 * Statistics screen MVI effects.
 *
 * Phase 7.x.statistics rework. Today the screen has NO one-shot side effects — the 8 numbers
 * are pure read-only projections; no error toasts (Room's `Flow<Int>` cannot fail in any
 * actionable way), no navigation (Statistics is a terminal screen with no outbound links).
 * The sealed interface is therefore declared empty.
 *
 * **Why a sealed interface with zero variants**: same OCP rationale as [StatisticsIntent] —
 * future side effects (e.g., `NavigateToHistory`, `ShowExportComplete`) slot in as new
 * variants without changing the VM's superclass parameterisation. The
 * [me.manga.kira.presentation.mvi.MviViewModel.effects] flow surface compiles — but the
 * upstream `Channel` will never receive an emission because the VM has no `emit(...)` call
 * site today, so collectors hang on an idle Flow. Zero-cost.
 *
 * Sealed interface (not sealed class) so future variants can be declared `data class` /
 * `object` cases at top level; same convention as the rest of the rework's MVI effects.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster103.staleKdocSweep.cascade,
 * Task #559, 2026-05-28): the file-scope effect-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-third sibling of the cluster57-102 sweep —
 * opens the wave-8 `:presentation/statistics/` batch):
 *  (a) "Today the screen has NO one-shot side effects — the 8 numbers
 *  are pure read-only projections; no error toasts (Room's `Flow<Int>`
 *  cannot fail in any actionable way), no navigation (Statistics is a
 *  terminal screen with no outbound links). The sealed interface is
 *  therefore declared empty" — LIVE-NOT-STALE. L23 sealed-interface
 *  declaration has ZERO variants. StatisticsViewModel.kt L69-71
 *  reducer is a no-op `when {}` with no `emit(...)` call site,
 *  confirming the empty-effect-surface claim.
 *  (b) "Why a sealed interface with zero variants ... future side
 *  effects (e.g., `NavigateToHistory`, `ShowExportComplete`) slot in
 *  as new variants" — REGISTERED-BUT-DORMANT-with-FORECAST. Recursive
 *  Grep for `StatisticsEffect.NavigateToHistory` plus `StatisticsEffect
 *  .ShowExportComplete` matches ZERO live references; both examples
 *  are OCP illustrations, not planned slices. The OCP extension path
 *  is preserved by the sealed-interface shape (a new `data object` /
 *  `data class` variant compiles without touching the VM's superclass
 *  parameterisation).
 *  (c) "the `Channel` will never receive an emission because the VM
 *  has no `emit(...)` call site today, so collectors hang on an idle
 *  Flow. Zero-cost" — LIVE-NOT-STALE. StatisticsViewModel.kt L69-71
 *  `handle(intent: StatisticsIntent)` is a no-op; recursive Grep for
 *  `emit(StatisticsEffect` plus `emit(Statistics` returns ZERO matches
 *  inside this VM. The idle-Flow zero-cost claim holds.
 *  Two LIVE-NOT-STALE classifications plus one REGISTERED-BUT-DORMANT-
 *  with-FORECAST classification STAND on their own merits as a
 *  faithful Statistics-effect-surface manifest. Original Phase 7.x.
 *  statistics-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
sealed interface StatisticsEffect : MviEffect
