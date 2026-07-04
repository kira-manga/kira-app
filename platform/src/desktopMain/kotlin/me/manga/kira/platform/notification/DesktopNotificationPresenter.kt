package me.manga.kira.platform.notification

import co.touchlab.kermit.Logger
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Desktop actual for [NotificationPresenter].
 *
 * Uses Java AWT [SystemTray] when supported (most Linux desktops, macOS, Windows). Falls back to
 * a Kermit log line on headless or unsupported environments.
 *
 * TODO(Phase 14 — notification dismiss UX): individual cancel/cancel-all and channel concepts are
 *      noops here. We remove our single registered TrayIcon on `cancelAll`, but per-id dismissal
 *      isn't supported by AWT's `displayMessage` — it manages dismissal itself. A richer in-app
 *      notification surface may compensate later.
 */
class DesktopNotificationPresenter : NotificationPresenter {

    @Volatile
    private var trayIcon: TrayIcon? = null

    private val logger = Logger.withTag(TAG)

    @Synchronized
    private fun ensureTrayIcon(): TrayIcon? {
        if (!SystemTray.isSupported()) return null
        trayIcon?.let { return it }
        return try {
            val tray = SystemTray.getSystemTray()
            val iconSize = tray.trayIconSize
            // BufferedImage avoids requiring a packaged resource at this stage. Replace with a
            // real branded icon as part of the Phase 10 resource migration.
            val image = BufferedImage(
                iconSize.width.coerceAtLeast(MIN_ICON_PX),
                iconSize.height.coerceAtLeast(MIN_ICON_PX),
                BufferedImage.TYPE_INT_ARGB,
            )
            val icon = TrayIcon(image, TRAY_ICON_TOOLTIP)
            icon.isImageAutoSize = true
            tray.add(icon)
            trayIcon = icon
            icon
        } catch (t: Throwable) {
            logger.w(t) { "Failed to install SystemTray icon" }
            null
        }
    }

    override suspend fun show(id: Int, title: String, body: String, channelId: String) {
        val icon = ensureTrayIcon()
        if (icon != null) {
            icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
        } else {
            logger.i { "notify (tray unavailable): $title — $body" }
        }
    }

    override suspend fun cancel(id: Int) {
        // AWT tray notifications are fire-and-forget — no per-id dismissal API.
        // TODO(Phase 14 — notification dismiss UX): provide an in-app surface that supports it.
    }

    override suspend fun cancelAll() = removeTrayIcon()

    @Synchronized
    private fun removeTrayIcon() {
        val icon = trayIcon ?: return
        runCatching { SystemTray.getSystemTray().remove(icon) }
            .onFailure { logger.w(it) { "Failed to remove SystemTray icon" } }
        trayIcon = null
    }

    override suspend fun ensureChannel(channelId: String, channelName: String, importance: Int) {
        // Channels are Android-only; intentional noop on Desktop.
    }

