package me.manga.kira.presentation.statistics

import me.manga.kira.presentation.mvi.MviIntent

/**
 * Statistics screen MVI intents.
 *
 * Phase 7.x.statistics rework. Today the screen has NO user-driven mutations — the 8 numbers
 * are pure flow-driven projections from
 * [me.manga.kira.domain.usecase.statistics.ObserveReadingStatisticsUseCase]. The sealed
 * interface is therefore declared empty.
 *
 * **Why a sealed interface with zero variants** (rather than omitting the type and using
 * `MviViewModel<S, Nothing, Nothing>` in the VM signature):
 *  - OCP (contract §6): future user actions (e.g., `OnClearReadTime`, `OnExportCsv`,
 *    `OnTapEntriesCard` for drill-down navigation) slot in as new variants without changing
 *    the VM's base-class signature or the [me.manga.kira.presentation.mvi.MviViewModel]
 *    superclass parameterisation.
 *  - The `MviViewModel.submit(intent: StatisticsIntent)` public API compiles — there's just no
 *    way for a caller to construct an instance to pass, so `submit` is unreachable until a
 *    variant is added. No runtime cost, documents the slice's extensibility hook.
 *
 * Sealed interface (not sealed class) so future variants can be declared `object` cases
 * without nesting; same convention as the rest of the rework's MVI intents.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster103.staleKdocSweep.cascade,
 * Task #559, 2026-05-28): the file-scope intent-surface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-third sibling of the cluster57-102 sweep —
 * sibling of cluster103 StatisticsEffect.kt):
 *  (a) "Today the screen has NO user-driven mutations — the 8 numbers
 *  are pure flow-driven projections from [ObserveReadingStatisticsUse-
 *  Case]" — LIVE-NOT-STALE. L26 sealed-interface declaration has ZERO
 *  variants. StatisticsViewModel.kt L43-47 single-collaborator
 *  constructor binds only `observeReadingStatistics`; L49-67 init
 *  collector is the sole projection path into state.
 *  (b) "Why a sealed interface with zero variants ... future user
 *  actions (e.g., `OnClearReadTime`, `OnExportCsv`, `OnTapEntries-
 *  Card`) slot in as new variants" — REGISTERED-BUT-DORMANT-with-
 *  FORECAST. Recursive Grep for `OnClearReadTime` plus `OnExportCsv`
 *  plus `OnTapEntriesCard` returns ZERO live references; all three
 *  are OCP illustrations for future slices.
 *  (c) "the `MviViewModel.submit(intent: StatisticsIntent)` public API
 *  compiles — there's just no way for a caller to construct an
 *  instance to pass, so `submit` is unreachable until a variant is
 *  added" — LIVE-NOT-STALE. Sealed-with-zero-subtypes is uninstantia-
 *  ble at the type level; submit-call-site verification across `:ui/
 *  statistics/` plus `:composeApp/.../routes/StatisticsScreenRoute.kt`
 *  yields ZERO `submit(...)` calls.
 *  Two LIVE-NOT-STALE classifications plus one REGISTERED-BUT-DORMANT-
 *  with-FORECAST classification STAND on their own merits as a
 *  faithful Statistics-intent-surface manifest. Original Phase 7.x.
 *  statistics-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
sealed interface StatisticsIntent : MviIntent
