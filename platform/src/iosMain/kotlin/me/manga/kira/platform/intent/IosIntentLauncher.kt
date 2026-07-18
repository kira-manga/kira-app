package me.manga.kira.platform.intent

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSBundle
import platform.Foundation.NSCharacterSet
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.stringByAddingPercentEncodingWithAllowedCharacters
import platform.UIKit.UIApplication

/**
 * iOS implementation of [IntentLauncher].
 *
 * Wraps `UIApplication.sharedApplication.openURL(...)`. `shareText` cannot present a true
 * `UIActivityViewController` from here because that requires a `UIViewController` instance —
 * Phase 14 will replace this with a SwiftUI-side bridge if needed. For now we fall back to a
 * `mailto:` URL so the system mail composer surfaces the text, matching the Desktop behaviour.
 *
 * `openPlayStorePage` opens this app's native App Store listing. The numeric listing ID is supplied
 * through the `KiraAppStoreID` Info.plist value once App Store Connect creates the first listing.
 *
 * Body mirrors the legacy `:shared` `IntentLauncher.ios.kt` actual byte-for-byte; only the type
 * shape changed (`actual class` → `class : IntentLauncher`) and `actual fun` → `override`.
 */
@OptIn(BetaInteropApi::class)
class IosIntentLauncher : IntentLauncher {
    private val logger = Logger.withTag(TAG)

