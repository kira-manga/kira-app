#!/usr/bin/env bash
# PREPARATION ONLY / NOT_RUN. The primary must admit the exact hosted recipe commit first.
set -euo pipefail

[[ "${KIRA_AVIF_PRODUCER_ADMITTED:-}" == native15-reviewed-source-only ]]
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux && "${RUNNER_ARCH:-}" == X64 ]]
[[ "${EXPECTED_RECIPE_COMMIT:-}" =~ ^[0-9a-f]{40}$ ]]
[[ "${GITHUB_SHA:-}" == "$EXPECTED_RECIPE_COMMIT" ]]
[[ "${GITHUB_REF_TYPE:-}" == branch && "${GITHUB_REF_NAME:-}" != main ]]

recipe="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$(git -C "$recipe" rev-parse HEAD)" == "$EXPECTED_RECIPE_COMMIT" ]]
work="${RUNNER_TEMP:?}/avif-native-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"
result="${RUNNER_TEMP}/avif-native-result"
if [[ "${1:-}" != --owned-stage ]]; then
    exec python3 "$recipe/producer/owned_resources.py" run rebuild
fi
python3 "$recipe/producer/owned_resources.py" check-stage
ndk="${ANDROID_HOME:?}/ndk/25.2.9519653"
cmake="${ANDROID_HOME}/cmake/3.22.1/bin/cmake"
export LC_ALL=C TZ=UTC PYTHONDONTWRITEBYTECODE=1 GIT_TERMINAL_PROMPT=0
export ANDROID_SDK_ROOT="$ANDROID_HOME" ANDROID_NDK_HOME="$ndk"
export PATH="${ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
export CMAKE_BUILD_PARALLEL_LEVEL=2 MAKEFLAGS=-j2
export GRADLE_USER_HOME="$work/gradle-home"

check_space() {
    local available
    available="$(df -Pk "$RUNNER_TEMP" | awk 'NR==2 {print $4}')"
    [[ "$available" -ge 8388608 ]] || { echo 'Producer stopped: <8 GiB free.' >&2; exit 1; }
}

check_space
[[ "$(sed -n 's/^Pkg.Revision = //p' "$ndk/source.properties")" == 25.2.9519653 ]]
[[ "$(sed -n 's/^Pkg.Revision = //p' "${ANDROID_HOME}/cmake/3.22.1/source.properties")" == 3.22.1 ]]
[[ "$("$cmake" --version | head -1)" == 'cmake version 3.22.1' ]]
[[ "$(meson --version)" == 1.7.0 ]]
[[ "$(nasm -v | awk '{print $3}')" == 2.15.05 ]]
[[ "$("${JAVA_HOME:?}/bin/java" -version 2>&1 | head -1)" == *'"17.'* ]]
export KIRA_AVIF_NINJA="${ANDROID_HOME}/cmake/3.22.1/bin/ninja"
export NINJA="$KIRA_AVIF_NINJA"
export PATH="${ANDROID_HOME}/cmake/3.22.1/bin:$PATH"
[[ -x "$KIRA_AVIF_NINJA" && "$(command -v ninja)" == "$KIRA_AVIF_NINJA" ]]
# Deliberate primary-approved tool rebind: use the real SDK payload AGP itself selects.
# Its exact version/SHA-256 are pending acquisition and must be recorded, never invented.
"$KIRA_AVIF_NINJA" --version
mkdir "$result"
python3 "$recipe/producer/prepare_sources.py" "$recipe" "$work" "$ndk"

abis=(armeabi-v7a arm64-v8a x86 x86_64)
arches=(arm aarch64 x86 x86_64)
dav1d="$work/libavif/ext/dav1d"
libyuv="$work/libavif/ext/libyuv"
for index in "${!abis[@]}"; do
    check_space
    abi="${abis[$index]}"
    meson setup "$dav1d/build/$abi" "$dav1d" --default-library=static --buildtype=release \
        --cross-file="$dav1d/package/crossfiles/${arches[$index]}-android.meson" \
        -Denable_tools=false -Denable_tests=false -Denable_examples=false
    meson compile -C "$dav1d/build/$abi" -j2
    "$cmake" -S "$libyuv" -B "$libyuv/build/$abi" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$KIRA_AVIF_NINJA" \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DCMAKE_DISABLE_FIND_PACKAGE_JPEG=ON -DANDROID_PLATFORM=android-21 -DANDROID_ABI="$abi"
    "$cmake" --build "$libyuv/build/$abi" --target yuv --parallel 2
    # Fail instead of silently using a different LOCAL fallback revision/producer path.
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

check_space
(
    trap stop_gradle_after_batch EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    cd "$work/libavif/android_jni"
    ./gradlew :avifandroidjni:assembleRelease --no-daemon --max-workers=1 \
        --init-script "$recipe/producer/native-build.init.gradle" -Pandroid.builder.sdkDownload=false \
        -Dorg.gradle.parallel=false '-Dorg.gradle.jvmargs=-Xmx2g -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8'
)
check_space
python3 "$recipe/producer/collect_provenance.py" "$recipe" "$work" "$result" "$ndk"
echo 'Produced a source-bound AAR candidate only. Native/app qualification remains NOT_RUN.'
