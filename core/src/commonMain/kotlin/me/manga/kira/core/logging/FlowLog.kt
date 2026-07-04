package me.manga.kira.core.logging

import co.touchlab.kermit.Logger as KermitLogger
import kotlin.concurrent.Volatile

/**
 * Single structured **app-flow / state** debug trace for the read path, under one searchable tag
 * [TAG] = `"KiraFlow"`. It tracks the full journey:
 *
 *   Library open → Details (saved-Room load → refresh/overlay) → chapter click →
 *   downloaded-vs-source resolve → Reader enter → state transitions / pages count /
 *   empty-or-error / append-next / active chapter+index+page → resume position → history →
 *   mark-read / new-cleared.
 *
 * Intentionally **app-flow/state only** — it must NEVER log network requests/responses or image
 * requests (those are noise and live in the source scrapers / Coil, which this trace deliberately
 * avoids). Filter the whole flow in logcat with the single tag `KiraFlow`.
 *
 * Emitted at DEBUG severity, so the Android-release `Logger.setMinSeverity(Severity.Warn)` floor
 * (set in the host `MyApp`) suppresses it in release builds with no extra wiring; debug builds keep
 * it. A top-level object (not DI) so any ViewModel, repository, or Composable can call it without
 * threading a logger through constructors. Toggle [enabled] to mute at runtime.
 *
 * Message shape: `[<stage>] <event> | <data>` — e.g. `[Details] open | title=Naruto api=Azora`,
 * `[Reader] pages | chapter=… count=20`, `[Reader] resolve | chapter=… source=downloaded`.
 */
object FlowLog {

    /** The one tag for the entire flow trace. Grep / logcat-filter on this. */
    const val TAG = "KiraFlow"

    /** Runtime mute switch (mirrors the SourceDebugFlags pattern). Default on (debug-gated anyway). */
    @Volatile
    var enabled: Boolean = true

    /**
     * Emit one flow line. [stage] is the pipeline stage (`Library` / `Details` / `Reader` /
     * `History`); [event] is the step (`open`, `savedLoaded`, `chapterClick`, `resolve`, `enter`,
     * `pages`, `emptyPages`, `error`, `appendNext`, `page`, `resume`, `history`, `markRead`,
     * `clearNew`, …); [data] is optional `key=value` context (no PII, no request bodies).
     */
    fun log(stage: String, event: String, data: String = "") {
        if (!enabled) return
        KermitLogger.withTag(TAG).d {
            if (data.isEmpty()) "[$stage] $event" else "[$stage] $event | $data"
        }
    }
}
