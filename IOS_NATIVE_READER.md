# Native iOS Reader

## Why this exists
The shared Compose-Multiplatform reader scrolls smoothly on Android and Desktop, but on **iOS** the
WEBTOON / continuous reader stutters on very tall webtoon strips (panels up to ~9440 px): a slow finger
drag barely moves while a hard fling coasts. It reproduces mid-chapter, `canScrollForward = true`, main
thread idle — so it is not a content-edge clamp, not jank, and not the chapter-append timing. It is the
Compose-MP **iOS** scroll/render/gesture pipeline straining on oversized Skia textures; Android
(`RenderNode` + `RGB_565`) is unaffected. Two cheap mitigations were tried and did **not** fix it: a
per-frame `GraphicsLayer` screenshot capture (made on-demand) and a temporary 4096 decode cap
(diagnostic, reverted). See the framework reports: JetBrains/compose-multiplatform #4329, #3335, #4740,
and YouTrack CMP-6828 / CMP-3806 (Compose↔native scroll/gesture).

The fix is to render the iOS reader with **native UIKit**, so iOS owns the pan/scroll pipeline and
native ImageIO downsampling avoids the oversized-texture cost — while keeping **all** reader
business logic in shared Kotlin (KMP). The Compose reader stays as the iOS fallback and remains the
implementation on Android/Desktop.

## Safety / rollout
- Behind `IosReaderFlags.NATIVE_READER_ENABLED` (`composeApp/iosMain/.../reader/IosReaderFlags.kt`),
  **default `false`**. With it off (or if Swift never registered the factory), the reader route renders
  the Compose `ReaderScreen` exactly as before — zero behavior change, instant rollback.
- Enable by flipping that constant to `true` and rebuilding (after on-device verification).
- Android and Desktop never touch this path (their `ReaderHostSwitch` actuals call `ReaderScreen`).

## Architecture (shared logic stays in KMP)
The native UI is a **pure renderer** of shared state; it owns no list/append/resume/index/history/progress
logic.

```
NavHost route (composeApp, commonMain)
  └─ ReaderHostSwitch(...)                      expect/actual
       ├─ android/desktop actual → ReaderScreen (Compose)              [unchanged]
       └─ iOS actual → if flag+factory → UIKitViewController(native)   [new]
                        else            → ReaderScreen (Compose)        [fallback]
```

- **Bridge** (`composeApp/iosMain/.../reader/ReaderNativeBridge.kt`): `ReaderNativeSession` wraps the
  route-scoped shared `ReaderViewModel`. It streams a flat `IosReaderSnapshot` (stdlib-only DTO defined
  in the always-exported `:composeApp` framework, so `:presentation`/`:domain` need no export), routes
  one-shot effects (nav → Compose callbacks; not-in-library/error → Swift), and exposes intent methods.
  Swift registers a VC factory via `ReaderNativeBridge.setViewControllerFactory { ... }` from
  `iOSApp.swift`.
- **Same ViewModel**: the native path reuses the route's `koinViewModel()` `ReaderViewModel`, so page
  resolution (network + downloaded CBZ/loose + fallback), resume, history, mark-read, bookmark,
  reading-mode persistence, and cross-chapter append are byte-identical to the Compose reader.

## Swift files (`iosApp/iosApp/NativeReader/`)
- `ReaderImageLoading.swift` — dependency-free loader: `URLSession` (per-request headers) or local
  `file://`, ImageIO `CGImageSourceCreateThumbnailAtIndex` downsampling to ~screen width, cost-bounded
  `NSCache` + `URLCache` disk, request coalescing + cancellation.
- `ReaderPageCell.swift` — `UICollectionViewCell`: `UIImageView` + spinner + tap-to-retry on error.
- `WebtoonReaderViewController.swift` — continuous mode: vertical `UICollectionView`, variable heights,
  prefetch, append-without-reload, top-visible → `OnPageChanged`, reach-end → `OnAppendNextChapter`.
- `PagedReaderViewController.swift` — paged modes: paging `UICollectionView` (vertical; horizontal with
  RTL via `transform`), page index stays canonical.
- `ReaderChromeView.swift` — UIKit chrome bars (`ReaderChromeBars`): a translucent **top** bar
  (back / title / bookmark) and **bottom** bar (chapter nav + page HUD + scrubber + share), pinned to the
  top/bottom edges only. **UIKit, not a SwiftUI `UIHostingController` overlay**, on purpose: a full-screen
  hosting overlay does not reliably pass touches through its transparent regions, which ate the scroll pan
  and the tap-to-toggle. With top/bottom bars the **middle is never covered**, so hit-testing is
  deterministic. Hidden bars are `isUserInteractionEnabled = false` so taps fall through to re-show chrome.
- `ReaderHostViewController.swift` — owns the session, swaps the child VC by reading mode, installs the
  chrome bars, and keeps the scroll view at **z-index 0 / full-screen** so it always owns the pan.
  Tap-to-toggle is a recognizer on the scroll view (`cancelsTouchesInView=false` so the pan is never
  stolen). 3 s auto-hide, lifecycle resume/pause, native share.

## Supported reader features (native)
- Reading modes: **WEBTOON** + **CONTINUOUS_VERTICAL** (continuous), **DEFAULT/VERTICAL** (vertical
  paging), **LEFT_TO_RIGHT** / **RIGHT_TO_LEFT** (horizontal paging, RTL). **Switchable in-reader** via the
  top-bar mode button (action sheet, parity with the Compose `ReadingModeDialog`); the choice persists
  (shared `SetReadingModeUseCase`) and the reader swaps continuous↔paged in place, resuming the same page.
