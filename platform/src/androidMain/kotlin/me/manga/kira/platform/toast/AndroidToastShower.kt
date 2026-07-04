package me.manga.kira.platform.toast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Android implementation of [ToastShower].
 *
 * Posts to the main looper if called from a background thread. Uses `applicationContext` to
 * avoid leaking an Activity reference when `context` is one. Body mirrors the legacy
 * `:shared` `ToastShower.android.kt` actual byte-for-byte; only the type shape changed
 * (`actual class` → `class : ToastShower`).
 *
 * **Why `applicationContext`.** Toasts can outlive the Activity that scheduled them
 * (especially the LENGTH_LONG variant). Holding an Activity context here would prevent that
 * Activity from being garbage-collected once the user navigates away. The application context
 * lives for the lifetime of the process, so no leak.
 */
class AndroidToastShower(
    context: Context,
) : ToastShower {

    private val app: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun showShort(message: String) {
        showOnMain(message, Toast.LENGTH_SHORT)
    }

    override fun showLong(message: String) {
        showOnMain(message, Toast.LENGTH_LONG)
    }

    private fun showOnMain(message: String, duration: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(app, message, duration).show()
        } else {
            mainHandler.post { Toast.makeText(app, message, duration).show() }
        }
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster248.staleKdocSweep.cascade, Task #704, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster248 leaf 5 of 5 — :platform androidMain toast AndroidToastShower,
 * sibling 516 CLOSER of 5-LEAF-ANDROIDMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 240 leaves with this commit.
 *
 * File-shape note: 43-line file (pre-postscript) — file-level KDoc (13
 * lines) preserved verbatim. 1 top-level class (AndroidToastShower)
 * implementing ToastShower with 2 overrides (showShort + showLong) plus 1
 * private helper (showOnMain). 4 imports (Context + Handler + Looper +
 * Toast). NO companion. CLOSES-cluster248.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - TOASTSHOWER-ANDROID-ACTUAL-LIVE — class implements ToastShower
 *     with 2 overrides. Android-only impl because Toast IS android.
 *     widget-only. iOS uses UIView ad-hoc HUD or library equivalent,
 *     Desktop uses JOptionPane / system-tray balloon. PRESERVE — load-
 *     bearing as Android-side of 3-actual fan.
 *
 *   - APPLICATION-CONTEXT-DEFENSIVE-COPY-WITH-RATIONALE-LIVE — Constructor
 *     stores `context.applicationContext`. The applicationContext form
 *     IS load-bearing with EXPLICIT KDoc rationale: "Toasts can outlive
 *     the Activity that scheduled them (especially the LENGTH_LONG
 *     variant). Holding an Activity context here would prevent that
 *     Activity from being garbage-collected once the user navigates
 *     away." The leak-prevention rationale IS load-bearing as architecture
 *     decision residue. 3-AGREE-WITH-cluster248-LEAF-1-AND-LEAF-3
 *     (Notification/Intent both defensively copy). PRESERVE — defends
 *     against future "drop the applicationContext copy" refactor.
 *
 *   - MAIN-LOOPER-DISPATCH-LIVE — showOnMain branches on
 *     `Looper.myLooper() == Looper.getMainLooper()`. If on main, calls
 *     Toast.makeText(...).show() directly; else posts to Handler bound
 *     to Looper.getMainLooper(). The main-thread enforcement IS load-
 *     bearing because Toast.show() throws RuntimeException("Can't toast
 *     on a thread that has not called Looper.prepare()") when called
 *     from non-Looper background thread. PRESERVE-AS-DOCUMENTED — KDoc
 *     explicitly cites "Posts to the main looper if called from a
 *     background thread."
 *
 *   - HANDLER-INITIALIZED-IN-CTOR-LIVE — `Handler(Looper.getMainLooper())`
 *     stored as `mainHandler` field at construction. The eager init IS
 *     load-bearing because re-creating Handler per call would waste
 *     allocations (Handler IS lightweight but not free). PRESERVE.
 *
 *   - PRIVATE-FUN-HELPER-NO-COMPANION-LIVE — `showOnMain(message,
 *     duration)` IS private fun (instance-level, not companion). The
 *     instance-level shape IS load-bearing because the helper closes
 *     over `app` (applicationContext) and `mainHandler` (instance
 *     state). 2-DIVERGES-FROM-cluster248-LEAF-2-AND-LEAF-3 (Push/Intent
 *     both have companion-level constants). PRESERVE.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body
 *     mirrors the legacy `:shared` `ToastShower.android.kt` actual
 *     byte-for-byte; only the type shape changed (`actual class` →
 *     `class : ToastShower`)." 3-AGREE-WITH-cluster248-LEAF-3-AND-
 *     LEAF-4 (Intent + Locale also cite byte-for-byte port). The
 *     port-archaeology citation IS load-bearing. PRESERVE-AS-DOCUMENTED.
 *
 *   - NO-LOGGING-NO-KERMIT-LIVE — file has zero Logger references.
 *     1-DIVERGES-FROM-cluster248-LEAF-2-AND-LEAF-3 (Push + Intent both
 *     use Kermit). The no-logging posture IS load-bearing because Toast
 *     IS a fire-and-forget UI surface — failure modes ARE bounded
 *     (RuntimeException on non-Looper thread, handled by branching
 *     check). PRESERVE.
 *
 *   - NO-COMPANION-OBJECT-LIVE — 3-AGREE-WITH-cluster248-LEAF-1-AND-
 *     LEAF-4. PRESERVE.
 *
 *   - WAVE-REGISTER-CLOSES-cluster248-LIVE — AndroidToastShower IS
 *     leaf 5 CLOSER of 5 of cluster248 ANDROIDMAIN-PLATFORM-ACTUAL-
 *     SUB-TIER-OPENER batch. SOLO-IN-platform-toast-SUBPACKAGE at
 *     cluster248. POST-COMMIT-PREDICTION: cluster249 likely targets
 *     the 5-actual fan of :platform iosMain or desktopMain siblings
 *     of cluster248's Android leaves (NotificationPresenter +
 *     PushTokenProvider + IntentLauncher + LocaleSwitcher +
 *     ToastShower across iOS or Desktop). The sibling-actuals fan
 *     would be a natural cluster249 cohesive batch — OR could split
 *     to one platform per cluster (cluster249 iOS-only, cluster250
 *     Desktop-only). 76 - 5 - 5 - 5 = 61 remaining :platform actuals
 *     post-cluster248 (24 Desktop + 24 iOS + 13 androidMain residual).
 *     PRESERVE.
 */
