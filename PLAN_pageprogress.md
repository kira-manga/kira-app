# PLAN — Phase 7.x.reader.modelayout.pageprogress

Slice goal: surface download progress for the reader's per-page Coil image loads to the `:ui` loading placeholder, so the user sees a real % indicator (or at minimum a coarse-grained 3-stage indicator) instead of an indeterminate spinner.

Recorded by the architecture-rework auto-continue at the close of Phase 7.x.reader.pagescrubber (`bdb8362`). My own close-out (SOLID_AUDIT.md, Phase 7.x.reader.pagescrubber section, Next-recommended block) flagged this slice as Plan-mode-worthy because the Coil interceptor pipeline carries multiple load-bearing fixes that must be preserved verbatim. This file captures the design so a follow-on session can execute it cleanly.

## Load-bearing fixes that MUST survive the slice (verbatim)

From the Coil pipeline survey (see SOLID_AUDIT.md Phase 7.x.reader.pagescrubber close-out + `composeApp/src/commonMain/.../App.kt:194-212` + `composeApp/src/{androidMain,iosMain,desktopMain}/.../PlatformNetworkFetcher.*.kt` + `platform/src/{androidMain,nonAndroidMain}/.../image/*.kt` + `ui/src/commonMain/.../reader/ReaderScreen.kt:913-938`):

- **`App.kt:210`** — `.maxBitmapSize(Size.ORIGINAL)` on the singleton ImageLoader. Removes Coil 3.3+'s default 4096×4096 cap so the per-request `.maxBitmapSize(Undefined, Undefined)` is the only constraint. Touching this collapses tall webtoon pages — re-introduces the regression diagnosed in commit 98bf8ed.
- **`App.kt:202`** — `CoilSourceHeaderInterceptor(sourcesRepository)` on the singleton ImageLoader. Injects per-source headers (Cookie / Referer / User-Agent) for Cloudflare-protected CDNs. Must remain.
- **`PlatformNetworkFetcher.android.kt`** — `OkHttpNetworkFetcherFactory()`. ktor3+okhttp both on Android classpath; ServiceLoader is non-deterministic, so the explicit Android factory is load-bearing. Per the `[Force OkHttp fetcher on Android in singleton ImageLoader](project_yami_okhttp_fetcher.md)` memory.
- **`AndroidImageDecoderRegistry.kt`** — `AvifDecoderCoil.Factory()` registered. Per the `[AVIF decoder must be registered on Coil ImageLoader](project_yami_avif_decoder.md)` memory — dropping this regresses post-port "image quality bad".
- **`AvifDecoderCoil.kt:73`** — `Bitmap.Config.RGB_565` for opaque, `ARGB_8888` for alpha. Per-decoder bitmap-config logic; do not touch.
- **`HighQualitySkiaImageDecoder.kt:92`** (iOS + Desktop) — `SamplingMode.CATMULL_ROM` replacing Coil stock `DEFAULT` (nearest-neighbor) + N32 color depth. Per the `[iOS/Desktop image quality fix](project_yami_desktop_skia_size_cap.md)` memory.
- **`ReaderScreen.kt:931-936`** — per-page `ImageRequest.Builder` body: `.data(page.url).httpHeaders(headers).maxBitmapSize(Size(Undefined, Undefined)).build()`. The `maxBitmapSize(Undefined, Undefined)` overrides the loader-level `Size.ORIGINAL` (a future-Coil safety belt) — touching it triggers the same tall-webtoon regression.
- **`PlatformDecoderHints.android.kt`** — `.allowHardware(false).bitmapConfig(Bitmap.Config.RGB_565)`. Cover-image specific (in `SourceImageRequest.kt`), but the same hints are needed by reader pages if we re-build the per-page request. Per the `[Mirror native buildImageRequest for reader pages](project_yami_image_quality_buildrequest.md)` memory. **NB:** the reader's per-page `ImageRequest.Builder` does NOT currently call `applyPlatformDecoderHints()` — see open item below.

## Open item: should the reader's per-page request call `applyPlatformDecoderHints()`?

The cover-image builder in `SourceImageRequest.kt:35-64` calls `applyPlatformDecoderHints()` (Android: `allowHardware(false) + RGB_565`). The reader's per-page builder in `ReaderScreen.kt:913-938` does NOT.

