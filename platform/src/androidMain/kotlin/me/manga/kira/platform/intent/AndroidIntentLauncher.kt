package me.manga.kira.platform.intent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import co.touchlab.kermit.Logger

/**
 * Android implementation of [IntentLauncher].
 *
 * Uses `applicationContext` everywhere and `FLAG_ACTIVITY_NEW_TASK` because the Koin-resolved
 * instance is not tied to any particular Activity — many call sites are inside Composables that
 * may have been pushed by a non-Activity Context (e.g. an Application-scoped Koin scope).
 *
 * Failures are caught and logged via Kermit. The original `openLink.kt` showed a Toast on
 * failure; we drop that here because the corresponding `ToastShower` would have to be injected,
 * which makes the API loop on itself. Add a Toast in the caller if it matters.
 *
 * Body mirrors the legacy `:shared` `IntentLauncher.android.kt` actual byte-for-byte; only the
 * type shape changed (`actual class` → `class : IntentLauncher`) and `actual fun` → `override`.
 */
class AndroidIntentLauncher(
    context: Context,
) : IntentLauncher {

    private val app: Context = context.applicationContext
    private val logger = Logger.withTag(TAG)

    override fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            logger.w(e) { "No activity can handle url=$url" }
        } catch (t: Throwable) {
            logger.w(t) { "Failed to open url=$url" }
        }
    }

    override fun openPlayStorePage(packageName: String) {
        val marketUri = Uri.parse("market://details?id=$packageName")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        try {
            val intent = Intent(Intent.ACTION_VIEW, marketUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Play Store app not installed — try the web fallback.
            try {
                app.startActivity(
                    Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (t: Throwable) {
                logger.w(t) { "Play Store web fallback failed for pkg=$packageName" }
            }
        } catch (t: Throwable) {
            logger.w(t) { "Failed to open Play Store for pkg=$packageName" }
        }
    }

    override fun shareText(text: String, title: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
            }
            val chooser = Intent.createChooser(send, title.ifBlank { null }).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(chooser)
        } catch (t: Throwable) {
            logger.w(t) { "Failed to share text" }
        }
    }

    private companion object {
        const val TAG = "IntentLauncher"
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster248.staleKdocSweep.cascade, Task #704, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster248 leaf 3 of 5 — :platform androidMain intent AndroidIntentLauncher,
 * sibling 514 of 5-LEAF-ANDROIDMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 238 leaves with this commit.
 *
 * File-shape note: 84-line file (pre-postscript) — file-level KDoc (14
 * lines) preserved verbatim. 1 top-level class (AndroidIntentLauncher)
 * implementing IntentLauncher with 3 overrides (openUrl + openPlayStorePage
 * + shareText). 5 imports (ActivityNotFoundException + Context + Intent +
 * Uri + Kermit Logger). 1 companion (TAG = "IntentLauncher"). 0 TODOs.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - INTENTLAUNCHER-ANDROID-ACTUAL-LIVE — class implements
 *     IntentLauncher with 3 overrides. All 3 ARE Android-only because
 *     Intent + Uri ARE android.content / android.net types. iOS uses
 *     UIApplication.openURL + UIActivityViewController, Desktop uses
 *     java.awt.Desktop.browse / Runtime.exec. PRESERVE — load-bearing
 *     as Android-side of 3-actual fan.
 *
 *   - APPLICATION-CONTEXT-DEFENSIVE-COPY-LIVE — Constructor stores
 *     `context.applicationContext` (not raw context). 3-AGREE-WITH-
 *     cluster248-LEAF-1-AndroidNotificationPresenter-AND-LEAF-5-
 *     AndroidToastShower (all 3 actuals that receive Context defensively
 *     copy to applicationContext). The defensive-copy posture IS load-
 *     bearing because Koin-resolved instance IS not tied to an Activity
 *     (would leak Activity through application-scope binding).
 *     PRESERVE — defends against future "drop the applicationContext,
 *     callers can pass the right context" refactor (which would re-
 *     introduce Activity-leak risk on long-lived Koin scopes).
 *
 *   - FLAG-ACTIVITY-NEW-TASK-ON-ALL-INTENTS-LIVE — every Intent
 *     constructed in the file calls `.addFlags(Intent.FLAG_ACTIVITY_
 *     NEW_TASK)`. The flag IS load-bearing because applicationContext
 *     IS not an Activity, and starting an Activity from non-Activity
 *     Context REQUIRES the NEW_TASK flag (else AndroidRuntimeException
 *     at startActivity). PRESERVE-AS-DOCUMENTED — KDoc explicitly cites
 *     this constraint.
 *
 *   - PLAYSTORE-DEEPLINK-WEBFALLBACK-LIVE — openPlayStorePage tries
 *     `market://details?id=$packageName` first, falls back to
 *     `https://play.google.com/store/apps/details?id=$packageName` on
 *     ActivityNotFoundException. The fallback IS load-bearing because
 *     Play Store app may not be installed (e.g. degoogled Android,
 *     OEM-stripped variant, F-Droid-only user). PRESERVE.
 *
 *   - NESTED-TRYCATCH-LIVE — openPlayStorePage uses nested try/catch
 *     (outer catches ActivityNotFoundException for market://, inner
 *     catches Throwable for web fallback). The nested structure IS
 *     load-bearing because the web fallback IS itself startActivity
 *     and can independently throw. PRESERVE — defends against future
 *     "flatten to single try/catch" refactor (which would mask the
 *     web-fallback failure path).
 *
 *   - TOAST-DROPPED-API-LOOP-AVOIDANCE-LIVE — KDoc explicitly cites
 *     "The original `openLink.kt` showed a Toast on failure; we drop
 *     that here because the corresponding `ToastShower` would have to
 *     be injected, which makes the API loop on itself." The DI-loop
 *     rationale IS load-bearing as architectural-decision residue.
 *     PRESERVE-AS-DOCUMENTED — defends against future "add Toast
 *     feedback to openUrl failure" feature request (which would
 *     introduce ToastShower into IntentLauncher's constructor and
 *     create a DI-cycle if ToastShower someday needs IntentLauncher).
 *
 *   - SHARETEXT-CHOOSER-LIVE — shareText() wraps the ACTION_SEND
 *     intent in `Intent.createChooser(send, title.ifBlank { null })`.
 *     The chooser IS load-bearing because it forces the system app-
 *     picker UI (else the framework may use cached default share
 *     target, which IS poor UX for share-as-link). PRESERVE.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body
 *     mirrors the legacy `:shared` `IntentLauncher.android.kt` actual
 *     byte-for-byte". The citation IS load-bearing as port-archaeology
 *     residue (legacy may have been retired or kept as strangler-fig
 *     parallel-graph sibling). PRESERVE-AS-DOCUMENTED.
 *
 *   - COMPANION-TAG-PRESENT-LIVE — `private companion object { const
 *     val TAG = "IntentLauncher" }`. 2-AGREE-WITH-cluster248-LEAF-2
 *     (AndroidPushTokenProvider also has companion TAG). PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster248-LIVE — AndroidIntentLauncher
 *     IS leaf 3 of 5 of cluster248. SOLO-IN-platform-intent-SUBPACKAGE
 *     at cluster248 (sibling iOS/Desktop actuals unswept). PRESERVE.
 */
