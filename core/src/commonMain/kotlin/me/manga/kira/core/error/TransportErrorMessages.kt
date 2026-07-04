package me.manga.kira.core.error

/**
 * Message-shape heuristics shared by every transport-error classifier at the `:data` / sources
 * boundary (`HomeMappers`, `MangaDetailsRepositoryImpl`, `ChapterPagesRepositoryImpl`,
 * `LegacyKotlinSourceClient`, `GenericSourceClient`).
 *
 * Kept in ONE place so all classifiers recognize every platform engine's error text (2026-07 audit:
 * the previous per-file copies only matched OkHttp/Android shapes, so iOS airplane mode surfaced
 * [AppError.Unexpected] — a generic "unexpected error" with no connectivity hint — on every fetch):
 *  - **OkHttp / Android / JVM DNS**: `UnknownHostException` texts ("unable to resolve host",
 *    "no address associated", macOS "nodename nor servname", Linux "name or service not known",
 *    glibc "temporary failure in name resolution").
 *  - **Ktor Darwin (iOS)**: `DarwinHttpRequestException` wraps `NSURLError` — matched by BOTH the
 *    English `localizedDescription` texts and the locale-independent
 *    `nsurlerrordomain code=-100x` fragments (the description is localized on non-English devices,
 *    so the code fragment is the load-bearing match there). -1009 offline, -1003 host not found,
 *    -1004 can't connect, -1005 connection lost, -1006 DNS failed; -1001 request timed out.
 *  - **Socket-level (all engines)**: "connection refused" / "network is unreachable" /
 *    "no route to host" — the device or server is unreachable; retrying after connectivity returns
 *    is the right user hint. "connection reset" is deliberately NOT here: it happens mid-exchange
 *    while connectivity is fine (server-side behavior), so calling it "no connectivity" would
 *    mislead.
 *
 * Callers pass the LOWERCASED throwable/state message; classification order (challenge → HTTP code
 * → connectivity → timeout → unexpected) stays at each call site.
 */
object TransportErrorMessages {
    private val connectivitySignatures =
        listOf(
            // DNS resolution — OkHttp/Android/JVM (macOS, Linux, glibc variants)
            "unknown host",
            "no address associated",
            "nodename nor servname",
            "unable to resolve host",
            "name or service not known",
            "temporary failure in name resolution",
            // Darwin NSURLError — English localizedDescription texts
            "internet connection appears to be offline",
            "hostname could not be found",
            "could not connect to the server",
            "network connection was lost",
            "dns lookup failed",
            // Darwin NSURLError — locale-independent code fragments (Ktor Darwin message shape:
            // "… Error Domain=NSURLErrorDomain Code=-1009 …" lowercased)
            "nsurlerrordomain code=-1009",
            "nsurlerrordomain code=-1003",
            "nsurlerrordomain code=-1004",
            "nsurlerrordomain code=-1005",
            "nsurlerrordomain code=-1006",
            // Socket-level reachability — all engines
            "connection refused",
            "network is unreachable",
            "no route to host",
            // Canonical legacy-State friendly texts (State.Error.fromException replaces the raw
            // exception message BEFORE LegacyKotlinSourceClient re-classifies it, so the downstream
            // classifier sees these, not the engine text)
            "cannot reach server",
            "unable to connect to the server",
        )

    private val timeoutSignatures =
        listOf(
            "timeout",
            "timed out",
            // Darwin NSURLErrorTimedOut, locale-independent
            "nsurlerrordomain code=-1001",
        )

    /** True when the (lowercased) message names a no-connectivity / unreachable-host condition. */
    fun isConnectivityMessage(raw: String): Boolean = connectivitySignatures.any { raw.contains(it) }

    /** True when the (lowercased) message names a timeout. */
    fun isTimeoutMessage(raw: String): Boolean = timeoutSignatures.any { raw.contains(it) }
}
