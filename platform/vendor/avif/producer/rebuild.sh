#!/usr/bin/env bash
# PREPARATION ONLY / NOT_RUN. The primary must admit the exact hosted recipe commit first.
set -euo pipefail

diagnostic_stage=admission
report_failure() {
    local status="$?"
    if [[ "$status" -ne 0 ]]; then
        printf 'producer_failure stage=%s exit_status=%s\n' "$diagnostic_stage" "$status" >&2
    fi
}
trap report_failure EXIT

[[ "${KIRA_AVIF_PRODUCER_ADMITTED:-}" == native15-reviewed-source-only ]]
diagnostic_stage=hosted-platform
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux && "${RUNNER_ARCH:-}" == X64 ]]
diagnostic_stage=expected-recipe-format
[[ "${EXPECTED_RECIPE_COMMIT:-}" =~ ^[0-9a-f]{40}$ ]]
diagnostic_stage=event-recipe-binding
[[ "${GITHUB_SHA:-}" == "$EXPECTED_RECIPE_COMMIT" ]]
diagnostic_stage=branch
[[ "${GITHUB_REF_TYPE:-}" == branch && "${GITHUB_REF_NAME:-}" != main ]]

diagnostic_stage=recipe-path
recipe="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
diagnostic_stage=recipe-head
actual_head="$(git -C "$recipe" rev-parse HEAD)"
printf 'producer_binding stage=recipe-head actual=%q\n' "$actual_head"
[[ "$actual_head" == "$EXPECTED_RECIPE_COMMIT" ]]
diagnostic_stage=run-paths
work="${RUNNER_TEMP:?}/avif-native-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
result="${RUNNER_TEMP}/avif-native-result"
if [[ "${1:-}" != --owned-stage ]]; then
    diagnostic_stage=owned-supervisor
    exec python3 "$recipe/producer/owned_resources.py" run rebuild
fi
diagnostic_stage=owned-stage
python3 "$recipe/producer/owned_resources.py" check-stage
diagnostic_stage=tool-environment
ndk="${ANDROID_HOME:?}/ndk/25.2.9519653"
cmake="${ANDROID_HOME}/cmake/3.22.1/bin/cmake"
export LC_ALL=C TZ=UTC PYTHONDONTWRITEBYTECODE=1 GIT_TERMINAL_PROMPT=0
export ANDROID_SDK_ROOT="$ANDROID_HOME" ANDROID_NDK_HOME="$ndk"
export PATH="${ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
export CMAKE_BUILD_PARALLEL_LEVEL=2 MAKEFLAGS=-j2
export GRADLE_USER_HOME="$work/gradle-home"

probe() {
    # Fixed public metadata/version commands only: no shell trace, environment dump, or eval.
    local label="$1" target="$2" capture bytes status
    shift 2
    diagnostic_stage="$label"
    capture="$(mktemp "$work/.preflight.XXXXXX")"
    if timeout --kill-after=2s 10s "$@" </dev/null 2>&1 | head -c 4097 > "$capture"; then
        status=0
    else
        status=$?
    fi
    bytes="$(wc -c < "$capture")"
    printf 'producer_preflight stage=%s executable=%q target=%q exit_status=%s captured_bytes=%s\n' \
        "$label" "$1" "$target" "$status" "$bytes"
    if [[ "$bytes" -gt 4096 ]]; then
        rm -- "$capture"
        echo 'producer_preflight output_oversized=true; truncated values are not accepted.' >&2
        return 1
    fi
    probe_value=''
    if IFS= read -r -d '' probe_value < "$capture"; then
        rm -- "$capture"
        echo 'producer_preflight output_contains_nul=true; non-text values are not accepted.' >&2
        return 1
    fi
    rm -- "$capture"
    # read preserves trailing newlines; %q retains the entire bounded value safely in the log.
    printf 'producer_preflight stage=%s actual=%q\n' "$label" "$probe_value"
    # Match the original command-substitution comparisons, which strip trailing newlines.
    while [[ "$probe_value" == *$'\n' ]]; do
        probe_value="${probe_value%$'\n'}"
    done
    return "$status"
}

check_space() {
    local available
    probe "$diagnostic_stage" "$RUNNER_TEMP" df -Pk "$RUNNER_TEMP"
    available="$(printf '%s' "$probe_value" | awk 'NR==2 {print $4}')"
    printf 'producer_preflight stage=%s available_kib=%q\n' "$diagnostic_stage" "$available"
    [[ "$available" =~ ^[0-9]+$ && "$available" -ge 8388608 ]] || {
        echo 'Producer stopped: invalid disk observation or <8 GiB free.' >&2
        exit 1
    }
}

diagnostic_stage=preflight-disk
check_space
probe ndk-revision "$ndk/source.properties" sed -n 's/^Pkg.Revision = //p' "$ndk/source.properties"
[[ "$probe_value" == 25.2.9519653 ]]
probe cmake-revision "${ANDROID_HOME}/cmake/3.22.1/source.properties" \
    sed -n 's/^Pkg.Revision = //p' "${ANDROID_HOME}/cmake/3.22.1/source.properties"
