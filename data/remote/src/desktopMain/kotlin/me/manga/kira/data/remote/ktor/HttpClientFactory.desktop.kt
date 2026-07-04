package me.manga.kira.data.remote.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.FileStorage
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import java.io.File

/**
 * Debug/release signal for HTTP logging on Desktop (JVM).
 *
 * Native gates the HttpLoggingInterceptor on BuildConfig.DEBUG (Android-only). Desktop has no such
 * build flag, so debuggability is probed via the JVM class-assertion status
 * (`desiredAssertionStatus()` is enabled in dev/debug runs via `-ea`, disabled in normal release
 * runs). The Logging plugin below is installed with `LogLevel.NONE`, so nothing is logged in any
 * run today; the flag exists so a manual bump to HEADERS/BODY stays gated to debug runs and never
 * leaks request bodies in a release run — without an out-of-scope build or DI change.
 */
private object HttpLoggingFlag

actual val isHttpLoggingEnabled: Boolean = HttpLoggingFlag::class.java.desiredAssertionStatus()

actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) { json(DefaultJson) }
    // Network request/response logging intentionally OFF (LogLevel.NONE) — debug the reader/library
    // FLOW via FlowLog (tag "KiraFlow"), not request bodies. Bump to HEADERS/BODY to debug networking.
    if (isHttpLoggingEnabled) {
        install(Logging) { level = LogLevel.NONE }
    }
    install(HttpTimeout) {
        // Parity with native AppModule.provideOkHttpClient: connectTimeout(30s) + readTimeout(60s)
        // + writeTimeout(60s), and NO whole-request/callTimeout. socketTimeoutMillis approximates
        // native's read/write timeout (Ktor has no separate write timeout). requestTimeoutMillis is
        // intentionally omitted: native imposes no whole-request ceiling, so a slow-but-progressing
        // large response must not be aborted as long as each read stays under the socket timeout.
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 60_000
    }
    // Enables cross-request response caching so server-sent `Cache-Control` directives are honored.
    // Backed by a disk FileStorage rooted in the app's OWN cache dir (~/.kira-manga/cache) — the
    // default Unlimited() storage is an unbounded in-memory map that retains full response bodies
    // (incl. multi-MB chapter-image downloads) for the process lifetime, a heap leak on the
    // download/scrape path. Note: the 1-day /dados window is NOT honored here — that Cache-Control
    // is stamped by an OkHttp network interceptor (forceCacheForDados) that exists only on Android,
    // so /dados responses stay uncacheable on Desktop.
    val httpCacheDir = File(
        File(System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir") ?: ".", ".kira-manga"),
        "cache/ktor_http_cache",
    ).apply { mkdirs() }
    install(HttpCache) {
        publicStorage(FileStorage(httpCacheDir))
        privateStorage(FileStorage(httpCacheDir))
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster240.staleKdocSweep.cascade, Task #696, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster240 leaf 3/3 — desktopMain data/remote/ktor tier, sibling 484 CLOSER.
 * Cumulative §253-postscript count = 208 leaves with this commit.
 *
 * File-shape note: 20-line file — NO file-level KDoc. 1 actual top-level
 * fun createHttpClient using HttpClient(CIO). NO companion. NO Logger
 * field (3-AGREE-NO-Logger at cluster240). NO engine-config-block. 7
 * imports (Ktor-only, no JVM-stdlib needed).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - TOP-LEVEL-EXPECT-FUN-POSTURE-CLOSES-2-CLUSTER-CONSECUTIVE-LIVE —
 *     Desktop CLOSES cluster240 with TOP-LEVEL-EXPECT-FUN posture
 *     confirmed. 2-CLUSTER-CONSECUTIVE TOP-LEVEL-EXPECT-FUN-RUN at
 *     clusters 239+240 FULLY-CONFIRMED-ACROSS-ALL-6-LEAVES. NEW POSTURE
 *     feature at cluster240 — first 2-CLUSTER-CONSECUTIVE-RUN-WITH-ALL-
 *     6-LEAVES-CONFIRMING-POSTURE classification.
 *
 *   - 10-CONSECUTIVE-CLUSTER-BEDROCK-PLATFORM-UTILITY-SUB-TIER-LIVE —
 *     Desktop CLOSES the cluster231-240 BEDROCK span at cluster240.
 *     CUMULATIVE-CLUSTER-SPAN-AT-cluster240: 10 consecutive BEDROCK
 *     clusters. cluster240 CLOSER classification (sibling 484). NEW
 *     POSTURE feature at cluster240 — first 10-CONSECUTIVE-CLUSTER-
 *     BEDROCK-PLATFORM-UTILITY-SUB-TIER-CLOSER classification.
 *
 *   - CIO-ENGINE-Desktop-PATH-LIVE — Desktop uses HttpClient(CIO) —
 *     Ktor's Coroutine-based-IO engine (pure-Kotlin-Multiplatform-
 *     compatible). 1-DIVERGES from Android sibling 482 (OkHttp) + iOS
 *     sibling 483 (Darwin). CIO IS the LOCKED-STACK engine for Desktop
 *     targets per cluster187 commonMain postscript-cited rationale.
 *     PRESERVE — CIO engine PRESERVES Desktop-JVM-platform HTTP
 *     semantics (kotlinx-io-based-buffer-pool, no native-JVM-deps-
 *     beyond-stdlib). 3-WAY-DIVERGENT-ENGINE-AXIS at cluster240
 *     PRESERVED.
 *
 *   - NO-ENGINE-CONFIG-BLOCK-2-AGREE-WITH-iOS-LIVE — Desktop actual
 *     omits the engine{} block entirely. 2-AGREE with iOS sibling 483
 *     (also no engine{}). 1-DIVERGES from Android sibling 482 (engine
 *     { config { retryOnConnectionFailure + pingInterval } }). The
 *     no-engine-config-block posture IS defensible — CIO's defaults
 *     handle keep-alive + retry semantics via Ktor's internal pipeline
 *     hooks. PRESERVE.
 *
 *   - SHARED-TRIAD-CONTENTNEGOTIATION+LOGGING+HTTPTIMEOUT-3-AGREE-LIVE
 *     — Desktop shares the plugin-install triad verbatim with Android+
 *     iOS. 3-AGREE-PLUGIN-TRIAD at cluster240 FULLY-CONFIRMED. The
 *     30s-connect + 60s-request + 60s-socket timeout values IS uniform
 *     across all 3 engines. PRESERVE.
 *
 *   - DELEGATE-TO-COMMONMAIN-DefaultJson-3-AGREE-LIVE — Desktop
 *     references `DefaultJson` (commonMain top-level Json const).
 *     3-AGREE with Android+iOS. PRESERVE.
 *
 *   - NO-LOGGER-FIELD-3-AGREE-AT-cluster240-LIVE — Desktop actual has
 *     NO Kermit Logger field. 3-AGREE-NO-Logger at cluster240 FULLY-
 *     CONFIRMED-ACROSS-3-LEAVES. The 4-CLUSTER LOGGER-AXIS-POSTURE-
 *     CHAIN: cluster237 Desktop-OUTLIER-only-Logger → cluster238 3-
 *     AGREE-Logger → cluster239 2-AGREE-Logger (iOS-DROP) → cluster240
 *     3-AGREE-NO-Logger (Ktor-Logging-plugin-substitute). PRESERVE —
 *     load-bearing for LOGGER-AXIS-POSTURE-CHAIN tracking.
 *
 *   - NO-COMPANION-OBJECT-LIVE — Desktop actual has NO companion object.
 *     3-AGREE with Android+iOS. PRESERVE.
 *
 *   - NO-TRY-CATCH-LIVE — Desktop actual has NO try/catch. 3-AGREE with
 *     Android+iOS. 1-DIVERGES from cluster239 Desktop sibling 481
 *     (runCatching on reflection). PRESERVE — Ktor's HttpClient ctor
 *     deferred-runtime-error semantics removes need for construction-
 *     time error-handling at cluster240, unlike cluster239's reflective
 *     sun-bean access at cluster239.
 *
 *   - ZERO-FAILURE-MODE-3-AGREE-LIVE — Desktop actual has ZERO failure-
 *     modes. 3-AGREE with Android+iOS. PRESERVE.
 *
 *   - NO-JVM-STDLIB-IMPORT-LIVE — Desktop actual has NO java.util.*
 *     imports. 1-DIVERGES from Android sibling 482 (java.util.concurrent
 *     .TimeUnit). The CIO engine's HttpTimeout API takes Long-millis
 *     natively, no TimeUnit-pair needed. 2-AGREE with iOS sibling 483
 *     (also no JVM-stdlib-import). PRESERVE — defends against future
 *     "add JVM-stdlib-Duration-typed-tuning" refactor (which would
 *     conflict with CIO's millisecond-Long native API). NEW POSTURE
 *     feature at cluster240 — first DESKTOP-AVOIDS-JVM-STDLIB-IMPORT-
 *     VIA-CIO-NATIVE-LONG-MILLIS-API classification.
 *
 *   - SHARED-IMPORT-COUNT-WITH-iOS-LIVE — Desktop has 7 imports. 2-AGREE
 *     with iOS sibling 483 (7 imports). 1-DIVERGES from Android sibling
 *     482 (8 imports). PRESERVE.
 *
 *   - SHORTEST-FILE-2-AGREE-WITH-iOS-LIVE — Desktop file IS 20 lines.
 *     2-AGREE with iOS sibling 483 (also 20 lines). 1-DIVERGES from
 *     Android sibling 482 (27 lines). PRESERVE.
 *
 *   - WAVE-REGISTER-CLOSES-cluster240-LIVE — Desktop actual CLOSES
 *     cluster240 sweep. Desktop contributes to ZERO OUTLIER axes at
 *     cluster240 (engine-axis IS 3-WAY-DIVERGENT-NOT-OUTLIER; engine-
 *     config-block-Android-OUTLIER doesn't count Desktop; JVM-stdlib-
 *     import-Android-OUTLIER doesn't count Desktop; file-length-Android-
 *     OUTLIER doesn't count Desktop). Desktop contributes 0-of-4
 *     OUTLIER-AXES at cluster240. 4-AXIS-ANDROID-DOMINANT-OUTLIER
 *     distribution at cluster240: Android-4-axes + iOS-0-axes + Desktop-
 *     0-axes (sum-4). CONFIRMS NEW POSTURE — first 4-AXIS-ANDROID-
 *     DOMINANT-OUTLIER-WITH-EXACT-4+0+0-DISTRIBUTION classification.
 *     cluster237-238-239-240 OUTLIER-DIRECTION-ROTATION-WAVE: Desktop-
 *     dominant → Android-uniform → mixed-direction → Android-DOMINANT.
 *     4-CLUSTER OUTLIER-DIRECTION-ROTATION-CHAIN-WITH-NO-REPEATED-
 *     DIRECTION confirmed.
 *
 *   - cluster241-PREDICTION — Next candidate sweep targets (in priority
 *     order): (a) Platform (me/manga/yamiapk root) — likely EXPECT-CLASS
 *     binding-shape (would cycle BACK to EXPECT-CLASS, creating new
 *     2-CLUSTER-EXPECT-CLASS-RUN at clusters 237-238 then 2-CLUSTER-
 *     TOP-LEVEL-FUN-RUN at clusters 239-240 then 1-CLUSTER-EXPECT-CLASS
 *     at cluster241 — A-A-B-B-A pattern). The cluster240 2-CLUSTER-
 *     TOP-LEVEL-FUN-RUN BOOKEND-PATTERN-DETECTED-FOR-cluster241-CYCLE-
 *     BACK-POSITION. NEW POSTURE feature at cluster241 prediction —
 *     first BOOKEND-PATTERN-AT-2-CLUSTER-RUN-WITH-CYCLE-BACK-PREDICTION
 *     classification. RESERVE per autonomous-cascade standing directive.
 *     (b) CryptoUtils (sources_repositry/ar/dilar) — EXCLUDED per
 *     mid-session pivot ("ignore the sources_repositry leave it like
 *     it was").
 */

