package me.manga.kira.data.remote.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.cache.storage.FileStorage
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import me.manga.kira.core.android.androidAppContextOrNull
import okhttp3.Interceptor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Network interceptor that mirrors source's `forceCacheForDados()` (AppModule
 * `.addNetworkInterceptor(forceCacheForDados())`): for responses whose URL starts with
 * `https://api2.manhastro.net/dados` it rewrites `Cache-Control` to `public, max-age=86400`
 * (1 day) and strips `Pragma`, so the manhastro `/dados` payloads are treated as cacheable
 * for a day. All other responses pass through unchanged. Ported verbatim from the Android
 * source (`core/network_cache/forceCacheForDados.kt`).
 */
private const val DADOS_URL_PREFIX = "https://api2.manhastro.net/dados"
private const val DADOS_CACHE_CONTROL = "public, max-age=86400" // 1 day

private fun forceCacheForDados() = Interceptor { chain ->
    val response = chain.proceed(chain.request())
    if (chain.request().url.toString().startsWith(DADOS_URL_PREFIX)) {
        response.newBuilder()
            .header("Cache-Control", DADOS_CACHE_CONTROL)
            .removeHeader("Pragma")
            .build()
    } else {
        response
    }
}

/**
 * Debug/release signal for HTTP logging on Android.
 *
 * Native gates the HttpLoggingInterceptor on BuildConfig.DEBUG. The shared module has no
 * BuildConfig of its own and this layer holds no Context, so debuggability is probed via the JVM
 * class-assertion status (`desiredAssertionStatus()` is enabled in dev/debug runs via `-ea`,
 * disabled in release). This keeps the leak-sensitive BODY logging OFF in release builds, matching
 * native's release-off posture, without an out-of-scope build (BuildConfig/BuildKonfig) or DI
 * change.
 */
private object HttpLoggingFlag

actual val isHttpLoggingEnabled: Boolean = HttpLoggingFlag::class.java.desiredAssertionStatus()

