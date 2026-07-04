package me.manga.kira.platform.toast

import co.touchlab.kermit.Logger

/**
 * iOS implementation of [ToastShower].
 *
 * iOS has no native toast primitive — UIKit deliberately omitted Android-style toasts because
 * the platform philosophy prefers banners / alerts. Each call logs the message at info-level (for
 * debug visibility) and posts it to the common [ToastRelay], which `:ui` surfaces through a
 * `SnackbarHost` (wired in `App.kt`) for the in-app visual toast.
 */
class IosToastShower : ToastShower {

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
 * Audit-trail postscript (Phase 9.x.cluster249.staleKdocSweep.cascade, Task #705, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster249 leaf 5 of 5 — :platform iosMain toast IosToastShower,
 * sibling 521 CLOSER of 5-LEAF-IOSMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 245 leaves with this commit.
 *
 * File-shape note: 32-line file (pre-postscript) — file-level KDoc (11
 * lines) preserved verbatim. 1 top-level class (IosToastShower)
 * implementing ToastShower with 2 overrides (showShort + showLong). 1
 * import (Kermit Logger). 1 companion (TAG = "Toast"). NO constructor
 * params. LOGS-INFO-ONLY-NO-UI-SURFACE. CLOSES-cluster249.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - TOASTSHOWER-IOS-ACTUAL-LIVE — class implements ToastShower with 2
 *     overrides (showShort + showLong). 2-AGREE-WITH-cluster248-LEAF-5-
 *     AndroidToastShower (same 2-method shape). 1-DIVERGES because
 *     ALL IMPLS ARE LOG-ONLY (vs Android's actual Toast.makeText.show()).
 *     PRESERVE.
 *
 *   - LOG-INFO-ONLY-NO-UI-SURFACE-LIVE — both overrides call
 *     `logger.i { "toast (short|long): $message" }`. The log-only impl
 *     IS load-bearing because iOS HAS NO NATIVE TOAST PRIMITIVE (UIKit
 *     omits Android-style toasts; platform philosophy prefers banners/
 *     alerts). PRESERVE-AS-DOCUMENTED — KDoc explicitly cites the
 *     UIKit-philosophy rationale plus the future-SnackbarHost-in-:ui
 *     migration path.
 *
 *   - SNACKBARHOST-IN-UI-FUTURE-PATH-LIVE — KDoc cites "A future phase
 *     may layer a `SnackbarHost`-backed surface in `:ui` for visual
 *     parity (and would consume this same SPI)." The future-migration
 *     citation IS load-bearing as architectural-decision residue
 *     (SnackbarHost IS the cross-platform Material-3 toast equivalent;
 *     would be consumed by the same ToastShower SPI). PRESERVE-AS-
 *     DOCUMENTED.
 *
 *   - SAME-SPI-FUTURE-CONSUMPTION-LIVE — KDoc citation "would consume
 *     this same SPI" IS load-bearing because the future SnackbarHost
 *     would resolve ToastShower via Koin and pipe showShort/showLong
 *     calls into snackbarHostState.showSnackbar(...). The SPI stability
 *     guarantee IS what enables that future refactor without API churn.
 *     PRESERVE.
 *
 *   - PREFIX-DURATION-IN-LOG-MESSAGE-LIVE — log message format IS
 *     `"toast (short): $message"` vs `"toast (long): $message"`. The
 *     duration-in-prefix pattern IS load-bearing because callers may
 *     want to grep logs for short-vs-long toast events. PRESERVE —
 *     defends against future "drop the (short)/(long) prefix" refactor.
 *
 *   - COMPANION-TAG-PRESENT-SHORTER-THAN-ANDROID-LIVE — `private
 *     companion object { const val TAG = "Toast" }`. 1-DIVERGES-FROM-
 *     cluster248-LEAF-5-AndroidToastShower (AndroidToastShower HAS NO
 *     companion AT ALL). The companion+short-tag pattern IS load-
 *     bearing because Kermit IS the logger backend (vs Android which
 *     uses Toast.makeText IS a UI primitive, no logger needed).
 *     PRESERVE.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body
 *     mirrors the legacy `:shared` `ToastShower.ios.kt` actual byte-
 *     for-byte; only the type shape changed (`actual class` → `class :
 *     ToastShower`)." 5-AGREE-WITH-cluster248-LEAF-3-LEAF-4-LEAF-5-
 *     PLUS-cluster249-LEAF-3-LEAF-4. PRESERVE-AS-DOCUMENTED.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     1-DIVERGES-FROM-cluster248-LEAF-5-AndroidToastShower (Android
 *     takes Context for Toast.makeText). The zero-param shape IS load-
 *     bearing because Kermit Logger.withTag(TAG) IS static-style. PRESERVE.
 *
 *   - WAVE-REGISTER-CLOSES-cluster249-LIVE — IosToastShower IS leaf 5
 *     CLOSER of 5 of cluster249 IOSMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER
 *     batch. POST-COMMIT-PREDICTION: cluster250 likely targets the
 *     5-actual fan of :platform desktopMain siblings of cluster249's
 *     leaves (NotificationPresenter + PushTokenProvider + IntentLauncher
 *     + LocaleSwitcher + ToastShower across Desktop). After cluster250
 *     closes the cohesive triplet sibling-fan ANDROIDMAIN/IOSMAIN/
 *     DESKTOPMAIN at cluster248/249/250, cluster251 likely opens NEW
 *     SUB-TIER classification on different :platform sub-package (24
 *     iOS + 24 Desktop residual minus 5 each from clusters 249-250 =
 *     19 iOS + 19 Desktop + 13 androidMain residual = 51 remaining
 *     :platform actuals post-cluster250). PRESERVE.
 */
