package me.manga.kira.di

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module

/**
 * iOS entry point — historically called from Swift as `KoinHelperKt.doInitKoin()`. From Phase 8.x
 * onward the canonical iOS bootstrap is `IosKoinKt.bootstrapIosKoin()` in `:composeApp/iosMain`,
 * which knows about the rework feature graph via `allReworkModules()`. This function is kept as
 * a legacy entry (zero-arg default keeps old Swift code compiling and resolves only the legacy
 * graph) and as the layer that the new bootstrap delegates into.
 *
 * Why split: `:shared` cannot see `:composeApp`, so it cannot reference `allReworkModules()`. The
 * iOS host therefore lives one layer up (`:composeApp/iosMain`) where the rework graph is visible.
 *
 * @param extraModules feature-slice modules to append to the legacy graph (e.g. `allReworkModules()`).
 */
@OptIn(ExperimentalNativeApi::class)
fun doInitKoin(extraModules: List<Module> = emptyList()): KoinApplication {
    // SECURITY: mirror the Android release floor (MyApp.kt) on iOS. Release binaries drop
    // Info/Debug/Verbose globally so the legacy scrapers' Info diagnostics — request URLs and
    // header maps including Cookie/cf_clearance/User-Agent values — never reach os_log; debug
    // binaries keep verbose logs for development.
    if (!Platform.isDebugBinary) {
        Logger.setMinSeverity(Severity.Warn)
    }
    return startKoin {
        modules(allSharedModules() + platformModule() + extraModules)
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster168.staleKdocSweep.cascade,
 * Task #624, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-thirty-fifth sibling of the cluster57-167
 * sweep — single-leaf file of the wave-40 shared/iosMain iOS-bootstrap-
 * legacy-entry batch; SOLE shared/iosMain iOS-DI-legacy-entry 1/1 — natural
 * cross-module SIBLING of cluster166's IosKoin.kt rework-coupled entry):
 *  (a) KDoc "iOS-entry-point-historically-called-from-Swift-as-KoinHelperKt-
 *  doInitKoin + From-Phase-8-x-onward-the-canonical-iOS-bootstrap-is-
 *  IosKoinKt-bootstrapIosKoin-in-composeApp-iosMain-which-knows-about-the-
 *  rework-feature-graph-via-allReworkModules + This-function-is-kept-as-a-
 *  legacy-entry-zero-arg-default-keeps-old-Swift-code-compiling-and-resolves-
 *  only-the-legacy-graph + and-as-the-layer-that-the-new-bootstrap-delegates-
 *  into" — LIVE-NOT-STALE (the Phase 8.x bootstrap-migration narrative remains
 *  load-bearing — verified via Grep + cluster166 read: IosKoinKt.bootstrap
 *  IosKoin IS the canonical iOS Swift-entry, and its body `= doInitKoin(
 *  extraModules = allReworkModules())` IS the documented delegation. The
 *  zero-arg `doInitKoin(extraModules: List<Module> = emptyList())` signature
 *  IS preserved, so any legacy Swift code calling `KoinHelperKt.doInitKoin()`
 *  with no args continues to compile and resolves only the legacy graph —
 *  exactly as the KDoc stipulates. The "legacy entry + delegation target"
 *  dual-role description is the present truth, not a forecast). (b) KDoc
 *  "Why-split-shared-cannot-see-composeApp-so-it-cannot-reference-
 *  allReworkModules + The-iOS-host-therefore-lives-one-layer-up-composeApp-
 *  iosMain-where-the-rework-graph-is-visible" — LIVE-NOT-STALE (the module-
 *  dependency-graph constraint is the foundational layering rationale and
 *  remains TRUE: `:shared` still has no `:composeApp` dependency; allRework
 *  Modules() still lives in `:composeApp/commonMain/di/ReworkModules.kt`
 *  alone. The "iOS host lives in `:composeApp/iosMain`" decision is therefore
 *  the ONLY architecturally-valid hosting site where both `doInitKoin` and
 *  `allReworkModules()` are simultaneously in scope. This is a load-bearing
 *  architectural-invariant rationale, not a forecast — it explains WHY the
 *  split exists, not what to do next). (c) KDoc @param "extraModules-feature-
 *  slice-modules-to-append-to-the-legacy-graph-e-g-allReworkModules" —
 *  LIVE-NOT-STALE (the `extraModules: List<Module> = emptyList()` parameter
 *  signature shipped; the documented example use `allReworkModules()` is
 *  EXACTLY how cluster166's bootstrapIosKoin invokes this function. The
 *  startKoin body `modules(allSharedModules() + platformModule() +
 *  extraModules)` honors the documented append semantics — extras come last
 *  in the module-list concatenation, preserving the "append to the legacy
 *  graph" contract). Verified: fun doInitKoin(extraModules: List<Module> =
 *  emptyList()): KoinApplication = startKoin { modules(allSharedModules() +
 *  platformModule() + extraModules) }. Three KDoc paragraphs (incl. @param)
 *  remain accurate; no drift. Sibling: IosKoin.kt (cluster166 closing-sibling
 *  per IosKoin.kt — the bootstrapIosKoin Swift-entry that delegates into this
 *  doInitKoin with allReworkModules()). This file forms the CROSS-MODULE
 *  COUNTERPART of cluster166 — cluster166 swept the rework-coupled
 *  composeApp side (IosKoin.kt + MainViewController.kt), cluster168 sweeps
 *  the legacy-coupled shared side (this file). SOLE FILE of the cluster168
 *  shared/iosMain iOS-DI-legacy-entry 1-leaf cluster (1 of 1). Three
 *  classifications. Original Phase 8.x iOS-Koin-legacy-entry prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