The `[Mirror native buildImageRequest for reader pages](project_yami_image_quality_buildrequest.md)` memory explicitly says reader pages need RGB_565+allowHardware(false) to keep the LRU cache from being filled by ARGB_8888 pages (which forces Coil to evict and re-decode → subsampled quality).

Audit before starting the slice: confirm whether the reader's per-page request is missing these hints and whether the `AvifDecoderCoil` already enforces `RGB_565` for AVIF pages (which the survey shows it does at line 73). If non-AVIF pages on Android currently decode as ARGB_8888, that's a separate bug from this slice — fix it in a prep commit BEFORE the pageprogress work (or document it as a deliberate parallel deferral).

## Design — abstraction shape

### `:domain`

New file: `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/reader/PageDownloadProgress.kt`

```kotlin
sealed interface PageDownloadProgress {
    /** No download attempted yet (default state). */
    data object Idle : PageDownloadProgress
    /** Network request started; bytes not yet flowing. */
    data object Started : PageDownloadProgress
    /** Bytes are being received. `fraction` is 0.0..1.0 if Content-Length is known, null otherwise. */
    data class InProgress(val fraction: Float?) : PageDownloadProgress
    /** All bytes received; decoding has started. */
    data object Decoding : PageDownloadProgress
    /** Decode complete. UI swaps to the rendered image. */
    data object Complete : PageDownloadProgress
    /** Download or decode failed. Reader's existing error pane takes over via Coil's `error` slot. */
    data object Failed : PageDownloadProgress
}
```

New file: `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/PageProgressRepository.kt`

```kotlin
interface PageProgressRepository {
    /** Observe progress for a specific page URL. Cold flow; emits [PageDownloadProgress.Idle] when no record exists. */
    fun observe(url: String): kotlinx.coroutines.flow.Flow<PageDownloadProgress>

    /** Internal reporter — called by the platform interceptors (OkHttp on Android, future ktor3 on iOS/Desktop). */
    fun report(url: String, status: PageDownloadProgress)
}
```

### `:data`

New file: `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/PageProgressRepositoryImpl.kt`

Backed by a `MutableStateFlow<Map<String, PageDownloadProgress>>`. The `observe(url)` projection maps the state to the per-URL value via `map { it[url] ?: PageDownloadProgress.Idle }.distinctUntilChanged()`. The `report(url, status)` updates the map atomically. Same posture as the existing `:data` impls (commonMain, no platform reach).

### `:platform`

The actual per-byte progress reporter needs platform divergence:

- **Android — `OkHttpProgressInterceptor`** (new `:platform/androidMain` file). Wraps `OkHttpClient` requests so the response body is decorated with a `ForwardingSource`-based body that reports bytes-read to `PageProgressRepository.report(url, InProgress(fraction))`. Adds a Coil `Coil3.intercept.Interceptor` hook for `onStart` → `report(url, Started)` and `onComplete` → `report(url, Complete)`. **Wiring caution:** the slice will need to either (a) construct a new OkHttpClient with the interceptor and pass it to `OkHttpNetworkFetcherFactory(client)`, or (b) intercept at the Coil level via a custom NetworkFetcher.Factory that wraps the platform fetcher.
- **iOS + Desktop — coarse-grained Coil listener** (new `:platform/nonAndroidMain` file, or split iosMain+desktopMain). Uses `ImageRequest.Builder.listener(...)` for `onStart` → `report(url, Started)`, no per-byte progress (ktor3 interception deferred to a follow-on slice), `onSuccess` → `report(url, Complete)`. The UI placeholder shows an indeterminate spinner during `Started` (no fraction), so visually identical to today on iOS/Desktop. The differentiator vs today: when iOS/Desktop later get per-byte interception, the same `:domain` and `:ui` plumbing works unchanged.

The cleanest abstraction at the `:platform` boundary: a `PageProgressReporterAttacher` expect/actual that takes an `ImageRequest.Builder` and a `PageProgressRepository` and returns a `Builder` with the platform-appropriate hooks attached. Android's actual installs both the OkHttp body wrap (via a custom NetworkFetcher.Factory that delegates to OkHttpNetworkFetcherFactory) AND the Coil listener for fetchStart/decodeEnd events. iOS/Desktop actuals install only the Coil listener for fetchStart/onSuccess.

