# Bundled sources release posture

## Decision

The first production release uses the source configuration bundled into the signed application.
There is no runtime integration with `kira-backend` and no remote source-catalog/configuration
synchronization in this release.

## Runtime evidence

- The document is `CONFIG_BACKED_SOURCES_JSON` in
  `composeApp/src/commonMain/kotlin/me/manga/kira/sources/runtime/BundledSourcesConfig.kt`.
- `SourcesGenericModule.kt` creates `RoomSourceConfigStore` with that bundled document.
- The production `RemoteSourceConfigManager` receives `remote = null`.
- `DenyRemoteSignatureVerifier` rejects every detached signature.
- No `BackendRemoteConfigSource`, source-config backend endpoint, ETag client, or remote catalog
  synchronization implementation is wired in the app.
- The remote interfaces and their isolated unit tests remain future-stage abstractions; their
  existence does not create a network path.

The Room `source_config_cache` table is retained as part of schema v11. With no remote source wired,
the bundled document remains the source of truth and the production path does not fetch a new one.

## Release verification

Run from `Kira manga/`:

```sh
rg -n "remote = null|DenyRemoteSignatureVerifier" composeApp/src/commonMain
rg -n "BackendRemoteConfigSource|source-config/document" \
  composeApp/src data/src platform/src sources/*/src
./gradlew :sources:config:desktopTest :composeApp:desktopTest
```

Expected result: production DI has the explicit null/deny posture; the backend symbols/endpoints are
absent; config/parser/registry tests pass.

## Updating a source before remote delivery exists

1. Edit only the bundled document using the source authoring rules in
   `docs/sources/ADDING_SOURCES.md`.
2. Run parser, completeness, registry, generic-engine, and captured-fixture parity tests.
3. Exercise all verbs for the changed source: Home, Featured/Popular, Search, Details/Chapters,
   Pages/images, refresh, and download.
4. Bump the app version/build number and distribute a new signed app release.
5. Do not imply a server-side config edit can repair an already-installed build.

## Out of scope

Do not add a backend client, cache protocol, signature scheme, ETag behavior, or source-request gate
as part of first-release preparation. Those require a separately approved cross-project contract.
