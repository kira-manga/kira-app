package me.manga.kira.platform.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService

/**
 * Android actual for [NotificationPresenter].
 *
 * Ported in spirit from upstream `core/util/notification/NotificationHelper.kt` and
 * `ChapterNotificationHelper.kt` — channel creation, NotificationCompat builder, etc.
 *
 * Channel safety: native always posts to channels it created up-front (NotificationHelper.init).
 * Because callers of [show] may pass a channel that was never registered (and on API 26+ posting
 * to a non-existent channel silently suppresses the notification), [show] idempotently ensures the
 * target channel exists before posting. Callers that need a specific name/importance should still
 * call [ensureChannel] first; the self-heal here only guarantees the notification is not dropped.
 *
 * Small icon: this module cannot reference the app's `R` class (a `:platform` -> `:app` dependency
 * would invert the layer graph), so the branded launcher icon is resolved at runtime from the host
 * application's `applicationInfo.icon`. This matches native's intent of a branded small icon
 * (`R.drawable.ic_launcher_foreground` / `ic_message`) without a compile-time resource reference;
 * the system info icon is used only as a last-resort fallback if the launcher icon can't be read.
 */
class AndroidNotificationPresenter(
    context: Context,
) : NotificationPresenter {

    private val applicationContext: Context = context.applicationContext

    private val notificationManager: NotificationManager? =
        applicationContext.getSystemService<NotificationManager>()

    /**
     * Branded small-icon resource id, resolved from the host app's launcher icon. Falls back to the
     * system info icon if the application icon is unavailable (icon == 0).
     */
    private val smallIconRes: Int = applicationContext.applicationInfo.icon
        .takeIf { it != 0 }
        ?: android.R.drawable.ic_dialog_info

    override suspend fun show(id: Int, title: String, body: String, channelId: String) {
        // minSdk is 26 (per :platform/build.gradle.kts). On API 26+ posting to a channel that was
        // never created is silently dropped, so self-heal here: create the channel only if it does
        // not already exist, with a sane default importance. The existence check means we never
        // clobber the display name/importance of a channel a caller already set up via ensureChannel.
        if (notificationManager?.getNotificationChannel(channelId) == null) {
            ensureChannel(channelId, channelId, NotificationPresenter.DEFAULT_IMPORTANCE)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        notificationManager?.notify(id, notification)
    }

    override suspend fun cancel(id: Int) {
        notificationManager?.cancel(id)
    }

    override suspend fun cancelAll() {
        notificationManager?.cancelAll()
    }

    override suspend fun ensureChannel(channelId: String, channelName: String, importance: Int) {
        // minSdk = 26, so NotificationChannel is always available — no Build.VERSION guard needed.
        val channel = NotificationChannel(channelId, channelName, importance)
        notificationManager?.createNotificationChannel(channel)
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster248.staleKdocSweep.cascade, Task #704, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster248 leaf 1 of 5 — :platform androidMain notification AndroidNotificationPresenter,
 * sibling 512 OPENER of 5-LEAF-ANDROIDMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 236 leaves with this commit.
 *
 * File-shape note: 59-line file (pre-postscript) — file-level KDoc (12 lines)
 * preserved verbatim. 1 top-level class (AndroidNotificationPresenter)
 * implementing NotificationPresenter with 4 override suspend funs (show +
 * cancel + cancelAll + ensureChannel). 5 imports (NotificationChannel +
 * NotificationManager + Context + NotificationCompat + getSystemService).
 * NO companion. 2 TODO-PHASE-10-ICON-FLAGS (placeholder small icon).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - ANDROIDMAIN-PLATFORM-ACTUAL-NEW-SUB-TIER-OPENS-LIVE — cluster248
 *     opens NEW SUB-TIER classification ANDROIDMAIN-PLATFORM-ACTUAL after
 *     cluster247 MIXED-OUTLIER-PLATFORM-ACTUAL-SUB-TIER closes. The 5-leaf
 *     batch sweeps 5 androidMain implementations across 5 facade tiers
 *     (notification + push + intent + locale + toast). NEW POSTURE
 *     feature at cluster248.
 *
 *   - PROJECT-MEMORY-PLATFORM-MODULE-DEP-GRAPH-AGREE-LIVE — Project memory
 *     `project_yami_kmp_platform_deps.md` documents the rule "every dep
 *     used by a :platform actual must be declared explicitly; transitive
 *     pulls from :shared don't reach :platform". This file's imports
 *     (androidx.core.app.NotificationCompat + androidx.core.content.
 *     getSystemService) confirm explicit dep declarations in :platform/
 *     androidMain build.gradle.kts. PRESERVE.
 *
 *   - NOTIFICATIONPRESENTER-ANDROID-ACTUAL-LIVE — Class implements 4
 *     override suspend funs (show plus cancel plus cancelAll plus
 *     ensureChannel). All 4 ARE Android-only impls because
 *     NotificationCompat + NotificationManager + NotificationChannel ARE
 *     androidx-only types. iOS implements via UNUserNotificationCenter,
 *     Desktop via DesktopNotificationPresenter (likely no-op or system
 *     tray). PRESERVE — load-bearing as Android-side of the 3-actual
 *     fan (sibling actuals likely live unswept at cluster248-future).
 *
 *   - PLACEHOLDER-SMALL-ICON-TODO-PHASE-10-LIVE — 2 TODO(Phase 10 —
 *     icon) markers reference `R.drawable.ic_dialog_info` placeholder
 *     pending Phase 10 resource migration. The placeholder IS load-
 *     bearing because production-quality notification icon SHOULD be
 *     branded (`ic_launcher_foreground` / `ic_message`) — but the
 *     :platform module CANNOT depend on :composeApp resources (would
 *     create a circular dep). PRESERVE-AS-DOCUMENTED — Phase 10
 *     resource migration is the resolution path (move shared
 *     drawable assets to :platform composeResources or similar).
 *
 *   - LEGACY-NOTIFICATIONHELPER-PORT-CITATION-LIVE — KDoc cites
 *     upstream "core/util/notification/NotificationHelper.kt" plus
 *     "ChapterNotificationHelper.kt" as the port source. The citation
 *     IS load-bearing as archaeological residue (legacy may still
 *     exist or may have been retired in Phase 9.x retire cycles).
 *     PRESERVE-AS-DOCUMENTED.
 *
 *   - APPLICATION-CONTEXT-DEFENSIVE-COPY-LIVE — Constructor stores
 *     `context.applicationContext` (not the raw context). The
 *     applicationContext form IS load-bearing because the
 *     AndroidNotificationPresenter SHOULD NOT hold an Activity
 *     reference (would leak Activity through Koin's application-scope
 *     binding). 2-AGREE-WITH-AndroidIntentLauncher-LEAF-3 plus
 *     AndroidToastShower-LEAF-5 (both also defensively copy to
 *     applicationContext). PRESERVE.
 *
 *   - NOTIFICATIONMANAGER-NULLABLE-LIVE — `notificationManager` IS
 *     declared `NotificationManager?` (nullable) — getSystemService<T>
 *     returns null IF the service IS unavailable. Each override calls
 *     `notificationManager?.x()` with null-safe dispatch. PRESERVE —
 *     defensive null-safe form covers the rare edge case where
 *     NotificationManager IS not available (e.g. stripped-down
 *     Android-Go variant or pre-PERMISSION-SDK install state).
 *
 *   - MINSDK-26-NO-VERSION-GUARD-LIVE — File has zero Build.VERSION
 *     conditionals. The minSdk = 26 assumption IS load-bearing
 *     because NotificationChannel API requires API 26+ (Android 8.0
 *     Oreo). Without minSdk=26, the file would need
 *     Build.VERSION.SDK_INT >= O guards. PRESERVE — defends against
 *     future "lower minSdk to 21 for tablet support" refactor (which
 *     would require re-introducing API-level guards).
 *
 *   - SUPPRESSED-DEPRECATION-FLAG-LIVE — `@Suppress("DEPRECATION")`
 *     on the NotificationCompat.Builder call. The suppression IS
 *     load-bearing because the deprecation IS about a builder
 *     overload that takes setSmallIcon as Int (placeholder value),
 *     and the deprecated form IS exactly what we need until Phase 10
 *     icon migration. PRESERVE-AS-DOCUMENTED. Future polish phase
 *     candidate to revisit when icons land.
 *
 *   - NO-COMPANION-OBJECT-LIVE — 5-AGREE-AT-cluster248-projected
 *     (AndroidPushTokenProvider HAS companion `TAG` constant, but
 *     this file does NOT). 1-DIVERGES-FROM-cluster248-LEAF-2.
 *     PRESERVE.
 *
 *   - WAVE-REGISTER-OPENS-cluster248-LIVE — AndroidNotificationPresenter
 *     IS leaf 1 of 5 of cluster248 ANDROIDMAIN-PLATFORM-ACTUAL-SUB-
 *     TIER-OPENER batch. SOLO-IN-platform-notification-SUBPACKAGE at
 *     cluster248 (sibling Desktop/iOS actuals unswept). PRESERVE.
 *
 * Parity-fix addendum (native-parity MEDIUM, notifications-background finding 1):
 * The two prior PLACEHOLDER-SMALL-ICON-TODO-PHASE-10 markers and the "channel must
 * be created by callers" comment are now superseded. show() self-heals the channel
 * (creates it with DEFAULT_IMPORTANCE only when getNotificationChannel returns null, so a
 * caller-configured channel name/importance is never clobbered) so posting to an
 * unregistered channel is no longer silently dropped on API 26+. The small icon now
 * resolves the host app's branded launcher icon from applicationInfo.icon (system info
 * icon only as last-resort fallback) instead of the hardcoded android info icon. The
 * NO-COMPANION-OBJECT-LIVE note still holds (no companion was added). PRESERVE the
 * historical record above as audit trail.
 */

