package me.manga.kira.core.webview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Shared, process-wide init-state handshake between the desktop launcher (`Main.kt` in
 * `:desktopApp`) and the Desktop `WebViewHost` actual (`WebViewHost.desktop.kt`, same module as
 * this file).
 *
 * **Why this exists.** On Windows/Linux KCEF init runs ASYNCHRONOUSLY on a background coroutine
 * while `application { … }` opens the window immediately (r7-cc-3 / owner decision A6 — the
 * first-run ~150-200 MB CEF download must never block startup), so a WebView screen can mount
 * BEFORE init finishes. On macOS init is HARD-SKIPPED entirely (see Main.kt's KDoc — the embedded
 * JCEF bring-up is non-viable under this project's run setups) and [markUnavailable] is published
 * instead. Without a recovery signal, `WebViewHost.desktop.kt` would cache a `null` client in
 * `remember { … }` and never recover even after an async init completed.
 *
 * [initialized] is the recovery signal: the launcher flips it to `true` the moment KCEF init
 * succeeds (synchronously on Win/Linux, in the `onInitialized` callback / post-success path on
 * macOS). The WebView actual collects this flow and re-keys its `remember`-ed client acquisition on
 * the boolean, so the client is re-fetched (and the browser mounts) as soon as init flips true.
 *
 * **Fail-safe contract.** If KCEF init never succeeds (e.g. the documented macOS
 * `icudtl.dat`/`NSBundle` upstream bug), [initialized] stays `false` forever and the WebView actual
 * shows its graceful placeholder/progress UI — startup is NEVER blocked or crashed by this.
 */
object KcefState {
    private val _initialized = MutableStateFlow(false)
    private val _unavailable = MutableStateFlow(false)

    /**
     * `true` once KCEF has finished initializing successfully in this process. Starts `false` and
     * is set exactly once by the desktop launcher on init success. Never reset to `false`.
     */
    val initialized: StateFlow<Boolean> = _initialized

    /**
     * `true` when the launcher has decided KCEF will NOT initialize this session (e.g. the macOS
     * branch skips init entirely). Lets the WebView actual show its "unavailable" placeholder
     * instead of an eternal in-flight spinner. Distinct from [initialized] staying `false` while
     * an async init is genuinely still running.
     */
    val unavailable: StateFlow<Boolean> = _unavailable

    /** Called by the desktop launcher (`Main.kt`) when KCEF init has succeeded. Idempotent. */
    fun markInitialized() {
        _initialized.value = true
    }

    /** Called by the desktop launcher (`Main.kt`) when KCEF init is skipped / cannot run. Idempotent. */
    fun markUnavailable() {
        _unavailable.value = true
    }
}
