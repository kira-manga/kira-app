package me.manga.kira.core.states

import me.manga.kira.core.error.TransportErrorMessages

sealed class State<out T> {
    data class Success<T>(val data: T) : State<T>()
    data object Loading : State<Nothing>()

    fun toData(): T? = if (this is Success) data else null


    data class Error(val code: Int?, val message: String) : State<Nothing>() {
        companion object {
            /** Factory that looks up a friendly message for you */
            fun fromCode(code: Int): Error =
                Error(code, httpStatusMessage(code))

            fun fromException(t: Throwable): Error {
                val raw = (t.message ?: "").lowercase()
                return when {
                    // No-connectivity / unreachable-host shapes across ALL platform engines
                    // (OkHttp/Android, Darwin/NSURLError, JVM Linux) — shared signature list so the
                    // legacy path classifies offline identically to the rework classifiers.
                    TransportErrorMessages.isConnectivityMessage(raw) ->
                        Error(0, "Cannot reach server—please check your internet connection.")

                    // SocketTimeoutException equivalents
                    TransportErrorMessages.isTimeoutMessage(raw) ->
                        Error(0, "The request timed out—please try again later.")

                    // ConnectException equivalents
                    raw.contains("connect") && (
                        raw.contains("refused") ||
                            raw.contains("failed") ||
                            raw.contains("reset") ||
                            raw.contains("unreachable")
                        ) ->
                        Error(0, "Unable to connect to the server.")

                    else ->
                        Error(0, "An unexpected error occurred: ${t.message}")
                }
            }

            private fun httpStatusMessage(code: Int): String =
                when (code) {
                    in 400..499 -> when (code) {
                        400 -> "Bad Request"
                        401 -> "Unauthorized"
                        403 -> "Forbidden Click On Help To Solve The Problem "
                        404 -> "Not Found"
                        408 -> "Request Timeout"
                        else -> "Client Error $code"
                    }
                    in 500..599 -> when (code) {
                        500 -> "Internal Server Error"
                        502 -> "Bad Gateway"
                        503 -> "Service Unavailable"
                        504 -> "Gateway Timeout"
                        else -> "Server Error $code"
                    }
                    else -> "Unexpected HTTP status $code"
                }
        }
    }
}

/*
 * §253 audit-trail postscript — cluster279 §253 sweep (2026-05-29)
 * Classification: LEGACY-BUT-HEAVILY-LIVE sealed ADT (pre-rework :shared/commonMain UI-state
 * wrapper with a built-in HTTP/exception error-message classifier). No rework twin — the rework
 * :data layer interoperates with it via a `LegacyState` import alias rather than relocating it.
 *
 * LIVE evidence — reached across multiple modules; a representative subset of actual sites:
 *   - data/.../repository/MangaDetailsRepositoryImpl.kt:9
 *       `import me.manga.kira.core.states.State as LegacyState` — the rework :data impl
 *       deliberately matches LegacyState.Error.fromException heuristics (lines 37, 131) so the
 *       surfaced AppError buckets line up with the legacy classifier.
 *   - data/.../repository/ChapterPagesRepositoryImpl.kt:11 — same `as LegacyState` alias,
 *       same fromException bucket-matching (lines 50, 161).
 *   - composeApp/.../navigation/routes/ChapterImagesScreenRoute.kt:171 —
 *       `val chaptersState: State<List<ReaderChapters>> by chaptersFlow.collectAsState(initial = State.Loading)`.
 *   - composeApp/.../home/ui/screens/HomeScreen.kt:187-188 / SearchScreen.kt:133 /
 *       MultiRepoResults.kt:148 — function params typed `State<List<...>>`.
 *   - shared/.../sources_repositry/common/BaseManga.kt:201 `emit(State.Error.fromCode(errorCode))`
 *       plus ~20 source-repository files importing the type and calling fromCode(0) as the
 *       canonical "feature not supported" sentinel (e.g. TapasticRepository.kt:199/203).
 *   - shared/.../presentation/common/viewmodel/MangaViewModel.kt:208
 *       `_mangaItems.value = State.Error.fromException(t)`. ARCHITECTURE.md:32258 confirms
 *       "heavily live across :data, :composeApp, the :shared sources_repositry subtree, and
 *       several legacy VMs" — only the orphan sibling ImagesState.kt was retired (Task #374),
 *       State.kt stays.
 *
 * LEGACY status (not a platform facade): never expect/actual, never relocated to :platform.
 * The error-classification heuristics here are the reference the rework :data mappers mirror.
 *
 * Delta-axes:
 *   1. Platform API used: none — pure commonMain Kotlin. fromException inspects Throwable.message
 *      via string heuristics (lowercased substring matching) precisely BECAUSE typed JVM/Native
 *      socket exceptions are not available uniformly in commonMain.
 *   2. Threading/dispatcher: not owned here — value type collected via collectAsState / emitted
 *      into Flows by repositories and ViewModels; no coroutine surface of its own.
 *   3. Error handling: this ADT IS an error channel — Error(code, message) with two companion
 *      factories: fromCode(code) maps HTTP status ranges to friendly text (400-499 / 500-599),
 *      and fromException(t) buckets DNS / timeout / connect-refused signatures to user copy.
 *   4. DI binding mechanism: NOT Koin-bound. Constructed directly by repositories/VMs and
 *      pattern-matched in composables; the State.Error companion is the only factory surface.
 *   5. Member contract: closed three-arm ADT — Success<T>(data), Loading (data object), and
 *      Error(code, message); the `toData()` helper returns data-or-null. The rework :data side
 *      preserves bucket parity by aliasing rather than re-deriving the classifier.
 * Nested-comment hazard check: this file has exactly one pre-existing block-comment opener — the
 * one-line KDoc on the fromCode companion factory (it reads "Factory that looks up a friendly
 * message for you") plus two line-style comments, which are not block delimiters; all balanced.
 * This appended block adds one more opener and one closer, with zero interior slash-star,
 * star-slash, or slash-star-star sequences in the prose; the file's comment delimiters remain
 * balanced.
 */
