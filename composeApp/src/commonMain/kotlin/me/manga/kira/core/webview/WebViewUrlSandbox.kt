package me.manga.kira.core.webview

import io.ktor.http.Url

/**
 * Cross-platform port of the legacy `WebViewComposeScreen.isAllowed` / `shouldOverrideUrlLoading`
 * URL sandbox (GAP-WV-01). The embedded browser is pinned to the host of the URL it was opened
 * with, so a main-frame navigation that would drift to a different host is blocked.
 *
 * **Why this is load-bearing.** The WebView exists to clear a source's Cloudflare / anti-bot
 * challenge and capture the resulting `cf_clearance` / `__cf_bm` cookies for that exact host. If a
 * page (or an injected ad / redirect) navigates the main frame off-host, the captured cookie ends
 * up bound to the wrong origin and the source HTML fetch still 403s. Pinning the main frame to the
 * initial host keeps the session cookie tied to the host the source repo will later replay it to.
 *
 * The legacy Android rule set, tightened for top-level navigation:
 *  - For a main-frame navigation: allow only `http`/`https` whose host is the initial host or a
 *    true sub-domain of it. The one exception is the browser-owned `about:blank` page.
 *    Arbitrary pseudo-URLs such as `about:about` and top-level `data:` documents are blocked.
 *  - `about:` and `data:` remain available to sub-frames for ordinary inline browser content.
 *  - For a sub-frame navigation: block `javascript:` and `file:` schemes (anti-exfiltration),
 *    allow the rest (iframes, images, XHR to third parties are normal page resources).
 *
 * Host matching is tightened vs the legacy `uri.host?.endsWith(host, ignoreCase = true)` bare
 * suffix test: only an exact host match or a dot-boundary sub-domain (`targetHost.endsWith(".$host")`)
 * is allowed, so a registrable lookalike like `evil-lek-manga.net` no longer slips through the pin
 * for host `lek-manga.net`. Main-frame parse failures fail closed: an address the browser policy
 * cannot identify as HTTP(S) for the pinned host must not replace the visible page.
 */
class WebViewUrlSandbox(initialUrl: String) {
    private val initialHost: String? =
        runCatching { Url(initialUrl).host.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Returns `true` when the navigation may proceed, `false` when the host should block it. Mirrors
     * the legacy `shouldOverrideUrlLoading` decision (which *blocked* by returning `true`); here the
     * sense is inverted to a plain "is this allowed" predicate so each platform actual maps it onto
     * its own gate.
     *
     * @param url the navigation target URL.
     * @param isMainFrame whether this is a top-level (main-frame) navigation. Sub-frame loads use
     *   the looser scheme-only rule.
     */
    fun isAllowed(url: String, isMainFrame: Boolean): Boolean {
        val lower = url.trimStart().lowercase()

        if (isMainFrame) {
            // WKWebView uses about:blank for its own empty/error-document lifecycle. Do not extend
            // that exception to arbitrary about:* targets: source pages have been observed
            // redirecting to the invalid about:about pseudo-URL.
            if (lower == "about:blank") return true
            val parsed = runCatching { Url(url) }.getOrNull() ?: return false
            val scheme = parsed.protocol.name.lowercase()
            if (scheme != "http" && scheme != "https") return false
            val host = initialHost ?: return false
            val targetHost = parsed.host.lowercase().takeIf { it.isNotBlank() } ?: return false
            return targetHost == host || targetHost.endsWith(".$host")
        }

        if (lower.startsWith("about:") || lower.startsWith("data:")) return true

        // Sub-frame: block javascript: / file: schemes, allow ordinary resource loads.
        val scheme = lower.substringBefore(':', missingDelimiterValue = "")
        if (scheme == "javascript" || scheme == "file") return false
        return true
    }
}