- Current-page tracking, resume/open-at-page, prev/next chapter.
- **Continuous cross-chapter feed** (WEBTOON / CONTINUOUS_VERTICAL): the next chapter auto-appends as you
  near the end, with an **inline boundary panel** between chapters ("Finished: … / Next: …", or a terminal
  "You're at the last chapter" panel). Built from the shared, unit-tested `buildReaderFeed` (same projection
  the Compose reader uses), so the page↔feed index mapping, active-chapter tracking, and per-chapter HUD/
  scrubber are byte-identical. Paged modes advance by replacing the chapter (next/prev), not appending.
- Chrome show/hide with tap + 3 s auto-hide; **per-chapter** page HUD ("n / N") + scrubber (tap-to-seek
  anywhere on the track, or drag); bookmark toggle; share current page. The HUD/scrubber are driven by the
  VM snapshot (`activeChapterPageNumber`/`Count`), so they update immediately on chapter nav; scroll-driven
  page tracking only fires for user-driven scrolls so a programmatic resume/seek never clobbers the index.
- **Pinch + double-tap zoom in all modes** (single-tap still toggles chrome — it requires the double-tap
  to fail). Two mode-appropriate implementations:
  - **Paged** (LTR/RTL/vertical-paged): per-page `UIScrollView` (Photos-style containment) owns the pan
    when zoomed and yields to the outer paging at 1×. Pages decode at 2.5× screen width for crisp zoom.
  - **Continuous** (webtoon/CONTINUOUS_VERTICAL): "magnify & keep reading" — the collection view is nested
    in an outer horizontal scroll view; zoom makes the strip genuinely **wider** (cells wider + taller),
    so the column keeps scrolling vertically with its **native** pan (no transform → 1:1 tracking,
    virtualization preserved) while the outer scroll view pans horizontally. **Both scroll views are
    disabled while a pinch is active**, which is what avoids the classic webtoon "pinch → random scrolling"
    bug (Tachiyomi/Mihon issue). Content is anchored (vertical center + horizontal focal) across zoom
    changes; visible pages re-decode at the zoomed width on pinch-end for sharpness, and 1× stays light
    (no scroll regression). Max zoom 2.5×.
- Per-page loading indicator that upgrades from an indeterminate spinner to a **determinate ring + "NN%"**
  when the loader reports a download fraction (throttled to ≥1% advances); a full-screen loading/error
  placeholder while the chapter is still resolving its image URLs; downloaded (`file://`) and network pages.
- Per-page error slot with **Retry** and **Open in WebView** (the latter opens the active chapter in the
  WebView for Cloudflare recovery, via the shared `OnOpenInWebView` → `OpenChapterInWebView` effect).
- **Localized strings**: the frequently-visible strings (reading-mode picker, error slot, boundary
  next/last-chapter) are resolved from the shared compose-resources in `ReaderHostSwitch.ios` and handed to
  the Swift UI via `ReaderNativeBridge`, so they match the Compose reader's translations in all locales.
- Reading-session lifecycle (resume/pause) bracketed via the shared VM.

## Deferred (documented, not yet implemented in the native reader)
- **Paged auto-advance** by swiping past the last page (Compose's dummy "next chapter" page). Native v1
  advances via the chrome's next/prev buttons instead.
- A few **occasional strings remain English** (the "couldn't load this chapter" / "add to library first"
  toasts and the "Finished:" boundary prefix) — they have no direct shared-resource equivalent yet; adding
  them to composeResources is a small follow-up.

## Extreme-tall strips (memory / rendering audit)
Audited 2026-06; **CATiledLayer tiling intentionally NOT used** — it would be over-engineering here:
- **Memory is bounded.** `ReaderImageLoading` downsamples via ImageIO with the long edge capped at
  `maxPixelDimension = 12000 px` **while preserving width** (tall strips stay crisp — only the longest edge
  is bounded, never blurred like the rejected 4096 cap). Decoded bitmaps live in an `NSCache` (256 MB cost
  limit) that **evicts automatically under memory pressure**, and only visible + prefetched cells retain a
  bitmap. A memory-warning observer also purges the cache promptly. So there is no unbounded growth.
- **Rendering degrades gracefully.** Strips taller than the GPU texture limit (8192 px on pre-A12 devices;
  16384 px on A12+) are handled by CoreAnimation auto-downsampling the `UIImageView` layer contents — soft,
  not blank/crashing. Modern devices render 12000 px directly.
- **Why not CATiledLayer / region decode:** ImageIO cannot region-decode JPEG/WebP without a full decode,
  so tile sub-images would share the parent bitmap (no memory win) and only add complexity to the core
  continuous-scroll path. If OOM is ever observed on old devices, the cheapest lever is lowering
  `maxPixelDimension` (a documented, named constant), then true vertical segmentation — not before.

## Verification status
- Kotlin compile gate green on iOS + Android + Desktop with the flag OFF (Compose reader unchanged).
- Swift built via `xcodebuild` (simulator) — see the migration session report.
- **On-device sign-off (smooth slow-drag on a 9440 px chapter; resume; append; paged; chrome) is the
  final gate before flipping `NATIVE_READER_ENABLED` to true.** It is intentionally still OFF.