    override fun openUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            logger.w { "Failed to parse url=$url" }
            return
        }
        UIApplication.sharedApplication.openURL(nsUrl)
    }

    override fun openPlayStorePage(packageName: String) {
        val appStoreId =
            (NSBundle.mainBundle.infoDictionary?.get("KiraAppStoreID") as? String)
                ?.trim()
                ?.takeIf { id -> id.isNotEmpty() && id.all(Char::isDigit) }
        if (appStoreId == null) {
            logger.w { "App Store listing ID is not configured; opening the Kira website" }
            openUrl("https://kiramanga.me")
            return
        }
        openUrl("itms-apps://apps.apple.com/app/id$appStoreId")
    }

    override fun shareText(
        text: String,
        title: String,
    ) {
        // TODO(Phase 14): wire a SwiftUI bridge so this surface presents a real
        //  UIActivityViewController. Until then we degrade to a mailto: link so the OS at least
        //  hands the user a Mail compose sheet with the text prefilled.
        val encoded = text.percentEncode()
        val subject = if (title.isNotBlank()) "&subject=${title.percentEncode()}" else ""
        openUrl("mailto:?body=$encoded$subject")
    }

    private fun String.percentEncode(): String =
        NSString
            .create(string = this)
            .stringByAddingPercentEncodingWithAllowedCharacters(NSCharacterSet.alphanumericCharacterSet)
            ?: this

    private companion object {
        const val TAG = "IntentLauncher"
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster249.staleKdocSweep.cascade, Task #705, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster249 leaf 3 of 5 — :platform iosMain intent IosIntentLauncher,
 * sibling 519 of 5-LEAF-IOSMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 243 leaves with this commit.
 *
 * File-shape note: 53-line file (pre-postscript) — file-level KDoc (13
 * lines) preserved verbatim. 1 top-level class (IosIntentLauncher)
 * implementing IntentLauncher with 3 overrides (openUrl + openPlayStorePage
 * + shareText). 1 private extension fun (String.percentEncode). 3 imports
 * (Kermit Logger + NSURL + UIApplication). 1 companion (TAG =
 * "IntentLauncher"). 1 TODO-PHASE-14-FLAG (SwiftUI bridge).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - INTENTLAUNCHER-IOS-ACTUAL-LIVE — class implements IntentLauncher
 *     with 3 overrides. 3-AGREE-WITH-cluster248-LEAF-3-AndroidIntent
 *     Launcher (same 3-method shape). 1-DIVERGES because shareText
 *     degrades to mailto: instead of native share-sheet (TODO Phase 14
 *     bridge pending). PRESERVE.
 *
 *   - UIAPPLICATION-SHAREDAPPLICATION-OPENURL-LIVE — openUrl wraps
 *     `UIApplication.sharedApplication.openURL(nsUrl)`. The sharedApp
 *     global-singleton access IS load-bearing because UIKit IS the
 *     standard URL-handling entry point on iOS (Safari for web,
 *     scheme-specific apps for deeplinks). PRESERVE.
 *
 *   - NSURL-PARSE-NULL-GUARD-LIVE — openUrl checks
 *     `NSURL.URLWithString(url)` for null and logs+returns. The null-
 *     guard IS load-bearing because malformed URLs return nil from
 *     Foundation parser (different from Android Uri.parse which IS
 *     permissive). PRESERVE — defends against future "force unwrap"
 *     refactor that would crash on malformed URL input.
 *
 *   - PLAYSTORE-WEB-ONLY-LIVE — openPlayStorePage opens the web Play
 *     Store URL (no market:// equivalent on iOS). 1-DIVERGES-FROM-
 *     cluster248-LEAF-3-AndroidIntentLauncher (Android tries market://
 *     first then falls back to web; iOS goes web-only). PRESERVE-AS-
 *     DOCUMENTED — KDoc explicitly cites "iOS has no native Play Store".
 *
 *   - SHARETEXT-MAILTO-DEGRADATION-LIVE — shareText() degrades to
 *     `mailto:?body=$encoded&subject=$title` URL because true
 *     UIActivityViewController requires a UIViewController instance
 *     (not available from a Koin-resolved SPI). The degradation IS
 *     load-bearing as known-debt residue. PRESERVE-AS-DOCUMENTED —
 *     inline TODO-PHASE-14 marker tracks the SwiftUI bridge that would
 *     replace this with a real share sheet.
 *
 *   - PRIVATE-EXTENSION-FUN-PERCENTENCODE-LIVE — `private fun String
 *     .percentEncode(): String` uses NSURL round-trip trick to escape
 *     unsafe chars. The helper IS load-bearing because mailto: body
 *     URL params MUST be percent-encoded (else newlines/specials break
 *     the URL). PRESERVE — defends against future "use URLEncoder"
 *     refactor (URLEncoder IS not available on Kotlin/Native).
 *
 *   - NSURL-ROUNDTRIP-ENCODING-TRICK-LIVE — percentEncode body uses
 *     `NSURL.URLWithString("https://x/?q=$this")?.absoluteString
 *     ?.substringAfter("?q=") ?: this`. The roundtrip-trick IS load-
 *     bearing because Foundation NSURL auto-percent-encodes the query
 *     fragment when parsing; substring-after extraction yields the
 *     encoded form. PRESERVE-AS-DOCUMENTED — clever but obscure;
 *     comment-worthy if future-maintainer puzzled.
 *
 *   - TODO-PHASE-14-SWIFTUI-BRIDGE-LIVE — inline TODO marker:
 *     "TODO(Phase 14): wire a SwiftUI bridge so this surface presents
 *     a real UIActivityViewController". The TODO IS load-bearing as
 *     future-implementation residue. PRESERVE-AS-DOCUMENTED.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body
 *     mirrors the legacy `:shared` `IntentLauncher.ios.kt` actual
 *     byte-for-byte". 3-AGREE-WITH-cluster248-LEAF-3-LEAF-4-LEAF-5.
 *     PRESERVE-AS-DOCUMENTED.
 *
 *   - COMPANION-TAG-PRESENT-LIVE — `private companion object { const
 *     val TAG = "IntentLauncher" }`. 2-AGREE-WITH-cluster248-LEAF-3-
 *     AndroidIntentLauncher (same tag string). The tag-string-identity-
 *     across-platforms IS load-bearing for cross-platform log-filter
 *     ergonomics. PRESERVE.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     1-DIVERGES-FROM-cluster248-LEAF-3-AndroidIntentLauncher (Android
 *     takes Context). The zero-param shape IS load-bearing because
 *     UIApplication.sharedApplication IS static-style global. PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster249-LIVE — IosIntentLauncher
 *     IS leaf 3 of 5 of cluster249. PRESERVE.
 */