The Android side's NetworkFetcher.Factory swap is the riskiest move in this slice — it must preserve `OkHttpNetworkFetcherFactory`'s default OkHttpClient unless we explicitly construct a new one with the interceptor. **Safest path:** wrap the underlying `OkHttpClient` with `.newBuilder().addNetworkInterceptor(progressInterceptor).build()`, hand the wrapped client to `OkHttpNetworkFetcherFactory(client)`, and confirm via a side-channel that the existing `CoilSourceHeaderInterceptor` still runs (it should — `CoilSourceHeaderInterceptor` is a Coil-level `Interceptor`, not OkHttp-level, so the two are orthogonal).

### `:presentation`

`ReaderViewModel` gains:
- a `PageProgressRepository` constructor dep
- a `pageProgress: Map<String, PageDownloadProgress>` field on `ReaderState` (or a projected `pageProgress: (String) -> PageDownloadProgress` accessor — TBD; prefer the Map because MVI state should be pure data)
- an `onEnter` cascade: for every page in the chapter's pages, launch a `collect` job that updates `state.pageProgress[url]` on each emission. (Implementation note: a single `combine` over `pages.map { observe(it.url) }` is the idiomatic shape, but with 100+-page chapters that creates a hot combine — consider an on-demand observe scoped to currently-visible pages instead.)

### `:ui`

`ReaderPageItem`'s `loading` slot (currently `ReaderScreen.kt:953-961`) becomes:

```kotlin
loading = {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = screenHeightDb),
        contentAlignment = Alignment.Center,
    ) {
        val progress = state.pageProgress[page.url] ?: PageDownloadProgress.Idle
        when (val p = progress) {
            PageDownloadProgress.Idle, PageDownloadProgress.Started, PageDownloadProgress.Decoding ->
                CircularProgressIndicator()
            is PageDownloadProgress.InProgress ->
                if (p.fraction != null)
                    CircularProgressIndicator(progress = { p.fraction })
                else
                    CircularProgressIndicator()
            PageDownloadProgress.Complete, PageDownloadProgress.Failed ->
                CircularProgressIndicator() // Coil's success/error slots take over moments later
        }
    }
}
```

The `state.pageProgress` accessor needs to be threaded through `ReaderPageLayout` → child layouts → `ReaderPageItem`. Same plumbing posture as the §70 `screenHeightDb` thread.

### `:composeApp`

`ReaderReworkModule.kt` gains:
- `single<PageProgressRepository> { PageProgressRepositoryImpl() }`
- `factory { PageProgressReporterAttacher(get()) }` (or move the attacher into the App.kt builder block — TBD)
- `ReaderViewModel`'s constructor-arg count grows by 1

`App.kt`'s singleton builder gains:
- Reading the `PageProgressRepository` from Koin (or constructing the attacher inline)
- Either: passing the attacher into the `NetworkFetcher.Factory` slot via a thin wrapper, or: leaving the network fetcher as-is and only attaching the Coil-level `listener(...)` hooks per-request from `ReaderPageItem`'s `ImageRequest.Builder`.

The cleanest design here is **per-request listener attachment** at the `ReaderPageItem` site, not at the singleton-loader level. This way the App.kt builder is untouched (zero risk to the load-bearing fixes), and only the reader-page request opts into progress reporting. The OkHttp interceptor approach for Android per-byte progress is the only piece that needs to touch the platform-level OkHttpClient construction.

## Commit roadmap (7-9 commits, all ≤5 files, build-gated)

