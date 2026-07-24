#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
app_root=$(cd "$script_dir/../../.." && pwd)
validator="$app_root/scripts/release/validate-android-release-config.sh"
backend_key_tool="$app_root/../kira-backend/scripts/signing/generate-key.sh"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/kira-release-validator-test.XXXXXX")
trap 'chmod 600 "$tmp_dir"/unreadable.public.b64 2>/dev/null || true; rm -rf "$tmp_dir"' EXIT HUP INT TERM
umask 077

pass() { printf 'PASS %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1" >&2; exit 1; }

key_id=validator-regression
mkdir -p "$tmp_dir/signing"
"$backend_key_tool" "$key_id" "$tmp_dir/signing" >/dev/null
private_key_file="$tmp_dir/signing/$key_id.private.b64"
public_key_file="$tmp_dir/signing/$key_id.public.b64"
public_key_b64=$(tr -d '[:space:]' < "$public_key_file")

printf '%s\n' '{"project_info":{"project_id":"validator-test"}}' > "$tmp_dir/google-services.json"
printf '%s\n' '{"type":"service_account","project_id":"validator-test"}' > "$tmp_dir/play-service-account.json"
printf '%s\n' '{"schemaVersion":1,"revision":4,"sources":[]}' > "$tmp_dir/source-document.json"
printf '%s' 'validator-store-password' > "$tmp_dir/store-password.txt"
printf '%s' 'validator-store-password' > "$tmp_dir/key-password.txt"
printf '%s' 'validator-upload' > "$tmp_dir/key-alias.txt"
printf '%s' 'release-admin@example.invalid' > "$tmp_dir/admin-email.txt"
printf '%s' 'validator-admin-password' > "$tmp_dir/admin-password.txt"

KEYTOOL_STOREPASS='validator-store-password' KEYTOOL_KEYPASS='validator-store-password' \
  keytool -genkeypair -storetype PKCS12 -keystore "$tmp_dir/upload.jks" \
    -storepass:env KEYTOOL_STOREPASS -keypass:env KEYTOOL_KEYPASS \
    -alias validator-upload -keyalg RSA -keysize 2048 -validity 2 \
    -dname 'CN=Validator Test, O=Kira, C=EG' >/dev/null 2>&1

write_config() {
  local destination=$1 configured_private=$2 configured_public=$3
  {
    printf 'ANDROID_KEYSTORE_BASE64=\n'
    printf 'ANDROID_KEYSTORE_FILE=%s\n' "$tmp_dir/upload.jks"
    printf 'ANDROID_KEYSTORE_PASSWORD=\n'
    printf 'ANDROID_KEYSTORE_PASSWORD_FILE=%s\n' "$tmp_dir/store-password.txt"
    printf 'ANDROID_KEY_ALIAS=\n'
    printf 'ANDROID_KEY_ALIAS_FILE=%s\n' "$tmp_dir/key-alias.txt"
    printf 'ANDROID_KEY_PASSWORD=\n'
    printf 'ANDROID_KEY_PASSWORD_FILE=%s\n' "$tmp_dir/key-password.txt"
    printf 'GOOGLE_SERVICES_JSON=\n'
    printf 'GOOGLE_SERVICES_JSON_FILE=%s\n' "$tmp_dir/google-services.json"
    printf 'GOOGLE_PLAY_SERVICE_ACCOUNT_JSON=\n'
    printf 'GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_FILE=%s\n' "$tmp_dir/play-service-account.json"
    printf 'KIRA_SOURCE_CONFIG_BASE_URL=https://api.kiramanga.me\n'
    printf 'KIRA_SOURCE_CONFIG_PINNED_KEYS=%s=%s\n' "$key_id" "$public_key_b64"
    printf 'SOURCE_CONFIG_KEY_ID=%s\n' "$key_id"
    printf 'SOURCE_CONFIG_PRIVATE_KEY_FILE=%s\n' "$configured_private"
    printf 'SOURCE_CONFIG_PUBLIC_KEY_FILE=%s\n' "$configured_public"
    printf 'KIRA_SIGNING_ACTIVE_KEY_ID=%s\n' "$key_id"
    printf 'KIRA_SIGNING_PRIVATE_KEY=\n'
    printf 'KIRA_SIGNING_PRIVATE_KEY_FILE=%s\n' "$configured_private"
    printf 'KIRA_SIGNING_VERIFICATION_KEYS_0_KEY_ID=%s\n' "$key_id"
    printf 'KIRA_SIGNING_VERIFICATION_KEYS_0_PUBLIC_KEY=\n'
    printf 'KIRA_SIGNING_VERIFICATION_KEYS_0_PUBLIC_KEY_FILE=%s\n' "$configured_public"
    printf 'SOURCE_CONFIG_DOCUMENT_FILE=%s\n' "$tmp_dir/source-document.json"
    printf 'SOURCE_CONFIG_ADMIN_EMAIL=\n'
    printf 'SOURCE_CONFIG_ADMIN_EMAIL_FILE=%s\n' "$tmp_dir/admin-email.txt"
    printf 'SOURCE_CONFIG_ADMIN_PASSWORD=\n'
    printf 'SOURCE_CONFIG_ADMIN_PASSWORD_FILE=%s\n' "$tmp_dir/admin-password.txt"
    printf 'ANDROID_BUILD_NUMBER_OFFSET=1000\n'
  } > "$destination"
  chmod 600 "$destination"
}

valid_config="$tmp_dir/valid.env"
valid_output="$tmp_dir/valid.output"
write_config "$valid_config" "$private_key_file" "$public_key_file"
if ! "$validator" "$valid_config" > "$valid_output" 2>&1; then
  grep -E '^(PASS|FAIL|Validation)' "$valid_output" >&2 || true
  fail "file-backed source signing configuration should validate"
fi
grep -Fq 'PASS Source-config private key file is readable and non-empty' "$valid_output" ||
  fail "readable private key file check did not pass"
grep -Fq 'PASS Source-config public key file is readable and non-empty' "$valid_output" ||
  fail "readable public key file check did not pass"
grep -Fq 'PASS Source-config public key matches the private key' "$valid_output" ||
  fail "Ed25519 key-pair verification did not run or pass"
if grep -Fq "$tmp_dir" "$valid_output" || grep -Fq "$public_key_b64" "$valid_output"; then
  fail "validator output exposed a path or key value"
fi
pass "readable non-empty *_FILE key inputs validate"
pass "Ed25519 private/public key-pair verification runs and passes"

missing_config="$tmp_dir/missing.env"
missing_output="$tmp_dir/missing.output"
write_config "$missing_config" "$tmp_dir/missing.private.b64" "$public_key_file"
if "$validator" "$missing_config" > "$missing_output" 2>&1; then
  fail "missing private key file must fail closed"
fi
grep -Fq 'FAIL Source-config private key file is missing, unreadable, or empty' "$missing_output" ||
  fail "missing private key failure was not reported"
pass "missing key file fails closed"

cp "$public_key_file" "$tmp_dir/unreadable.public.b64"
chmod 000 "$tmp_dir/unreadable.public.b64"
if [[ -r "$tmp_dir/unreadable.public.b64" ]]; then
  fail "test environment could not create an unreadable key file"
fi
unreadable_config="$tmp_dir/unreadable.env"
unreadable_output="$tmp_dir/unreadable.output"
write_config "$unreadable_config" "$private_key_file" "$tmp_dir/unreadable.public.b64"
if "$validator" "$unreadable_config" > "$unreadable_output" 2>&1; then
  fail "unreadable public key file must fail closed"
fi
grep -Fq 'FAIL Source-config public key file is missing, unreadable, or empty' "$unreadable_output" ||
  fail "unreadable public key failure was not reported"
pass "unreadable key file fails closed"
