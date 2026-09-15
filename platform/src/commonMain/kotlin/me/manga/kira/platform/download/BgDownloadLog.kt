package me.manga.kira.platform.download

import co.touchlab.kermit.Logger

/**
 * Structured, greppable tracing for the iOS background-download system (test builds).
 *
 * Every line is emitted under the single Kermit tag **`KiraBgDownload`** so the whole subsystem can be
 * filtered with one search. Call sites pass an event name plus identifier fields, rendered as:
 *
 * ```
 * KiraBgDownload: <event> | chapterId=12 pageIndex=3 attempt=2 …
 * ```
 *
 * Gated behind [VERBOSE] (a separate switch from `DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED`,
 * which selects the engine). It is **on** for this test build; set it `false` to silence before
 * shipping.
 *
 * **Privacy:** only closed event names and allowlisted primitive fields/code-owned tokens are emitted.
 * Unknown values, URLs, hosts, headers, paths and Throwable details never reach this boundary's writer.
 */
object BgDownloadLog {
    const val TAG = "KiraBgDownload"

    /**
     * Verbose **info** tracing switch. The default `true` serves device/TestFlight QA; on iOS the
     * value is **enforced at launch by distribution** (owner decision 2026-07-02): the Swift host
     * (`AppDelegate` → `IosBackgroundBridgeKt.setBgDownloadVerboseLogging`) keeps it on for Debug +
     * TestFlight (sandbox receipt) and turns it OFF for App Store builds — the old "flip to false
     * before shipping" checklist item can no longer be forgotten. (Android's release log floor is
     * Warn, which filters this stream's `logger.i` lines regardless; Desktop keeps the default.)
     * [warn]/[error] are intentionally NOT gated by this, so failures stay visible in production.
     */
    var VERBOSE: Boolean = true

    /**
     * Dedicated, default-**off** gate for the `DLPERF.*` performance-timing lines (encode / finalize /
     * resolve / window / mutex / mainStall), kept separate from [VERBOSE] so the reusable perf harness
     * can be flipped on for a profiling session without re-enabling the whole high-volume event trace.
     * **Leave `false` in committed code** (mirrors the Swift `ReaderPerfLog.enabled` pattern); flip
     * locally to benchmark — e.g. the Skia-vs-libwebp encoder A/B (`IosWebpEncoderFlags`). When `false`,
     * callers that guard heavier instrumentation on it (the `ChapterFinalizer` main-thread stall
     * watchdog) skip that work entirely, so there is zero runtime cost.
     */
    var DLPERF: Boolean = false

    private val logger = Logger.withTag(TAG)

    fun log(event: String, vararg fields: Pair<String, Any?>) {
        if (!VERBOSE) return
        logger.i { BgDownloadLogFormat.format(event, fields) }
    }

    /**
     * Emit a `DLPERF.<event>` performance line, gated by [DLPERF] (independent of [VERBOSE]). Use for the
     * encode/finalize/resolve timing instrumentation; default-off so it never adds log volume in normal
     * builds yet stays available for future perf work.
     */
    fun dlperf(event: String, vararg fields: Pair<String, Any?>) {
        if (!DLPERF) return
        logger.i { BgDownloadLogFormat.format(event, fields, performance = true) }
    }

    /** Always logged (not gated by [VERBOSE]) — a warning must survive the release info-silence flip. */
    fun warn(event: String, vararg fields: Pair<String, Any?>) {
        logger.w { BgDownloadLogFormat.format(event, fields) }
    }

    /** Always emitted; [t] remains call-compatible but is never inspected or forwarded to the writer. */
    fun error(
        @Suppress("UnusedParameter") t: Throwable?,
        event: String,
        vararg fields: Pair<String, Any?>,
    ) {
        logger.e { BgDownloadLogFormat.format(event, fields) }
    }
}
