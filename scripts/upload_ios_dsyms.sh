#!/usr/bin/env bash
#
# Manually (re)upload every Kira iOS dSYM found on this Mac to Firebase Crashlytics, and report a
# per-UUID success/failure. Use this to:
#   - verify the current build's symbols are uploaded (upload is idempotent — safe to re-run), or
#   - recover after a build where the auto-upload didn't run (e.g. re-launching an old install).
#
# The build itself already GATES on a successful upload (see the "Firebase Crashlytics dSYM upload
# (required)" phase in iosApp/project.yml), so this is a manual backstop / verifier, not a substitute.
#
# Usage:  scripts/upload_ios_dsyms.sh
# Exit:   0 if every dSYM uploaded; non-zero if any failed or none were found.
set -o pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
GSP="$REPO/iosApp/iosApp/GoogleService-Info.plist"
UPLOAD="$(find "$HOME/Library/Developer/Xcode/DerivedData" -path '*firebase-ios-sdk/Crashlytics/upload-symbols' 2>/dev/null | head -1)"

[ -f "$GSP" ]    || { echo "error: GoogleService-Info.plist not found at $GSP"; exit 1; }
[ -n "$UPLOAD" ] || { echo "error: upload-symbols not found — build the iOS app once so SPM resolves firebase-ios-sdk"; exit 1; }

echo "GoogleService-Info.plist : $GSP"
echo "upload-symbols           : $UPLOAD"
echo

fail=0
count=0
while IFS= read -r dsym; do
  count=$((count + 1))
  case "$dsym" in
    *iphoneos*|*Archives*) kind="DEVICE " ;;
    *iphonesimulator*)     kind="SIM    " ;;
    *)                     kind="?      " ;;
  esac
  echo "=== [$kind] $dsym"
  dwarfdump --uuid "$dsym" 2>/dev/null | grep -oE 'UUID: [0-9A-Fa-f-]+' | sed 's/^/    /'
  if "$UPLOAD" -gsp "$GSP" -p ios "$dsym" 2>&1 | grep -q "Successfully uploaded Crashlytics symbols"; then
    echo "    -> uploaded ✅"
  else
    echo "    -> FAILED ❌"
    fail=1
  fi
done < <(find "$HOME/Library/Developer/Xcode/DerivedData" "$HOME/Library/Developer/Xcode/Archives" -name "Kira.app.dSYM" 2>/dev/null)

echo
if [ "$count" -eq 0 ]; then
  echo "No Kira.app.dSYM found. Build the iOS app first (DEBUG_INFORMATION_FORMAT=dwarf-with-dsym is set for all configs)."
  exit 1
fi
if [ "$fail" -eq 0 ]; then
  echo "All $count dSYM bundle(s) uploaded. Give Firebase a few minutes to process, then refresh Crashlytics."
else
  echo "Some uploads FAILED — see ❌ above."
  exit 1
fi
