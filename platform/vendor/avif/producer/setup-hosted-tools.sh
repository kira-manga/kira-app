#!/usr/bin/env bash
# Inert preparation. This acquires tools ONLY during a separately admitted hosted workflow run.
set -euo pipefail

[[ "${KIRA_AVIF_PRODUCER_ADMITTED:-}" == native15-reviewed-source-only ]]
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux && "${RUNNER_ARCH:-}" == X64 ]]
[[ "${EXPECTED_RECIPE_COMMIT:-}" =~ ^[0-9a-f]{40}$ && "${GITHUB_SHA:-}" == "$EXPECTED_RECIPE_COMMIT" ]]
[[ "${GITHUB_REF_TYPE:-}" == branch && "${GITHUB_REF_NAME:-}" != main ]]
[[ "$(df -Pk "${RUNNER_TEMP:?}" | awk 'NR==2 {print $4}')" -ge 8388608 ]]
producer="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ "${1:-}" != --owned-stage ]]; then
    exec python3 "$producer/owned_resources.py" run setup
fi
python3 "$producer/owned_resources.py" check-stage
work="$RUNNER_TEMP/avif-native-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}"

timeout --kill-after=15s 120s sudo apt-get -o Acquire::Retries=0 update
timeout --kill-after=15s 120s sudo apt-get -o Acquire::Retries=0 install --yes --no-install-recommends nasm=2.15.05-1
[[ "$(df -Pk "$RUNNER_TEMP" | awk 'NR==2 {print $4}')" -ge 8388608 ]]
# Do not auto-accept new SDK licenses. A missing license requires the primary/owner's decision.
timeout --kill-after=15s 600s "${ANDROID_HOME:?}/cmdline-tools/latest/bin/sdkmanager" \
    --sdk_root="$ANDROID_HOME" 'platforms;android-31' 'build-tools;30.0.3' \
    'ndk;25.2.9519653' 'cmake;3.22.1' </dev/null

wheel_dir="$work/wheels"
venv="$work/python"
mkdir "$wheel_dir"
curl -q --fail --location --max-time 60 --retry 0 \
    'https://files.pythonhosted.org/packages/ab/3b/63fdad828b4cbeb49cef3aad26f3edfbc72f37a0ab54917d445ec0b9d9ff/meson-1.7.0-py3-none-any.whl' \
    --output "$wheel_dir/meson-1.7.0-py3-none-any.whl"
printf '%s  %s\n' ae3f12953045f3c7c60e27f2af1ad862f14dee125b4ed9bcb8a842a5080dbf85 \
    "$wheel_dir/meson-1.7.0-py3-none-any.whl" | sha256sum --check --status
python3 -m venv "$venv"
"$venv/bin/python3" -m pip install --no-index --no-deps "$wheel_dir/meson-1.7.0-py3-none-any.whl"
printf '%s\n' "$venv/bin" >> "${GITHUB_PATH:?}"
[[ "$(df -Pk "$RUNNER_TEMP" | awk 'NR==2 {print $4}')" -ge 8388608 ]]
