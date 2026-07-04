package me.manga.kira.presentation.common.componants.images

import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponseContainer
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlin.time.TimeSource
import me.manga.kira.domain.model.reader.PageDownloadProgress

/**
 * Ktor3 client extension that wires a [HttpResponsePipeline.Receive] interceptor into an
 * [HttpClient] to count bytes as they stream through the response body and forward
 * [PageDownloadProgress.InProgress] ticks to a [reporter] callback.
 *
 * Phase 7.x.reader.modelayout.pageprogress.ktor3 — iOS (Darwin) and Desktop (CIO) byte-progress
 * equivalent of the Android [OkHttpProgressInterceptor]. The parent slice landed Android per-byte
 * fraction via OkHttp; iOS / Desktop kept emitting only `Started → Complete / Failed` via the Coil
 * per-request listener. This helper closes that gap on the ktor3 path used by
 * `coil-network-ktor3`.
 *
 * **Strategy choice** — `responsePipeline.intercept(HttpResponsePipeline.Receive)`. Alternatives
 * considered and rejected:
 *  - Per-request `onDownload`: Coil constructs the `HttpRequestBuilder` internally; no hook to
 *    inject a per-request callback.
 *  - `createClientPlugin { onResponse }`: fires after the body is fully read — useful for
 *    "got a response, here's the content" but not for incremental byte counting.
 *
 * The pipeline's Receive phase fires after the engine has produced the HttpResponse but before
 * the body is parsed by downstream phases. We replace the subject's body (a [ByteReadChannel])
 * with a counting bridge channel built via [writer]; the bridge reads bytes from the original
 * channel, counts them, throttles emissions, then writes the same bytes downstream so parsing
 * proceeds normally.
 *
 * **Throttling** — parity with [OkHttpProgressInterceptor]:
 *  - [THROTTLE_MS] (50ms) wall-clock gate using KMP-portable [TimeSource.Monotonic].
 *  - [THROTTLE_TENTHOUSANDTHS] (100 = 1%) fraction-advance gate using Int tenThousandths to dodge
 *    Float equality fuzz.
 *  - Without throttling, the read loop emits a tick per ~8KB buffer fill, drowning the MVI
 *    reducer in updates.
 *
 * **`contentLength` unknown** — when the server omits `Content-Length` or uses chunked encoding
 * without one, the wrap still runs but `fraction` stays `null`. The reporter still receives
 * `InProgress(null)` ticks throttled to one per 50ms — purely as a "bytes are flowing" signal,
 * mirroring Android. The UI placeholder treats `InProgress(null)` as an indeterminate spinner.
 *
 * **Cancellation safety** — the [writer] builder inherits the HttpClientCall's coroutine context.
 * When Coil cancels the call (e.g., page swiped past mid-fetch), cooperative cancellation
 * propagates: the writer coroutine cancels, the wrapped channel closes, and the read loop's
 * suspending `readAvailable` call returns from cancellation. No leaked coroutines, no zombie
 * progress emissions.
 *
 * **Lifecycle bookend reporting** — this helper emits ONLY [PageDownloadProgress.InProgress]
 * ticks. The Coil per-request listener attached at the `:ui` `ImageRequest.Builder` site (from
 * the parent slice's Step 5) handles `Started` / `Complete` / `Failed`. The state machine stays
 * total without this helper duplicating those edges.
 *
 * **Why every response is wrapped** (not just reader pages) — mirroring the Android interceptor:
 *  - The Coil singleton ImageLoader's ktor3 client is shared by every image request in the app
 *    (covers + reader pages). The reporter writes to a `MutableStateFlow<Map>`-backed repository;
 *    URLs the Reader VM never subscribes to leave residual entries (`≤` a few KB, the
 *    repository's `observe(url).filter{ !Idle }` ignores them).
 *  - Selectively wrapping by URL would require a side-channel to identify reader vs cover
 *    requests — premature complexity. The per-request cost of byte counting is dominated by
 *    network IO itself.
 *
 * **DIP** — depends only on ktor3 + `:domain` ([PageDownloadProgress]). The reporter callback
 * shape `(String, PageDownloadProgress) -> Unit` keeps the helper from reaching into `:data`
 * ([me.manga.kira.domain.repository.PageProgressRepository.report]).
 *
 * **SRP** — one rule: "wrap a ktor3 response body so per-byte reads emit progress ticks".
 * Body-length sniffing, throttling, and channel forwarding all serve that one rule.
 *
 * **Thread-safety** — each call gets a fresh writer coroutine with per-call local state
 * (`bytesRead`, `lastTen`, `lastMark`); no cross-call shared mutable state. The reporter
 * callback handed in is expected to be thread-safe (the `MutableStateFlow.update` path in
 * [me.manga.kira.data.repository.PageProgressRepositoryImpl] is atomic).
 *
 * **Block-and-ask trigger #1 audit** — Coil 3.4.0's default ktor3 `HttpClient()` is constructed
 * by `coil-network-ktor3` with no explicit plugins installed (vanilla engine defaults). The
 * load-bearing image-quality fixes for iOS / Desktop (`HighQualitySkiaImageDecoder`,
 * `maxBitmapSize(Size.ORIGINAL)`, `CoilSourceHeaderInterceptor`) live at the `ImageLoader`-
 * component layer, NOT the HTTP-client layer — untouched by this helper. The
 * `CoilSourceHeaderInterceptor` is a Coil `Interceptor`, not a ktor3 plugin; it runs on the
 * Coil interceptor chain ahead of the network fetcher.
 */
