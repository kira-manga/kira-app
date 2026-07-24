#!/usr/bin/env bash
set -euo pipefail

# This script is intentionally write-protected by an explicit confirmation flag. It validates first,
# streams secrets to gh without putting them in argv, configures the documented backend signing
# secrets, and only imports a source document when --publish-source-config is also requested.

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/release/apply-android-release-config.sh .secrets/android-release.env --confirm-apply
  scripts/release/apply-android-release-config.sh .secrets/android-release.env --confirm-apply --publish-source-config

The second form also imports the configured source document and verifies the public endpoint. This
script is never run automatically by CI.
EOF
}

config_file=${1:-}
confirm_apply=false
publish_source=false
for arg in "${@:2}"; do
  case "$arg" in
    --confirm-apply) confirm_apply=true ;;
    --publish-source-config) publish_source=true ;;
    *) usage; exit 64 ;;
  esac
done
if [[ -z "$config_file" || "$confirm_apply" != true ]]; then
  usage
  printf 'Refusing to modify GitHub or backend state without --confirm-apply.\n' >&2
  exit 64
fi
if [[ "$publish_source" == true && ! -f "$config_file" ]]; then
  printf 'Refusing source publication because the configuration file is missing.\n' >&2
  exit 2
fi

script_dir=$(cd "$(dirname "$0")" && pwd)
app_root=$(cd "$script_dir/../.." && pwd)
"$script_dir/validate-android-release-config.sh" "$config_file"

