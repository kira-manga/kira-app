# iOS-native compression pipeline — technical plan (proposal, not yet implemented)

Replace the iOS CBZ finalize/transcode path (currently **skiko/Skia WebP**, run from Kotlin/Native) with a
fully **Apple-native** pipeline (ImageIO + CoreGraphics), to eliminate the COMPRESSING-stage main-thread
stalls. **No code changed yet** — this is the design + options + risks + benchmark plan to approve first.

## 1. Why Android is smooth and iOS is not (root cause)

Measured on device (`DLPERF` logs), finalizing a 5-page Lekmanga webtoon chapter:
- Pages are giant strips: `1280×31849`, `1280×40169`, … → **155–196 MiB decoded bitmaps each**.
- `DLPERF.webpEncode totalMs` ≈ 1.0–2.4 s/page; `DLPERF.finalize.ms ≈ 9.6 s` total.
- `DLPERF.mainStall` fired repeatedly with **gaps of 274–566 ms** *during* the encode — the scroll jank.

This is a **background** encode on a multi-core device, yet it freezes the **main** thread for ~½ s at a
time. That is the signature of **Kotlin/Native GC stop-the-world / allocator-lock contention**, not plain
CPU contention. The Skia path forces big bitmaps + interop objects through the K/N heap:
- `org.jetbrains.skia.Image.makeFromEncoded` + `allocN32Pixels` band bitmaps live in skiko's native heap,
  but every skiko handle has a K/N wrapper, and the source/output `ByteArray`s (`readByteArray()`,
  `data.bytes`) are on the K/N heap. The allocation **rate** under encode is high enough to trigger K/N GC
  pauses that stop the main thread.

**Android** does the equivalent (`Bitmap.compress(WEBP_LOSSY)`) with no UI jank because:
- `BitmapFactory` decode + `Bitmap.compress` are platform-native (libjpeg/libwebp), often hardware-assisted.
- Bitmap **pixel memory is native** (ashmem/native heap since Android O), not the Java/ART heap.
- ART's GC is **concurrent + generational** with sub-frame pauses; it doesn't stop the world for ~½ s.
- The work runs in a `WorkManager` worker at background priority.

So the fix is to make iOS look like Android: do the decode + encode in **native image memory**
(CoreGraphics), off the Kotlin/Native heap, so K/N GC is never on the critical path.

## 2. Proposed pipeline (source bytes → CBZ)

All image work in **Kotlin/Native via ImageIO/CoreGraphics cinterop** (precedent already in the repo). The
CGImage pixel buffers are CoreGraphics-managed (native), so the K/N heap only ever sees small encoded byte
arrays → **no K/N GC pressure**.

```
loose page file (image_N.jpg on disk)
  → CGImageSourceCreateWithURL(fileURL)            // no source ByteArray on the K/N heap
  → read dimensions (CGImageSourceCopyPropertiesAtIndex) — cheap, no full decode
  → if tall: band into ≤ FORMAT_MAX_DIM-high chunks
       per band: CGImageSourceCreateImageAtIndex (or one full decode + CGImageCreateWithImageInRect)
  → CGImageDestinationCreateWithData(CFMutableData, UTI, 1,
        { kCGImageDestinationLossyCompressionQuality: q })   // nil ⇒ format unsupported on this device
     CGImageDestinationAddImage(band); CGImageDestinationFinalize()
  → copy CFData → small Kotlin ByteArray  (≈ 1–4 MiB, not 190 MiB)
  → StoreZipWriter.writeEntry("page_NNNN.<ext>", bytes)      // REUSE existing Kotlin CBZ writer
```

- **Decode/downsample**: `CGImageSource*`. For display-grade downsizing, `CGImageSourceCreateThumbnailAtIndex`
  with `kCGImageSourceThumbnailMaxPixelSize` decodes *directly* to the target size (low peak memory). For
  archival we keep full width, so we decode full-res and band (see §4).
- **Encode**: `CGImageDestination` with the chosen UTI + lossy-quality key (same key for HEIC and JPEG).
- **CBZ**: keep the existing, tested Kotlin `StoreZipWriter` + `IosCbzWriter` orchestration (atomic
  `.part`→`.cbz` rename, verbatim fallback, 0-page guard, source deletion). Only the *encoder* changes.
- **Threading**: run the finalize on a **low-QoS** background queue (utility/background) so iOS schedules it
  behind the UI; combined with no-K/N-GC this is what removes the jank.

## 3. Output format — DECIDED: WebP via libwebp (HEIC rejected)

**Locked (2026-06-29): keep WebP as the unified CBZ image format on both Android and iOS.**

