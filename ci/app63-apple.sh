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

cleanup() {
  code=$?; trap - EXIT INT TERM; set +e
  stop_gradle final; stop_code=$?
  # Literal source/output roots: nested data/download is not the data test module.
  mkdir -p reports/xml/data
  find app/data/build/test-results/iosSimulatorArm64Test -name '*.xml' -exec cp {} reports/xml/data/ \; 2>/dev/null
  python3 control/ci/app63-apple-verify.py cleanup; cleanup_code=$?
  (cd app && shasum -a 256 -c ../reports/app-before.sha256 >/dev/null); app_code=$?
  (cd engine && shasum -a 256 -c ../reports/engine-before.sha256 >/dev/null); engine_code=$?
  printf 'command_exit=%s\nstop_exit=%s\ncleanup_exit=%s\napp_source_exit=%s\nengine_source_exit=%s\n' "$code" "$stop_code" "$cleanup_code" "$app_code" "$engine_code" > reports/result.txt
  printf 'selection_exit=%s\nnative_command_exit=%s\nnative_stop_exit=%s\nnative_verification_exit=%s\nnative_cleanup_exit=%s\n' \
    "$selection_code" "$native_code" "$native_stop_code" "$native_verify_code" "$native_cleanup_code" >> reports/result.txt
  # Fresh checkouts/run-specific output only; no shared dependency cache deletion.
  if [ "$app_code" -eq 0 ] && [ "$engine_code" -eq 0 ] && [ "$stop_code" -eq 0 ] && [ "$cleanup_code" -eq 0 ]; then
    for repo in app engine; do
      for module in '' app composeApp desktopApp core domain data data/local data/remote data/download platform presentation ui sources/contracts sources/engine sources/config sources/legacy source-contract source-engine source-testkit; do
        rm -rf "$repo/${module:+$module/}build" "$repo/${module:+$module/}.gradle" "$repo/${module:+$module/}.kotlin"
      done
    done
    rm -rf "$RUN"
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
if python3 control/ci/app63-apple-verify.py selection > reports/native-selection.log 2>&1; then
  selection_code=0
else selection_code=$?; exit "$selection_code"; fi
# Corrected retained-page fixture only. Production and prior26 passing Native cases unchanged.
set +e
(cd "$APP" && ./gradlew :data:iosSimulatorArm64Test \
  --tests me.manga.kira.data.repository.IosBackgroundArtifactTest.retryRebindsRetainedRosterWithoutRedownloadingVerifiedPageOrAcceptingOldCapture \
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
if [ "$native_stop_code" -ne 0 ] || [ "$native_cleanup_code" -ne 0 ]; then exit 1; fi
# Ordinary Compose iOS compilation passed Apple07; test-fixture-only change does not affect it.
for component_code in "$selection_code" "$native_code" "$native_stop_code" "$native_verify_code" "$native_cleanup_code"; do
  if [ "$component_code" != 0 ]; then exit 1; fi
done
exit 0
