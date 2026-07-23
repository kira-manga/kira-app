# Signed incremental source-catalog release posture

## Trust boundary

The revision-6 bundled `CONFIG_BACKED_SOURCES_JSON` is the always-available floor and contains only
the 12 reviewed generic sources. Production wires `KtorRemoteSourceCatalog` to the backend v2
manifest and immutable per-source endpoints. `Ed25519ConfigSignatureVerifier` authenticates the
exact UTF-8 manifest and each referenced source revision with an in-app pinned public key.

The client rejects HTTP endpoints, credentials in the URL, missing signature metadata, oversized
responses, non-generic entries, unknown key identifiers, invalid signatures, checksum or identity
mismatches, stale/replayed catalog revisions, lower per-source revisions, silent source omissions,
discarded tombstones, and rollback links behind its durable acceptance floor. It
sends `If-None-Match` for the manifest, reuses verified immutable source rows, and fetches only
missing active revisions. Room activates the manifest, entries, source rows, and app source
projection in one transaction. Any failure preserves the complete last-known-good catalog or bundle;
catalogs are never partially combined.

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

The tests cover backend-compatible manifest/source signatures, tampering, wrong keys,
replay/rollback, immutable row conflicts, re-verification of cached data, lifecycle removals,
HTTPS/size bounds, ETag/304 behavior, delta fetches, atomic activation, and safe fallback.
