# Signed source-document release posture

## Trust boundary

The bundled `CONFIG_BACKED_SOURCES_JSON` remains the always-available floor. Production also wires
`KtorRemoteConfigSource` to the backend document endpoint and accepts an update only after
`Ed25519ConfigSignatureVerifier` authenticates its exact UTF-8 bytes and metadata with an in-app
pinned public key.

The client rejects HTTP endpoints, credentials in the URL, missing signature headers, documents
larger than 5 MiB, unknown key identifiers, invalid signatures, checksum mismatches, stale or replayed
revisions, and rollback links behind the last accepted signed document. The last verified envelope is
stored in Room and re-verified after every process restart. Any failure preserves the last good cache
or bundle.

## Release configuration

- `kira.sourceConfigBaseUrl` (or build environment variable `KIRA_SOURCE_CONFIG_BASE_URL`) is the
  credential-free HTTPS backend origin. Leave it empty to disable network delivery safely.
- `kira.sourceConfigPinnedKeys` (or `KIRA_SOURCE_CONFIG_PINNED_KEYS`) contains comma-separated
  `key-id=X509-public-key-base64` entries. Keep retiring and replacement keys together during rotation.
- `kira.appVersion` (or `KIRA_APP_VERSION`) is sent as the endpoint's `appVersion` query parameter.
- Never put an Ed25519 private key in this repository or an app build.

Android release assembly fails unless the pipeline supplies both the real public HTTPS backend
origin and the production public key pins. `-PallowUnconfiguredSourceRemote=true` exists only for
non-shipping release-path validation; artifacts built with it must not be distributed.

## Verification

Run from `Kira manga/`:

```sh
./gradlew :sources:config:desktopTest :composeApp:desktopTest \
  :composeApp:compileAndroidMain :composeApp:compileKotlinIosSimulatorArm64
```

The tests cover valid backend-compatible signatures, tampering, wrong keys, replay/rollback,
re-verification of cached data, metadata completeness, HTTPS enforcement, size bounds, ETag
conditional requests, 304 responses, and safe fallback.
