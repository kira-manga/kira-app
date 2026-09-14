# Android AVIF bounded-native candidate binding

**SOURCE-BOUND CANDIDATE, NOT RUNTIME QUALIFIED.** The genuine producer completed structural
checks; Android dependency resolution, compilation, native/app tests and final APK/device
qualification remain **NOT_RUN** for this integration. No workflow is activated here.

The three existing Android `libs.avif` declarations in `:app`, `:composeApp` and `:platform` now
strictly select `org.aomedia.avif.android:avif:1.3.0.841110fd-kira-limits1` from the checked-in
`maven/` layout. This is a local candidate coordinate, **not an upstream or remotely published
release**. The same group/module avoids a parallel old/new AVIF dependency; other platforms'
dependencies are unchanged. No direct-file AAR, fake wrapper, reflective fallback or source
exclusion substitutes for the genuine Java/JNI implementation.

## Bound candidate and metadata

* AAR: **3,002,110 bytes**, SHA-256
  `3fb46514a771c9d8efc38192d80e69031021c3b6f7f5f09a154131ca083c5888`.
* Actual producer: [run34905266420, attempt1](https://github.com/kira-manga/kira-app/actions/runs/34905266420),
  carrier `5b91c57f3d6976a3d5b0f753370d8aabff0fe085`; recipe source
  `08d4de62c495254ce5945d70454ce25afb701790`.
* `candidate/binding.json` pins the AAR/POM, raw public provenance, patched source inputs,
  all eight notices, Java/native payloads and the three activated fixture copies.
  `candidate/provenance.json`, `artifact-report.json` and `source-preparation.json` are exact
  producer artifact bytes, not replacement summaries or runtime results.
* The actual cached upstream POM is retained as `candidate/upstream-avif-1.3.0.841110fd.pom`
  (SHA-256 `9909b13b535c17869462ef113b2d8b942c1974b0f5203c68e894ccd13b8dec39`).
  It declares AAR packaging, **empty dependencies**, no parent/import and no Gradle-metadata
  redirection. The local POM changes **only its version literal**; no transitive metadata is
  guessed, dropped or replaced by a dependency on the old AAR.
* `settings.gradle.kts` checks the fixed AAR and POM sizes/hashes and uses a module-exclusive,
  POM-only local repository. Missing/changed bytes cannot fall back to an upstream AAR;
  unrelated repositories and modules are untouched. The settings guard itself is **NOT_RUN**
  by this source integration; real resolved-artifact/classpath and APK evidence remain required.
* Exact fixture copies are active in
  `platform/src/androidDeviceTest/kotlin/me/manga/kira/platform/image/`. The originals under
  `qualification/androidDeviceTest/` and their historical comments remain byte-identical.
  Activation is not execution: their seven native tests and existing Android suite remain pending.

The original `source-manifest.json`, patch, notices and producer implementation are unchanged.
The manifest's source-preparation/pending fields are intentionally preserved as the original
recipe and embedded AAR asset; **current actual producer observations live in `candidate/`**.
Neither a produced candidate nor this dependency binding is App15 completion.

## Contents

| Path | Purpose |
| --- | --- |
| `source-manifest.json` | Exact source revisions, selected input/notice hashes, tool versions, resource checks, and explicit non-claims |
| `limits.patch` | Additive Java/JNI API and exception-safe bitmap-unlock lifetime; exactly two upstream files |
| `notices/` | Native payload's license/patent notices, also staged as AAR assets |
| `producer/` | Inert hosted recipe, source preparation, and structural/provenance collectors |
| `producer/README.md` | Primary admission, producer stages, artifacts, limitations, and binding handoff |
| `qualification/` | Preserved **real JNI** preparation originals and remaining qualification matrix |
| `candidate/` | Current binding and exact actual public producer evidence |
| `maven/` | Exact candidate AAR and version-only-derived POM for Android resolution |

Only the version catalog and settings repository/integrity binding change dependency selection.
No module build script, protected reference tree, iOS/Desktop implementation or active workflow
is changed by this integration.

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
candidate label `1.3.0.841110fd-kira-limits1` is bound only in this checked-in local Maven
repository, not published remotely. Its actual SHA-256 and producer provenance are retained in
`candidate/`; there is no claimed byte-identical rebuild or baseline-Maven equivalence.

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
AAR native payload or a redistributed output here. Actual provenance records Ninja **1.10.2**,
SHA-256 `6fa84be1efc3ab25d1cf397d0bb35891e5f99316a35d89cd8c04be5898730174`. The
primary-approved recipe amendment in `producer/README.md` preserves the historical source-only
record; it does not claim Ninja 1.12.1. Actual tool evidence is in `candidate/provenance.json`.

## Completion boundary

Only the primary may admit execution, run qualification, change this binding or push. See the
producer and qualification READMEs. The recorded structural checks establish only a source-bound
**candidate**; binding it and copying fixtures do not add runtime evidence.
They do not establish app compilation, readability, memory safety for every input, device
compatibility, runtime behavior, App64 fallback correctness, or App15 completion.
