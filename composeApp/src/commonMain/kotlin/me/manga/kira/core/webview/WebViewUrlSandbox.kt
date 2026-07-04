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
 * The legacy Android rule set, reproduced verbatim:
 *  - `about:` and `data:` URLs are always allowed (used for blank / inline content).
 *  - For a main-frame navigation: allow only `http`/`https` whose host is the initial host or a
 *    true sub-domain of it; block everything else.
 *  - For a sub-frame navigation: block `javascript:` and `file:` schemes (anti-exfiltration),
 *    allow the rest (iframes, images, XHR to third parties are normal page resources).
 *
 * Host matching is tightened vs the legacy `uri.host?.endsWith(host, ignoreCase = true)` bare
 * suffix test: only an exact host match or a dot-boundary sub-domain (`targetHost.endsWith(".$host")`)
 * is allowed, so a registrable lookalike like `evil-lek-manga.net` no longer slips through the pin
 * for host `lek-manga.net`. Parse failures fall back to *allowed* (legacy `catch → true`),
 * matching the legacy behaviour of never blocking on a URL it couldn't parse.
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
        if (lower.startsWith("about:") || lower.startsWith("data:")) return true

        if (isMainFrame) {
            val parsed = runCatching { Url(url) }.getOrNull() ?: return true
            val scheme = parsed.protocol.name.lowercase()
            if (scheme != "http" && scheme != "https") return false
            val host = initialHost ?: return true
            val targetHost = parsed.host.lowercase().takeIf { it.isNotBlank() } ?: return true
            return targetHost == host || targetHost.endsWith(".$host")
        }

        // Sub-frame: block javascript: / file: schemes, allow ordinary resource loads.
        val scheme = lower.substringBefore(':', missingDelimiterValue = "")
        if (scheme == "javascript" || scheme == "file") return false
        return true
    }
}