[[ "$probe_value" == 3.22.1 ]]
probe cmake-version "$cmake" "$cmake" --version
[[ "${probe_value%%$'\n'*}" == 'cmake version 3.22.1' ]]
diagnostic_stage=meson-path
meson_path="$(command -v meson)"
probe meson-version "$meson_path" "$meson_path" --version
[[ "$probe_value" == 1.7.0 ]]
diagnostic_stage=nasm-path
nasm_path="$(command -v nasm)"
probe nasm-version "$nasm_path" "$nasm_path" -v
[[ "$(printf '%s' "$probe_value" | awk '{print $3}')" == 2.15.05 ]]
diagnostic_stage=java-path
java_path="${JAVA_HOME:?}/bin/java"
probe java-version "$java_path" "$java_path" -version
[[ "${probe_value%%$'\n'*}" == *'"17.'* ]]
diagnostic_stage=ninja-path
export KIRA_AVIF_NINJA="${ANDROID_HOME}/cmake/3.22.1/bin/ninja"
export NINJA="$KIRA_AVIF_NINJA"
export PATH="${ANDROID_HOME}/cmake/3.22.1/bin:$PATH"
ninja_path="$(command -v ninja)"
printf 'producer_preflight stage=ninja-path expected=%q actual=%q\n' "$KIRA_AVIF_NINJA" "$ninja_path"
[[ -x "$KIRA_AVIF_NINJA" && "$ninja_path" == "$KIRA_AVIF_NINJA" ]]
# Deliberate primary-approved tool rebind: use the real SDK payload AGP itself selects.
# Its exact version/SHA-256 are pending acquisition and must be recorded, never invented.
probe ninja-version "$KIRA_AVIF_NINJA" "$KIRA_AVIF_NINJA" --version
diagnostic_stage=result-directory
mkdir "$result"
diagnostic_stage=source-preparation
python3 "$recipe/producer/prepare_sources.py" "$recipe" "$work" "$ndk"

abis=(armeabi-v7a arm64-v8a x86 x86_64)
arches=(arm aarch64 x86 x86_64)
dav1d="$work/libavif/ext/dav1d"
libyuv="$work/libavif/ext/libyuv"
for index in "${!abis[@]}"; do
    abi="${abis[$index]}"
    diagnostic_stage="prebuild-disk-$abi"
    check_space
    diagnostic_stage="dav1d-setup-$abi"
    meson setup "$dav1d/build/$abi" "$dav1d" --default-library=static --buildtype=release \
        --cross-file="$dav1d/package/crossfiles/${arches[$index]}-android.meson" \
        -Denable_tools=false -Denable_tests=false -Denable_examples=false
    diagnostic_stage="dav1d-compile-$abi"
    meson compile -C "$dav1d/build/$abi" -j2
    diagnostic_stage="libyuv-configure-$abi"
    "$cmake" -S "$libyuv" -B "$libyuv/build/$abi" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$KIRA_AVIF_NINJA" \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DCMAKE_DISABLE_FIND_PACKAGE_JPEG=ON -DANDROID_PLATFORM=android-21 -DANDROID_ABI="$abi"
    diagnostic_stage="libyuv-build-$abi"
    "$cmake" --build "$libyuv/build/$abi" --target yuv --parallel 2
    # Fail instead of silently using a different LOCAL fallback revision/producer path.
    diagnostic_stage="prebuilt-libraries-$abi"
    [[ -s "$dav1d/build/$abi/src/libdav1d.a" && -s "$libyuv/build/$abi/libyuv.a" ]]
done

stop_gradle_after_batch() {
    local build_status="$?"
    trap - EXIT
    if ! python3 "$recipe/producer/owned_resources.py" stop-gradle; then
        # Preserve the original batch failure; a failed stop must not make a successful batch green.
        [[ "$build_status" -ne 0 ]] || build_status=1
    fi
    exit "$build_status"
}

diagnostic_stage=gradle-disk
check_space
diagnostic_stage=gradle-batch
(
    trap stop_gradle_after_batch EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    cd "$work/libavif/android_jni"
    ./gradlew :avifandroidjni:assembleRelease --no-daemon --max-workers=1 \
        --init-script "$recipe/producer/native-build.init.gradle" -Pandroid.builder.sdkDownload=false \
        -Dorg.gradle.parallel=false '-Dorg.gradle.jvmargs=-Xmx2g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'
)
diagnostic_stage=collection-disk
check_space
diagnostic_stage=provenance-collection
python3 "$recipe/producer/collect_provenance.py" "$recipe" "$work" "$result" "$ndk"
echo 'Produced a source-bound AAR candidate only. Native/app qualification remains NOT_RUN.'