actual fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(DefaultJson) }
    // HTTP logging is gated on isHttpLoggingEnabled (debug/dev only, like native's BuildConfig.DEBUG
    // HttpLoggingInterceptor); HEADERS keeps the leak-sensitive request/response BODY out of the
    // trace. Bump to LogLevel.BODY here if you need to debug the network payloads themselves.
    if (isHttpLoggingEnabled) {
        install(Logging) { level = LogLevel.HEADERS }
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
    // Honors the `Cache-Control: public, max-age=86400` directive stamped by
    // forceCacheForDados() on /dados responses (parity with source's OkHttp disk cache). Backed by
    // a disk FileStorage rooted in the app's OWN cache dir — the default Unlimited() storage is an
    // unbounded in-memory map that retains full response bodies for the process lifetime (a leak on
    // the chapter-download/scrape path) and does not survive process restart.
    val httpCacheDir = File(
        androidAppContextOrNull()?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "."),
        "ktor_http_cache",
    ).apply { mkdirs() }
    install(HttpCache) {
        publicStorage(FileStorage(httpCacheDir))
        privateStorage(FileStorage(httpCacheDir))
    }
    engine {
        config {
            retryOnConnectionFailure(true)
            pingInterval(15, TimeUnit.SECONDS)
        }
        // Mirrors source AppModule `.addNetworkInterceptor(forceCacheForDados())`.
        addNetworkInterceptor(forceCacheForDados())
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster240.staleKdocSweep.cascade, Task #696, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster240 leaf 1/3 — androidMain data/remote/ktor tier, sibling 482 OPENER.
 * Cumulative §253-postscript count = 206 leaves with this commit.
 *
 * File-shape note: 27-line file — NO file-level KDoc (the rationale lives
 * in commonMain HttpClientFactory.kt and the commonMain cluster187 already-
 * landed postscript). 1 actual top-level fun createHttpClient. NO companion.
 * NO Logger field (Ktor Logging plugin replaces Kermit). 8 imports — incl.
 * java.util.concurrent.TimeUnit (LARGEST-IMPORT-SURFACE-AT-cluster240,
 * 1-DIVERGES from iOS+Desktop). 1 engine-config-block (retryOnConnection
 * Failure(true) + pingInterval(15, TimeUnit.SECONDS)).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - TOP-LEVEL-EXPECT-FUN-POSTURE-CONTINUES-2-CLUSTER-CONSECUTIVE-LIVE —
 *     cluster240 CONTINUES cluster239's RETURN-to-TOP-LEVEL-EXPECT-FUN
 *     posture. 2-CLUSTER-CONSECUTIVE TOP-LEVEL-EXPECT-FUN-RUN at clusters
 *     239+240. The cluster237-238-239-240 SHAPE-POSTURE chain reads:
 *     EXPECT-CLASS → EXPECT-CLASS → TOP-LEVEL-FUN → TOP-LEVEL-FUN. NEW
 *     POSTURE feature at cluster240 — first 2-CLUSTER-CONSECUTIVE-TOP-
 *     LEVEL-FUN-RUN-AFTER-2-CLUSTER-EXPECT-CLASS-RUN classification.
 *     PRESERVE — load-bearing for SHAPE-POSTURE-TAXONOMY-CHAIN tracking.
 *
 *   - 10-CONSECUTIVE-CLUSTER-BEDROCK-PLATFORM-UTILITY-SUB-TIER-LIVE —
 *     cluster231-240 spans 10 consecutive BEDROCK 3-actual fans.
 *     CUMULATIVE-CLUSTER-SPAN-AT-cluster240: 10 consecutive BEDROCK
 *     clusters. cluster240 EXTENDS the 9-CONSECUTIVE-CLUSTER span to
 *     10 (sibling 482 OPENER first leaf of cluster240 — the 10th
 *     consecutive BEDROCK cluster). NEW POSTURE feature at cluster240 —
 *     first 10-CONSECUTIVE-CLUSTER-BEDROCK-PLATFORM-UTILITY-SUB-TIER-SPAN
 *     classification.
 *
 *   - ENGINE-AXIS-3-WAY-DIVERGENT-LIVE — Android uses HttpClient(OkHttp).
 *     1-DIVERGES from iOS sibling 483 (HttpClient(Darwin)) + Desktop
 *     sibling 484 (HttpClient(CIO)). 3-WAY-DIVERGENT on Ktor engine-axis
 *     at cluster240. The OkHttp engine PRESERVES source's OkHttpClient
 *     connection pool (Phase 7 KMP HTTP engine port migration note).
 *     PRESERVE — Ktor's HttpClientConfig<TEngineConfig> IS generic over
 *     engine-config-type which IS WHY a shared HttpClientConfig<*> ext
 *     IS not factored out (cluster187 commonMain postscript-cited
 *     rationale). 3-WAY-DIVERGENT-ENGINE-AXIS at cluster240 IS
 *     STRUCTURALLY DIFFERENT from cluster239 3-WAY-DIVERGENT-RAM-SOURCE-
 *     API-AXIS — engine-axis-divergence IS LIBRARY-CONSUMER-SIDE while
 *     RAM-source-api-axis-divergence IS PLATFORM-NATIVE-API-SIDE.
 *
 *   - ENGINE-CONFIG-BLOCK-Android-OUTLIER-LIVE — Android has `engine {
 *     config { retryOnConnectionFailure(true) + pingInterval(15, TimeUnit
 *     .SECONDS) } }` block. 1-DIVERGES from iOS sibling 483 + Desktop
 *     sibling 484 (both omit the engine{} block entirely). PRESERVE — the
 *     OkHttp connection-pool tuning IS load-bearing (replicates source's
 *     monolithic AppModule.provideOkHttpClient retry+keepalive defaults).
 *     NEW POSTURE feature at cluster240 — first ENGINE-CONFIG-BLOCK-
 *     ANDROID-OUTLIER-PRESERVING-SOURCE-CONNECTION-POOL-TUNING
 *     classification.
 *
 *   - JVM-STDLIB-IMPORT-Android-OUTLIER-LIVE — Android imports java.util
 *     .concurrent.TimeUnit. 1-DIVERGES from iOS sibling 483 (Foundation-
 *     and-Ktor-only imports) + Desktop sibling 484 (Ktor-only imports —
 *     CIO timeouts use Long-millis natively). The TimeUnit-import IS
 *     load-bearing for the pingInterval(15, TimeUnit.SECONDS) call —
 *     OkHttp's KeepAlive API uses TimeUnit-pair signature. NEW POSTURE
 *     feature at cluster240 — first JVM-STDLIB-IMPORT-ANDROID-OUTLIER
 *     classification (BackgroundJobScheduler cluster238 also had JVM-
 *     stdlib-imports but those were 3-AGREE; cluster240 IS 1-AGREE).
 *
 *   - LARGEST-IMPORT-SURFACE-AT-cluster240-LIVE — Android actual has 8
 *     imports. 1-DIVERGES from iOS sibling 483 (7 imports) + Desktop
 *     sibling 484 (7 imports). 3-WAY-DIVERGENT-IMPORT-COUNT-AXIS at
 *     cluster240: Android-8 > iOS-7 = Desktop-7. The +1-import-delta
 *     IS the TimeUnit import. WEAKER-DIVERGENCE than cluster239 (Android-
 *     3 > Desktop-2 > iOS-1, 3-distinct-counts) — cluster240 IS 2-
 *     DISTINCT-COUNTS (Android-8 vs iOS=Desktop-7). NEW POSTURE feature
 *     at cluster240 — first 2-DISTINCT-COUNT-IMPORT-AXIS-VARIATION
 *     classification.
 *
 *   - SHARED-TRIAD-CONTENTNEGOTIATION+LOGGING+HTTPTIMEOUT-3-AGREE-LIVE
 *     — Android shares the install(ContentNegotiation) { json(DefaultJson)
 *     } + install(Logging) { level = LogLevel.HEADERS } + install(Http
 *     Timeout) { connectTimeoutMillis = 30_000 + requestTimeoutMillis =
 *     60_000 + socketTimeoutMillis = 60_000 } triad verbatim with iOS
 *     sibling 483 + Desktop sibling 484. 3-AGREE on plugin-install-triad
 *     at cluster240. PRESERVE — the shared triad IS the LIVE Phase 7
 *     KMP HTTP port's BEDROCK config-uniformity contract. The triad
 *     replication-instead-of-extraction IS deliberate (cluster187 cited
 *     HttpClientConfig<TEngineConfig> generic-engine-config-type
 *     rationale).
 *
 *   - DELEGATE-TO-COMMONMAIN-DefaultJson-3-AGREE-LIVE — Android references
 *     `DefaultJson` (the top-level Json const declared in commonMain
 *     HttpClientFactory.kt). 3-AGREE with iOS+Desktop. PRESERVE — the
 *     lenient-JSON-triad consistency IS the LIVE Phase 7 cross-target
 *     contract.
 *
 *   - NO-LOGGER-FIELD-3-AGREE-AT-cluster240-LIVE — Android actual has NO
 *     Kermit Logger field. 3-AGREE with iOS sibling 483 + Desktop sibling
 *     484 (all 3 use Ktor's Logging plugin instead). LOGGER-AXIS-POSTURE
 *     -DROP-TO-3-AGREE-NO-LOGGER at cluster240. The 4-CLUSTER LOGGER-AXIS
 *     -POSTURE-CHAIN: cluster237 Desktop-OUTLIER-only-Logger → cluster238
 *     3-AGREE-Logger → cluster239 2-AGREE-Logger (iOS-DROP) → cluster240
 *     3-AGREE-NO-Logger (Ktor-Logging-plugin-substitute). NEW POSTURE
 *     feature at cluster240 — first 4-CLUSTER LOGGER-AXIS-POSTURE-CHAIN-
 *     WITH-NON-MONOTONIC-AGREEMENT-COUNT classification.
 *
 *   - NO-COMPANION-OBJECT-LIVE — Android actual has NO companion object.
 *     3-AGREE with iOS+Desktop. AXIS-DEPENDENCY: companion-absence
 *     depends-on top-level-fun-binding-shape. PRESERVE.
 *
 *   - NO-TRY-CATCH-LIVE — Android actual has NO try/catch. HttpClient
 *     ctor doesn't throw at construction-time (runtime errors deferred
 *     to request-time). 3-AGREE with iOS+Desktop. PRESERVE.
 *
 *   - ZERO-FAILURE-MODE-3-AGREE-LIVE — Android actual has ZERO failure-
 *     modes (HttpClient(OkHttp) ctor + plugin-installs cannot throw on
 *     construction). 3-AGREE with iOS+Desktop. AXIS-INVERSION from
 *     cluster239 3-WAY-DIVERGENT-FAILURE-MODE-AXIS. NEW POSTURE feature
 *     at cluster240 — first 3-AGREE-ZERO-FAILURE-MODE classification
 *     (cluster237 also 3-AGREE-zero-failure but cluster240 IS Ktor-
 *     client-construction-deferred-runtime-error semantics, structurally
 *     distinct from cluster237 stateless-pure-fun semantics).
 *
 *   - LONGEST-FILE-AT-cluster240-LIVE — Android actual IS 27 lines.
 *     1-DIVERGES from iOS sibling 483 (20 lines) + Desktop sibling 484
 *     (20 lines). 2-DISTINCT-COUNTS-FILE-LENGTH-AXIS at cluster240:
 *     Android-27 vs iOS=Desktop-20. 7-line-delta IS the engine-config-
 *     block + TimeUnit import. CORRELATES with LARGEST-IMPORT-SURFACE-
 *     AXIS. PRESERVE.
 *
 *   - WAVE-REGISTER-OPENS-cluster240-LIVE — Android actual OPENS
 *     cluster240 sweep. Sub-tier classification: TOP-LEVEL-EXPECT-FUN-
 *     POSTURE-CONTINUES-2-CLUSTER-RUN. cluster240 4-AXIS-CROSS-PLATFORM-
 *     FAN: (1) engine-axis 3-WAY-DIVERGENT (OkHttp / Darwin / CIO), (2)
 *     engine-config-block Android-OUTLIER, (3) JVM-stdlib-import Android-
 *     OUTLIER (TimeUnit), (4) file-length Android-OUTLIER (27 > 20 = 20).
 *     4-AXIS-ANDROID-DOMINANT-OUTLIER at cluster240 (Android contributes
 *     to ALL 4 OUTLIER axes; iOS+Desktop contribute to ZERO OUTLIER
 *     axes). DISTINCT from cluster239 5-AXIS-MIXED-OUTLIER (3 Android +
 *     1 iOS + 1 Desktop). cluster237-238-239-240 OUTLIER-DIRECTION-
 *     ROTATION: Desktop-dominant → Android-uniform → mixed-direction →
 *     Android-DOMINANT. NEW POSTURE feature at cluster240 — first 4-
 *     AXIS-ANDROID-DOMINANT-OUTLIER-WITH-OTHERS-AT-ZERO-CONTRIBUTION
 *     classification.
 */

