#!/usr/bin/env bash
# Remove only the private directory published by install-xcodegen.sh.
set +x
set -euo pipefail

fail() { printf 'XcodeGen cleanup failed: %s\n' "$1" >&2; exit 1; }
[[ $# -eq 0 ]] || fail 'this cleanup takes no overrides'
executable="${KIRA_XCODEGEN:-}"
[[ -n "$executable" ]] || exit 0
temp_root="$(CDPATH= cd -- "${RUNNER_TEMP:-${TMPDIR:-/tmp}}" && pwd -P)"
suffix='/xcodegen/bin/xcodegen'
[[ "$executable" == *"$suffix" ]] || fail 'not an installer-owned executable'
tool_dir="${executable%"$suffix"}"
[[ "${tool_dir%/*}" == "$temp_root" &&
   "${tool_dir##*/}" =~ ^kira-xcodegen\.[a-zA-Z0-9]{6}$ ]] || fail 'not an installer-owned temporary path'
[[ ! -L "$tool_dir" && ! -L "$tool_dir/xcodegen" &&
   ! -L "$tool_dir/xcodegen/bin" && ! -L "$executable" ]] || fail 'refusing symlinked XcodeGen paths'
[[ -e "$tool_dir" ]] || exit 0
marker="$tool_dir/.kira-xcodegen-owned"
[[ -d "$tool_dir" && -f "$marker" && ! -L "$marker" &&
   "$(< "$marker")" == "$executable" ]] || fail 'ownership marker is missing or invalid'
rm -rf -- "$tool_dir"
