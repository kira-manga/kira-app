package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.states.State as LegacyState

/**
 * Collect a legacy source [Flow] of [LegacyState] down to its single terminal (non-`Loading`)
 * emission **without cancelling the upstream early**.
 *
 * ## Why this exists (the "failed to load" / "something went wrong" root cause)
 *
 * The legacy per-source flows (`sources_repositry/.../common/BaseManga.kt#fetchDataWithHeaders`)
 * emit their terminal `State` from **inside** a broad try/catch:
 *
 * ```
 * flow {
 *     emit(State.Loading)
 *     try {
 *         …
 *         emit(State.Success(parsed))   // <-- terminal on 2xx
 *     } catch (e: Exception) {
 *         emit(State.Error(0, e.message))
 *     }
 * }
 * ```
 *
 * The rework `:data` strangler-fig used to consume these with a **cancelling** terminal operator —
 * `.first { it !is State.Loading }`. When `.first`'s predicate matches `State.Success`, kotlinx
 * aborts the collection by throwing `AbortFlowException` **out of the matching `emit(...)` call**.
 * That throwable lands in the legacy `catch (e: Exception)`, which swallows it and calls `emit(...)`
 * again — and emitting from a catch after a downstream abort trips kotlinx's exception-transparency
 * guard:
 *
 * > `IllegalStateException: Flow exception transparency is violated … Emissions from 'catch' blocks
 * > are prohibited …`
 *
 * That `IllegalStateException` propagated out of `.first`, was caught by the repo's outer
 * `catch (t: Throwable)`, and became a bogus `AppError.Unexpected` — surfacing as "failed to load"
 * (+ a "something went wrong" snackbar) on **every successful 200-OK fetch**. The legacy app never
 * hit this because its ViewModels collected the flow with a non-cancelling `.collect {}` and
 * processed each `State` as it arrived; the rework's early-cancelling `.first` was the sole trigger.
 *
 * ## Why `.collect` is safe here
 *
 * The legacy flow emits `Loading` then **exactly one** terminal `State` and then completes normally.
 * A full (non-cancelling) [Flow.collect] therefore observes the terminal and lets the upstream finish
 * on its own — the legacy `catch` is only ever entered by a *genuine* exception (a real network/parse
 * failure), never by our abort. We keep the **last** non-`Loading` emission (defensive against a
 * source that emits more than one), or a synthetic transport-error `State.Error(0, …)` if a flow ever
 * completes without any terminal (mapped downstream to `AppError.Unexpected`, same as before).
 *
 * Mirror of the already-correct `.collect {}` consumption in
 * [ChapterPagesRepositoryImpl][ChapterPagesRepositoryImpl] and
 * [SearchRepositoryImpl.searchAllRepos][SearchRepositoryImpl]; this helper retrofits the single-source
 * Home / Details / Search paths to the same safe shape.
 */
internal suspend fun <T> Flow<LegacyState<T>>.awaitTerminalState(): LegacyState<T> {
    var terminal: LegacyState<T>? = null
    collect { state -> if (state !is LegacyState.Loading) terminal = state }
    return terminal ?: LegacyState.Error(code = 0, message = "Source flow completed without a terminal state")
}