config_dir=$(cd "$(dirname "$config_file")" && pwd)
config_file="$config_dir/$(basename "$config_file")"
while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
  [[ "$line" =~ ^[[:space:]]*([A-Z][A-Z0-9_]*)=(.*)$ ]] || continue
  name=${BASH_REMATCH[1]}
  value=${BASH_REMATCH[2]}
  if [[ ${#value} -ge 2 && ${value:0:1} == "\"" && ${value: -1} == "\"" ]] ||
    [[ ${#value} -ge 2 && ${value:0:1} == "'" && ${value: -1} == "'" ]]; then
    value=${value:1:${#value}-2}
  fi
  printf -v "$name" '%s' "$value"
done < "$config_file"

resolve_path() {
  if [[ "$1" == /* ]]; then printf '%s' "$1"; else printf '%s/%s' "$config_dir" "$1"; fi
}
value_for() {
  local name=$1 file_name="${1}_FILE" value=${!1:-} file=${!file_name:-}
  if [[ -n "$file" ]]; then value=$(<"$(resolve_path "$file")"); fi
  printf '%s' "$value"
}
base64_encode_file() { base64 < "$1" | tr -d '[:space:]'; }

app_repo=${KIRA_APP_REPOSITORY:-kira-manga/kira-app}
backend_repo=${KIRA_BACKEND_REPOSITORY:-kira-manga/Kira-backend}
play_environment=google-play-internal

set_environment_secret() {
  local name=$1 value=$2
  if ! printf '%s' "$value" | gh secret set "$name" --repo "$app_repo" --env "$play_environment" >/dev/null; then
    printf 'FAIL GitHub environment secret: %s\n' "$name" >&2
    exit 1
  fi
  printf 'Applied GitHub environment secret: %s\n' "$name"
}

set_repository_variable() {
  local name=$1 value=$2
  if ! gh variable set "$name" --repo "$app_repo" --body "$value" >/dev/null; then
    printf 'FAIL GitHub repository variable: %s\n' "$name" >&2
    exit 1
  fi
  printf 'Applied GitHub repository variable: %s\n' "$name"
}

keystore_value=${ANDROID_KEYSTORE_BASE64:-}
if [[ -n "${ANDROID_KEYSTORE_FILE:-}" ]]; then keystore_value=$(base64_encode_file "$(resolve_path "$ANDROID_KEYSTORE_FILE")"); fi
firebase_value=${GOOGLE_SERVICES_JSON:-}
if [[ -n "${GOOGLE_SERVICES_JSON_FILE:-}" ]]; then firebase_value=$(base64_encode_file "$(resolve_path "$GOOGLE_SERVICES_JSON_FILE")"); fi
play_json_value=$(value_for GOOGLE_PLAY_SERVICE_ACCOUNT_JSON)
store_password=$(value_for ANDROID_KEYSTORE_PASSWORD)
key_alias=$(value_for ANDROID_KEY_ALIAS)
key_password=$(value_for ANDROID_KEY_PASSWORD)

set_environment_secret ANDROID_KEYSTORE_BASE64 "$keystore_value"
set_environment_secret ANDROID_KEYSTORE_PASSWORD "$store_password"
set_environment_secret ANDROID_KEY_ALIAS "$key_alias"
set_environment_secret ANDROID_KEY_PASSWORD "$key_password"
set_environment_secret GOOGLE_SERVICES_JSON "$firebase_value"
set_environment_secret GOOGLE_PLAY_SERVICE_ACCOUNT_JSON "$play_json_value"

set_repository_variable KIRA_SOURCE_CONFIG_BASE_URL "$(value_for KIRA_SOURCE_CONFIG_BASE_URL)"
set_repository_variable KIRA_SOURCE_CONFIG_PINNED_KEYS "$(value_for KIRA_SOURCE_CONFIG_PINNED_KEYS)"
if [[ -n "${ANDROID_BUILD_NUMBER_OFFSET:-}" ]]; then
  set_repository_variable ANDROID_BUILD_NUMBER_OFFSET "$ANDROID_BUILD_NUMBER_OFFSET"
fi

key_id=$(value_for SOURCE_CONFIG_KEY_ID)
[[ -n "$key_id" ]] || key_id=$(value_for KIRA_SIGNING_ACTIVE_KEY_ID)
private_key_path=${SOURCE_CONFIG_PRIVATE_KEY_FILE:-${KIRA_SIGNING_PRIVATE_KEY_FILE:-}}
private_key_file=$(resolve_path "${private_key_path:?validated private key path is required}")
key_directory=$(dirname "$private_key_file")
if ! "$app_root/../kira-backend/scripts/signing/install-github-secret.sh" "$backend_repo" "$key_id" "$key_directory" >/dev/null; then
  printf 'FAIL backend signing secret installation. No private key was printed.\n' >&2
  exit 1
fi
printf 'Applied backend signing secrets through the documented signing helper.\n'

if [[ "$publish_source" == true ]]; then
  tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/kira-source-publish.XXXXXX")
  chmod 700 "$tmp_dir"
  trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM
  base_url=$(value_for KIRA_SOURCE_CONFIG_BASE_URL); base_url=${base_url%/}
  admin_email=$(value_for SOURCE_CONFIG_ADMIN_EMAIL)
  admin_password=$(value_for SOURCE_CONFIG_ADMIN_PASSWORD)
  document_file=$(resolve_path "${SOURCE_CONFIG_DOCUMENT_FILE:?validated source document path is required}")

  ADMIN_EMAIL="$admin_email" ADMIN_PASSWORD="$admin_password" jq -n \
    '{email: env.ADMIN_EMAIL, password: env.ADMIN_PASSWORD}' > "$tmp_dir/login.json"
  login_status=$(curl --silent --show-error --location --max-time 30 --proto '=https' --tlsv1.2 \
    -o "$tmp_dir/login-response.json" -w '%{http_code}' \
    -H 'Content-Type: application/json' --data-binary "@$tmp_dir/login.json" \
    "$base_url/api/v1/auth/login" || true)
  if [[ "$login_status" != 200 ]]; then
    printf 'FAIL backend admin login returned HTTP %s. Response body was discarded.\n' "$login_status" >&2
    exit 1
  fi
  admin_token=$(jq -er '.accessToken' "$tmp_dir/login-response.json" >/dev/null 2>&1 && jq -er '.accessToken' "$tmp_dir/login-response.json")
  printf 'Backend admin authentication succeeded.\n'

  printf 'header = "Authorization: Bearer %s"\n' "$admin_token" > "$tmp_dir/curl.conf"
  chmod 600 "$tmp_dir/curl.conf"
  import_status=$(curl --silent --show-error --location --max-time 60 --proto '=https' --tlsv1.2 \
    --config "$tmp_dir/curl.conf" -o "$tmp_dir/import-response.json" -w '%{http_code}' \
    -X POST -H 'Content-Type: application/json' --data-binary "@$document_file" \
    "$base_url/api/v1/admin/sources/import-bundled" || true)
  if [[ "$import_status" != 200 ]]; then
    printf 'FAIL source-config import returned HTTP %s. Response body was discarded.\n' "$import_status" >&2
    exit 1
  fi
  printf 'Source-config document imported; backend created the signed snapshot.\n'

  document_status=$(curl --silent --show-error --location --max-time 30 --proto '=https' --tlsv1.2 \
    --config "$tmp_dir/curl.conf" -D "$tmp_dir/document.headers" -o "$tmp_dir/document.json" \
    -w '%{http_code}' "$base_url/api/v1/source-config/document" || true)
  if [[ "$document_status" != 200 ]]; then
    printf 'FAIL source-config document endpoint returned HTTP %s (expected 200, not 404).\n' "$document_status" >&2
    exit 1
  fi
  live_key_id=$(awk -F': *' 'tolower($1) == "x-config-signing-key-id" { print $2 }' "$tmp_dir/document.headers" | tr -d '\r')
  if [[ "$live_key_id" != "$key_id" ]]; then
    printf 'FAIL source-config document was signed by an unexpected key ID.\n' >&2
    exit 1
  fi
  printf 'Source-config document endpoint returned HTTP 200 with the expected signing key ID.\n'
fi

printf 'Apply completed. No secret values were printed.\n'
