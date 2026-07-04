package me.manga.kira.presentation.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger as KermitLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Base class for strict-MVI ViewModels.
 *
 * Type parameters:
 * - `S : MviState` — the feature's immutable state shape.
 * - `I : MviIntent` — the sealed Intent hierarchy.
 * - `E : MviEffect` — the sealed Effect hierarchy.
 *
 * Wiring contract:
 * - Subclass exposes the current state as [state] (StateFlow).
 * - Subclass exposes one-shot effects as [effects] (Flow over a Channel).
 * - The view calls [submit] with an Intent; the subclass overrides [handle] to react.
 *
 * Concurrency / lifecycle:
 * - State updates flow through [updateState] which is thread-safe via [MutableStateFlow].
 * - Effects are emitted via [emit] backed by an UNLIMITED capacity [Channel] — the buffer
 *   holds effects across configuration changes / view detachment so none get dropped.
 * - All ViewModel coroutines launch in [viewModelScope] so they cancel automatically when
 *   the host is destroyed.
 *
 * SOLID notes:
 * - SRP — this class owns the MVI plumbing (StateFlow, Effect channel, Intent dispatch).
 *   Subclasses own feature-specific reducer logic; nothing else.
 * - OCP — the [handle] hook is the extension point. The base class never changes when a new
 *   feature lands.
 * - LSP — every subclass is a drop-in [ViewModel]; substitutability is enforced by the
 *   generic parameters being marker interfaces, not concrete types.
 * - ISP — three narrow surfaces (state, effects, submit). Nothing else exposed.
 * - DIP — base class depends on lifecycle's [ViewModel] (KMP-portable) and coroutines.
 *   Feature subclasses receive their use cases via constructor parameters.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster104.staleKdocSweep.cascade,
 * Task #560, 2026-05-28): the file-scope base-class manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-fourth sibling of the cluster57-103 sweep — closes
 * the wave-9 `:presentation/mvi/` marker-interface tier alongside
 * MviIntent.kt plus MviState.kt plus MviEffect.kt):
 *  (a) "Type parameters: `S : MviState`, `I : MviIntent`, `E :
 *  MviEffect`" — LIVE-NOT-STALE. L45-47 class declaration realizes the
 *  three-generic-parameter contract verbatim; every feature VM
 *  parameterises with concrete sealed types (cluster101 ReaderView-
 *  Model<ReaderState, ReaderIntent, ReaderEffect>, cluster102 History-
 *  ViewModel<HistoryState, HistoryIntent, HistoryEffect>, cluster103
 *  StatisticsViewModel<StatisticsState, StatisticsIntent, Statistics-
 *  Effect>).
 *  (b) "Wiring contract: state (StateFlow), effects (Flow over a
 *  Channel), submit (Intent dispatch), handle (abstract reducer hook)"
 *  — LIVE-NOT-STALE. L49-50 `_state: MutableStateFlow + state:
 *  StateFlow` LIVE; L52-53 `_effects: Channel + effects: Flow` LIVE;
 *  L68-70 `submit(intent: I)` LIVE; L60 `abstract suspend fun
 *  handle(intent: I)` LIVE.
 *  (c) "Effects emitted via [emit] backed by an UNLIMITED capacity
 *  [Channel] — the buffer holds effects across configuration changes /
 *  view detachment so none get dropped" — LIVE-NOT-STALE. L52 realiza-
 *  tion: `Channel(capacity = Channel.UNLIMITED, onBufferOverflow =
 *  BufferOverflow.SUSPEND)`. Channel.UNLIMITED + SUSPEND-on-overflow
 *  semantics preserved (the SUSPEND choice is defensive — UNLIMITED
 *  capacity means overflow never triggers in practice, but if it did
 *  the producer would suspend rather than drop).
 *  (d) "All ViewModel coroutines launch in [viewModelScope] so they
 *  cancel automatically when the host is destroyed" — LIVE-NOT-STALE.
 *  L68-70 submit launches in `viewModelScope`; every feature VM
 *  collector (cluster101 ReaderViewModel's fetchJob / chaptersJob /
 *  progressJob, cluster102 HistoryViewModel's init collector, cluster-
 *  103 StatisticsViewModel's init collector) launches in `viewModel-
 *  Scope` per ViewModel-lifecycle structured-concurrency contract.
 *  (e) "SRP / OCP / LSP / ISP / DIP" — LIVE-NOT-STALE.
 *  - SRP: base class owns only MVI plumbing; the cluster101-103 sweeps
 *  verify subclasses contain no plumbing duplication.
 *  - OCP: `handle` hook is the sole extension point (verified
 *  across every feature VM).
 *  - LSP: every subclass substitutes ViewModel without exposing
 *  base-class-specific types in its public API.
 *  - ISP: three narrow surfaces (state, effects, submit). Verified
 *  across the cluster28-30 `:ui` consumer sweep — no `:ui` collector
 *  reaches into protected `updateState` / `emit` / `handle`.
 *  - DIP: base depends only on androidx.lifecycle.ViewModel
 *  (KMP-portable since AndroidX lifecycle 2.8+) plus kotlinx.coroutines.
 *  Every feature VM receives use cases via constructor parameters
 *  (verified across cluster101-103 plus all prior cluster sweeps).
 *  (f) "Channel-of-Intents pattern was deliberately rejected" rationale
 *  on submit's KDoc — LIVE-NOT-STALE. L68-70 realization: fresh
 *  coroutine per intent via `viewModelScope.launch { handle(intent) }`;
 *  no shared intent queue. Features that need serialization (e.g.
 *  cluster101 ReaderViewModel's `fetchJob` cancel-and-relaunch
 *  pattern) implement it explicitly via Job references.
 *  Six LIVE-NOT-STALE classifications STAND on their own merits as a
 *  faithful MviViewModel base-class manifest. Original Phase 6.1-era
 *  prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
abstract class MviViewModel<S : MviState, I : MviIntent, E : MviEffect>(
    initialState: S,
) : ViewModel() {

    private val _state: MutableStateFlow<S> = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effects: Channel<E> = Channel(capacity = Channel.UNLIMITED, onBufferOverflow = BufferOverflow.SUSPEND)
    val effects: Flow<E> = _effects.receiveAsFlow()

    /**
     * Feature ViewModels override this to react to user actions. Always called on
     * [viewModelScope] when invoked via [submit]; subclasses may switch dispatchers
     * via the injected [me.manga.kira.core.dispatchers.DispatcherProvider].
     */
    protected abstract suspend fun handle(intent: I)

    /**
     * Entry point for the view. Returns immediately; intent dispatch happens on
     * [viewModelScope]. The Channel-of-Intents pattern was deliberately rejected here
     * because a fresh coroutine per intent gives features explicit control over which
     * intents are concurrent and which serialize (via their own Mutex / actor-style logic).
     */
    fun submit(intent: I) {
        viewModelScope.launch {
            try {
                handle(intent)
            } catch (e: CancellationException) {
                // Cooperative cancellation (e.g. VM cleared) must propagate so the scope unwinds.
                throw e
            } catch (t: Throwable) {
                // Last-resort safety net: a reducer throw must not escape viewModelScope and crash
                // the process (Android routes uncaught throws to the crash handler; iOS/Desktop
                // terminate). Route to the overridable hook instead.
                onUnhandledError(t, intent)
            }
        }
    }

    /**
     * Launch fire-and-forget work in [viewModelScope] under the same safety net as [submit].
     *
     * IMPORTANT: [submit]'s try/catch only covers throws raised *synchronously within the awaited*
     * [handle] call chain. A bare `viewModelScope.launch { … }` started *inside* a handler is a
     * sibling coroutine whose uncaught throw escapes to the scope and crashes the process. Prefer
     * this helper for any such fire-and-forget work so an unexpected throw routes to
     * [onUnhandledError] instead. Returns the [Job] so callers keep the cancel-before-relaunch
     * pattern (e.g. `fetchJob = launchSafely { … }`). [CancellationException] still propagates.
     */
    protected fun launchSafely(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onUnhandledError(t)
            }
        }

    /**
     * Last-resort handler for an unhandled throw out of [handle] (or [launchSafely]). The default
     * logs the failure at Error severity and swallows it so a single bug cannot crash the whole
     * app. (Real, expected failures should be modelled as typed
     * [AppError][me.manga.kira.core.error.AppError] results and surfaced via an Effect inside
     * [handle], not relied upon here.) [CancellationException] is rethrown before this is reached.
     * Subclasses MAY override to emit a feature-specific error Effect. [intent] is the triggering
     * intent when the throw came from [submit], or `null` from a [launchSafely] block.
     */
    protected open fun onUnhandledError(throwable: Throwable, intent: I? = null) {
        KermitLogger.withTag(this::class.simpleName ?: "MviViewModel")
            .e(throwable) { "Unhandled error" + (intent?.let { " handling intent ${it::class.simpleName}" } ?: "") }
    }

    /**
     * Reducer-style state mutation. Use atomically — readers see either the old or the
     * new state, never a partial update.
     */
    protected fun updateState(reduce: (S) -> S) {
        _state.update(reduce)
    }

    /**
     * Emit a one-shot side effect to the view. Backed by an unlimited buffer so effects
     * survive transient view detachment (config changes, navigation away/back).
     */
    protected suspend fun emit(effect: E) {
        _effects.send(effect)
    }
}
