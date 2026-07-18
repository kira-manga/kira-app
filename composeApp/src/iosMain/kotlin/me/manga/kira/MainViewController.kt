package me.manga.kira

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.uikit.ComposeUIViewControllerConfiguration
import androidx.compose.ui.uikit.EndEdgePanGestureBehavior
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSBundle
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
 *
 * Navigation remains the standard Compose Navigation [androidx.navigation.compose.NavHost]. The
 * host publishes native back events from both physical edges, then the default NavHost accepts the
 * correct edge for its current [androidx.compose.ui.platform.LocalLayoutDirection]. This matters
 * when Kira changes language inside the running process: UIKit's native direction can lag the
 * Compose-local direction that NavHost already sees. [IosHostLayoutDirection] also keeps the UIKit
 * host semantics aligned for subsequent recognizer refreshes and other native behavior.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController(): UIViewController {
    val controller =
        ComposeUIViewController(
            configure = { configureKiraNavigationHost() },
        ) {
            App(crashDiagnosticsEnabled = isCrashDiagnosticsEnabled())
        }
    IosHostLayoutDirection.bind(controller.view)
    return controller
}

private fun isCrashDiagnosticsEnabled(): Boolean =
    NSBundle.mainBundle.objectForInfoDictionaryKey("KiraCrashDiagnosticsEnabled")
        ?.toString()
        ?.lowercase() in setOf("yes", "true", "1")

@OptIn(ExperimentalComposeUiApi::class)
internal fun ComposeUIViewControllerConfiguration.configureKiraNavigationHost() {
    // Compose Navigation's untouched iOS NavHost filters these events to LEFT in LTR and RIGHT in
    // RTL. Publishing the end edge as Back prevents a live app-language switch from losing the RTL
    // event when UIKit has not re-sampled its start edge.
    endEdgePanGestureBehavior = EndEdgePanGestureBehavior.Back
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
 *  MainViewController(): UIViewController still owns one ComposeUIViewController;
 *  its root view is additionally registered for UIKit/Compose layout-direction parity.
 *  The standard NavHost remains direct and keeps its default transitions; the Compose UIKit host
 *  only publishes back events from both edges so that NavHost can select the locale-correct edge.
 *  No navigation wrapper or remember-keyed config is introduced. Sibling: IosKoin.kt (closing-sibling per IosKoin.kt — the
 *  bootstrapIosKoin Swift-entry that delegates into KoinHelper.doInitKoin
 *  with allReworkModules()). OPENING FILE of the cluster166 iOS host-entry
 *  2-leaf batch (1 of 2). One classification. Original Phase 8.x iOS-
 *  scaffold prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