internal const val THROTTLE_MS: Long = 50L
internal const val THROTTLE_TENTHOUSANDTHS: Int = 100
private const val BUFFER_SIZE: Int = 8 * 1024

internal fun HttpClient.installPageProgressObserver(
    reporter: (url: String, status: PageDownloadProgress) -> Unit,
) {
    val scope = this
    responsePipeline.intercept(HttpResponsePipeline.Receive) { (info, body) ->
        if (body !is ByteReadChannel) return@intercept
        val total: Long = context.response.contentLength() ?: -1L
        val url: String = context.request.url.toString()
        val wrapped: ByteReadChannel = scope.writer(context.coroutineContext) {
            val buf = ByteArray(BUFFER_SIZE)
            var read: Long = 0L
            var lastTen: Int = -1
            var lastMark = TimeSource.Monotonic.markNow()
            while (!body.isClosedForRead) {
                val n = body.readAvailable(buf, 0, buf.size)
                if (n <= 0) break
                channel.writeFully(buf, 0, n)
                read += n
                val fraction: Float? = if (total > 0L) {
                    (read.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                val tenK: Int = fraction?.let { (it * 10_000f).toInt() } ?: -1
                val timeElapsed = lastMark.elapsedNow().inWholeMilliseconds >= THROTTLE_MS
                val fractionAdvanced = fraction != null &&
                    tenK - lastTen >= THROTTLE_TENTHOUSANDTHS
                if (timeElapsed || fractionAdvanced) {
                    reporter(url, PageDownloadProgress.InProgress(fraction))
                    lastMark = TimeSource.Monotonic.markNow()
                    if (fraction != null) lastTen = tenK
                }
            }
        }.channel
        proceedWith(HttpResponseContainer(info, wrapped))
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster156.staleKdocSweep.cascade,
 * Task #612, 2026-05-28): classified as follows after recursive symbol
 * verification (two-hundred-and-sixth sibling of the cluster57-155 sweep —
 * CLOSING file of the wave-28 :composeApp/presentation/common/componants/
 * images/ 2-leaf batch alongside SourceImageRequest; CLOSES images/ tier
 * 2/2):
 *  (a) "Ktor3-client-extension-that-wires-a-HttpResponsePipeline.Receive-
 *  interceptor-into-an-HttpClient-to-count-bytes-as-they-stream-through-
 *  the-response-body-and-forward-PageDownloadProgress.InProgress-ticks-to
 *  -a-reporter-callback + Phase-7.x.reader.modelayout.pageprogress.ktor3
 *  + iOS-Darwin-and-Desktop-CIO-byte-progress-equivalent-of-the-Android-
 *  OkHttpProgressInterceptor + The-parent-slice-landed-Android-per-byte-
 *  fraction-via-OkHttp-iOS-Desktop-kept-emitting-only-Started-Complete-
 *  Failed-via-the-Coil-per-request-listener + This-helper-closes-that-
 *  gap-on-the-ktor3-path-used-by-coil-network-ktor3 + Strategy-choice-
 *  responsePipeline.intercept-HttpResponsePipeline.Receive + Per-request
 *  -onDownload-Coil-constructs-the-HttpRequestBuilder-internally-no-hook-
 *  to-inject-a-per-request-callback + createClientPlugin-onResponse-fires
 *  -after-the-body-is-fully-read-useful-for-got-a-response-here-s-the-
 *  content-but-not-for-incremental-byte-counting + The-pipeline-s-Receive
 *  -phase-fires-after-the-engine-has-produced-the-HttpResponse-but-before
 *  -the-body-is-parsed-by-downstream-phases + We-replace-the-subject-s-
 *  body-a-ByteReadChannel-with-a-counting-bridge-channel-built-via-writer
 *  + The-bridge-reads-bytes-from-the-original-channel-counts-them-
 *  throttles-emissions-then-writes-the-same-bytes-downstream-so-parsing-
 *  proceeds-normally + Throttling-parity-with-OkHttpProgressInterceptor +
 *  THROTTLE_MS-50ms-wall-clock-gate-using-KMP-portable-TimeSource.
 *  Monotonic + THROTTLE_TENTHOUSANDTHS-100-equals-1-percent-fraction-
 *  advance-gate-using-Int-tenThousandths-to-dodge-Float-equality-fuzz +
 *  Without-throttling-the-read-loop-emits-a-tick-per-8KB-buffer-fill-
 *  drowning-the-MVI-reducer-in-updates + contentLength-unknown-when-the-
 *  server-omits-Content-Length-or-uses-chunked-encoding-without-one-the-
 *  wrap-still-runs-but-fraction-stays-null + The-reporter-still-receives-
 *  InProgress-null-ticks-throttled-to-one-per-50ms-purely-as-a-bytes-are
 *  -flowing-signal-mirroring-Android + The-UI-placeholder-treats-In
 *  Progress-null-as-an-indeterminate-spinner + Cancellation-safety-the-
 *  writer-builder-inherits-the-HttpClientCall-s-coroutine-context + When-
 *  Coil-cancels-the-call-cooperative-cancellation-propagates-the-writer-
 *  coroutine-cancels-the-wrapped-channel-closes-and-the-read-loop-s-
 *  suspending-readAvailable-call-returns-from-cancellation + No-leaked-
 *  coroutines-no-zombie-progress-emissions + Lifecycle-bookend-reporting-
 *  this-helper-emits-ONLY-PageDownloadProgress.InProgress-ticks + The-
 *  Coil-per-request-listener-attached-at-the-:ui-ImageRequest.Builder-
 *  site-handles-Started-Complete-Failed + Why-every-response-is-wrapped-
 *  not-just-reader-pages-mirroring-the-Android-interceptor + The-Coil-
 *  singleton-ImageLoader-s-ktor3-client-is-shared-by-every-image-request
 *  -in-the-app-covers-plus-reader-pages + The-reporter-writes-to-a-
 *  MutableStateFlow-Map-backed-repository-URLs-the-Reader-VM-never-
 *  subscribes-to-leave-residual-entries-a-few-KB + Selectively-wrapping-
 *  by-URL-would-require-a-side-channel-to-identify-reader-vs-cover-
 *  requests-premature-complexity + The-per-request-cost-of-byte-counting
 *  -is-dominated-by-network-IO-itself + DIP-depends-only-on-ktor3-plus-:
 *  domain-PageDownloadProgress + The-reporter-callback-shape-keeps-the-
 *  helper-from-reaching-into-:data + SRP-one-rule-wrap-a-ktor3-response-
 *  body-so-per-byte-reads-emit-progress-ticks + Body-length-sniffing-
 *  throttling-and-channel-forwarding-all-serve-that-one-rule + Thread-
 *  safety-each-call-gets-a-fresh-writer-coroutine-with-per-call-local-
 *  state-bytesRead-lastTen-lastMark-no-cross-call-shared-mutable-state +
 *  The-reporter-callback-handed-in-is-expected-to-be-thread-safe + Block
 *  -and-ask-trigger-1-audit-Coil-3.4.0-s-default-ktor3-HttpClient-is-
 *  constructed-by-coil-network-ktor3-with-no-explicit-plugins-installed-
 *  vanilla-engine-defaults + The-load-bearing-image-quality-fixes-for-
 *  iOS-Desktop-HighQualitySkiaImageDecoder-maxBitmapSize-Size.ORIGINAL-
 *  CoilSourceHeaderInterceptor-live-at-the-ImageLoader-component-layer-
 *  NOT-the-HTTP-client-layer-untouched-by-this-helper + The-CoilSource
 *  HeaderInterceptor-is-a-Coil-Interceptor-not-a-ktor3-plugin-it-runs-on
 *  -the-Coil-interceptor-chain-ahead-of-the-network-fetcher" —
 *  LIVE-NOT-STALE plus FULFILLED-PORT (closed the iOS/Desktop per-byte
 *  progress gap left open after the Android OkHttp interceptor parent
 *  slice). Verified: installPageProgressObserver(reporter) extension on
 *  HttpClient ships as an internal extension wiring a
 *  HttpResponsePipeline.Receive interceptor. The 50ms wall-clock +
 *  1%-fraction-advance throttle gates honored (THROTTLE_MS = 50L +
 *  THROTTLE_TENTHOUSANDTHS = 100 + BUFFER_SIZE = 8 * 1024). The "wrap
 *  every response, filter at the repository subscription side" stance
 *  honored — no URL-side-channel routing, the Coil singleton's ktor3
 *  client wraps every image request (covers + reader pages alike). The
 *  "contentLength unknown → InProgress(null) indeterminate-spinner
 *  fallback" graceful-degradation honored. The "cancellation
 *  cooperatively propagates through writer coroutine" no-leak posture
 *  honored. The "Coil interceptor-chain image-quality fixes live at
 *  ImageLoader layer, NOT HTTP client layer" untouched-by-this-helper
 *  audit honored — HighQualitySkiaImageDecoder + maxBitmapSize(ORIGINAL)
 *  + CoilSourceHeaderInterceptor remain intact at the ImageLoader. The
 *  reporter callback shape (url, PageDownloadProgress) -> Unit keeps the
 *  helper from reaching into :data (DIP). Consumed by the iOS / Desktop
 *  ImageLoader constructor (composeApp/iosMain + desktopMain) installing
 *  the interceptor into the ktor3 client used by coil-network-ktor3.
 *  CLOSING FILE of the cluster156 :composeApp/presentation/common/
 *  componants/images/ 2-leaf batch (2 of 2: SourceImageRequest +
 *  KtorPageProgressObserver). One classification. Original Phase
 *  7.x.reader.modelayout.pageprogress.ktor3 ktor3-pipeline-interceptor
 *  prose preserved verbatim per the audit-trail-preservation convention.
 */
