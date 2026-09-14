package me.manga.kira.core.webview

import android.util.AndroidRuntimeException
import kotlinx.coroutines.CancellationException
import java.util.IdentityHashMap

internal fun webViewCauses(failure: Throwable): Sequence<Throwable> =
    sequence {
        val seen = IdentityHashMap<Throwable, Boolean>()
        var current: Throwable? = failure
        while (current != null && seen.put(current, true) == null) {
            yield(current)
            current = current.cause
        }
    }

internal fun webViewCriticalCause(failure: Throwable): Throwable? =
    webViewCauses(failure).firstOrNull { it is CancellationException || it is Error }

// Only called for constructor/settings/client installation, never application callbacks or Compose.
internal fun isWebViewInitializationFailure(failure: RuntimeException): Boolean =
    failure is UnsupportedOperationException || failure is AndroidRuntimeException || failure is IllegalStateException

internal fun webViewCleanupFailure(
    original: Throwable,
    cleanup: Throwable,
): Throwable {
    val preferred = webViewCriticalCause(original) ?: webViewCriticalCause(cleanup) ?: original
    val secondary = if (preferred === cleanup) original else cleanup
    if (webViewCauses(secondary).none { it === preferred }) preferred.addSuppressed(secondary)
    return preferred
}

@Suppress("TooGenericExceptionCaught") // Preserve original/cleanup critical-failure precedence.
internal fun releaseAfterWebViewFailure(owned: OwnedAndroidWebView?, original: Throwable) {
    try {
        owned?.release()
    } catch (cleanup: RuntimeException) {
        throw webViewCleanupFailure(original, cleanup)
    } catch (cleanup: Error) {
        throw webViewCleanupFailure(original, cleanup)
    }
}
