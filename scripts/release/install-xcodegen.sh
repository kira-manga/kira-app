#!/usr/bin/env bash
# Install only the reviewed upstream archive; never trust an existing PATH tool.
set +x
set -euo pipefail
umask 077

fail() { printf 'XcodeGen bootstrap failed: %s\n' "$1" >&2; exit 1; }
[[ $# -eq 0 ]] || fail 'this installer takes no overrides'
root="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
/usr/bin/ruby "$root/scripts/release/verify-toolchain-inputs.rb" >&2
[[ "$(uname -s)" == Darwin ]] || fail 'macOS is required'

metadata="$(/usr/bin/ruby -rjson -e '
  tool = JSON.parse(File.read(ARGV.fetch(0))).fetch("xcodegen")
  puts tool.values_at("version", "url", "sha256", "binary_path", "binary_sha256").join("\t")
' "$root/release/verified-tools.json")"
IFS=$'\t' read -r version url archive_sha binary_path binary_sha <<< "$metadata"

temp_root="$(CDPATH= cd -- "${RUNNER_TEMP:-${TMPDIR:-/tmp}}" && pwd -P)"
tool_dir="$(mktemp -d "$temp_root/kira-xcodegen.XXXXXX")"
installed=0
cleanup() { if [[ "$installed" == 0 ]]; then rm -rf -- "$tool_dir"; fi; }
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
[[ "$tool_dir" != *$'\n'* && "$tool_dir" != *$'\r'* ]] || fail 'invalid temporary directory'
archive="$tool_dir/download.zip"

curl --fail --silent --show-error --location --connect-timeout 15 --max-time 90 \
  --proto '=https' --proto-redir '=https' --tlsv1.2 --output "$archive" "$url"

verify_sha256() {
  local actual
  actual="$(/usr/bin/ruby -rdigest -e 'puts Digest::SHA256.file(ARGV.fetch(0)).hexdigest' "$1")"
  [[ "$actual" == "$2" ]] || fail "$3 checksum mismatch"
}
verify_sha256 "$archive" "$archive_sha" archive
# Extraction and execution both happen only after the archive digest matched.
# Retain runtime presets and the license, not the upstream installer or other extras.
unzip -q "$archive" "$binary_path" 'xcodegen/share/xcodegen/SettingPresets/*' \
  'xcodegen/LICENSE' -d "$tool_dir"
executable="$tool_dir/$binary_path"
[[ -f "$executable" && ! -L "$executable" && -x "$executable" ]] || fail 'expected executable is missing'
verify_sha256 "$executable" "$binary_sha" executable
actual_version="$("$executable" --version)"
[[ "$actual_version" == "Version: $version" ]] || fail 'executable version mismatch'
rm -f -- "$archive"
printf '%s\n' "$executable" > "$tool_dir/.kira-xcodegen-owned"

if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'KIRA_XCODEGEN=%s\n' "$executable" >> "$GITHUB_ENV"
fi
printf '%s\n' "$executable"
installed=1
