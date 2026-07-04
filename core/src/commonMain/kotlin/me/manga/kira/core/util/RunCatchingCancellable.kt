package me.manga.kira.core.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * Cancellation-aware [runCatching].
 *
 * The stdlib [runCatching] catches **every** [Throwable], including [CancellationException]. Inside a
 * suspending block that means a coroutine cancelled mid-flight (the user navigates away, a
 * `collectLatest` restarts, a parent scope is torn down) is swallowed into `Result.failure(ce)`
 * instead of propagating — so the caller runs its failure path (often a spurious error snackbar) and
 * structured-concurrency cancellation no longer unwinds cooperatively. The project's boundary
 * contract requires [CancellationException] to be rethrown unchanged.
 *
 * This helper preserves the stdlib `kotlin.Result` return shape (the deliberate legacy wire-format
 * parity choice in the strangler `:data` slices that still return `Result<T>`) while honouring the
 * cancellation contract: it rethrows [CancellationException] first and only wraps genuine failures.
 * It is a drop-in replacement for `runCatching {}` at suspend-wrapping call sites.
 *
 * For `AppResult`-returning repositories use the layer-local `runCatchingStorage`/error-mapping
 * helpers instead — those already rethrow cancellation and map to `AppError`.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }
