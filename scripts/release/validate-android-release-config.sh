#!/usr/bin/env bash
set -euo pipefail

# Validate the local release configuration without printing any value. This file deliberately uses
# a small assignment parser instead of sourcing the config, so command substitutions in a password or
# JSON value cannot execute while validation is running.

config_file=${1:-.secrets/android-release.env}
if [[ ! -f "$config_file" ]]; then
  printf 'FAIL configuration file is missing: %s\n' "$config_file" >&2
  exit 2
fi
if [[ ! -r "$config_file" ]]; then
  printf 'FAIL configuration file is not readable\n' >&2
  exit 2
fi

config_dir=$(cd "$(dirname "$config_file")" && pwd)
config_file=$(cd "$(dirname "$config_file")" && pwd)/$(basename "$config_file")
failures=0
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/kira-android-release-validate.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

while IFS= read -r line || [[ -n "$line" ]]; do
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
  if [[ ! "$line" =~ ^[[:space:]]*([A-Z][A-Z0-9_]*)=(.*)$ ]]; then
    printf 'FAIL malformed configuration line (line content hidden)\n' >&2
    failures=$((failures + 1))
    continue
  fi
  name=${BASH_REMATCH[1]}
  value=${BASH_REMATCH[2]}
  if [[ ${#value} -ge 2 && ${value:0:1} == "\"" && ${value: -1} == "\"" ]] ||
    [[ ${#value} -ge 2 && ${value:0:1} == "'" && ${value: -1} == "'" ]]; then
    value=${value:1:${#value}-2}
  fi
  printf -v "$name" '%s' "$value"
done < "$config_file"

trimmed() {
  local value=${1:-}
  value=${value#"${value%%[!$' \t\r\n']*}"}
  value=${value%"${value##*[!$' \t\r\n']}"}
  printf '%s' "$value"
}

resolve_path() {
  local path=$1
  if [[ "$path" == /* ]]; then
    printf '%s' "$path"
  else
    printf '%s/%s' "$config_dir" "$path"
  fi
}

value_for() {
  local name=$1 file_name="${1}_FILE" value file
  value=${!name:-}
  file=${!file_name:-}
  if [[ -n "$file" ]]; then
    file=$(resolve_path "$file")
    if [[ ! -r "$file" ]]; then
      return 1
    fi
    value=$(<"$file")
  fi
  printf '%s' "$value"
}

file_for() {
  # Bash expands every RHS in one `local` command before applying any assignment. Keeping these as
  # separate commands is required: otherwise `${!name}` can resolve a stale/global `name` left by
  # the config parser instead of the variable name supplied to this function.
  local name=$1
  local file=${!name:-}
  [[ -n "$file" ]] || return 1
  resolve_path "$file"
}

is_placeholder() {
  local value
  value=$(trimmed "${1:-}")
  [[ -z "$value" || "$value" == *REPLACE_ME* || "$value" == *CHANGE_ME* ||
    "$value" == *TODO* || "$value" == *PASTE_* || "$value" == /absolute/path/* ||
    "$value" == \<*\> || "$value" == *example.com* || "$value" == *yami-local-placeholder* ]]
}

pass() { printf 'PASS %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; failures=$((failures + 1)); }

require_value() {
  local label=$1 name=$2 value
  if ! value=$(value_for "$name"); then
    fail "$label file reference is unreadable"
  elif is_placeholder "$value"; then
    fail "$label is empty or still a placeholder"
  else
    pass "$label is present"
  fi
}

check_file_reference() {
  local label=$1 name=$2 path
  if ! path=$(file_for "$name"); then
    fail "$label path is not configured"
  elif [[ ! -f "$path" || ! -r "$path" ]]; then
    fail "$label path is missing or unreadable"
  else
    pass "$label path is readable"
  fi
}

base64_decode() {
  if base64 --decode </dev/null >/dev/null 2>&1; then
    base64 --decode
  else
    base64 -D
  fi
}

# Use the same OpenSSL 3 / Ed25519 selection rule as the backend's generate-key.sh. macOS's system
# LibreSSL binary cannot reliably parse the PKCS#8/X.509 Ed25519 files produced by that tool.
openssl_bin=${OPENSSL_BIN:-}
if [[ -z "$openssl_bin" ]]; then
  for candidate in /opt/homebrew/opt/openssl@3/bin/openssl /usr/local/opt/openssl@3/bin/openssl "$(command -v openssl)"; do
    if [[ -x "$candidate" ]] && "$candidate" list -public-key-algorithms 2>/dev/null | grep -qi Ed25519; then
      openssl_bin=$candidate
      break
    fi
  done
fi
if [[ -n "$openssl_bin" ]]; then
  pass "OpenSSL supports Ed25519 key validation"
else
  fail "OpenSSL 3 with Ed25519 support is unavailable"
fi

require_value "Android keystore password" ANDROID_KEYSTORE_PASSWORD
require_value "Android key alias" ANDROID_KEY_ALIAS
require_value "Android key password" ANDROID_KEY_PASSWORD

keystore_path=''
if [[ -n "${ANDROID_KEYSTORE_FILE:-}" ]]; then
  if keystore_path=$(resolve_path "$ANDROID_KEYSTORE_FILE") && [[ -f "$keystore_path" && -r "$keystore_path" ]]; then
    pass "Android keystore file is readable"
  else
    fail "Android keystore file is missing or unreadable"
    keystore_path=''
  fi
elif ! is_placeholder "${ANDROID_KEYSTORE_BASE64:-}"; then
  if printf '%s' "${ANDROID_KEYSTORE_BASE64:-}" | tr -d '[:space:]' | base64_decode > "$tmp_dir/upload.jks" 2>/dev/null &&
    [[ -s "$tmp_dir/upload.jks" ]]; then
    keystore_path="$tmp_dir/upload.jks"
    pass "Android keystore Base64 decodes to a file"
  else
    fail "Android keystore Base64 is invalid or empty"
  fi
else
  fail "Android keystore file or Base64 value is not configured"
fi

keystore_password=''; key_alias=''; key_password=''
keystore_password=$(value_for ANDROID_KEYSTORE_PASSWORD 2>/dev/null || true)
key_alias=$(value_for ANDROID_KEY_ALIAS 2>/dev/null || true)
key_password=$(value_for ANDROID_KEY_PASSWORD 2>/dev/null || true)
if [[ -n "$keystore_path" && -n "$keystore_password" && -n "$key_alias" && -n "$key_password" ]]; then
  if KEYTOOL_STOREPASS="$keystore_password" KEYTOOL_KEYPASS="$key_password" \
    keytool -list -keystore "$keystore_path" -storepass:env KEYTOOL_STOREPASS \
      -alias "$key_alias" >/dev/null 2>&1 &&
    KEYTOOL_STOREPASS="$keystore_password" KEYTOOL_KEYPASS="$key_password" \
      keytool -certreq -keystore "$keystore_path" -storepass:env KEYTOOL_STOREPASS \
        -alias "$key_alias" -keypass:env KEYTOOL_KEYPASS -file "$tmp_dir/upload-key.csr" >/dev/null 2>&1; then
    pass "Android keystore opens and configured alias/key password are valid"
  else
    fail "Android keystore credentials or alias are invalid"
  fi
else
  fail "Android keystore credential validation could not run"
fi

firebase_json=''
if [[ -n "${GOOGLE_SERVICES_JSON_FILE:-}" ]]; then
  if firebase_file=$(resolve_path "$GOOGLE_SERVICES_JSON_FILE") && [[ -r "$firebase_file" ]]; then
    if jq empty < "$firebase_file" >/dev/null 2>&1; then
      pass "Firebase JSON file is readable and valid JSON"
      firebase_json=valid
    else
      fail "Firebase JSON file is not valid JSON"
    fi
  else
    fail "Firebase JSON file is missing or unreadable"
  fi
else
  if ! is_placeholder "${GOOGLE_SERVICES_JSON:-}" &&
    printf '%s' "$GOOGLE_SERVICES_JSON" | tr -d '[:space:]' | base64_decode > "$tmp_dir/google-services.json" 2>/dev/null &&
    jq empty < "$tmp_dir/google-services.json" >/dev/null 2>&1; then
    pass "Firebase Base64 decodes to valid JSON"
    firebase_json=valid
  else
    fail "Firebase JSON file or Base64 value is missing or invalid"
  fi
fi

play_json=''
if [[ -n "${GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_FILE:-}" ]]; then
  if play_file=$(resolve_path "$GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_FILE") && [[ -r "$play_file" ]] &&
    jq empty < "$play_file" >/dev/null 2>&1; then
    pass "Google Play service-account JSON file is readable and valid JSON"
    play_json=valid
  else
    fail "Google Play service-account JSON file is missing, unreadable, or invalid"
  fi
else
  if ! is_placeholder "${GOOGLE_PLAY_SERVICE_ACCOUNT_JSON:-}" &&
    printf '%s' "$GOOGLE_PLAY_SERVICE_ACCOUNT_JSON" | jq empty >/dev/null 2>&1; then
    pass "Google Play service-account JSON value is valid JSON"
    play_json=valid
  else
    fail "Google Play service-account JSON file or value is missing or invalid"
  fi
fi

base_url=$(value_for KIRA_SOURCE_CONFIG_BASE_URL 2>/dev/null || true)
if [[ "$base_url" =~ ^https://[^/@?#]+/?$ ]]; then
  pass "Source-config base URL uses HTTPS and has no credentials/query/fragment"
else
  fail "Source-config base URL must be an HTTPS origin"
fi

key_id=$(value_for SOURCE_CONFIG_KEY_ID 2>/dev/null || true)
[[ -n "$key_id" ]] || key_id=$(value_for KIRA_SIGNING_ACTIVE_KEY_ID 2>/dev/null || true)
if [[ "$key_id" =~ ^[A-Za-z0-9._-]{1,64}$ ]] && ! is_placeholder "$key_id"; then
  pass "Source-config key ID has the required format"
else
  fail "Source-config key ID is missing or invalid"
fi

private_key_file=''; public_key_file=''
if [[ -z "${SOURCE_CONFIG_PRIVATE_KEY_FILE:-}" && -n "${KIRA_SIGNING_PRIVATE_KEY_FILE:-}" ]]; then
  SOURCE_CONFIG_PRIVATE_KEY_FILE=$KIRA_SIGNING_PRIVATE_KEY_FILE
fi
if [[ -z "${SOURCE_CONFIG_PUBLIC_KEY_FILE:-}" && -n "${KIRA_SIGNING_VERIFICATION_KEYS_0_PUBLIC_KEY_FILE:-}" ]]; then
  SOURCE_CONFIG_PUBLIC_KEY_FILE=$KIRA_SIGNING_VERIFICATION_KEYS_0_PUBLIC_KEY_FILE
fi
if private_key_file=$(file_for SOURCE_CONFIG_PRIVATE_KEY_FILE 2>/dev/null); then
  if [[ -f "$private_key_file" && -r "$private_key_file" && -s "$private_key_file" ]]; then pass "Source-config private key file is readable and non-empty"; else fail "Source-config private key file is missing, unreadable, or empty"; fi
else
  fail "Source-config private key file path is not configured"
fi
if public_key_file=$(file_for SOURCE_CONFIG_PUBLIC_KEY_FILE 2>/dev/null); then
  if [[ -f "$public_key_file" && -r "$public_key_file" && -s "$public_key_file" ]]; then pass "Source-config public key file is readable and non-empty"; else fail "Source-config public key file is missing, unreadable, or empty"; fi
else
  fail "Source-config public key file path is not configured"
fi

private_der="$tmp_dir/private.der"
public_der="$tmp_dir/public.der"
derived_public_der="$tmp_dir/derived-public.der"
if [[ -n "$private_key_file" && -f "$private_key_file" && -r "$private_key_file" && -s "$private_key_file" &&
  -n "$public_key_file" && -f "$public_key_file" && -r "$public_key_file" && -s "$public_key_file" ]]; then
  if ! "$openssl_bin" pkey -inform DER -in "$private_key_file" -noout >/dev/null 2>&1; then
    if ! tr -d '[:space:]' < "$private_key_file" | base64_decode > "$private_der" 2>/dev/null; then
      fail "Source-config private key is neither DER nor valid Base64"
    fi
  else
    cp "$private_key_file" "$private_der"
  fi
  if ! "$openssl_bin" pkey -inform DER -in "$private_der" -pubout -outform DER -out "$derived_public_der" >/dev/null 2>&1; then
    fail "Source-config private key is not a usable Ed25519 private key"
  fi
  if ! "$openssl_bin" pkey -pubin -inform DER -in "$public_key_file" -noout >/dev/null 2>&1; then
    if ! tr -d '[:space:]' < "$public_key_file" | base64_decode > "$public_der" 2>/dev/null; then
      fail "Source-config public key is neither DER nor valid Base64"
    fi
  else
    cp "$public_key_file" "$public_der"
  fi
  if "$openssl_bin" pkey -pubin -inform DER -in "$public_der" -outform DER -out "$tmp_dir/normalized-public.der" >/dev/null 2>&1 &&
    cmp -s "$derived_public_der" "$tmp_dir/normalized-public.der"; then
    pass "Source-config public key matches the private key"
  else
    fail "Source-config public key does not match the private key"
  fi
else
  fail "Source-config key-pair verification could not run"
fi

pinned_keys=$(value_for KIRA_SOURCE_CONFIG_PINNED_KEYS 2>/dev/null || true)
if [[ -n "$pinned_keys" && "$pinned_keys" != *$'\n'* && "$pinned_keys" != *[[:space:]]* ]]; then
  pin_ok=1
  old_ifs=$IFS; IFS=','
  read -r -a entries <<< "$pinned_keys"
  IFS=$old_ifs
  for entry in "${entries[@]}"; do
    pin_id=${entry%%=*}; pin_b64=${entry#*=}
    if [[ ! "$pin_id" =~ ^[A-Za-z0-9._-]{1,64}$ || ! "$pin_b64" =~ ^[A-Za-z0-9+/]+={0,2}$ ]]; then
      pin_ok=0; break
    fi
    if ! printf '%s' "$pin_b64" | base64_decode > "$tmp_dir/pinned.der" 2>/dev/null ||
      ! "$openssl_bin" pkey -pubin -inform DER -in "$tmp_dir/pinned.der" -outform DER -out /dev/null >/dev/null 2>&1; then
      pin_ok=0; break
    fi
  done
  if (( pin_ok == 1 )); then pass "Pinned-key serialization is valid key-id=Base64-X.509"; else fail "Pinned-key serialization is invalid"; fi
else
  fail "Pinned-key serialization is empty or contains whitespace"
fi

if [[ -n "$key_id" && -n "$pinned_keys" && -n "${public_der:-}" && -s "${public_der:-/dev/null}" ]]; then
  expected_public_b64=$(base64 < "$public_der" | tr -d '[:space:]')
  if printf '%s\n' "$pinned_keys" | tr ',' '\n' | awk -F= -v expected="$key_id" -v public="$expected_public_b64" '$1 == expected && substr($0, index($0, "=") + 1) == public { found = 1 } END { exit(found ? 0 : 1) }'; then
    pass "Pinned key contains the generated public key for the active key ID"
  else
    fail "Pinned key does not contain the generated public key for the active key ID"
  fi
fi

for pair in \
  "KIRA_SIGNING_ACTIVE_KEY_ID:$key_id" \
  "KIRA_SIGNING_VERIFICATION_KEYS_0_KEY_ID:$(value_for KIRA_SIGNING_VERIFICATION_KEYS_0_KEY_ID 2>/dev/null || true)"; do
  name=${pair%%:*}; value=${pair#*:}
  if [[ "$value" == "$key_id" && -n "$key_id" ]]; then pass "$name matches active source-config key ID"; else fail "$name does not match active source-config key ID"; fi
done

document_file=''
if document_file=$(file_for SOURCE_CONFIG_DOCUMENT_FILE 2>/dev/null) && [[ -r "$document_file" ]] && jq empty < "$document_file" >/dev/null 2>&1; then
  pass "Source-config document file is readable and valid JSON"
else
  fail "Source-config document file is missing, unreadable, or invalid JSON"
fi

admin_email=$(value_for SOURCE_CONFIG_ADMIN_EMAIL 2>/dev/null || true)
admin_password=$(value_for SOURCE_CONFIG_ADMIN_PASSWORD 2>/dev/null || true)
if [[ -n "$admin_email" && "$admin_email" == *@*.* ]] && ! is_placeholder "$admin_email"; then
  pass "Source-config admin email is present"
else
  fail "Source-config admin email is missing or invalid"
fi
if ! is_placeholder "$admin_password"; then
  pass "Source-config admin password is present"
else
  fail "Source-config admin password is missing or still a placeholder"
fi

if (( failures > 0 )); then
  printf 'Validation failed: %d check(s) failed. No values were printed.\n' "$failures" >&2
  exit 1
fi
printf 'Validation passed: all Android, Play, Firebase, source-signing, and publication inputs are ready.\n'
