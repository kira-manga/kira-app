package me.manga.kira.data.remote.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * Debug/release signal for HTTP logging on iOS.
 *
 * Native gates the HttpLoggingInterceptor on BuildConfig.DEBUG (Android-only). The Kotlin/Native
 * equivalent is `Platform.isDebugBinary` (true for debug binaries, false for release), which keeps
 * the leak-sensitive BODY logging OFF in release builds, matching native's release-off posture.
 */
@OptIn(ExperimentalNativeApi::class)
actual val isHttpLoggingEnabled: Boolean = Platform.isDebugBinary

actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
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
    // Backed by a bounded [BoundedCacheStorage] instead of the default unbounded in-memory
    // CacheStorage.Unlimited(): Ktor 3.4 ships no disk-backed FileStorage on Kotlin/Native, and an
    // unbounded store retains every cacheable body (incl. multi-MB chapter images) for the process
    // lifetime — which lets jetsam kill the app under memory pressure mid-download. The bounded
    // storage caps retained entries with LRU eviction so the cache footprint stays finite. Note:
    // the 1-day /dados window is NOT honored here — that Cache-Control is stamped by an OkHttp
    // network interceptor (forceCacheForDados) that exists only on Android, so /dados responses
    // stay uncacheable on iOS.
    install(HttpCache) {
        publicStorage(BoundedCacheStorage())
        privateStorage(BoundedCacheStorage())
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster240.staleKdocSweep.cascade, Task #696, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster240 leaf 2/3 — iosMain data/remote/ktor tier, sibling 483.
 * Cumulative §253-postscript count = 207 leaves with this commit.
 *
 * File-shape note: 20-line file — NO file-level KDoc. 1 actual top-level
 * fun createHttpClient using HttpClient(Darwin). NO companion. NO Logger
 * field (3-AGREE-NO-Logger at cluster240). NO engine-config-block. 7
 * imports (Ktor-only, no Foundation needed).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - TOP-LEVEL-EXPECT-FUN-POSTURE-CONTINUES-2-CLUSTER-CONSECUTIVE-LIVE —
 *     iOS confirms cluster240 CONTINUES TOP-LEVEL-EXPECT-FUN posture.
 *     2-CLUSTER-CONSECUTIVE TOP-LEVEL-EXPECT-FUN-RUN at clusters 239+240.
 *     PRESERVE.
 *
 *   - 10-CONSECUTIVE-CLUSTER-BEDROCK-PLATFORM-UTILITY-SUB-TIER-LIVE —
 *     iOS leaf continues the cluster231-240 BEDROCK span. PRESERVE.
 *
 *   - DARWIN-ENGINE-iOS-PATH-LIVE — iOS uses HttpClient(Darwin) — Ktor's
 *     iOS engine wrapping NSURLSession. 1-DIVERGES from Android sibling
 *     482 (OkHttp) + Desktop sibling 484 (CIO). Darwin IS the LOCKED-
 *     STACK engine for iOS targets per cluster187 commonMain postscript-
 *     cited rationale. PRESERVE — Darwin engine PRESERVES iOS-platform-
 *     native HTTP semantics (auto-cookie-store, system-proxy-respect,
 *     keychain-cert-pinning hooks). 3-WAY-DIVERGENT-ENGINE-AXIS at
 *     cluster240 PRESERVED.
 *
 *   - NO-ENGINE-CONFIG-BLOCK-2-AGREE-WITH-DESKTOP-LIVE — iOS actual
 *     omits the engine{} block entirely. 2-AGREE with Desktop sibling
 *     484 (also no engine{}). 1-DIVERGES from Android sibling 482
 *     (engine { config { retryOnConnectionFailure + pingInterval } }).
 *     The no-engine-config-block posture IS defensible — Darwin's
 *     NSURLSession defaults handle keep-alive + retry semantics natively
 *     without explicit config. PRESERVE — defends against future "add
 *     symmetric engine{} block for iOS+Desktop" refactor (which would
 *     conflict with NSURLSession's session-shared-config semantics).
 *
 *   - SHARED-TRIAD-CONTENTNEGOTIATION+LOGGING+HTTPTIMEOUT-3-AGREE-LIVE
 *     — iOS shares the plugin-install triad verbatim with Android+
 *     Desktop. 3-AGREE-PLUGIN-TRIAD at cluster240 confirmed. PRESERVE.
 *
 *   - DELEGATE-TO-COMMONMAIN-DefaultJson-3-AGREE-LIVE — iOS references
 *     `DefaultJson` (commonMain top-level Json const). 3-AGREE with
 *     Android+Desktop. PRESERVE.
 *
 *   - NO-LOGGER-FIELD-3-AGREE-AT-cluster240-LIVE — iOS actual has NO
 *     Kermit Logger field. 3-AGREE with Android+Desktop. LOGGER-AXIS-
 *     POSTURE-DROP-TO-3-AGREE-NO-LOGGER at cluster240 confirmed. The
 *     4-CLUSTER LOGGER-AXIS-POSTURE-CHAIN preserved.
 *
 *   - NO-COMPANION-OBJECT-LIVE — iOS actual has NO companion object.
 *     3-AGREE with Android+Desktop. PRESERVE.
 *
 *   - NO-TRY-CATCH-LIVE — iOS actual has NO try/catch. 3-AGREE with
 *     Android+Desktop. PRESERVE.
 *
 *   - ZERO-FAILURE-MODE-3-AGREE-LIVE — iOS actual has ZERO failure-modes.
 *     3-AGREE with Android+Desktop. PRESERVE.
 *
 *   - NO-FOUNDATION-IMPORT-LIVE — iOS actual has NO platform.Foundation
 *     imports. 1-DIVERGES from cluster239 iOS sibling 480 (NSProcessInfo
 *     required Foundation import). The Darwin Ktor engine HIDES the
 *     underlying NSURLSession behind a Ktor-package API surface — no
 *     direct Foundation reach needed. PRESERVE — defends against future
 *     "add NSURLSessionConfiguration tuning" refactor (which would pull
 *     a Foundation import). NEW POSTURE feature at cluster240 — first
 *     iOS-ACTUAL-WITH-ZERO-FOUNDATION-IMPORTS-VIA-KTOR-ENGINE-FACADE
 *     classification.
 *
 *   - SHARED-IMPORT-COUNT-WITH-DESKTOP-LIVE — iOS has 7 imports. 2-AGREE
 *     with Desktop sibling 484 (7 imports). 1-DIVERGES from Android
 *     sibling 482 (8 imports — TimeUnit). 2-DISTINCT-COUNT-IMPORT-AXIS
 *     at cluster240: 7=7 vs 8. PRESERVE.
 *
 *   - SHORTEST-FILE-2-AGREE-WITH-DESKTOP-LIVE — iOS file IS 20 lines.
 *     2-AGREE with Desktop sibling 484 (also 20 lines). 1-DIVERGES from
 *     Android sibling 482 (27 lines). 2-DISTINCT-COUNT-FILE-LENGTH-AXIS
 *     at cluster240: 20=20 vs 27. PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster240-LIVE — iOS actual IS leaf 2/3
 *     of cluster240 4-AXIS-ANDROID-DOMINANT-OUTLIER fan. iOS contributes
 *     to ZERO OUTLIER axes at cluster240 (engine-axis IS 3-WAY-
 *     DIVERGENT-NOT-OUTLIER; engine-config-block-Android-OUTLIER doesn't
 *     count iOS; JVM-stdlib-import-Android-OUTLIER doesn't count iOS;
 *     file-length-Android-OUTLIER doesn't count iOS). iOS contributes
 *     0-of-4 OUTLIER-AXES at cluster240. PRESERVE — confirms 4-AXIS-
 *     ANDROID-DOMINANT-OUTLIER-WITH-iOS-AND-DESKTOP-AT-ZERO-CONTRIBUTION
 *     posture.
 */