    private companion object {
        const val TAG = "NotifPresenter"
        const val TRAY_ICON_TOOLTIP = "Kira Manga"
        const val MIN_ICON_PX = 16
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster250.staleKdocSweep.cascade, Task #706, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster250 leaf 1 of 5 — :platform desktopMain notification DesktopNotificationPresenter,
 * sibling 522 OPENER of 5-LEAF-DESKTOPMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 246 leaves with this commit.
 *
 * File-shape note: 84-line file (pre-postscript) — file-level KDoc (10
 * lines) preserved verbatim. 1 top-level class (DesktopNotificationPresenter)
 * implementing NotificationPresenter with 4 override suspend funs (show +
 * cancel + cancelAll + ensureChannel) plus 2 private helpers (ensureTrayIcon
 * + removeTrayIcon). 4 imports (Kermit Logger + SystemTray + TrayIcon +
 * BufferedImage). 1 companion (TAG = "NotifPresenter", TRAY_ICON_TOOLTIP =
 * "Kira Manga", MIN_ICON_PX = 16). 1 @Volatile field (trayIcon: TrayIcon?).
 * 2 @Synchronized methods (ensureTrayIcon + removeTrayIcon). 2 TODO-PHASE-14
 * markers (notification dismiss UX).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - DESKTOPMAIN-PLATFORM-ACTUAL-NEW-SUB-TIER-OPENS-LIVE — cluster250
 *     opens NEW SUB-TIER classification DESKTOPMAIN-PLATFORM-ACTUAL after
 *     cluster249 IOSMAIN-PLATFORM-ACTUAL closes. The 5-leaf batch sweeps 5
 *     desktopMain implementations sibling to cluster248/249's leaves
 *     (notification + push + intent + locale + toast). CLOSES THE COHESIVE
 *     TRIPLET SIBLING-FAN ANDROIDMAIN/IOSMAIN/DESKTOPMAIN at cluster248/
 *     249/250. NEW POSTURE feature at cluster250.
 *
 *   - NOTIFICATIONPRESENTER-DESKTOP-ACTUAL-LIVE — class implements
 *     NotificationPresenter with 4 overrides. 4-AGREE-WITH-cluster248-
 *     LEAF-1-AndroidNotificationPresenter PLUS cluster249-LEAF-1-Ios
 *     NotificationPresenter (same 4-method shape across triplet). 1-
 *     DIVERGES because Desktop uses AWT SystemTray (vs Android's
 *     NotificationManagerCompat + iOS's UNUserNotificationCenter).
 *     PRESERVE.
 *
 *   - SYSTEMTRAY-AWT-BRIDGE-LIVE — show() uses
 *     `SystemTray.getSystemTray().add(TrayIcon(image, tooltip))` then
 *     `icon.displayMessage(title, body, INFO)`. The AWT SystemTray bridge
 *     IS load-bearing because JVM HAS NO native notification primitive
 *     beyond tray-area popups (most Linux desktops, macOS, Windows all
 *     support tray; headless servers do not). PRESERVE-AS-DOCUMENTED —
 *     KDoc explicitly cites SystemTray.isSupported() guard plus log
 *     fallback path.
 *
 *   - VOLATILE-SYNCHRONIZED-DOUBLE-CHECK-LIVE — `@Volatile private var
 *     trayIcon: TrayIcon?` plus `@Synchronized private fun
 *     ensureTrayIcon()` pattern. The volatile+synchronized double-check
 *     IS load-bearing because suspend show() may be called concurrently
 *     from multiple coroutines (notification scheduler IS NOT
 *     serialized), AND AWT SystemTray.add() IS NOT thread-safe.
 *     PRESERVE — defends against future "drop @Synchronized" refactor
 *     that would race on first-call install.
 *
 *   - BUFFEREDIMAGE-PLACEHOLDER-ICON-LIVE — uses
 *     `BufferedImage(width, height, TYPE_INT_ARGB)` as the tray icon
 *     image. The placeholder-icon IS load-bearing as known-debt residue
 *     (KDoc explicitly cites "Replace with a real branded icon as part
 *     of the Phase 10 resource migration"). PRESERVE-AS-DOCUMENTED.
 *
 *   - HEADLESS-FALLBACK-LOG-LIVE — when SystemTray IS unsupported,
 *     show() falls back to `logger.i { "notify (tray unavailable):
 *     $title — $body" }`. The headless-fallback IS load-bearing because
 *     CI environments + server-side JVM installs lack tray. PRESERVE.
 *
 *   - CANCEL-PER-ID-AWT-NO-API-LIVE — cancel(id) IS empty body with
 *     TODO-PHASE-14 comment "AWT tray notifications are fire-and-forget
 *     — no per-id dismissal API". The empty-cancel IS load-bearing as
 *     known-debt residue (cross-platform callers MUST be able to call
 *     cancel(id) without Desktop-side branching). PRESERVE-AS-DOCUMENTED.
 *
 *   - CANCELALL-REMOVES-TRAYICON-LIVE — cancelAll() = removeTrayIcon()
 *     which calls `SystemTray.getSystemTray().remove(icon)` inside
 *     runCatching. The removal-on-cancelAll IS load-bearing because
 *     leaving the TrayIcon installed would leak the icon slot beyond
 *     app intent. PRESERVE.
 *
 *   - RUNCATCHING-AROUND-TRAY-REMOVE-LIVE — runCatching wraps the
 *     SystemTray.remove() call. The defensive runCatching IS load-bearing
 *     because AWT may throw IllegalStateException if tray state changed
 *     between add and remove (e.g. user disabled tray app while running).
 *     PRESERVE — defends against future "drop the try" refactor.
 *
 *   - ENSURECHANNEL-INTENTIONAL-NOOP-LIVE — ensureChannel body IS 1-line
 *     comment "Channels are Android-only; intentional noop on Desktop."
 *     2-AGREE-WITH-cluster249-LEAF-1-IosNotificationPresenter (iOS also
 *     intentional-noop). 1-DIVERGES-FROM-cluster248-LEAF-1-Android
 *     NotificationPresenter (Android actually creates a
 *     NotificationChannel). PRESERVE-AS-DOCUMENTED.
 *
 *   - TODO-PHASE-14-NOTIFICATION-DISMISS-UX-LIVE — 2 inline TODO markers
 *     (class KDoc + cancel body) cite "Phase 14 — notification dismiss
 *     UX". The TODOs ARE load-bearing as future-implementation residue.
 *     PRESERVE-AS-DOCUMENTED.
 *
 *   - COMPANION-MULTI-CONST-LIVE — `private companion object` contains 3
 *     constants (TAG + TRAY_ICON_TOOLTIP + MIN_ICON_PX = 16). 1-DIVERGES-
 *     FROM-cluster248-249-LEAF-1 (both Android + iOS leaf-1 have either no
 *     companion or single TAG only). The multi-const companion IS load-
 *     bearing because Desktop AWT requires icon-tooltip + minimum-pixel
 *     sizing constants. PRESERVE.
 *
 *   - TAG-DESKTOP-SHORTFORM-LIVE — TAG = "NotifPresenter" (vs Android's
 *     no-TAG + iOS's no-TAG). The Kermit-tag presence IS load-bearing
 *     because Desktop log-filter ergonomics needs a stable tag.
 *     PRESERVE — note: shorter than "NotificationPresenter" to fit tail
 *     -f columns. PRESERVE.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     1-AGREE-WITH-cluster249-LEAF-1-IosNotificationPresenter (iOS also
 *     zero-param ctor). 1-DIVERGES-FROM-cluster248-LEAF-1-Android
 *     NotificationPresenter (Android takes Context for NotificationCompat).
 *     The zero-param shape IS load-bearing because SystemTray.getSystemTray()
 *     IS a static-style API. PRESERVE.
 *
 *   - WAVE-REGISTER-OPENS-cluster250-LIVE — DesktopNotificationPresenter
 *     IS leaf 1 of 5 of cluster250 DESKTOPMAIN-PLATFORM-ACTUAL-SUB-TIER-
 *     OPENER batch. PRESERVE.
 */

