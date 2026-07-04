package me.manga.kira

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS entry — Swift calls this to obtain a [UIViewController] hosting the [App] composable.
 *
 * Usage from Swift (`ContentView.swift` -> `UIViewControllerRepresentable`):
 * ```swift
 * func makeUIViewController(context: Context) -> UIViewController {
 *     MainViewControllerKt.MainViewController()
 * }
 * ```
 *
 * Koin must be initialized BEFORE this view controller mounts (see `KoinHelper.doInitKoin()`
 * called from `iOSApp.init` in the Swift app).
 */
fun MainViewController(): UIViewController {
    return ComposeUIViewController { App() }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster166.staleKdocSweep.cascade,
 * Task #622, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-thirty-second sibling of the cluster57-165
 * sweep — OPENING file of the wave-38 iOS host-entry 2-leaf batch; OPENS
 * iOS host-entry tier 1/2):
 *  (a) KDoc "iOS-entry-Swift-calls-this-to-obtain-a-UIViewController-
 *  hosting-the-App-composable + Usage-from-Swift-ContentView-swift-
 *  UIViewControllerRepresentable-makeUIViewController + Koin-must-be-
 *  initialized-BEFORE-this-view-controller-mounts + see-KoinHelper-
 *  doInitKoin-called-from-iOSApp-init-in-the-Swift-app" — LIVE-NOT-STALE
 *  (the Swift-bridge contract holds: fun MainViewController(): UIView
 *  Controller = ComposeUIViewController { App() } shipped. The Koin-init-
 *  precondition prose remains TRUE — Koin must be initialized before the
 *  view controller mounts. The lower-level entry KoinHelper.doInitKoin()
 *  still exists in shared/iosMain/KoinHelper.kt; the Swift app now calls
 *  IosKoinKt.bootstrapIosKoin() (cluster166 closing-sibling per IosKoin.kt),
 *  which delegates into doInitKoin() with allReworkModules() — the
 *  precondition is therefore satisfied through the bootstrapIosKoin path
 *  rather than calling doInitKoin directly, but the KDoc's prose remains
 *  load-bearing as the precondition is unchanged). Verified: fun
 *  MainViewController(): UIViewController = ComposeUIViewController {
 *  App() }. No state, no remember-keyed config — pure ComposeUIViewController
 *  factory. Sibling: IosKoin.kt (closing-sibling per IosKoin.kt — the
 *  bootstrapIosKoin Swift-entry that delegates into KoinHelper.doInitKoin
 *  with allReworkModules()). OPENING FILE of the cluster166 iOS host-entry
 *  2-leaf batch (1 of 2). One classification. Original Phase 8.x iOS-
 *  scaffold prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
