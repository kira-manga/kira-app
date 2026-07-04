package me.manga.kira.platform.intent

/**
 * Cross-platform external-intent surface.
 *
 * Originally ported from upstream `presentation/features/about/common/openLink.kt`,
 * `OpenAppInPlayStore.kt`, and `Intent.ACTION_SEND` share helpers; here we are relocating that
 * surface from `:shared` (an `expect class`) into `:platform` (a contract `interface` with three
 * concrete actuals — Android / iOS / Desktop). The migration follows the strangler-fig pattern
 * laid out by contract §6 (DIP) and §10 (Strict Layer Separation): the legacy `:shared` surface
 * stays in place during the transition so consumers keep compiling. A later phase rewires
 * consumers through Koin against this `:platform` interface.
 *
 * Each method is fire-and-forget — failures are logged but never propagated, so call sites can
 * stay simple. Phase 10 consumers (About / Welcome / Theme / Settings / Share buttons) use this
 * directly via Koin instead of taking a `Context`.
 *
 * Behavioural parity with the legacy `:shared` expect class:
 *   - The Android implementation still issues real `Intent` objects with `FLAG_ACTIVITY_NEW_TASK`.
 *   - iOS routes everything through `UIApplication.openURL(...)`.
 *   - Desktop opens URIs via AWT `Desktop.browse(...)`.
 *
 * Simplifications inherited from the original (NOT introduced here):
 *   - No `setPackage("com.facebook.katana")` / `com.whatsapp` package-pinning. We open the web URL
 *     directly. The OS chooser still kicks in on Android if the user has the social app installed.
 *   - No Custom Tabs (`androidx.browser`). On Android, plain `ACTION_VIEW` covers 99% of cases.
 *   - No final `Toast.makeText("No app available")` fallback. The `try/catch` swallows
 *     `ActivityNotFoundException` and logs via Kermit.
 *
 * Threading: all methods are safe to call from any thread on every platform — Android marshals to
 * the main looper via `Context.startActivity`, iOS schedules onto the UI runloop via UIKit, and
 * Desktop's AWT `Desktop` API is itself thread-safe in this respect.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster144.staleKdocSweep.cascade,
 * Task #600, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-fifty-fourth sibling of the cluster57-143
 * sweep — second file of the wave-26 :platform tier opening cluster144
 * 5-leaf-bedrock-UX batch alongside ToastShower plus AppFileSystem plus
 * FileSizeFormatter plus LocaleSwitcher):
 *  (a) "Cross-platform-external-intent-surface + Originally-ported-from-
 *  upstream-presentation-features-about-common-openLink + The-migration-
 *  follows-the-strangler-fig-pattern-laid-out-by-contract-§6-DIP-and-§10-
 *  Strict-Layer-Separation + The-legacy-:shared-surface-stays-in-place-
 *  during-the-transition-so-consumers-keep-compiling + A-later-phase-
 *  rewires-consumers-through-Koin-against-this-:platform-interface +
 *  Each-method-is-fire-and-forget-failures-are-logged-but-never-
 *  propagated-so-call-sites-can-stay-simple + Phase-10-consumers-About-
 *  Welcome-Theme-Settings-Share-buttons-use-this-directly-via-Koin-
 *  instead-of-taking-a-Context" — LIVE-NOT-STALE plus FULFILLED-
 *  PREDICTION. Verified via recursive grep: IntentLauncher 3-method
 *  surface (openUrl + openPlayStorePage + shareText) wired with 3
 *  actuals at platform/src/{android,ios,desktop}Main/. The "Phase 10
 *  consumers use this via Koin" prediction held for rework About +
 *  Welcome + Theme + Settings rework slices (consumed through Koin
 *  injection rather than Context-handling). The strangler-fig contract
 *  also holds — the legacy :shared surface remains for backward-
 *  compat with not-yet-migrated consumers.
 *  (b) "Behavioural-parity-with-the-legacy-:shared-expect-class + The-
 *  Android-implementation-still-issues-real-Intent-objects-with-FLAG_-
 *  ACTIVITY_NEW_TASK + iOS-routes-everything-through-UIApplication.
 *  openURL + Desktop-opens-URIs-via-AWT-Desktop.browse +
 *  Simplifications-inherited-from-the-original-NOT-introduced-here-No-
 *  setPackage-com.facebook.katana-com.whatsapp-package-pinning-We-open-
 *  the-web-URL-directly + No-Custom-Tabs-androidx.browser + No-final-
 *  Toast.makeText-No-app-available-fallback + Threading-all-methods-
 *  are-safe-to-call-from-any-thread-on-every-platform" — LIVE-NOT-
 *  STALE. Verified: the 3 actuals (AndroidIntentLauncher + IosIntent-
 *  Launcher + DesktopIntentLauncher) preserve the documented behaviour
 *  contracts — Android uses FLAG_ACTIVITY_NEW_TASK + market://-first
 *  fallback, iOS calls UIApplication.sharedApplication.openURL, Desktop
 *  uses AWT Desktop.browse / Desktop.mail. The "no package pinning + no
 *  Custom Tabs + no Toast-fallback" simplifications are still honored.
 *  Two classifications STAND on their own merits. Original Phase 5.3
 *  (Task #166) :platform-relocation prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
interface IntentLauncher {

    /** Opens an arbitrary http(s) URL in the user's preferred browser/app. */
    fun openUrl(url: String)

    /**
     * Opens the Play Store listing for the given package. On Android, tries `market://` first and
     * falls back to `play.google.com`. On iOS/Desktop, always opens the web URL — they have no
     * native Play Store.
     */
    fun openPlayStorePage(packageName: String)

    /**
     * Presents the platform share sheet (Android `ACTION_SEND` chooser, iOS
     * `UIActivityViewController`-equivalent — currently a `mailto:` fallback, see Phase 14 TODO,
     * Desktop `mailto:`).
     */
    fun shareText(text: String, title: String = "")
}