Rationale: the library must stay cross-platform for planned manga **sharing/export** — a chapter compressed
on iOS must open on Android and elsewhere. HEIC (HEVC) decode is poor/absent off-Apple platforms, so an
iOS-HEIC chapter would not reliably open on Android → **HEIC is rejected** despite its better compression
and native ImageIO support. JPEG re-encode is rejected too (marginal saving + generation loss). WebP is
already the format Android writes (`Bitmap.compress(WEBP_LOSSY)`) and both readers' allow-list accepts it,
so it is the only choice that preserves parity + reader compatibility + the storage saving.

WebP **cannot** be encoded by Apple's ImageIO (decode-only since iOS 14), so we link Google's **libwebp**
and call `WebPEncodeRGBA`. The decode/band stays native (ImageIO/CoreGraphics), libwebp does only the encode.

### How to link libwebp — DECIDED: Option K (see §10 for the shipped implementation)

Both keep WebP output + reader compat + Android parity, and both keep the big pixel buffers in **native**
memory (off the K/N heap). They differ in where the code lives and the build wiring.

**Option K — Kotlin/Native cinterop to a vendored libwebp (recommended for code cleanliness).**
- Decode+band in K/N (the existing `IosAvifDecoder` ImageIO/CoreGraphics pattern → an RGBA `CGBitmapContext`),
  then `WebPEncodeRGBA` via a cinterop binding of `webp/encode.h`. Everything in `:platform/iosMain`,
  mirroring the Skia encoder it replaces — **no Swift bridge, no cross-module plumbing.**
