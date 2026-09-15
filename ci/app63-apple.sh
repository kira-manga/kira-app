#!/usr/bin/env bash
# Validation carrier only; never merge into product branches.
set -euo pipefail
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
export RUN="$RUNNER_TEMP/kira-app63-$GITHUB_RUN_ID"
test ! -e "$RUN"
mkdir -p "$RUN" reports
export GRADLE_USER_HOME="$RUN/gradle" KONAN_DATA_DIR="$RUN/konan"
export TMPDIR="$RUN/tmp/"
mkdir -p "$TMPDIR"
export APP="$GITHUB_WORKSPACE/app" ENGINE="$GITHUB_WORKSPACE/engine"
test "$(git -C "$APP" rev-parse HEAD)" = "$SOURCE_SHA"
test "$(git -C "$ENGINE" rev-parse HEAD)" = "$ENGINE_SHA"
test -d "$DEVELOPER_DIR"
test ! -e "$APP/iosApp/iosApp/GoogleService-Info.plist"
{ git -C app rev-parse HEAD 'HEAD^{tree}'; git -C engine rev-parse HEAD 'HEAD^{tree}'; java -version; xcodebuild -version; } > reports/identity.txt 2>&1
(cd app && git ls-files -z | xargs -0 shasum -a 256) > reports/app-before.sha256
(cd engine && git ls-files -z | xargs -0 shasum -a 256) > reports/engine-before.sha256
xcrun simctl list devices --json > reports/simulators-before.json
ps -axo pid=,comm= > reports/processes-before.txt
stop_gradle() { (cd "$APP" && ./gradlew --stop --console=plain) > "reports/stop-$1.log" 2>&1; }
selection_code=NOT_RUN
native_code=NOT_RUN; native_stop_code=NOT_RUN; native_verify_code=NOT_RUN; native_cleanup_code=NOT_RUN
host_code=NOT_RUN; host_stop_code=NOT_RUN; host_verify_code=NOT_RUN
settings_debug_code=NOT_RUN; settings_release_code=NOT_RUN; settings_verify_code=NOT_RUN
cleanup() {
  code=$?; trap - EXIT INT TERM; set +e
  stop_gradle final; stop_code=$?
  mkdir -p reports/xml/data reports/xml/platform
  find app/platform/build/test-results/iosSimulatorArm64Test -name '*.xml' -exec cp {} reports/xml/platform/ \; 2>/dev/null
  find app/data/build/test-results/iosSimulatorArm64Test -name '*.xml' -exec cp {} reports/xml/data/ \; 2>/dev/null
  python3 control/ci/app63-apple-verify.py cleanup; cleanup_code=$?
  # Optional raw output survives even when Native or host compilation failed; never resolve/adopt here.
  python3 control/ci/app63-apple-verify.py lock > reports/lock-capture.log 2>&1; lock_capture_code=$?
  (cd app && shasum -a 256 -c ../reports/app-before.sha256 >/dev/null); app_code=$?
  (cd engine && shasum -a 256 -c ../reports/engine-before.sha256 >/dev/null); engine_code=$?
  printf 'command_exit=%s\nstop_exit=%s\ncleanup_exit=%s\napp_source_exit=%s\nengine_source_exit=%s\n' "$code" "$stop_code" "$cleanup_code" "$app_code" "$engine_code" > reports/result.txt
  printf 'optional_lock_capture_exit=%s\n' "$lock_capture_code" >> reports/result.txt
  printf 'selection_exit=%s\nnative_command_exit=%s\nnative_stop_exit=%s\nnative_verification_exit=%s\nnative_cleanup_exit=%s\nhost_command_exit=%s\nhost_stop_exit=%s\nhost_verification_exit=%s\nsettings_Debug_command_exit=%s\nsettings_Release_command_exit=%s\nsettings_verification_exit=%s\n' \
    "$selection_code" "$native_code" "$native_stop_code" "$native_verify_code" "$native_cleanup_code" \
    "$host_code" "$host_stop_code" "$host_verify_code" \
    "$settings_debug_code" "$settings_release_code" "$settings_verify_code" >> reports/result.txt
  # Fresh checkouts/run-specific output only; no shared dependency cache deletion.
  if [ "$app_code" -eq 0 ] && [ "$engine_code" -eq 0 ] && [ "$stop_code" -eq 0 ] && [ "$cleanup_code" -eq 0 ]; then
    for repo in app engine; do
      for module in '' app composeApp desktopApp core domain data data/local data/remote data/download platform presentation ui sources/contracts sources/engine sources/config sources/legacy source-contract source-engine source-testkit; do
        rm -rf "$repo/${module:+$module/}build" "$repo/${module:+$module/}.gradle" "$repo/${module:+$module/}.kotlin"
      done
    done
    rm -rf "$RUN" app/iosApp/iosApp.xcodeproj
    echo owned_outputs_removed=true >> reports/result.txt
  else code=1; fi
  df -h . >> reports/result.txt
  exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
df -h . > reports/resources.txt
python3 -c 'import shutil; assert shutil.disk_usage(".").free >= 8*1024**3'
# Verify bound inputs before Native work, independently of host project generation.
if python3 control/ci/app63-apple-verify.py selection > reports/native-selection.log 2>&1; then
  selection_code=0
else selection_code=$?; exit "$selection_code"; fi
# Native first: --continue preserves independent platform results if data compilation fails.
set +e
(cd "$APP" && ./gradlew :platform:iosSimulatorArm64Test \
  --tests me.manga.kira.platform.download.IosBackgroundTransportTest \
  --tests me.manga.kira.platform.download.IosBackgroundCompletionTest \
  --tests me.manga.kira.platform.backup.BackupByteBudgetTest \
  --tests me.manga.kira.platform.backup.BoundedBackupZipBackendTest \
  :data:iosSimulatorArm64Test \
  --tests me.manga.kira.data.repository.IosBackgroundArtifactTest \
  --tests me.manga.kira.data.repository.IosCbzFinalizationTest \
  --tests me.manga.kira.data.complaint.DebugComplaintServiceIsolationTest \
  --tests me.manga.kira.data.backup.BackupJsonAdmissionTest --continue \
  --include-build "$ENGINE" --no-daemon --no-parallel --max-workers=1 \
  --no-build-cache --no-configuration-cache --console=plain --stacktrace \
  -Pkotlin.compiler.execution.strategy=in-process -PkiraUseMavenLocal=false \
  -Dorg.gradle.vfs.watch=false '-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g') > reports/native.log 2>&1
native_code=$?
stop_gradle native
native_stop_code=$?
python3 control/ci/app63-apple-verify.py native > reports/native-verify.log 2>&1
native_verify_code=$?
python3 control/ci/app63-apple-verify.py cleanup > reports/cleanup-native.log 2>&1
native_cleanup_code=$?
if [ "$native_cleanup_code" -eq 0 ]; then
  mv reports/cleanup.json reports/cleanup-native.json || native_cleanup_code=$?
fi
set -e
printf 'command_exit=%s\nstop_exit=%s\nverification_exit=%s\ncleanup_exit=%s\n' \
  "$native_code" "$native_stop_code" "$native_verify_code" "$native_cleanup_code" > reports/native-status.txt
# Test/compile failures do not skip the host. Stop/owned-worker cleanup failures are safety barriers.
if [ "$native_stop_code" -ne 0 ] || [ "$native_cleanup_code" -ne 0 ]; then exit 1; fi
# Known campaign-pinned generator; no Homebrew or ambient executable selection.
curl --proto '=https' --proto-redir '=https' --tlsv1.2 --fail --silent --show-error --location --retry 0 --max-time 60 --max-filesize 4286070 \
  https://github.com/yonaskolb/XcodeGen/releases/download/2.46.0/xcodegen.artifactbundle.zip -o "$RUN/xcodegen.zip"
echo "ef6d0a23bfb7393387f98e321ffd78a487231172e2e78c48d3c26275c263fd0c  $RUN/xcodegen.zip" | shasum -a 256 -c -
python3 control/ci/app63-apple-verify.py generator
GENERATOR="$RUN/xcodegen/xcodegen.artifactbundle/xcodegen-2.46.0-macosx/bin/xcodegen"
"$GENERATOR" --version | tee reports/xcodegen-version.txt
grep -Fx 'Version: 2.46.0' reports/xcodegen-version.txt
"$GENERATOR" generate --spec "$APP/iosApp/project.yml" --project "$APP/iosApp" > reports/xcodegen.log 2>&1
python3 control/ci/app63-apple-verify.py project
# Only the generated embed-phase argv is augmented for exact Engine substitution/resource limits.
set +e
xcodebuild -project "$APP/iosApp/iosApp.xcodeproj" -scheme iosApp \
  -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$RUN/DerivedData" -clonedSourcePackagesDirPath "$RUN/SourcePackages" \
  -packageCachePath "$RUN/swiftpm-cache" -jobs 1 ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  CLANG_MODULE_CACHE_PATH="$RUN/clang-cache" SWIFT_MODULE_CACHE_PATH="$RUN/swift-cache" build > reports/host-build.log 2>&1
host_code=$?
stop_gradle host
host_stop_code=$?
set -e
if [ "$host_stop_code" -ne 0 ]; then exit 1; fi
for config in Debug Release; do
  if xcodebuild -project "$APP/iosApp/iosApp.xcodeproj" -scheme iosApp -configuration "$config" \
    -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$RUN/DerivedData" -clonedSourcePackagesDirPath "$RUN/SourcePackages" \
    -packageCachePath "$RUN/swiftpm-cache" -disableAutomaticPackageResolution -showBuildSettings -json \
    > "reports/settings-$config.json" 2> "reports/settings-$config.log"; then
    settings_code=0
  else settings_code=$?; fi
  if [ "$config" = Debug ]; then settings_debug_code=$settings_code
  else settings_release_code=$settings_code; fi
done
set +e
python3 control/ci/app63-apple-verify.py settings > reports/settings-verify.log 2>&1
settings_verify_code=$?
python3 control/ci/app63-apple-verify.py host > reports/host-verify.log 2>&1
host_verify_code=$?
set -e
printf 'command_exit=%s\nstop_exit=%s\nverification_exit=%s\nsettings_Debug_command_exit=%s\nsettings_Release_command_exit=%s\nsettings_verification_exit=%s\n' \
  "$host_code" "$host_stop_code" "$host_verify_code" "$settings_debug_code" "$settings_release_code" "$settings_verify_code" > reports/host-status.txt
# A later passing component must never erase an earlier failure.
for component_code in "$selection_code" "$native_code" "$native_stop_code" "$native_verify_code" "$native_cleanup_code" \
  "$host_code" "$host_stop_code" "$host_verify_code" "$settings_debug_code" "$settings_release_code" "$settings_verify_code"; do
  if [ "$component_code" != 0 ]; then exit 1; fi
done
exit 0
