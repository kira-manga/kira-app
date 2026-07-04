# PLAN — Phase 7.x.reader.modelayout.pageprogress.ktor3

Follow-on slice from Phase 7.x.reader.modelayout.pageprogress (close-out `31de3a8`,
8 source commits `c040142..7b8baef`). Ports per-byte download progress to iOS
(Darwin engine) and Desktop (CIO engine) via a ktor3 `responsePipeline`
interceptor on a custom `HttpClient` passed to `KtorNetworkFetcherFactory`.

The parent slice landed determinate progress on Android only (OkHttp interceptor
wrapping `Response.body` in a `ForwardingSource`-based byte counter); on
iOS / Desktop the reader's loading placeholder remains indeterminate during the
fetch because no surface emits `PageDownloadProgress.Fetching(fraction)`. The
`:domain` (`PageDownloadProgress`, `PageProgressRepository`),
`:data` (`PageProgressRepositoryImpl` — `MutableStateFlow<Map<...>>` backed),
`:presentation` (`ReaderState.pageProgress`, `ReaderViewModel.startObservingProgress`),
and `:ui` (per-request listener bridge + determinate placeholder branch)
plumbing is already in place. This slice only adds the iOS and Desktop emitters.

## Load-bearing fixes that MUST survive verbatim

Same list as the parent slice, plus the parent slice's own additions:

- **`App.kt`** singleton `ImageLoader` — `maxBitmapSize(Size.ORIGINAL)` +
  `CoilSourceHeaderInterceptor` + `HighQualitySkiaImageDecoder` (iOS + Desktop)
  + `AvifDecoderCoil.Factory()` (Android). **App.kt is not modified by this slice.**
- **`PlatformNetworkFetcher.android.kt`** — `OkHttpNetworkFetcherFactory(callFactory = { client })`
  pin + `OkHttpProgressInterceptor` from parent slice. **Android actual is not
  modified by this slice.**
- **`ui/.../reader/internal/ReaderDecoderHints.android.kt`** — `allowHardware(false)`
  + `RGB_565` per-request. **Not modified by this slice.**
- **`ReaderScreen.kt`** per-page `ImageRequest.Builder`: `maxBitmapSize(Undefined, Undefined)`
  + `.applyReaderDecoderHints()` + per-request `.listener(...)` bridge.
  **Not modified by this slice.**