- Build wiring (new — the repo has **no** existing cinterop): vendor a prebuilt **libwebp xcframework**
  (e.g. [awxkee/libwebp-ios](https://github.com/awxkee/libwebp-ios) or [TimOliver/WebP-Cocoa](https://github.com/TimOliver/WebP-Cocoa)),
  add a `libwebp.def` + a `cinterops { }` block to `platform/build.gradle.kts` for `iosArm64` +
  `iosSimulatorArm64`, and link the static lib. This is the only risk: greenfield cinterop config +
  vendoring a multi-slice binary, validated only by a full iOS build.

**Option S — Swift + SPM libwebp + a Kotlin↔Swift encoder bridge.**
- Add [SDWebImage/libwebp-Xcode](https://github.com/SDWebImage/libwebp-Xcode) via SPM to `iosApp`
  (xcodegen `packages:`). A Swift `NativeWebpCompressor` does ImageIO decode + band + `WebPEncodeRGBA`,
  returning the small WebP band bytes; Kotlin zips them with the existing `StoreZipWriter`.
- SPM resolves/builds libwebp automatically (more build-reliable than vendoring), but needs a DI seam:
  a `NativeWebpEncoder` **interface in `:platform`** (so `IosCbzWriter` can call it), its **impl + the
  Swift-registered closure in `:composeApp`** (only `:composeApp`/`:shared` symbols reach the Obj-C header,
  so the registration bridge must live there, like `ReaderNativeBridge`), wired via Koin. Encoder logic
  lives in Swift, not `:platform`.

**Recommendation: Option K** — it keeps the encoder in `:platform` beside the Skia/Desktop one, reuses the
proven `IosAvifDecoder` decode pattern, and avoids the bridge + cross-module DI. Fall back to **Option S**
if the cinterop/vendoring proves troublesome on a real build (SPM is the lower-integration-risk path).

## 4. Tall-strip banding (preserve webtoon quality)

Banding must stay, for two reasons: (a) format max-dimension limits (HEVC ≈ 8192 px, WebP 16383, JPEG
65500), and (b) reader crispness — a single 1280×40000 page renders **blurry** because the reader caps tall
decodes (`maxPixelDimension = 12000`, long-edge), collapsing width. Splitting into ≤8 k-px bands keeps each
piece full-width.

- Band height = `min(FORMAT_MAX_DIM, memoryBudgetHeight(width), sourceHeight)`, mirroring today's
  `effectiveBandHeight`. HEIC's ~8192 cap means ~5 bands for a 40 k-px strip (similar to today's 4–5).
- Peak **native** memory ≈ one full-res decode (~190 MiB) held while banding, then released per page. This
  is the same magnitude as Skia today, **but native** → no K/N GC. Mitigations to benchmark: encode pages
  strictly sequentially and release each page's CGImage before the next; optionally cap finalize concurrency
  to 1 (already effectively the case).

## 5. Avoiding Kotlin/Native huge allocations / GC (the core win)

- Source read via `CGImageSourceCreateWithURL(fileURL)` → **no source `ByteArray`** on the K/N heap.
- Decoded pixels live in **CoreGraphics native memory**, not the K/N heap.
- Only the **encoded output** (≈1–4 MiB/band) is copied into a K/N `ByteArray` to hand to `StoreZipWriter`.
- Net K/N allocation per page drops from ~190 MiB of bitmap churn to a few MiB of encoded bytes →
  K/N GC effectively leaves the critical path → the `DLPERF.mainStall` events should disappear.

## 6. Reader compatibility — unchanged (WebP)

Because the output stays **WebP**, there is **nothing to change** for the reader: the allow-list already
contains `webp`, and both the native (ImageIO) and Compose (Coil/Skia) reader paths already decode WebP —
that's how today's Skia-WebP CBZs are read. Same `page_NNNN.webp` entry naming, same banding, same
`DefaultCbzReader`. Cross-platform/sharing parity with Android is preserved (Android also writes WebP).
Net: this is a **drop-in encoder swap** — identical CBZ bytes-format, only the *encoder* implementation
changes on iOS.

## 7. Recommendation

1. **Output format: WebP** (locked, §3) — unified with Android for cross-platform sharing/export.
2. **Decode/band: native ImageIO/CoreGraphics** (the `IosAvifDecoder` K/N pattern) → RGBA in native memory.
   This + libwebp encode is the actual fix: the ~190 MiB buffers leave the Kotlin/Native heap, so the K/N GC
   stop-the-world stalls disappear.
3. **Encode: libwebp `WebPEncodeRGBA`**, integrated via **Option K** (K/N cinterop, vendored libwebp
   xcframework) for code cleanliness; **Option S** (SPM + Swift + bridge) as fallback if cinterop is
   troublesome. (Decision needed from owner — see §3.)
4. Keep the Kotlin `StoreZipWriter` + `IosCbzWriter` orchestration + banding (reuse, low-risk). Desktop
   keeps Skia. Run finalize at low QoS.

Contained change: a new iOS WebP encoder (`IosLibWebpEncoder`) replaces `SkiaWebpEncoder` **on iOS only**;
the `CbzWriter` interface, the zip writer, the banding contract, the CBZ format, and the download
orchestration are all unchanged.

## 8. Risks

- **libwebp build integration** — the main risk. Option K = greenfield K/N cinterop + a vendored multi-slice
  libwebp xcframework (`iosArm64` + `iosSimulatorArm64`), validated only by a full iOS build; custom repos /
  `FAIL_ON_PROJECT_REPOS` / no-buildSrc make the build delicate. Option S = an SPM dep + cross-module DI
  seam. Either adds a C dependency to the otherwise dependency-light build.
- **Encode-on-RGBA correctness** — must feed libwebp the right pixel layout (RGBA vs BGRA, premultiplied vs
  not) and stride from the `CGBitmapContext`; a mismatch = colour/alpha corruption. Verify against the Skia
  output visually.
- **Native peak memory** (~190 MiB/page transient, native not K/N) — jetsam risk on low-RAM devices;
  encode pages sequentially + release each CGImage/context promptly; consider a stricter band budget on
  low-RAM tiers. (Same magnitude as Skia today, but native + no GC.)
- **cinterop / manual CF memory management** (Option K) — ImageIO + libwebp buffers need explicit
  `CFRelease` / `WebPFree` / `use` discipline (decode precedent: `IosAvifDecoder`); a leak grows native
  memory across many chapters.
- **Encode time** — libwebp at q75 should be comparable to today's Skia WebP (same codec family); confirm it
  isn't slower. Total finalize time is acceptable as long as it's off-UI + GC-free (no jank).

## 9. Benchmark before replacing Skia

On a real device (mid + low tier), for the same giant-strip chapter, compare **Skia/today** vs **ImageIO
HEIC** vs **ImageIO JPEG** (and libwebp if pursued):
- `DLPERF.mainStall`: count + max gap **while scrolling** during finalize (target: ≈ none > ~80 ms).
- `DLPERF.finalize.ms` (total) + per-page encode ms.
- Peak memory (Instruments Allocations / `os_proc_available_memory`).
- Output CBZ size per format (storage saving).
- Reader render check: open the produced CBZ in the **active** reader; confirm pages decode + look crisp.
- HEIC support coverage: log `CGImageDestinationCopyTypeIdentifiers()` + the nil-check result across devices.

Only flip the iOS path once the native option shows **no main-thread stalls while scrolling** and an
acceptable size/time tradeoff, with the reader confirmed able to decode the output.

---

## 10. IMPLEMENTED — Option K (libwebp cinterop), build-validated 2026-06-29

Option K shipped; Option S (Swift bridge) was **not** needed — the vendored static lib links cleanly into
the umbrella framework. Output stays WebP (Android/sharing parity); HEIC stays rejected.

**What was built**
- `platform/libs/libwebp/` — vendored libwebp **1.5.0** built from source (cmake, `BUILD_SHARED_LIBS=OFF`):
  public headers under `include/webp/`, and a combined `libwebp.a` (libwebp + libsharpyuv) per slice:
  `ios-arm64/` (device) and `ios-arm64-simulator/` (sim). arm64 only — no `iosX64`.
- `platform/src/nativeInterop/cinterop/libwebp.def` + per-target `cinterops { create("libwebp") }` blocks
  in `platform/build.gradle.kts` (`includeDirs` + `-staticLibrary libwebp.a -libraryPath <slice>`), and
  `kotlin.mpp.enableCInteropCommonization=true` in `gradle.properties` so the bindings reach shared `iosMain`.
- `platform/.../cbz/IosLibWebpEncoder.kt` — ImageIO/CoreGraphics decode → **CoreGraphics-native** RGBA
  buffer (NOT a K/N `ByteArray`) → vertical banding by pointer (`base + top*rowBytes`) → `WebPEncodeRGBA`
  per band → only the small encoded bytes copied out → `WebPFree`. Drop-in signature == `SkiaWebpEncoder`.
- `platform/.../cbz/IosWebpEncoderFlags.kt` — `USE_LIBWEBP` toggle (default **true**); `IosCbzWriter`
  branches on it (libwebp vs skiko) so the A/B benchmark needs no code edit. Desktop keeps skiko always.
- `platform/.../cbz/IosLibWebpLinkageTest.kt` (iosTest) — permanent linkage regression guard.

**Validation evidence (all green)**
- 3-platform compile gate (Desktop + Android + iOS-sim) `BUILD SUCCESSFUL`.
- `.a` bundled into the cinterop klib `…/included/libwebp.a` (both slices) → propagates downstream.
- `_WebPEncodeRGBA` / `_WebPFree` **defined (`T`)** in *both* device and sim `ComposeApp.framework` binaries
  (an early "undefined-only" read was an `nm | head` truncation artifact — full output shows the `T` defs).
- `:platform:iosSimulatorArm64Test` ran the linkage test on-simulator: **1 test, 0 failures** — i.e.
  `WebPEncodeRGBA` links *and* executes, returning a non-empty WebP for synthetic pixels.

**On-device result (2026-06-29, libwebp default):** a 5-page webtoon chapter (strips up to 1280×40169,
~196 MiB decoded) finalized with **zero `DLPERF.mainStall` events** — the watchdog was active over the
whole encode and detected no main-thread block > 80 ms, vs the Skia baseline's 274–566 ms GC stalls.
`finalize.ms ≈ 11372` (encode-dominated, slightly slower than Skia's ~9.6 s) but entirely off the main
thread, so the COMPRESSING-stage scroll jank is gone. Output: 27 MB loose → **12.4 MB** CBZ (19 WebP
bands), q=75. The core goal — no main-thread stalls while scrolling — is met.

### DLPERF logging — now committed but gated (default off)
The benchmark instrumentation is kept (not stripped) for future encoder/download perf work, gated behind
`BgDownloadLog.DLPERF` (default `false`, mirrors the Swift `ReaderPerfLog.enabled` pattern). When off there
is zero cost — the `ChapterFinalizer` main-thread stall watchdog isn't even launched. To re-run the A/B:
1. Set `BgDownloadLog.DLPERF = true` (and keep `IosWebpEncoderFlags.USE_LIBWEBP = true`), rebuild, run on
   device. Download a **giant webtoon-strip chapter**; while it shows **COMPRESSING**, slow-drag the
   details screen. Capture the `DLPERF.*` log.
2. Flip `IosWebpEncoderFlags.USE_LIBWEBP = false`, rebuild, **delete + re-download the same chapter**,
   repeat the scroll + capture.
3. Compare — both `DLPERF.webpEncode` lines self-identify via `enc=libwebp` / `enc=skia`:
   - `DLPERF.mainStall` count + max gap **while scrolling** (headline — target ≈ none > ~80 ms).
   - `DLPERF.finalize.ms` + per-page `decodeMs`/`totalMs`; `outKiB` (CBZ size parity); memory behaviour.
   - Open the produced CBZ in the reader — pages must decode and webtoon strips stay crisp.
4. Restore `USE_LIBWEBP = true` and `BgDownloadLog.DLPERF = false`.
