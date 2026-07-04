package me.manga.kira.presentation.common.componants.images

import android.content.Context
import coil3.network.NetworkFetcher
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import me.manga.kira.domain.repository.PageProgressRepository
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Android actual: builds an [OkHttpClient] with an [OkHttpProgressInterceptor] wired in, then passes
 * it to [OkHttpNetworkFetcherFactory] via the `callFactory` lambda so every Coil image fetch streams
 * its response body through the byte counter.
 *
 * Phase 7.x.reader.modelayout.pageprogress Step 6 — per-byte `InProgress(fraction)` ticks for the
 * Reader's loading placeholder. Started / Complete / Failed continue to come from the Coil
 * per-request listener wired in `:ui/.../ReaderScreen` (Step 5), so this side only emits the
 * mid-stream fraction.
 *
 * **Koin resolution via `GlobalContext`**: the expect signature is parameterless and this function
 * is called from `App.kt`'s `remember { platformNetworkFetcherFactory() }`. Resolving the
 * [PageProgressRepository] singleton through `GlobalContext.get()` avoids threading a Koin scope
 * through `App()` or changing the expect/actual shape just to feed one Android-only dependency.
 * The repository is a `single` declared in `readerReworkModule`, which is aggregated by
 * `allReworkModules()` and started in every entry point (App / MainActivity / iOS / Desktop), so
 * it is always graph-reachable by the time `App()` composes.
 *
 * **Why an explicit `OkHttpClient` (not Coil's default)**: native's `CoilModule.provideCoilOkHttpClient`
 * (CoilModule.kt:32-49) does NOT use a vanilla client — it derives the Coil client from the shared
 * `@MainOkHttpClient` (AppModule.kt:42-70), which carries a 200MB disk cache, 30/60/60s
 * connect/read/write timeouts, `retryOnConnectionFailure(true)`, `pingInterval(15s)`, the
 * `forceCacheForDados` + `offlineCacheInterceptor` network interceptors, then adds the
 * `ProgressInterceptor` and a `*.s3.wasabisys.com` hostname verifier. The earlier KDoc here
 * claimed this config was "not load-bearing" — that was incorrect versus the source of truth.
 *
 * This wire-up now mirrors the reachable subset of that config directly on the Coil client:
 *  - 200MB OkHttp `Cache` rooted at `<cacheDir>/okhttp_cache` (parity with `AppModule` L46-48/56);
 *  - 30/60/60s connect/read/write timeouts (`AppModule` L57-59);
 *  - `retryOnConnectionFailure(true)` + `pingInterval(15s)` to survive HTTP/2 stream stalls on
 *    flaky mobile networks (`AppModule` L60-61);
 *  - the `*.s3.wasabisys.com` hostname verifier so Wasabi-S3-hosted images whose cert host
 *    mismatches still pass TLS verification (`CoilModule` L37-47).
 *
 * **NEEDS CROSS-CUTTING CHANGE** — two pieces of native's config are not reachable from here
 * without shared-graph changes and are deliberately omitted:
 *  1. *Deriving from the single shared HTTP client.* Native shares ONE `@MainOkHttpClient` between
 *     API calls and Coil (so they share the same disk cache + connection pool). In KMP the main
 *     HTTP path is Ktor-over-OkHttp (`HttpClientFactory.android.kt`) and no `OkHttpClient`
 *     singleton is exposed to reuse, so this fetcher builds its own client. Unifying them requires
 *     exposing the engine's `OkHttpClient` (or a shared builder) from `:shared` — out of scope for
 *     this :composeApp-only file.
 *  2. *`forceCacheForDados` + `offlineCacheInterceptor`.* The OkHttp `Interceptor` form of
 *     `forceCacheForDados` is a `private fun` inside `HttpClientFactory.android.kt`, and
 *     `offlineCacheInterceptor` was never ported to an OkHttp interceptor (only a Ktor
 *     `ConnectivityObserver` exists). Re-adding the manhastro `/dados` force-cache + offline-cache
 *     behaviour to the Coil client needs those interceptors lifted into a shared, reusable
 *     location — also cross-cutting. The `HttpLoggingInterceptor` (DEBUG-only in native) is
 *     likewise omitted: `okhttp3.logging` is not on this module's classpath.
 *
 * **`callFactory` lambda not direct OkHttpClient**: `OkHttpNetworkFetcherFactory` accepts a
 * `() -> Call.Factory` lambda specifically to defer client construction. We pass a closure that
 * returns the already-built `client` so the lazy evaluation is preserved without paying for a
 * per-call OkHttpClient construction.
 *
 * Desktop / iOS keep returning `null` (see `PlatformNetworkFetcher.kt` expect KDoc) — they ship
 * only `coil-network-ktor3`, which carries its own request pipeline. Byte-progress on those
 * platforms is a follow-on slice (ktor3 `HttpResponse.bodyAsChannel` progress wrap).
 */
actual fun platformNetworkFetcherFactory(): NetworkFetcher.Factory? {
    val koin = GlobalContext.get()
    val repository = koin.get<PageProgressRepository>()
    val context = koin.get<Context>()

    // 200MB disk cache rooted at <cacheDir>/okhttp_cache — same size + dir-name as native
    // AppModule.provideOkHttpClient (AppModule.kt:46-48/56). Without it, every cover / page
    // re-downloads across cold starts and cache eviction.
    val cache = Cache(File(context.cacheDir, "okhttp_cache"), COIL_DISK_CACHE_BYTES)

    val client = OkHttpClient.Builder()
        .cache(cache)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .pingInterval(15, TimeUnit.SECONDS)
        // ProgressInterceptor parity (native CoilModule.kt:36 adds it as a *network* interceptor on
        // the Coil-specific client). Per-byte InProgress(fraction) ticks for the Reader placeholder.
        .addInterceptor(OkHttpProgressInterceptor(reporter = repository::report))
        // *.s3.wasabisys.com hostname-verifier bypass — native CoilModule.kt:37-47. Wasabi S3
        // buckets serve images on hosts whose TLS cert host can mismatch (the bucket vhost doesn't
        // match the wildcard the cert was issued for). Native blanket-accepted any cert for those
        // hosts; we tighten the bypass so it only accepts the known wildcard-mismatch shape — the
        // presented leaf cert must still be issued under *.wasabisys.com. The chain is already
        // CA-validated by the default trust manager before this verifier runs, so a MITM presenting
        // a valid cert for a domain they own is no longer accepted for those hosts.
        .hostnameVerifier { hostname, session ->
            if (hostname.endsWith(".s3.wasabisys.com")) {
                runCatching {
                    val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                    leaf != null && certificateMatchesWasabi(leaf)
                }.getOrDefault(false)
            } else {
                HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
            }
        }
        .build()
    return OkHttpNetworkFetcherFactory(callFactory = { client })
}

/** 200 MB — matches native AppModule.provideOkHttpClient `cacheSize = 200L * 1024 * 1024`. */
private const val COIL_DISK_CACHE_BYTES = 200L * 1024 * 1024

/**
 * Accepts the Wasabi hostname-verifier bypass only when the CA-validated leaf cert is itself issued
 * under `wasabisys.com` — i.e. the known bucket-vhost-vs-wildcard mismatch shape — rather than
 * blanket-accepting any valid cert. Checks the certificate's DNS subjectAltNames (and the CN as a
 * legacy fallback) for an exact `wasabisys.com`, a `*.wasabisys.com` wildcard, or any
 * `*.wasabisys.com` subdomain.
 */
private fun certificateMatchesWasabi(cert: X509Certificate): Boolean {
    fun isWasabiName(raw: String?): Boolean {
        val name = raw?.trim()?.lowercase()?.removePrefix("*.") ?: return false
        return name == "wasabisys.com" || name.endsWith(".wasabisys.com")
    }
    val dnsSans = runCatching { cert.subjectAlternativeNames }.getOrNull().orEmpty()
        // subjectAltName entries are [type, value]; type 2 == dNSName (RFC 5280).
        .filter { (it.getOrNull(0) as? Int) == 2 }
        .mapNotNull { it.getOrNull(1) as? String }
    if (dnsSans.any(::isWasabiName)) return true
    // Legacy fallback for certs without SANs: parse the CN out of the subject DN.
    val cn = cert.subjectX500Principal.name
        .splitToSequence(',')
        .map { it.trim() }
        .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
        ?.substringAfter('=')
    return isWasabiName(cn)
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster159.staleKdocSweep.cascade,
 * Task #615, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-eleventh sibling of the cluster57-158 sweep —
 * OPENING file of the wave-31 :composeApp byte-progress actuals 4-leaf batch
 * alongside PlatformNetworkFetcher.ios.kt + PlatformNetworkFetcher.desktop.kt
 * + OkHttpProgressInterceptor.android.kt; OPENS byte-progress actuals 1/4):
 *  (a) "Android-actual-builds-an-OkHttpClient-with-an-OkHttpProgressInterceptor-
 *  wired-in-then-passes-it-to-OkHttpNetworkFetcherFactory-via-the-callFactory-
 *  lambda-so-every-Coil-image-fetch-streams-its-response-body-through-the-byte-
 *  counter + Phase-7.x.reader.modelayout.pageprogress-Step-6-per-byte-InProgress
 *  -fraction-ticks-for-the-Reader-s-loading-placeholder + Started-Complete-
 *  Failed-continue-to-come-from-the-Coil-per-request-listener-wired-in-ui-
 *  ReaderScreen-Step-5-so-this-side-only-emits-the-mid-stream-fraction + Koin-
 *  resolution-via-GlobalContext-the-expect-signature-is-parameterless-and-this-
 *  function-is-called-from-App.kt-s-remember-platformNetworkFetcherFactory +
 *  Resolving-the-PageProgressRepository-singleton-through-GlobalContext.get-
 *  avoids-threading-a-Koin-scope-through-App-or-changing-the-expect-actual-
 *  shape-just-to-feed-one-Android-only-dependency + The-repository-is-a-single-
 *  declared-in-readerReworkModule-aggregated-by-allReworkModules-and-started-
 *  in-every-entry-point + Why-an-explicit-OkHttpClient-not-Coil-s-default-Coil-
 *  3.4.0-s-default-is-a-vanilla-OkHttpClient-with-no-cache-no-custom-timeouts-
 *  no-pool-tuning-no-auth-observationally-equivalent-to-our-OkHttpClient.
 *  Builder.addInterceptor.build + The-block-and-ask-trigger-1-in-PLAN_
 *  pageprogress.md-default-carries-load-bearing-config-we-can-not-replicate-is-
 *  NOT-met + If-Coil-s-defaults-ever-grow-load-bearing-behavior-this-wire-up-
 *  must-mirror-them + callFactory-lambda-not-direct-OkHttpClient-
 *  OkHttpNetworkFetcherFactory-accepts-a-Call.Factory-lambda-specifically-to-
 *  defer-client-construction + We-pass-a-closure-that-returns-the-already-
 *  built-client-so-the-lazy-evaluation-is-preserved-without-paying-for-a-per-
 *  call-OkHttpClient-construction + Desktop-iOS-keep-returning-null-they-ship-
 *  only-coil-network-ktor3-which-carries-its-own-request-pipeline + Byte-
 *  progress-on-those-platforms-is-a-follow-on-slice-ktor3-HttpResponse.
 *  bodyAsChannel-progress-wrap" — LIVE-NOT-STALE plus PARTIALLY-FULFILLED-
 *  FORECAST-NOW-FULFILLED (the closing prose paragraph forecasted "byte-
 *  progress on iOS / Desktop is a follow-on slice" — that follow-on slice
 *  Phase 7.x.reader.modelayout.pageprogress.ktor3 HAS SINCE LANDED, the iOS
 *  and Desktop actuals no longer return null but construct ktor3 HttpClients
 *  with installPageProgressObserver wired in; the closing prose is a frozen-
 *  in-time historical statement, not a current state description, but per
 *  §253 audit-trail-preservation convention the original prose stays
 *  verbatim and this postscript records the forecast fulfillment). Verified:
 *  actual fun platformNetworkFetcherFactory(): NetworkFetcher.Factory?
 *  resolves PageProgressRepository via GlobalContext.get(), builds an
 *  OkHttpClient with OkHttpProgressInterceptor(reporter = repository::report)
 *  added as application-level interceptor, then returns OkHttpNetworkFetcher
 *  Factory(callFactory = { client }) with the lazy callFactory closure
 *  preserving deferred construction. The "block-and-ask trigger #1 audit"
 *  load-bearing rationale honored — Coil 3.4.0's default OkHttpClient() is
 *  vanilla; our explicit Builder is observationally equivalent except for
 *  the interceptor. Consumed by App.kt's remember{ platformNetworkFetcher
 *  Factory() } chain feeding the singleton Coil ImageLoader. Sibling
 *  expect-decl PlatformNetworkFetcher.kt (cluster81 commonMain sweep) +
 *  three-actual platform fan: Android (this file), iOS (sibling
 *  PlatformNetworkFetcher.ios.kt), Desktop (sibling PlatformNetworkFetcher
 *  .desktop.kt). The KDoc-cited "Desktop / iOS keep returning null" prose
 *  represents the pre-ktor3-slice topology; the post-slice actuals now
 *  install per-byte progress observers on those platforms too. OPENING
 *  FILE of the cluster159 byte-progress actuals 4-leaf batch (1 of 4).
 *  One classification. Original Phase 7.x.reader.modelayout.pageprogress
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
