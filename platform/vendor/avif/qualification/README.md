# Genuine native and shipping-target qualification handoff

**All runtime/app qualification is NOT_RUN.** The genuine candidate is now source-bound by
`../candidate/binding.json`; its producer's structural result is not runtime qualification.
The original Kotlin files under this `androidDeviceTest/` directory remain outside active source
sets and byte-identical as preparation history. Exact copies of `AvifNativeLimitTestSupport.kt`,
`AvifNativeLimitsDeviceTest.kt` and `AvifNativeLimitsFailureDeviceTest.kt` are now active under
`platform/src/androidDeviceTest/kotlin/me/manga/kira/platform/image/`. No original was moved,
rewritten or deleted, and no test was run by activation.

The active copies use the existing `AvifTestFixtures`, Android Bitmaps,
and direct `AvifDecoder` calls. **No reflection, host doubles, mocks of native admission, legacy
fallback, conditional missing-method skip, fake artifact, or baseline binary substitution** can
qualify the new native contract. Missing Java/JNI methods or a missing ABI must fail qualification.

## Fixture and decisive controls

`AvifTestFixtures.regular()` is 10,775 bytes and declares 320 x 640:

`4bc2db8cd43917b79f8c4b2ec4f0e4e5e7588336aef8dfbeedbd4326b6fd3a61`

`declaredSmall()` changes only the big-endian U32 `ispe` width and height at offsets **221** and
**225** to 32. It leaves the AV1 payload untouched and asserts both complete fixture hashes:

`82e85ff73dc302e51665ea1ee1b713a63a44ed660cff994be1c0366eb2d7eb31`

The real AV1 frame remains 320 x 640 = **204800 pixels**; container metadata is 32 x 32 = 1024.
The following controls must execute against the **same actual candidate**:

| Control | Required result |
| --- | --- |
| Bounded metadata, maxPixels=1024, maxDimension=32 | Success, reports 32 x 32; **not** readability proof |
| Legacy decode of the declared-small fixture | Success and newly drawn opaque pixels |
| Bounded decode, maxPixels=204799, maxDimension=32 | `false` |
| Bounded decode, maxPixels=204800, maxDimension=32 | Success and newly drawn opaque pixels |
| Bounded decode, maxPixels=204801, maxDimension=32 | Success and newly drawn opaque pixels |
| Bounded decode to 32 x 32, maxPixels=204800, maxDimension=32 | Success despite actual AV1 axes exceeding 32 |

Positive controls clear the bitmap before each new decode so previously drawn pixels cannot
stand in for a successful output conversion. If legacy/equal/+1 controls do not succeed, the
smaller-cap `false` is **not evidence** that the pixel cap was responsible. Do not weaken the
controls to make a broken or malformed-only fixture pass.

## Prepared real JNI suites

`AvifNativeLimitsDeviceTest` has four tests:

* ordinary 320 x 640 metadata/decode pixel and container-axis boundaries;
* the declared-small fixture's metadata versus actual AV1 pixel-cap controls;
* the independent actual-axis **non-guarantee**;
* both old and new APIs decoding the ordinary tall fixture.

`AvifNativeLimitsFailureDeviceTest` has three tests:

* zero/negative/too-large limits and lengths, heap/null/nonzero-position/short-limit inputs,
  null metadata, and recovery with a valid input;
* native decode limit checks, null input/destination, immutable/recycled destination rejection,
  and successful real decode afterward;
* real failure **after bitmap locking**, followed by reconfiguration and reuse of the same Bitmap.

The post-lock test creates an ARGB destination **32769 x 1** (about 128 KiB), which the pinned
`avifImageScale` rejects after locking. It then reconfigures that same allocation to 64 x 64,
clears it, and requires a successful bounded decode and drawn pixels. This is a modest-memory
recovery scenario, not an OOM experiment. Static RAII lifetime inspection plus this real recovery
is the evidence; Java-side recovery alone cannot prove an exact native lock/unlock count.

## Remaining gates owned by the primary

The producer does not run any of the following. Admit resource use and select exact narrow task
names from the current integrated build rather than running a root/aggregate build.

1. **Bound-candidate resolution and Android compilation:** all old/new Java and native API compatibility,
   the shipping `:platform` call sites, and the promoted device tests. Record exact commit/tree,
   dependency SHA-256, toolchain, task, and result. Missing-method compilation is not a skip.
2. **Real Android native behavior:** execute the above seven tests on admitted real JNI device/
   emulator ABIs. The four-ABI producer inspection is not four-ABI runtime coverage. Record ABI,
   API level, page size, native `versionString`, candidate hash, test count, and failures.
3. **Shared Kotlin allowance and permit regression:** run the authored common allowance and
   Android tests (`AvifNativeAllowanceTest`, `AndroidAvifNativeAllowanceDeviceTest`,
   `AndroidAvifPermitDeviceTest`, `AndroidAvifPermitFailureDeviceTest`) plus Android request-size,
   allocation, cancellation, decode-failure, and source-budget regressions. Specifically retain
   post-acquire/pre-return-dispatch cancellation, queued cancellation/interruption, one-owner
   maximum, no permit leak/over-release, and successful subsequent acquisition. These tests must
   not replace observed ordering with sleeps or bypass the shared production permit.
4. **All live Android AVIF paths:** inspect and qualify display decode, synchronous page inspection,
   and the `core.cbz.CbzImageDecoder` / `OptimizedCbzManager` path. The synchronous inspector must
   share the same process permit and dynamic allowance without `runBlocking` or nested acquisition.
   Every accepted payload needs a real bounded pixel decode; metadata-only or native-false results
   must not become Valid/validated-verbatim success. Preserve **App64's typed complete-roster
   fallback**. The primary owns the CBZ integration, not this vendor directory.
5. **Routing and authenticated images:** preserve App2's source-header handling and App17's SVG/
   AVIF routing. Qualify required routing and decode-failure/cancellation regressions against the
   actual integrated call sites; native admission must not swallow cancellation into fallback.
6. **Android packaging/loading:** inspect the final APK's packaged ABI payloads and ZIP alignment,
   and qualify native loading on the admitted shipping page-size/API/device matrix (including
   required 16-KiB support). AAR ELF alignment alone does not establish APK compatibility.
7. **iOS shipping regressions separately:** App16's ImageIO work is metadata-first and allocator/RSS
   estimates remain estimates. Keep its planning/cleanup/cancellation tests and unchanged
   `IosSvgDecoderRoutingTest`. This Android native patch makes no stronger iOS memory guarantee.

There is no measured whole-process RSS bound or exhaustive hostile-input proof in this handoff.
Record each genuinely run gate with its own evidence; keep all others **NOT_RUN**. A produced
candidate, one green device test, or an accepted Telegram/hosted notification is not final done.
