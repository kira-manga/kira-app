# Inert, primary-admitted hosted producer

**NOT_RUN in its entirety, including shell/Python/Groovy/YAML syntax checks.** These files were
authored and reviewed by reading public pinned sources and local recipe text only. No producer,
checker, dependency/tool download, native build, or workflow dispatch was executed by the author.

## Admission, not an automatic workflow

`avif-native-producer.yml.in` has no trigger at this vendor path. The primary must review and
explicitly admit the exact recipe, resource envelope, public inputs, and any owner decisions
before promoting identical bytes to `.github/workflows/avif-native-producer.yml`. The collector
requires the active workflow to match the template's SHA-256. Promotion is a separate, public-safe
carrier commit; record and review **that resulting full commit SHA** before its sole push.
The narrowly branch-scoped push avoids registering an unvalidated workflow on the default branch.

The proposed workflow:

* runs only for a push to `remediation/app-native15-producer-20260914-01` in `kira-manga/kira-app`,
  refusing `main`, tags, other branches/repositories and non-public repositories;
* binds the primary-admitted carrier's 40-character `GITHUB_SHA == checkout HEAD`, and preserves
  the `native15-reviewed-source-only` resource-admission guard;
* uses pinned action revisions, read-only `contents` permission, sparse checkout of only this
  public directory and the producer workflow, and `persist-credentials: false`;
* has no app build, aggregate tests, release, signing, private configuration, or private secrets;
* uses fresh hosted temporary source/result directories and an isolated Gradle user home;
* uploads only explicit candidate/provenance/notice paths after successful collection.

Do not move this recipe into an active workflow, invoke its scripts, or relax a failed guard as
part of ordinary source review. Missing SDK license acceptance, source availability, package
availability, or runner compatibility requires a primary/owner decision, not silent substitution.

## Declared tools and resource checks

The machine-readable pins live in `../source-manifest.json`.

| Input or boundary | Reviewed recipe |
| --- | --- |
| Hosted image / JDK | `ubuntu-22.04`, Temurin major 17; actual image, JDK patch/binaries/modules recorded |
| Upstream native project | Gradle 7.5 ZIP checksum, AGP 7.4.1, SDK 31 / minSDK 21, build-tools 30.0.3 |
| Android native tools | NDK 25.2.9519653, CMake 3.22.1 |
| Meson | 1.7.0, exact hash-pinned pure-Python wheel, `--no-index --no-deps` install |
| Ninja | Genuine `cmake;3.22.1` SDK package's `bin/ninja`; actual version/SHA-256 pending acquisition and required in provenance |
| NASM | 2.15.05, Ubuntu package `2.15.05-1` |
| Android ABIs | All four: armeabi-v7a, arm64-v8a, x86, x86_64 |
| Native prebuild | Per-ABI dav1d/libyuv prebuilds at most two jobs |
| JNI build | Upstream release / CMake `RelWithDebInfo`; one Gradle worker; JVM 2 GiB / two active processors; compile=2, link=1 pools |
| Disk | At least 8 GiB free before setup and at designated producer stage boundaries |
| Timeout | 45-minute job, 12-minute setup step, 30-minute producer timeout (31-minute containing step) |
| Candidate inspection | AAR at most 64 MiB, at most 256 ZIP members, at most 128 MiB expanded |

These are **admission ceilings/checks, not measured peak disk/RAM/time requirements**. Independent
compile/link pools do not imply a single two-process ceiling. Free-space checks are not a reserved
disk quota, and the JVM heap cap is not a process/native RSS cap. No peak-use or runtime measurement
exists. If the admitted environment cannot satisfy them, stop for review; do not increase them or
reduce ABIs, change the reviewed native/tool package bindings, or use an unrelated cached binary.

### Deliberate primary-approved Ninja amendment

An earlier unexecuted proposal tried to bootstrap Ninja 1.12.1 and bind `CMAKE_MAKE_PROGRAM`.
Public AGP source review showed why that was insufficient: AGP independently prefers Ninja next
to its selected CMake executable, stores that path in the module model, and writes it into the
generated native build command. A CMake cache entry by itself is not proof of AGP's executable.
The primary **explicitly approved replacing the proposed 1.12.1 bootstrap with the genuine Ninja
already supplied by SDK package `cmake;3.22.1`**. The source-bootstrap patch, tool notice, host C++
bootstrap, and optional-parser guard have been removed rather than spoofing discovery.

