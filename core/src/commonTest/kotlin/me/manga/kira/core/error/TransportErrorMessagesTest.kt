package me.manga.kira.core.error

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-07 audit — the shared transport-error signature list must recognize every platform engine's
 * offline/timeout shapes (the per-file copies it replaced only matched OkHttp/Android, so iOS
 * airplane mode classified as "unexpected" on every fetch). Inputs below are real engine messages,
 * lowercased the way every classifier call site lowercases before matching.
 */
class TransportErrorMessagesTest {
    private fun connectivity(raw: String) = TransportErrorMessages.isConnectivityMessage(raw.lowercase())

    private fun timeout(raw: String) = TransportErrorMessages.isTimeoutMessage(raw.lowercase())

    @Test
    fun okHttpAndJvmDnsShapes_areConnectivity() {
        assertTrue(connectivity("Unable to resolve host \"azora.test\": No address associated with hostname"))
        assertTrue(connectivity("java.net.UnknownHostException: unknown host azora.test"))
        assertTrue(connectivity("azora.test: nodename nor servname provided, or not known"))
        assertTrue(connectivity("azora.test: Name or service not known"))
        assertTrue(connectivity("azora.test: Temporary failure in name resolution"))
    }

    @Test
    fun darwinNsUrlErrorShapes_areConnectivity_byTextAndByLocaleIndependentCode() {
        // Ktor Darwin message shape (English device)
        assertTrue(
            connectivity(
                "Exception in http request: Error Domain=NSURLErrorDomain Code=-1009 " +
                    "\"The Internet connection appears to be offline.\"",
            ),
        )
        assertTrue(connectivity("A server with the specified hostname could not be found."))
        assertTrue(connectivity("Could not connect to the server."))
        assertTrue(connectivity("The network connection was lost."))
        // Non-English device: localizedDescription is localized → the code fragment must carry it
        assertTrue(connectivity("Error Domain=NSURLErrorDomain Code=-1009 \"يبدو أن اتصال الإنترنت غير متصل.\""))
        assertTrue(connectivity("Error Domain=NSURLErrorDomain Code=-1003 \"...\""))
        assertTrue(connectivity("Error Domain=NSURLErrorDomain Code=-1004 \"...\""))
        assertTrue(connectivity("Error Domain=NSURLErrorDomain Code=-1005 \"...\""))
    }

    @Test
    fun socketLevelReachabilityShapes_areConnectivity() {
        assertTrue(connectivity("Connection refused"))
        assertTrue(connectivity("connect failed: ENETUNREACH (Network is unreachable)"))
        assertTrue(connectivity("No route to host"))
    }

    @Test
    fun canonicalLegacyStateTexts_areConnectivity() {
        // State.Error.fromException replaces the raw engine message with these BEFORE the
        // LegacyKotlinSourceClient re-classifies, so the downstream classifier must know them.
        assertTrue(connectivity("Cannot reach server—please check your internet connection."))
        assertTrue(connectivity("Unable to connect to the server."))
    }

    @Test
    fun timeoutShapes_areTimeouts_notConnectivity() {
        assertTrue(timeout("timeout"))
        assertTrue(timeout("The request timed out."))
        assertTrue(timeout("Error Domain=NSURLErrorDomain Code=-1001 \"...\"")) // locale-independent
        assertFalse(connectivity("Request timeout has expired [url=https://azora.test, request_timeout=30000 ms]"))
    }

    @Test
    fun nonTransportShapes_areNeither() {
        assertFalse(connectivity("404 Not Found"))
        assertFalse(connectivity("Unexpected JSON token at offset 12"))
        // Deliberately unclassified: a reset happens mid-exchange while connectivity is fine.
        assertFalse(connectivity("Connection reset by peer"))
        assertFalse(timeout("404 Not Found"))
    }
}
