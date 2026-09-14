# Mobile Release Kit migration

Kira now carries a non-publishing, shadow configuration for Mobile Release Kit. It records the
current public release contract and lets the shared tool report missing work, but it is deliberately
blocked from building or uploading a candidate.

## Current shadow contract

- The committed release version remains `release/version.properties` (`VERSION_NAME` and
  `VERSION_CODE`). The shared tooling must pass those values to both Gradle and Xcode; it must not
  allocate a replacement build number in CI.
- Android uses `:app`, the `release` variant, Store package `me.manga.kira`, and the Play open-testing
  track `beta`.
- iOS is generated from `iosApp/project.yml`, archives scheme `iosApp` with `Release`, uses Store
  bundle `me.manga.kira`, App Store app `6792232678`, team `7CGZ2343AA`, and the external TestFlight
  group `External Testing`.
- Firebase configuration is required on both platforms. iOS dSYMs must be retained. The existing
  protected TestFlight path remains responsible for its mandatory Crashlytics upload until that
  gate has a shared-tool equivalent.
- The private GitHub Packages dependency still accepts `KIRA_PACKAGES_READ_TOKEN`; the shared path
  can supply the same least-privilege access through `MOBILE_RELEASE_PROJECT_READ_TOKEN`.
- Release source delivery also requires its verified public base URL and pinned public signing-key
  set. Those values currently enter only through repository variables in the legacy workflows; no
  value is copied or guessed by this shadow integration.
- The current credential-free release regression checks run as temporary project preflight hooks.
  Remove those hooks only when the corresponding legacy automation is retired.

## Exact blockers before candidate mode can be enabled

1. Android Debug currently has no `applicationIdSuffix`; a normal development install therefore
   uses the canonical Store package. Introduce and verify a separate Development identity without
   changing the Release package.
2. iOS Debug and Release currently inherit the same `PRODUCT_BUNDLE_IDENTIFIER`. Introduce and
   verify a separate Development bundle ID while keeping the Archive action on the canonical Store
   bundle.
3. Before either identity changes, decide and register the Development behavior for Firebase,
   Crashlytics, FCM/APNs, Associated Domains, deep links, and any backend allowlists. Do not reuse a
   production service configuration merely to make Debug build.
4. Record the existing Google Play upload-certificate SHA-256 and Apple Distribution certificate
   SHA-256 as public policy after they are verified against the owned Store records and final signed
   artifacts. No fingerprint is guessed in the shadow configuration.
5. Add reviewed Store metadata and approved fictional screenshots under `release/store/`. The
   existing `docs/release/STORE_SUBMISSION_DRAFTS.md` contains drafts and unresolved owner/legal
   inputs; the TestFlight text under `release/testflight/` is not a complete Store metadata tree.
6. Resolve the public-release gate in `docs/release/COMPLAINT_PRODUCTION_DECISION.md`, complete the
   owner/legal Store declarations, and finish the physical-device checks in
   `docs/release/INTERNAL_RELEASE_QA.md`.
7. Publish a reviewed Mobile Release Kit version, pin its full commit SHA in thin caller workflows,
   and configure only the credentials reported by the shared credential inventory. No caller is
   added while that immutable shared revision is unavailable.
8. Reconcile the protected branch and version-allocation policy before enabling a caller. At the
   migration audit, locally fetched `origin/release` still pointed to the 2026-07-04 initial commit
   (`8ea5568`), while the current legacy Android and TestFlight workflows dynamically select the
   next Store build number. The shared contract instead requires the committed
   `release/version.properties` build number. Re-fetch and inspect the exact protected branch tree,
   then change version policy deliberately; a passing check on `main` is not evidence about the
   stale release branch, and CI must not silently mix the two allocation models.
9. Move Crashlytics symbol publication behind the shared explicit symbol stage. Kira's Release
   Xcode phase normally uploads dSYMs during archive; shared builds now set
   `MOBILE_RELEASE_DEFER_EXTERNAL_UPLOADS=1` so that unmodeled external mutation is skipped. Before
   approval, change `ios.symbols.policy` to `required`, add a bounded project-owned upload command
   using `${MOBILE_RELEASE_DSYM_PATH}`, and retain confirmation evidence. A retained dSYM alone does
   not satisfy Kira's existing hard release gate.
10. Move the verified `KIRA_SOURCE_CONFIG_BASE_URL` and `KIRA_SOURCE_CONFIG_PINNED_KEYS` policy out
   of legacy-workflow-only variable plumbing and into a reviewed Kira-owned committed release
   properties source consumed by both `:app` and `:composeApp`. Both modules deliberately fail every
   Release build without those values, while reusable workflows cannot and should not know Kira's
   project-specific variable names. Verify the committed public values against the currently
   trusted endpoint/key set before removing the legacy mappings; do not guess them from examples.
11. Prove that the shared candidate path passes the existing signed-artifact checks, uploads one
   internal build, records its Store build IDs, and promotes those exact builds without rebuilding.

Until blockers 1 through 11 are closed in reviewed changes, both platform identities remain
`blocked` in `release/mobile-release.json`.

## Existing path preservation

The current workflows under `.github/workflows/`, the release scripts under `release/ios/` and
`scripts/release/`, and their credential names remain unchanged and authoritative. The Android build
also accepts the shared `MOBILE_RELEASE_ANDROID_*` signing environment names as fallbacks, while the
existing `KEYSTORE_*` and `KIRA_ANDROID_KEYSTORE_PATH` inputs keep precedence. It also accepts the
shared committed `MOBILE_RELEASE_VERSION_NAME` and `MOBILE_RELEASE_BUILD_NUMBER` after the explicit
legacy `KIRA_*` version overrides but before the legacy `GITHUB_RUN_NUMBER` allocator, so a shared
candidate cannot silently inherit the workflow-run number. Gradle dependency resolution likewise
keeps `KIRA_PACKAGES_READ_TOKEN` ahead of the shared project-read token and the existing GitHub token
fallback.

The Xcode application target maps the shared `MOBILE_RELEASE_IOS_*` signing variables through its
existing target-only signing indirection. The shared builder never sets global standard signing
keys that could leak into Firebase Swift Package targets; the legacy `KIRA_*` xcconfig remains the
default authority for the existing TestFlight workflow.

Do not delete or disable the existing path when adding the eventual pinned callers. First run shared
preflight beside it, compare identities, versions, signatures, entitlements, permissions, symbols,
and artifact hashes, then validate one complete internal-to-external promotion cycle. Retire the old
path only in a later reviewed change after the receipts and Store readback are accepted.
