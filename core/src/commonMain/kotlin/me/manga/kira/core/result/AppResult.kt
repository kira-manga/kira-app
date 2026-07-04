package me.manga.kira.core.result

import me.manga.kira.core.error.AppError

/**
 * Sealed result wrapper used at every cross-module boundary.
 *
 * Contract §10 / §19 forbid passing `kotlin.Result` across module boundaries — it can carry any
 * `Throwable`, which couples consumers to the raising layer. [AppResult] forces the data layer to
 * map every exception to an [AppError] at its boundary, so domain/presentation only ever see the
 * project's typed error hierarchy.
 *
 * Invariants:
 * - [Success] carries a value of T (must be non-null at the type level — wrap optional values in T).
 * - [Failure] carries a non-null [AppError]. No untyped throwables leak out.
 *
 * Why a sealed class rather than typealias of `kotlin.Result`:
 * 1. `kotlin.Result` is value-class encoded and discouraged for public APIs.
 * 2. We need exhaustive `when` on Success/Failure without import friction.
 * 3. The error type is constrained to [AppError], not arbitrary [Throwable].
 *
 * **Audit-trail postscript** (Phase 9.x.cluster142.staleKdocSweep.cascade,
 * Task #598, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-forty-sixth sibling of the cluster57-141
 * sweep — first file of the wave-26 opening cluster142 4-leaf-:core-
 * foundation batch; opens :core tier survey):
 *  (a) "Sealed-result-wrapper-used-at-every-cross-module-boundary +
 *  Contract-§10-§19-forbid-passing-kotlin.Result-across-module-boundaries
 *  + it-can-carry-any-Throwable-which-couples-consumers-to-the-raising-
 *  layer + AppResult-forces-the-data-layer-to-map-every-exception-to-an-
 *  AppError-at-its-boundary-so-domain-presentation-only-ever-see-the-
 *  project-typed-error-hierarchy + Invariants-Success-carries-a-value-
 *  of-T-must-be-non-null-at-the-type-level-wrap-optional-values-in-T +
 *  Failure-carries-a-non-null-AppError-No-untyped-throwables-leak-out" —
 *  LIVE-NOT-STALE plus PARTIALLY-FULFILLED-FORECAST. Verified via
 *  recursive grep: AppResult is the boundary return type for the
 *  library + details + reader + sources rework tier (LibraryRepository
 *  + MangaDetailsRepository + ChapterPagesRepository + ReadingMode-
 *  Repository + ReadProgressRepository + ReadingSessionRepository +
 *  SourcesRepository + LibraryRefreshRepository + UpdatesRepository
 *  + HistoryRepository + LibraryPrefsRepository + others). HOWEVER the
 *  contract-§10-§19-"forbidden-kotlin.Result-across-boundaries" claim is
 *  PARTIALLY violated by the later strangler-fig tier — the complaint
 *  + feedback + downloads + settings slices intentionally chose
 *  kotlin.Result for wire-format parity with their legacy :shared
 *  facade returns (~30 distinct kotlin.Result return-type sites across
 *  :domain alone: AdminComplaintActionRepository + ComplaintAction-
 *  Repository + AdminComplaintListRepository + ComplaintListRepository
 *  + FeedbackRepository + DownloadsActionRepository + SettingsRepository
 *  setToggle/clearLargeCache pair + all their corresponding use cases).
 *  The originally-prescribed AppResult-only-at-boundaries posture holds
 *  for the rework-native interfaces; the strangler-fig interfaces opted
 *  out for behaviour-preservation reasons (legacy callers expect
 *  Result.failure with .exceptionOrNull()-typed Throwable for retry-
 *  toast composition). FORECAST-NOT-YET-FULFILLED for the eventual
 *  AppResult migration of strangler-fig interfaces post-:shared-facade
 *  retire.
 *  (b) "Returns-the-value-or-null + For-paths-that-genuinely-tolerate-
 *  absence + Returns-the-error-or-null + Transform-a-successful-value-
 *  failure-is-propagated-unchanged + Monad-bind-for-chaining-operations-
 *  that-may-fail + Side-effect-on-success-only-Returns-the-receiver +
 *  Side-effect-on-failure-only-Returns-the-receiver + Recover-from-a-
 *  failure-by-mapping-the-error-to-a-fallback-value + Convenience-
 *  constructors" — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified:
 *  the seven extension functions (map + flatMap + onSuccess + onFailure
 *  + recover + appSuccess + appFailure) are consumed across :data (the
 *  rework-native repository impls fold-and-map into AppResult.Success/
 *  Failure within their boundaries) and :presentation (DetailsView-
 *  Model + LibraryViewModel + ReaderViewModel + SourcesViewModel use
 *  onSuccess/onFailure for effect-side branching). The exhaustive-when
 *  pattern is preserved — no smart-cast escape hatch has crept into
 *  the codebase. The `Failure` carries `Nothing` so the value type
 *  variance contract holds at every call site.
 *  Two classifications STAND on their own merits. Opens cluster142.
 *  Original Phase 2 (Task #153) :core-skeleton-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
sealed class AppResult<out T> {

    data class Success<out T>(val value: T) : AppResult<T>()

    data class Failure(val error: AppError) : AppResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    /** Returns the value or null. For paths that genuinely tolerate absence. */
    fun getOrNull(): T? = (this as? Success)?.value

    /** Returns the error or null. */
    fun errorOrNull(): AppError? = (this as? Failure)?.error
}

/** Transform a successful value; failure is propagated unchanged. */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

/** Monad-bind for chaining operations that may fail. */
inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(value)
    is AppResult.Failure -> this
}

/** Side-effect on success only. Returns the receiver. */
inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> = apply {
    if (this is AppResult.Success) action(value)
}

/** Side-effect on failure only. Returns the receiver. */
inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> = apply {
    if (this is AppResult.Failure) action(error)
}

/** Recover from a failure by mapping the error to a fallback value. */
inline fun <T> AppResult<T>.recover(transform: (AppError) -> T): AppResult<T> = when (this) {
    is AppResult.Success -> this
    is AppResult.Failure -> AppResult.Success(transform(error))
}

/** Convenience constructors. */
fun <T> appSuccess(value: T): AppResult<T> = AppResult.Success(value)
fun appFailure(error: AppError): AppResult<Nothing> = AppResult.Failure(error)