1. **`pageprogress.domain`** — Add `:domain` types: `PageDownloadProgress.kt` + `PageProgressRepository.kt`. 2 new files. Build gate Android + iOS Arm64 + iOS SimArm64.
2. **`pageprogress.data`** — Add `:data` impl: `PageProgressRepositoryImpl.kt` (MutableStateFlow-backed). 1 new file. Build gate.
3. **`pageprogress.koin`** — Bind in `ReaderReworkModule.kt`. 1 file modified. Build gate.
4. **`pageprogress.vm`** — Extend `ReaderState` with `pageProgress: Map<String, PageDownloadProgress>` field + `ReaderViewModel` constructor dep + `onEnter` observe-cascade. 2 files modified. Build gate.
5. **`pageprogress.ui.listener`** — Add `ImageRequest.Builder.listener(...)` per-request in `ReaderScreen.kt` that reports `Started` / `Complete` / `Failed` via the repository. Thread `state.pageProgress` through `ReaderPageLayout` → child layouts → `ReaderPageItem`. Swap the `loading` slot to read from `pageProgress` and render determinate (when fraction is known) vs indeterminate. 1 file modified. Build gate.
6. **`pageprogress.android.okhttp`** — Add Android-only `OkHttpProgressInterceptor` in `:platform/androidMain`. Wire into `OkHttpNetworkFetcherFactory(client)` by constructing the OkHttpClient with the new interceptor. 2-3 files modified/created (`:platform/androidMain` interceptor + `PlatformNetworkFetcher.android.kt` wiring + possibly a Koin binding in the platform module / App.kt). **CRITICAL: preserve all load-bearing OkHttpClient config that exists today.** Build gate Android.
7. **`pageprogress.audit`** — Verify the existing `applyPlatformDecoderHints()` is applied to the reader's per-page request, fix if not (parallel deferral split into this commit). Build gate Android.
8. **`pageprogress.close-out`** — `ARCHITECTURE.md` §79 + `SOLID_AUDIT.md` Phase 7.x.reader.modelayout.pageprogress entry. Final commit.

iOS/Desktop ktor3 per-byte progress is deferred to a follow-on slice ("Phase 7.x.reader.modelayout.pageprogress.ktor3"). The base slice ships with: Android per-byte %, iOS/Desktop coarse 3-stage (Idle → Started → Complete).

## Deferrals (out of scope for this slice)

- iOS/Desktop per-byte progress (ktor3 interception) — follow-on slice.
- Estimated time remaining ("47s left") — would need a smoothing/rate calculation atop fraction; out of scope.
- Per-page failed-state user message (e.g., "404 not found") — Coil's existing `error` slot already covers the failure UX; this slice doesn't extend it.
- Network-stack swap (replacing OkHttp with ktor3 on Android, or vice versa) — Rule 8(b) blocker.
- Decoder-stage progress (decode bytes-processed / total) — Coil 3.x doesn't expose this, and the decode stage is much shorter than the download stage on cellular networks. Negligible UX value, out of scope.

## SOLID Guardian considerations

- **SRP**: each new file owns one responsibility (state holder, reporter facade, interceptor).
- **OCP**: `:domain` types are sealed + additive. `:data` impl is one new class. `:platform` actuals are additive. MVI state field is additive.
- **DIP**: `:ui` depends on `:domain` types only (`PageDownloadProgress`). `:presentation` depends on `:domain` repository interface. `:data` and `:platform` impls are bound at the composition root.
- **Layer boundary**: no layer reaches into a layer below it that it doesn't already reach into. The `:platform` Android interceptor introduces a NEW reach from `:platform/androidMain` into `OkHttpClient` (which is on the Android classpath via Coil 3.x's `OkHttpNetworkFetcherFactory`). That's a `:platform` internal reach, no boundary violation.
- **Behaviour preservation**: every load-bearing fix listed above must survive verbatim. The slice is purely additive at the singleton ImageLoader level — only the per-page `ImageRequest.Builder` site gains a `listener(...)` call, and only the Android OkHttp side gains an interceptor.

## Build-gate cadence

After every commit:
- `gradlew.bat :composeApp:compileDebugKotlinAndroid` MUST pass.
- For commits touching commonMain or iosMain: also `:composeApp:compileKotlinIosArm64` AND `:composeApp:compileKotlinIosSimulatorArm64`.

Red = stop and diagnose. Don't proceed to the next commit until green.

## Block-and-ask triggers specific to this slice

- If the Android OkHttp interceptor wiring needs to construct a NEW OkHttpClient instance (rather than mutating an existing one), AND it turns out the OkHttpNetworkFetcherFactory's default client carries load-bearing config we can't easily replicate — **block-and-ask**, that's Rule 8(b) "refactor would change observable behavior" (cache, connection pool, timeouts could regress).
- If extending `ReaderState` with `pageProgress: Map<String, PageDownloadProgress>` triggers significant `data class copy()` perf concerns on long chapters — **block-and-ask** for the Map shape vs an alternative (e.g., a `PersistentMap` from `kotlinx.collections.immutable`, or a side-flow not in state).
- If the `applyPlatformDecoderHints()` audit (commit 7) reveals the reader's per-page request was already missing the hints AND a fix would change the on-disk Coil cache shape — **block-and-ask** before fixing, since cache key changes invalidate existing cached pages.
