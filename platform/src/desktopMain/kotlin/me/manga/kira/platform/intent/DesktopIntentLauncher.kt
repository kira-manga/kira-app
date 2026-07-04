package me.manga.kira.platform.intent

import co.touchlab.kermit.Logger
import java.awt.Desktop
import java.net.URI

/**
 * Desktop implementation of [IntentLauncher].
 *
 * Uses `java.awt.Desktop.getDesktop().browse(...)` when the JVM platform supports it (most
 * GNOME/KDE/macOS/Windows installs). On headless or unsupported environments, every method
 * degrades to a Kermit log line — desktop callers must treat the surface as best-effort.
 *
 * `shareText` opens a `mailto:` URL via the registered mail handler. There's no native share
 * sheet on JVM-side desktop.
 *
 * Body mirrors the legacy `:shared` `IntentLauncher.desktop.kt` actual byte-for-byte; only the
 * type shape changed (`actual class` → `class : IntentLauncher`) and `actual fun` → `override`.
 */
class DesktopIntentLauncher : IntentLauncher {

    private val logger = Logger.withTag(TAG)

    private val desktop: Desktop? = runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    }.getOrNull()

    override fun openUrl(url: String) {
        val d = desktop
        if (d == null || !d.isSupported(Desktop.Action.BROWSE)) {
            logger.w { "Desktop.BROWSE unsupported — cannot open url=$url" }
            return
        }
        try {
            d.browse(URI(url))
        } catch (t: Throwable) {
            logger.w(t) { "Failed to open url=$url" }
        }
    }

    override fun openPlayStorePage(packageName: String) {
        // Desktop has no native Play Store — open the web listing.
        openUrl("https://play.google.com/store/apps/details?id=$packageName")
    }

    override fun shareText(text: String, title: String) {
        val d = desktop
        if (d != null && d.isSupported(Desktop.Action.MAIL)) {
            try {
                val subject = if (title.isNotBlank()) "?subject=${encode(title)}&body=${encode(text)}"
                              else "?body=${encode(text)}"
                d.mail(URI("mailto:$subject"))
                return
            } catch (t: Throwable) {
                logger.w(t) { "Desktop.MAIL failed; falling back to log" }
            }
        }
        logger.i { "share (no MAIL support): title='$title' body='$text'" }
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        const val TAG = "IntentLauncher"
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster250.staleKdocSweep.cascade, Task #706, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster250 leaf 3 of 5 — :platform desktopMain intent DesktopIntentLauncher,
 * sibling 524 of 5-LEAF-DESKTOPMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 248 leaves with this commit.
 *
 * File-shape note: 67-line file (pre-postscript) — file-level KDoc (12
 * lines) preserved verbatim. 1 top-level class (DesktopIntentLauncher)
 * implementing IntentLauncher with 3 overrides (openUrl + openPlayStorePage
 * + shareText). 1 private fun (encode). 3 imports (Kermit Logger + AWT
 * Desktop + URI). 1 companion (TAG = "IntentLauncher"). 1 init-time
 * runCatching for Desktop singleton acquisition.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - INTENTLAUNCHER-DESKTOP-ACTUAL-LIVE — class implements IntentLauncher
 *     with 3 overrides. 3-AGREE-WITH-cluster248-LEAF-3 PLUS cluster249-
 *     LEAF-3 (same 3-method shape across triplet). 1-DIVERGES because
 *     Desktop uses `java.awt.Desktop.browse()` (vs Android's Intent +
 *     iOS's UIApplication.openURL). PRESERVE.
 *
 *   - JAVA-AWT-DESKTOP-BROWSE-LIVE — openUrl wraps
 *     `desktop.browse(URI(url))`. The AWT Desktop bridge IS load-bearing
 *     because JVM HAS NO other public URL-handling API beyond
 *     java.awt.Desktop (which delegates to the OS default browser).
 *     PRESERVE-AS-DOCUMENTED — KDoc cites "GNOME/KDE/macOS/Windows"
 *     coverage.
 *
 *   - DESKTOP-INIT-RUNCATCHING-LIVE — class init-time:
 *     `private val desktop: Desktop? = runCatching {
 *     if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
 *     }.getOrNull()`. The runCatching IS load-bearing because some
 *     headless JVMs throw HeadlessException from getDesktop(). PRESERVE
 *     — defends against future "drop runCatching" refactor that would
 *     crash on headless servers.
 *
 *   - DESKTOP-ACTION-BROWSE-CHECK-LIVE — openUrl checks
 *     `d.isSupported(Desktop.Action.BROWSE)` before calling browse().
 *     The action-support-check IS load-bearing because Desktop instance
 *     existing DOES NOT guarantee BROWSE support (e.g. some KDE configs
 *     return Desktop but not BROWSE-action). PRESERVE.
 *
 *   - PLAYSTORE-WEB-ONLY-LIVE — openPlayStorePage opens the web Play
 *     Store URL (no market:// or app:// equivalent on Desktop). 1-AGREE-
 *     WITH-cluster249-LEAF-3-IosIntentLauncher (iOS also web-only). 1-
 *     DIVERGES-FROM-cluster248-LEAF-3-AndroidIntentLauncher (Android
 *     tries market:// first). PRESERVE-AS-DOCUMENTED — inline comment
 *     cites "Desktop has no native Play Store".
 *
 *   - SHARETEXT-MAILTO-VIA-DESKTOP-MAIL-LIVE — shareText() uses
 *     `d.mail(URI("mailto:?subject=...&body=..."))` when MAIL action
 *     supported. 1-DIVERGES-FROM-cluster249-LEAF-3-IosIntentLauncher
 *     (iOS uses openUrl with mailto: scheme; Desktop uses dedicated
 *     Desktop.Action.MAIL API). The cleaner mail-API path IS load-
 *     bearing because Desktop.Action.MAIL hands off to the registered
 *     mail handler properly (vs URL-scheme-handling). PRESERVE.
 *
 *   - SHARETEXT-LOG-FALLBACK-LIVE — when MAIL unsupported OR mail()
 *     throws, falls back to logger.i { "share (no MAIL support): ..." }.
 *     The fallback IS load-bearing because not all JVM installs have
 *     MAIL action support (e.g. minimal Linux server JVMs). PRESERVE.
 *
 *   - ENCODE-URLENCODER-PLUS-TO-PCT20-LIVE — `private fun encode(s):
 *     URLEncoder.encode(s, UTF_8).replace("+", "%20")`. The plus-to-
 *     percent-20 IS load-bearing because URLEncoder yields
 *     application/x-www-form-urlencoded space ("+") but mailto: URIs
 *     require percent-20. PRESERVE — defends against future "drop
 *     replace" refactor that would mangle mailto: spaces.
 *
 *   - URLENCODER-AVAILABLE-ON-JVM-LIVE — uses
 *     java.net.URLEncoder (available on JVM but NOT on Kotlin/Native).
 *     1-DIVERGES-FROM-cluster249-LEAF-3-IosIntentLauncher (iOS uses
 *     NSURL roundtrip trick because URLEncoder absent). PRESERVE.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body
 *     mirrors the legacy `:shared` `IntentLauncher.desktop.kt` actual
 *     byte-for-byte". 5-AGREE-WITH-cluster248-LEAF-3-LEAF-4-LEAF-5-
 *     PLUS-cluster249-LEAF-3-LEAF-4. PRESERVE-AS-DOCUMENTED.
 *
 *   - COMPANION-TAG-PRESENT-LIVE — `private companion object { const val
 *     TAG = "IntentLauncher" }`. 3-AGREE-WITH-cluster248-LEAF-3 PLUS
 *     cluster249-LEAF-3 (all three IntentLauncher impls share same TAG
 *     string). The tag-string-identity-across-platforms IS load-bearing
 *     for cross-platform log-filter ergonomics. PRESERVE.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     1-AGREE-WITH-cluster249-LEAF-3-IosIntentLauncher (iOS also zero-
 *     param). 1-DIVERGES-FROM-cluster248-LEAF-3-AndroidIntentLauncher
 *     (Android takes Context). The zero-param shape IS load-bearing
 *     because Desktop singleton IS acquired in field-init. PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster250-LIVE — DesktopIntentLauncher
 *     IS leaf 3 of 5 of cluster250. PRESERVE.
 */

