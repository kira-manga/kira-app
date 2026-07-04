package me.manga.kira.presentation.mvi

/**
 * Marker for a one-shot side effect the ViewModel needs the view to perform.
 *
 * Examples: "navigate to X", "show a snackbar", "open the system share sheet". Effects are
 * delivered through a [kotlinx.coroutines.channels.Channel] (single consumer, never replayed)
 * so that view recomposition / re-attachment does not retrigger them.
 *
 * Effects MUST NOT carry rendering data — that's [MviState]'s job. They carry only the
 * trigger (e.g. a target route, a message resource id) so the view can react once and forget.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster104.staleKdocSweep.cascade,
 * Task #560, 2026-05-28): the file-scope marker-interface manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (forty-fourth sibling of the cluster57-103 sweep —
 * sibling of cluster104 MviIntent.kt plus MviState.kt):
 *  (a) "Marker for a one-shot side effect the ViewModel needs the view
 *  to perform" — LIVE-NOT-STALE. Recursive Grep across `:presentation/
 *  *Effect.kt` confirms every feature Effect surface implements
 *  `MviEffect`. Cluster102 HistoryEffect (2 navigation variants) plus
 *  cluster101 ReaderEffect (3 variants: NavigateBack, ShowError,
 *  OpenChapterInWebView) plus cluster103 StatisticsEffect (0 variants
 *  — empty-sealed OCP shape) all exemplify the marker contract.
 *  (b) "delivered through a [Channel] (single consumer, never replayed)
 *  so that view recomposition / re-attachment does not retrigger them"
 *  — LIVE-NOT-STALE. MviViewModel.kt L52-53 realization: `_effects =
 *  Channel(capacity = Channel.UNLIMITED, onBufferOverflow =
 *  BufferOverflow.SUSPEND)` plus `effects: Flow<E> = _effects.receive-
 *  AsFlow()`. Channel-receiveAsFlow single-consumer semantics
 *  preserved (effects consumed exactly once per send).
 *  (c) "Effects MUST NOT carry rendering data — that's [MviState]'s
 *  job. They carry only the trigger (e.g. a target route, a message
 *  resource id) so the view can react once and forget" — LIVE-NOT-
 *  STALE. Every Effect surface verified to carry only navigation
 *  payload or message identity across cluster sweeps (cluster101
 *  ReaderEffect.OpenChapterInWebView(url) plus cluster102 History-
 *  Effect.NavigateToDetails(api, mangaUrl) / NavigateToReader(entry)
 *  — payload-only, never rendering data).
 *  Three LIVE-NOT-STALE classifications STAND on their own merits as a
 *  faithful MviEffect marker-interface manifest. Original Phase 6.1-
 *  era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
interface MviEffect