The current recipe does not copy, overwrite, wrap, relabel, or otherwise modify an SDK binary.
It points `PATH`, Meson's `NINJA`, and `CMAKE_MAKE_PROGRAM` at that actual SDK `bin/ninja`, and
requires AGP's generated `buildTargetsCommandComponents` to select the same resolved executable.
The installed package metadata and the actual Ninja version/SHA-256 are recorded after admitted
acquisition. **Those binary values are currently unknown, not invented and not claimed to be
1.12.1.** NDK 25.2.9519653, compiler/native source revisions, CMake 3.22.1, and the other reviewed
tool package pins are unchanged. This source-only amendment authorizes no execution.

The inspected public Electric Eel source is tools/base commit
[`929c61f966c516f1105e540ac0f27fe3ceff9d12`](https://android.googlesource.com/platform/tools/base/+/929c61f966c516f1105e540ac0f27fe3ceff9d12/):
`internal/cxx/configure/NinjaLocator.kt` (SDK-first discovery),
`internal/cxx/model/CreateCxxModuleModel.kt` / `CxxAbiModel.kt` (chosen command),
`tasks/CmakeFileApiMetadataGenerator.kt` / `internal/cxx/cmake/CmakeFileApiV1.kt` (generated
build-target command), and `internal/cxx/build/CxxRegularBuilder.kt` (execution). These paths are
under `build-system/gradle-core/src/main/java/com/android/build/gradle/`.
`CreateCxxVariantModel.kt` also explains the default release-to-`RelWithDebInfo` mapping.
This source inspection is not a claim to have run AGP 7.4.1: the collector checks the **actual**
resolved producer output and requires that mapping and command equality.

## Stage and script ownership

1. **`setup-hosted-tools.sh`**: checks admission/resources; installs exactly the reviewed NASM,
   SDK/NDK/CMake packages and Meson wheel. SDK stdin is closed: the recipe does not auto-accept new
   licenses. The workflow's separate setup-java action selects the declared JDK major.
2. **`prepare_sources.py`**: fetches exact libavif/dav1d/libyuv Git revisions, compares selected
   source/license bytes and installed NDK cpufeatures inputs, applies the two-file JNI patch,
   adds the Gradle distribution SHA-256 property, stages the native notices,
   and records hashes of deliberately modified inputs.
3. **`rebuild.sh`**: binds the genuine SDK CMake/Ninja tools; prebuilds the genuine dav1d and libyuv
   static libraries for every ABI; invokes only upstream `:avifandroidjni:assembleRelease`. All four prebuilt library
   paths must exist before Gradle. This is important because `LocalLibyuv.cmake` advertises a
   **different fallback source revision**. A fallback is a rejection, not an acceptable substitute.
4. **`native-build.init.gradle`**: producer-only injection of the explicit Ninja executable and
   compile/link pools into the upstream Android library project. It changes no app Gradle file.
5. **`artifact_checks.py`**: inspects the actual candidate ZIP, all four ELF identities/LOAD
   alignments, exact old/new Java declarations on their owner classes, all old/new JNI exports,
   and the produced native notice/source-manifest assets. Uses produced `classes.jar`, `javap`,
   and the pinned NDK's `llvm-nm`, never source text as a replacement for binary API evidence.
6. **`native_build_evidence.py`**: checks actual `RelWithDebInfo` CMake caches, AGP build-command
   executable equality, generated Ninja job pools, and the JNI link edge's references to the
   reviewed prebuilt static libraries; records hashes.
7. **`producer_inputs.py`** and **`collect_provenance.py`**: recheck recipe/source revision and
   modified-source scope, collect actual tool/compiler/distribution/resolved-public-dependency
   hashes, and package the allowlisted candidate and provenance. They do not run native behavior
   tests, app builds/tests, or final APK checks.

No one should execute just the collection/check scripts locally as an unadmitted "quick check".
They are part of the hosted producer lane and include tool execution. The source pin is not a
hermetic-build claim: the hosted OS/JDK patch and resolved Gradle artifacts are observed and
hashed, not all pre-attested by this preparation. No private dependency cache or environment dump
is uploaded. Generated build/source paths in public evidence are hosted-temporary paths only.

## Owned cleanup and bounded operational evidence

`owned_resources.py` is lifecycle plumbing, not another validation gate. Setup creates exactly
`RUNNER_TEMP/avif-native-<run-id>-<attempt>` and a separate
`RUNNER_TEMP/avif-native-evidence-<run-id>-<attempt>`, each marked with the exact run, attempt,
recipe commit, and resolved work path. Existing, unmarked, linked, or differently owned roots
are refused. Both guarded shell stages run with that exact inherited process marker. Wheels and
the Meson venv now live under the work root alongside sources, native builds, and `gradle-home`;
the wheel provenance lookup follows that move without changing any package pin.

The single Gradle batch has an EXIT trap, including ordinary failure and handled INT/TERM, that
immediately stops only its isolated Gradle user home. It invokes the already-installed Gradle
7.5 launcher with `--offline --stop`, never a wrapper download, with a 10-second timeout and
3-second kill grace. A missing daemon home is recorded as skipped. An unsuccessful stop does
not turn a failed batch green and makes an otherwise successful batch fail.

The template's final `always()` cleanup repeats the isolated stop, then signals only same-UID
processes with the exact inherited run marker. Linux pidfds avoid recycled-PID signalling;
TERM has a 5-second grace and KILL a 2-second grace. It then copies bounded relevant native-log
tails and removes only the marked work root. Failed ownership/process inspection, survivors, or
evidence copy failures retain the work and fail cleanup rather than broadening its scope. A failed
Gradle stop still fails cleanup even if the marked-process fallback permits safe work removal.
No global Gradle/Python cache, SDK/NDK/CMake binary, installed OS package, other run, candidate
result directory, or retained evidence directory is deleted. Privileged/unmarked processes are
not force-reaped; the existing setup command timeouts are unchanged.

Setup and rebuild output is streamed to the normal job log while retaining only the latest
1 MiB each. Each of the two stop logs is also capped at 1 MiB. At most 24 allowlisted native
logs/preparation-report tails are retained at 1 MiB each, with a relative-path index: at most
28 MiB of log payload plus small index/status files. These are separate from the fresh candidate
result allowlist. After cleanup, a separate `always()` upload retains only those named logs and
status files for seven days, including failures. The workflow records cleanup's exit status in
its step output and job summary even if the checkout/helper is unavailable. Its two-minute
cleanup step fits inside the unchanged 45-minute job ceiling; it is not extra producer budget.

This cleanup remains **NOT_RUN**. SIGKILL, runner loss, or the overall job deadline can prevent
traps/final steps from running; `always()` is not a guarantee against those failures. A cleanup
log or upload is operational evidence only, never native/app qualification or acceptance.

## Candidate artifact and evidence

On success the producer would emit an **unsigned, unpublished candidate**:

* `avif-1.3.0.841110fd-kira-limits1.aar`;
* `provenance.json`, `artifact-report.json`, `source-preparation.json`, `source-manifest.json`;
* `limits.patch`, `native-build.init.gradle`;
* the native `notices/` and `SHA256SUMS`.

The checksum list covers every other allowlisted output. The provenance binds the exact recipe
commit/tree, source pins, patches, actual candidate and member hashes, tool/compiler inputs,
Gradle distribution/resolved dependency hashes, native build inputs, hosted run, and declared
resource checks. It explicitly leaves native behavior, app compilation/runtime, final APK
packaging, and byte-identical rebuild claims unset or **NOT_RUN**. The embedded source manifest
continues to say source-preparation-only; the separate provenance, not a rewritten embedded
manifest, records a produced candidate's status and SHA-256.

## Primary handoff after an admitted successful run

1. Retain the actual hosted run identifier and candidate/provenance/checksum bundle. Verify their
   consistency and review the actual input hashes and structural report; a dispatch acceptance,
   green upload step, filename, or metadata parse is not enough.
2. Bind **those genuine AAR bytes and SHA-256** in a separately reviewed Android-only dependency
   change. Do not combine patched Java classes with an old `.so`, relabel the baseline AAR, omit
   an ABI, or keep both old and new implementations on the classpath. Account explicitly for any
   needed transitive dependency metadata when moving from Maven to an AAR.
3. Promote the real JNI qualification sources only with that binding; compile and run the
   primary-admitted narrow shipping-target gates listed in `../qualification/README.md`.
4. Leave every unexecuted gate **NOT_RUN**. No Desktop-only verification substitutes for Android
   device or iOS shipping-target evidence. No producer step authorizes a push or release.