This slice's only ImageLoader-component touch is the iOS and Desktop
`PlatformNetworkFetcher` actuals' return value — pre-slice they return `null`
(letting `coil-network-ktor3`'s ServiceLoader pick its bundled vanilla default);
post-slice they return `KtorNetworkFetcherFactory(httpClient = { client })`
where `client` is our custom `HttpClient` with the progress observer installed.

## Block-and-ask trigger #1 audit (preserve load-bearing client config)

**Trigger NOT met.**

Coil 3.4.0's `coil-network-ktor3` constructs its default `HttpClient()` with no
explicit plugins, no timeout, no User-Agent — vanilla engine defaults. Replacing
it with our own custom client that adds only the progress observer preserves
observably-equivalent behaviour:

- Coil's per-request headers (Cookie / Referer / User-Agent) come from the
  Coil-level `CoilSourceHeaderInterceptor` (registered on the
  `ImageLoader.Builder().components { }`), which runs ahead of the network
  fetcher. Our custom client doesn't need to know about them.
- Timeouts: ktor3 default `HttpClient()` has unlimited timeout; we match this
  by not installing `HttpTimeout`. If Coil's default has a hidden timeout
  it does not — verified per recon, `coil-network-ktor3` does not install
  `HttpTimeout` on its default client.
- Default `SaveBodyPlugin`: enabled by default in ktor 3.x; we don't disable
  `useDefaultTransformers`, so the plugin stays active. Coil can re-read the
  body if its internal flow requires (it doesn't for streaming-only image
  decoders, but the safety belt costs nothing).

No `ImageLoader.Builder` config change is needed at `App.kt:183-212`.

## Approach

**Strategy A** from the ktor3 recon — `responsePipeline.intercept(HttpResponsePipeline.Receive)`
on a custom `HttpClient`. Mirrors the Android OkHttp interceptor pattern at the
structurally-equivalent ktor3 seam.

Two viable alternatives considered + rejected:

- **Per-request `onDownload`** — Coil constructs the `HttpRequestBuilder`
  internally, no hook to inject a per-request callback.
- **`createClientPlugin { onResponse }`** — `onResponse` fires after the body
  is read, useless for incremental byte counting.

## Design — abstraction shape

### `:composeApp/commonMain` — new helper

New file: `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/common/componants/images/KtorPageProgressObserver.kt`

```kotlin
internal fun HttpClient.installPageProgressObserver(
    reporter: (url: String, fraction: Float) -> Unit,
) {
    responsePipeline.intercept(HttpResponsePipeline.Receive) { (info, body) ->
        if (body !is ByteReadChannel) { proceed(); return@intercept }
        val total = context.response.contentLength() ?: -1L
        val url = context.request.url.toString()
        if (total <= 0L) { proceed(); return@intercept } // no denominator → no emissions

        val wrapped = writer(context.coroutineContext) {
            val buf = ByteArray(BUFFER_SIZE)
            var read = 0L
            var lastTen = -1
            var lastMs = TimeSource.Monotonic.markNow()
            while (!body.isClosedForRead) {
                val n = body.readAvailable(buf, 0, buf.size)
                if (n <= 0) break
                channel.writeFully(buf, 0, n)
                read += n
                val fraction = (read.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                val tenK = (fraction * 10_000f).toInt()
                val elapsedMs = lastMs.elapsedNow().inWholeMilliseconds
                if (tenK - lastTen >= THROTTLE_TENTHOUSANDTHS || elapsedMs >= THROTTLE_MS) {
                    reporter(url, fraction)
                    lastTen = tenK
                    lastMs = TimeSource.Monotonic.markNow()
                }
            }
        }.channel
        proceedWith(HttpResponseContainer(info, wrapped))
    }
}
```

Throttle constants `THROTTLE_MS = 50L`, `THROTTLE_TENTHOUSANDTHS = 100` (1%) —
parity with `OkHttpProgressInterceptor`. Buffer `BUFFER_SIZE = 8192`.

The `writer(context.coroutineContext) { ... }` builder produces a `ByteReadChannel`
bridge that pumps the original channel through, counts bytes, and emits throttled
progress via the reporter callback. The writer coroutine inherits the call's
context — Coil cancellation propagates cooperatively (no leaked coroutine).

`info` is `TypeInfo` (ktor 3.x renamed from the older `type` ContentType-ish
field). `proceedWith(HttpResponseContainer(info, wrapped))` substitutes the
wrapped channel into the pipeline; downstream consumers (Coil's decoder) read
through our counting bridge transparently.

### `:composeApp/iosMain` — updated actual

Modified file: `composeApp/src/iosMain/.../PlatformNetworkFetcher.ios.kt`

```kotlin
actual fun platformNetworkFetcherFactory(): NetworkFetcher.Factory? {
    val repository = GlobalContext.get().get<PageProgressRepository>()
    val client = HttpClient {
        // engine resolved via ServiceLoader / K/N link-time from :shared/iosMain
        // (ktor-client-darwin); no engine factory specified here so the actual
        // stays free of a direct ktor-client-darwin import.
    }.apply {
        installPageProgressObserver { url, fraction ->
            repository.report(url, PageDownloadProgress.Fetching(fraction))
        }
    }
    return KtorNetworkFetcherFactory(httpClient = { client })
}
```

### `:composeApp/desktopMain` — updated actual

Modified file: `composeApp/src/desktopMain/.../PlatformNetworkFetcher.desktop.kt`

Identical body to iOS. KDoc cross-references the iOS actual rather than
duplicating rationale. Engine: CIO via ServiceLoader from `:shared/desktopMain`.

### `GlobalContext.get<PageProgressRepository>()` rationale

Same pattern Phase 7.x...pageprogress Step 6 established on Android. The
`platformNetworkFetcherFactory()` function is non-Compose (called once at
App() composition time via `remember { platformNetworkFetcherFactory() }`),
so `koinInject()` is unavailable. The Koin graph is initialised before
App() composition starts (via `KoinApplication.initKoin()` in iOS / Desktop
hosts), so `GlobalContext.get().get<PageProgressRepository>()` resolves the
singleton interface cleanly. The repository outlives the ImageLoader
(both are app-singleton), so the closure-captured reference stays valid for
the loader's lifetime.

## Commit roadmap

5 commits, ≤5 files each, build gates after every source commit.

1. **`pageprogress.ktor3.plan`** — this file (1 file).
2. **`pageprogress.ktor3.helper`** — `KtorPageProgressObserver.kt` in
   `:composeApp/commonMain` (1 new file). Build gate Android + iOS Arm64
   + iOS SimArm64 + Desktop.
3. **`pageprogress.ktor3.ios`** — `PlatformNetworkFetcher.ios.kt` modified
   (1 file). Build gate Android + iOS Arm64 + iOS SimArm64 + Desktop.
4. **`pageprogress.ktor3.desktop`** — `PlatformNetworkFetcher.desktop.kt`
   modified (1 file). Build gate Android + iOS Arm64 + iOS SimArm64 + Desktop.
5. **`pageprogress.ktor3.close-out`** — `ARCHITECTURE.md` §80 + `SOLID_AUDIT.md`
   Phase entry (2 files).

If commit 2 or 3 / 4 turns out to need a direct ktor engine dep at the
`:composeApp` level (e.g., link errors on iOS due to symbol resolution), a
prep commit adds `implementation(libs.ktor.client.darwin)` to
`:composeApp/iosMain.dependencies` and/or `libs.ktor.client.cio` to
`:composeApp/desktopMain.dependencies`. This was deemed unlikely (the engines
reach `:composeApp`'s classpath transitively via `:shared`'s `implementation`
deps + Gradle's runtime classpath aggregation + K/N's static framework linking
pulling in all transitive klibs), but it's the documented fallback.

## Verification

After every source commit (steps 2-4):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — must remain SUCCESSFUL.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — must be SUCCESSFUL.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — must be SUCCESSFUL.
- `gradlew.bat :composeApp:compileKotlinDesktop` — must be SUCCESSFUL.

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- iOS: open chapter; loading placeholder fills 0% → 100% smoothly on a slow
  network; swiping past a mid-fetch page cancels the in-flight download
  without leaks.
- Desktop: same, plus verify throttle keeps emissions ≤20/sec/page.

## Deferrals (out of scope, follow-on slices)

- **Darwin streaming-aggregation polish** — if iOS shows "0% → 100% jump"
  behaviour due to Darwin's per-frame buffering, follow-on slice swaps to
  a streaming-friendly engine config or falls back to indeterminate on iOS.
- **Engine swap to OkHttp on Desktop** — if CIO has streaming issues,
  swap to `ktor-client-okhttp` on Desktop.
- **No time-remaining smoothing** — same as parent slice.
- **No per-page failed-state user message** — Coil's `error` slot covers it.
- **No `Decoding` emission** — variant stays in `:domain` for a future
  decoder-stage slice.
- **No `PageProgressReporterAttacher` expect/actual facade** — same as parent
  slice's conclusion; per-target wiring in the actual is the natural fit.

## SOLID Guardian considerations

- **SRP**: helper does one thing (install byte-counting pipeline interceptor);
  each actual does one thing (construct target's `KtorNetworkFetcherFactory`
  with the observer attached).
- **OCP**: helper closed under modification (throttle constants + pipeline phase
  file-local); open under reporter substitution (structural `(String, Float) -> Unit`
  callback).
- **DIP**: helper depends on ktor3 + structural callback only. Actuals resolve
  the `:domain` interface (`PageProgressRepository`) via Koin `GlobalContext`,
  no `:data` reach.
- **Layer boundary**: `:composeApp/{commonMain, iosMain, desktopMain}` only.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. Long arithmetic +
  `coerceIn(0f, 1f)` safety.
- **MVI contract**: unchanged. New emission source on the existing
  `PageDownloadProgress.Fetching(fraction)` variant.

## Build-gate cadence

After every source commit (2-4), all four gates run. Red = stop and diagnose.

## Block-and-ask triggers specific to this slice

- If the `responsePipeline` API in ktor 3.4.3 has a different signature than
  the recon assumed (e.g., `Pipeline.Receive` renamed or removed), **block-and-ask**
  before flailing — the helper's API surface is the slice's pivot.
- If `KtorNetworkFetcherFactory(httpClient = { client })` cannot accept our
  custom client (constructor signature mismatch), **block-and-ask** — Coil
  3.4's API surface is the slice's external contract.
- If the Darwin engine fails to link without an explicit dep at `:composeApp/iosMain`,
  add the dep in a prep commit (documented above as the fallback path) —
  not a block-and-ask, it's a documented pivot.
