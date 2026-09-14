# Android AVIF bounded-native preparation

**SOURCE PREPARATION ONLY. Producer, checks, compilation, native tests, app tests, and device
qualification: NOT_RUN. No AAR is included or bound, and no workflow is active here.**

This is the source handoff for App15's additive per-request native admission. The application
dependency remains `org.aomedia.avif.android:avif:1.3.0.841110fd`. The Android call sites authored
with the new methods are **compile-pending a separately admitted genuine AAR binding**; this
directory is not a substitute dependency or a completed remediation.

## Contents

| Path | Purpose |
| --- | --- |
| `source-manifest.json` | Exact source revisions, selected input/notice hashes, tool versions, resource checks, and explicit non-claims |
| `limits.patch` | Additive Java/JNI API and exception-safe bitmap-unlock lifetime; exactly two upstream files |
| `notices/` | Native payload's license/patent notices, also staged as AAR assets |
| `producer/` | Inert hosted recipe, source preparation, and structural/provenance collectors |
| `producer/README.md` | Primary admission, producer stages, artifacts, limitations, and binding handoff |
| `qualification/` | Inert **real JNI** device tests and the remaining qualification matrix |

No app build script, dependency declaration, protected reference tree, iOS implementation, or
active `.github/workflows/` file is changed by this preparation.

## Native contract

The original Java methods and JNI symbols remain. These public Java wrappers are additive:

```java
boolean getInfoWithLimits(ByteBuffer encoded, int length, Info info,
                          int maxPixels, int maxDimension);
boolean decodeWithLimits(ByteBuffer encoded, int length, Bitmap bitmap,
                         int threads, int maxPixels, int maxDimension);
```

Both are `public static`; the actual new private JNI bridges have the `Native` suffix. Input
must be a direct, position-zero buffer with a positive length no larger than `remaining()`.
Callers must keep its bytes unchanged through the synchronous native call. Output metadata
must be non-null; a decode destination must be non-null, mutable, and not recycled. Invalid
arguments or parse/decode/admission/conversion failure return `false`.

* JNI accepts `maxPixels` in **1..268435456** and `maxDimension` in **1..32768**. Zero is never
  used to mean unbounded. Existing APIs retain the pinned upstream defaults.
* `imageSizeLimit` and `imageDimensionLimit` are assigned **before `avifDecoderParse`** and
  remain on that same decoder through `avifDecoderNextImage`.
* Pinned libavif passes the pixel limit to dav1d's `frame_size_limit`. Pinned dav1d checks the
  actual AV1 frame/tile pixel count before frame decode/allocation. A small container declaration
  cannot by itself admit a larger actual AV1 frame beyond that cap.
* The dimension limit constrains **container axes**. It is **not** an independent actual-AV1-axis
  limit. The fixture controls intentionally document this distinction.
* Metadata success is **not readability/Valid evidence**. In particular, libavif can scale an
  AV1 frame whose dimensions disagree with the container. A small `ispe` is not automatically
  malformed, and rejecting one malformed file would not establish native-limit enforcement.
* The pixel limit and application's byte-per-pixel policy estimates are **not an aggregate
  native-allocator, process-RSS, wall-clock, or encoded-complexity ceiling**. Neither thumbnail
  output nor thread count alone bounds source decode work. Bounded callers pass one thread.
* Native `false` is a conservative failure, not a validated-verbatim success or a typed proof
  of which particular limit was reached.

`ScopedBitmapUnlock` is constructed only after a successful `AndroidBitmap_lockPixels`. Its
destructor covers every subsequent return, including crop/scale failures, in the common
conversion routine used by old and new entrypoints. The device recovery test exercises a
real post-lock failure but does **not** pretend Java alone can count native locks/unlocks.

## Source basis and provenance

The authoritative complete pins and hashes are in `source-manifest.json`:

* libavif `841110fd8723abd0d5439cbb2cfb8e9cfa11f808`;
* dav1d 1.5.1, `42b2b24fb8819f1ed3643aa9cf2a62f03868e3aa`;
* libyuv `464c51a0353c71f08fe45f683d6a97a638d47833`;
* NDK 25.2.9519653, with compiled cpufeatures inputs compared against public `ndk-r25c`
  source commit `e52b1c3b7b38a89aa1643631e8e4fb407f9d8757`.

The libavif pin follows the baseline version suffix and its checked-in Android recipe. This
is **not an attestation that the baseline Maven AAR used these exact build inputs**. The
candidate label `1.3.0.841110fd-kira-limits1` is a local artifact identifier, not a published
Maven coordinate. A future produced candidate must carry its own actual SHA-256 and producer
provenance; there is no claimed byte-identical rebuild or baseline-Maven equivalence.

All four original Android ABIs are required: `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.
The upstream JNI CMake recipe already specifies 16-KiB ELF LOAD alignment. Candidate-byte
inspection checks each produced ABI, but **final APK ZIP alignment and device loading remain
separate qualification gates**.

### Notices

The notice bundle retains the full libavif license, dav1d COPYING, libyuv LICENSE and PATENTS,
the JNI source's Google copyright plus BSD-2-Clause terms, the cpufeatures source's BSD header,
the NDK cpufeatures NOTICE, and Apache-2.0 text. libyuv PATENTS preserves its upstream bytes,
including its lack of a final newline. The cpufeatures source-header notice is supplemental
to the separate NDK NOTICE, not a replacement for it. The recipe stages all eight notices and
the source manifest under `assets/kira-avif-notices/` and checks their produced bytes.

The genuine Ninja bundled with Android SDK CMake 3.22.1 is a **host producer tool**, not an
AAR native payload or a redistributed output here. Its actual version/hash is pending admitted
acquisition and is required in produced provenance. The primary-approved recipe amendment in
`producer/README.md` explains this explicit package binding; it does not claim Ninja 1.12.1.

## Completion boundary

Only the primary may admit hosted execution, acquire/bind the resulting dependency, promote
the inert tests, run qualification, or push. See the producer and qualification READMEs.
Passing a producer's structural checks would establish only a source-bound **candidate**.
It would not establish app compilation, readability, memory safety for every input, device
compatibility, runtime behavior, App64 fallback correctness, or App15 completion.
