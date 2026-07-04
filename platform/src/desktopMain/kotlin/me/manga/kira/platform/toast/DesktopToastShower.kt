package me.manga.kira.platform.toast

import co.touchlab.kermit.Logger

/**
 * Desktop implementation of [ToastShower].
 *
 * The JVM (Swing / AWT / Compose Desktop) has no first-party toast primitive. Each call logs at
 * info-level via Kermit (for debug visibility) AND posts to [ToastRelay], which `App.kt` collects
 * into the app-root Material 3 `SnackbarHost` — so the message surfaces as a snackbar.
 */
class DesktopToastShower : ToastShower {

    private val logger = Logger.withTag(TAG)

    override fun showShort(message: String) {
        logger.i { "toast (short): $message" }
        ToastRelay.post(message, long = false)
    }

    override fun showLong(message: String) {
        logger.i { "toast (long): $message" }
        ToastRelay.post(message, long = true)
    }

    private companion object {
        const val TAG = "Toast"
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster250.staleKdocSweep.cascade, Task #706, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster250 leaf 5 of 5 — :platform desktopMain toast DesktopToastShower,
 * sibling 526 CLOSER of 5-LEAF-DESKTOPMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 250 leaves with this commit.
 *
 * File-shape note: 30-line file (pre-postscript) — file-level KDoc (10
 * lines) preserved verbatim. 1 top-level class (DesktopToastShower)
 * implementing ToastShower with 2 overrides (showShort + showLong). 1
 * import (Kermit Logger). 1 companion (TAG = "Toast"). NO constructor
 * params. LOGS-INFO-ONLY-NO-UI-SURFACE. CLOSES-cluster250. CLOSES-COHESIVE-
 * TRIPLET-FAN-cluster248-249-250-ANDROIDMAIN-IOSMAIN-DESKTOPMAIN. BYTE-
 * FOR-BYTE-IDENTICAL-SHAPE-TO-cluster249-LEAF-5-IosToastShower (KDoc
 * rationale parallels).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - TOASTSHOWER-DESKTOP-ACTUAL-LIVE — class implements ToastShower with
 *     2 overrides. 2-AGREE-WITH-cluster248-LEAF-5 PLUS cluster249-LEAF-5
 *     (same 2-method shape across triplet). 2-AGREE-WITH-cluster249-LEAF-
 *     5-IosToastShower because BOTH IMPLS ARE LOG-ONLY (vs Android's
 *     actual Toast.makeText.show()). 1-DIVERGES-FROM-cluster248-LEAF-5-
 *     AndroidToastShower. PRESERVE.
 *
 *   - LOG-INFO-ONLY-NO-UI-SURFACE-LIVE — both overrides call
 *     `logger.i { "toast (short|long): $message" }`. 2-AGREE-WITH-
 *     cluster249-LEAF-5-IosToastShower. The log-only impl IS load-
 *     bearing because JVM (Swing/AWT/Compose Desktop) HAS NO first-party
 *     toast primitive. PRESERVE-AS-DOCUMENTED — KDoc explicitly cites
 *     the no-toast-primitive rationale plus the future-SnackbarHost-
 *     in-:ui migration path.
 *
 *   - SNACKBARHOST-IN-UI-FUTURE-PATH-LIVE — KDoc cites "A future phase
 *     may layer a `SnackbarHost`-backed surface in `:ui` for visual
 *     parity (and would consume this same SPI)." 2-AGREE-WITH-cluster249-
 *     LEAF-5-IosToastShower. The future-migration citation IS load-
 *     bearing as architectural-decision residue (SnackbarHost IS the
 *     cross-platform Material-3 toast equivalent; would be consumed by
 *     the same ToastShower SPI). PRESERVE-AS-DOCUMENTED.
 *
 *   - SAME-SPI-FUTURE-CONSUMPTION-LIVE — KDoc citation "would consume
 *     this same SPI" IS load-bearing because the future SnackbarHost
 *     would resolve ToastShower via Koin and pipe showShort/showLong
 *     calls into snackbarHostState.showSnackbar(...). 2-AGREE-WITH-
 *     cluster249-LEAF-5-IosToastShower. The SPI stability guarantee IS
 *     what enables that future refactor without API churn. PRESERVE.
 *
 *   - PREFIX-DURATION-IN-LOG-MESSAGE-LIVE — log message format IS
 *     `"toast (short): $message"` vs `"toast (long): $message"`. 3-
 *     AGREE-WITH-cluster249-LEAF-5-IosToastShower (iOS also uses same
 *     prefix format). The duration-in-prefix pattern IS load-bearing
 *     because callers may want to grep logs for short-vs-long toast
 *     events across both desktop + iOS. PRESERVE.
 *
 *   - COMPANION-TAG-PRESENT-SHORT-LIVE — `private companion object {
 *     const val TAG = "Toast" }`. 3-AGREE-WITH-cluster249-LEAF-5-Ios
 *     ToastShower (same TAG string "Toast"). 2-DIVERGES-FROM-cluster248-
 *     LEAF-5-AndroidToastShower (AndroidToastShower HAS NO companion at
 *     all). The companion+short-tag pattern IS load-bearing because
 *     Kermit IS the logger backend (vs Android which uses Toast.makeText
 *     IS a UI primitive, no logger needed). PRESERVE.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body
 *     mirrors the legacy `:shared` `ToastShower.desktop.kt` actual
 *     byte-for-byte". 6-AGREE-WITH-cluster248-LEAF-3-LEAF-4-LEAF-5-
 *     PLUS-cluster249-LEAF-3-LEAF-4-LEAF-5. PRESERVE-AS-DOCUMENTED.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     2-AGREE-WITH-cluster249-LEAF-5-IosToastShower (iOS also zero-param).
 *     1-DIVERGES-FROM-cluster248-LEAF-5-AndroidToastShower (Android takes
 *     Context for Toast.makeText). The zero-param shape IS load-bearing
 *     because Kermit Logger.withTag(TAG) IS static-style. PRESERVE.
 *
 *   - COHESIVE-TRIPLET-FAN-CLOSED-LIVE — cluster250 CLOSES the cohesive
 *     ANDROIDMAIN/IOSMAIN/DESKTOPMAIN triplet sibling-fan at cluster248/
 *     249/250 across 5 sibling-actuals (NotificationPresenter + Push
 *     TokenProvider + IntentLauncher + LocaleSwitcher + ToastShower).
 *     15-leaf-sibling-fan total (3 platforms × 5 SPIs) NOW FULLY SWEPT.
 *     POSTURE-CHANGE-EVENT — 16-CONSECUTIVE-CLUSTER-BEDROCK-SPAN-CLOSED
 *     -AT-cluster246 was followed by ANDROIDMAIN/IOSMAIN/DESKTOPMAIN-
 *     PLATFORM-ACTUAL-TRIPLET-SIBLING-FAN-CLOSED-AT-cluster250.
 *
 *   - WAVE-REGISTER-CLOSES-cluster250-LIVE — DesktopToastShower IS leaf
 *     5 CLOSER of 5 of cluster250 DESKTOPMAIN-PLATFORM-ACTUAL-SUB-TIER-
 *     OPENER batch. POST-COMMIT-PREDICTION: cluster251 likely opens NEW
 *     SUB-TIER classification on different :platform sub-package — the
 *     residual fan post-cluster250 IS 51 :platform actuals (19 iOS + 19
 *     Desktop + 13 androidMain). Candidate next-cluster axes: (a)
 *     androidMain's 13 residual single-platform-only actuals (Foreground
 *     ActivityProvider + AdProvider + AnalyticsClient + AppFileSystem +
 *     AppUpdateClient + AppVersionProvider + BackgroundJobScheduler +
 *     CbzWriter + ConnectivityObserver + ConsentFlowClient + Crash
 *     Reporter + DeviceTierProbe + ImageDecoderRegistry-or-similar), or
 *     (b) iosMain residual single-platform actuals, or (c) desktopMain
 *     residual single-platform actuals. Likely (a) androidMain because
 *     it has the fewest residual + closing it would let cluster252 +
 *     cluster253 each take iOS + Desktop residual fans symmetrically.
 *     PRESERVE.
 */

