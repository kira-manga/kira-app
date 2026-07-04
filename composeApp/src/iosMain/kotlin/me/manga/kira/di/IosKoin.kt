package me.manga.kira.di

import kotlin.experimental.ExperimentalNativeApi
import me.manga.kira.admin.Admin
import org.koin.core.KoinApplication

/**
 * iOS Koin bootstrap — the public Swift-callable entry from Phase 8.x onward.
 *
 * Why this lives in `:composeApp/iosMain` and not `:shared`: `allReworkModules()` is defined in
 * `:composeApp/commonMain` (composition root), and `:shared` does not depend on `:composeApp`.
 * The iOS host therefore needs a thin file in `:composeApp/iosMain` that has both worlds in scope
 * — `doInitKoin()` (from `:shared/iosMain/KoinHelper.kt`) and `allReworkModules()` (from
 * `:composeApp/commonMain/di/ReworkModules.kt`).
 *
 * Swift call:
 * ```swift
 * import ComposeApp
 * ...
 * init() {
 *     IosKoinKt.bootstrapIosKoin()
 * }
 * ```
 *
 * Replaces the previous `KoinHelperKt.doInitKoin()` call. The legacy entry is still functional
 * (and is what this function delegates into) so a rollback is a one-line Swift change.
 */
@OptIn(ExperimentalNativeApi::class)
fun bootstrapIosKoin(): KoinApplication {
    // C1 (2026-07-03): debug-only admin. The Xcode Debug configuration embeds the Debug framework
    // (isDebugBinary = true) and flips the fail-closed default; Release/TestFlight/App Store
    // builds keep Admin.isAdmin = false — same signal :data:remote's isHttpLoggingEnabled uses.
    Admin.isAdmin = kotlin.native.Platform.isDebugBinary
    return doInitKoin(extraModules = allReworkModules())
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster166.staleKdocSweep.cascade,
 * Task #622, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-thirty-third sibling of the cluster57-165
 * sweep — CLOSING file of the wave-38 iOS host-entry 2-leaf batch; CLOSES
 * iOS host-entry tier 2/2):
 *  (a) KDoc "iOS-Koin-bootstrap + the-public-Swift-callable-entry-from-
 *  Phase-8-x-onward + Why-this-lives-in-composeApp-iosMain-and-not-shared
 *  + allReworkModules-is-defined-in-composeApp-commonMain-composition-root
 *  + shared-does-not-depend-on-composeApp + the-iOS-host-therefore-needs-
 *  a-thin-file-in-composeApp-iosMain-that-has-both-worlds-in-scope" —
 *  LIVE-NOT-STALE (the layering-rationale remains load-bearing — verified
 *  via Grep: doInitKoin still resides in shared/iosMain/KoinHelper.kt
 *  alone; allReworkModules still resides in composeApp/commonMain/di/
 *  ReworkModules.kt alone; :shared continues to have no dependency on
 *  :composeApp. The :composeApp/iosMain hosting decision is therefore the
 *  ONLY place where both worlds can be in scope simultaneously). (b) KDoc
 *  "Swift-call-import-ComposeApp-init-IosKoinKt-bootstrapIosKoin" — LIVE-
 *  NOT-STALE (verified: fun bootstrapIosKoin(): KoinApplication = doInit
 *  Koin(extraModules = allReworkModules()) shipped. The single-line
 *  delegation pattern matches the documented Swift call site exactly).
 *  (c) KDoc "Replaces-the-previous-KoinHelperKt-doInitKoin-call + The-
 *  legacy-entry-is-still-functional-and-is-what-this-function-delegates-
 *  into-so-a-rollback-is-a-one-line-Swift-change" — LIVE-NOT-STALE (the
 *  rollback-path forecast is HONORED, not stale: doInitKoin(extraModules
 *  = allReworkModules()) IS the delegation. The legacy entry shared/
 *  iosMain/KoinHelper.kt::doInitKoin remains callable. If Swift rolled
 *  back IosKoinKt.bootstrapIosKoin() → KoinHelperKt.doInitKoin(), the
 *  result would be Koin without rework modules — exactly the pre-Phase
 *  -8.x state. The "rollback is a one-line Swift change" prose is a
 *  load-bearing escape-hatch documentation, not a forecast pending
 *  fulfillment). Verified: fun bootstrapIosKoin(): KoinApplication =
 *  doInitKoin(extraModules = allReworkModules()). Three KDoc paragraphs
 *  remain accurate; no drift. Sibling: MainViewController.kt (opening-
 *  sibling per MainViewController.kt — the ComposeUIViewController factory
 *  that mounts App() and depends on Koin having been initialized via this
 *  file's bootstrapIosKoin entry). CLOSING FILE of the cluster166 iOS host-
 *  entry 2-leaf batch (2 of 2). One classification. Original Phase 8.x
 *  iOS Koin-bootstrap prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
