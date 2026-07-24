# Release configuration

Status: internal-release ready configuration; public signing, store, Firebase, and website owner
actions remain. Android and iOS are the only shipping targets.

## Version source of truth

- Product version: `1.0.0`.
- Android fallback build number: `1`.
- Committed values live in `release/version.properties`.
- Android CI may override them with `KIRA_VERSION_NAME` and `KIRA_BUILD_NUMBER`; GitHub's run
  number is also accepted as the build number.
- iOS `MARKETING_VERSION` is `1.0.0`. `CURRENT_PROJECT_VERSION` is `1` locally and the archive CI
  passes `KIRA_BUILD_NUMBER` so Android and iOS artifacts from one run share a build number.
- Increase the marketing version intentionally; never reuse an uploaded Android version code or
  App Store Connect build number.

## Android release

The app targets API 36, compiles against API 37, has minSdk 26, enables R8/resource shrinking, and
produces an AAB for Play distribution.

Production signing is secret-only. A release is signed only when all four environment values exist
and `KEYSTORE_FILE` points to a real file:

```text
KEYSTORE_FILE
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD
```

Partial signing configuration fails loudly. With no signing values, release tasks can still create
an unsigned validation APK/AAB; those artifacts are for internal build-path verification only and
must never be uploaded to Play.

The committed `app/google-services.json` is inert. A production-shaped release requires the real,
gitignored file. `-PallowPlaceholderGoogleServices=true` exists only so CI/local validation can
exercise lint, R8, packaging, and bundle generation without production credentials.

GitHub release secrets for the Android Internal and Open testing workflow:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
GOOGLE_SERVICES_JSON   # base64-encoded real file
GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
```

The `Internal Testing Release` workflow offers `android`, `ios`, and `both` on the
`internal-testing` branch. Its reusable Android workflow validates the source-config authority and
both Play testing tracks, reconstructs the upload
keystore only in the runner's temporary directory, runs tests/lint/R8, builds the signed AAB, and
uploads the same version to Google Play Internal and Open Testing. It removes the keystore and Firebase file in
an `always()` cleanup step. The older `ci.yml` workflow remains a build-validation workflow and
does not publish to Play.

Owner actions before Play upload:

1. Back up the upload keystore and passwords outside the repository.
2. Configure the five protected GitHub secrets above.
3. Build the signed path and install an R8 APK on a physical Android device.
4. Record the Play **app signing** SHA-256 certificate in `kira-web`; it may differ from the upload
   key and controls Android App Links after Play re-signs the app.
5. Upload only the signed `.aab`, then preserve its mapping file and CI provenance.

The complete Android/Play/backend input inventory and the non-printing local validation/apply
helpers are documented in [`ANDROID_INTERNAL_TESTING.md`](ANDROID_INTERNAL_TESTING.md). Fill only
the ignored `.secrets/android-release.env`; never commit the real configuration.

## iOS release

- Bundle ID: `me.manga.kira`.
- Apple team: `7CGZ2343AA`.
- Deployment target: iOS 15.
- Device target: arm64; simulator target: arm64. x86_64 is explicitly excluded because the current
  Compose/Kotlin dependencies do not publish Apple x86_64 artifacts.
- Release emits dSYMs and the Xcode build phase hard-fails when Crashlytics symbol upload does not
  confirm success. `CRASHLYTICS_DSYM_UPLOAD_OPTIONAL=1` is for deliberate offline validation only,
  never a distribution archive.
- The dedicated internal TestFlight workflow sets `KIRA_CRASH_DIAGNOSTICS_ENABLED=YES`, exposing
  five confirmed fatal-crash scenarios under Settings for Crashlytics verification. The committed
  default is `NO`; public production archives must leave the diagnostic route unavailable.
- Debug uses development push entitlements; Release uses production push entitlements.
- The Kotlin/Native Gradle daemon heap is 6 GiB to support release framework linking.

`KIRA_APP_STORE_ID` is `6792232678`. The dedicated workflow verifies through App Store Connect that
this record belongs to `me.manga.kira` before it chooses a build number or creates any TestFlight
group.

GitHub archive secrets:

```text
IOS_CERTIFICATE_BASE64
IOS_CERTIFICATE_PASSWORD
IOS_PROVISIONING_PROFILE_BASE64
IOS_PROVISIONING_PROFILE_SPECIFIER
GOOGLE_SERVICE_INFO_PLIST   # base64-encoded real file
APP_STORE_CONNECT_KEY_ID
APP_STORE_CONNECT_ISSUER_ID
APP_STORE_CONNECT_PRIVATE_KEY_BASE64
TESTFLIGHT_FEEDBACK_EMAIL
BETA_REVIEW_CONTACT_FIRST_NAME
BETA_REVIEW_CONTACT_LAST_NAME
BETA_REVIEW_CONTACT_EMAIL
BETA_REVIEW_CONTACT_PHONE
```

`.github/workflows/testflight.yml` is called only by `Internal Testing Release` when `ios` or
`both` is selected on `internal-testing`. It validates all supplied signing metadata without logging protected
contents, queries every uploaded/processing App Store Connect build for version `1.0.0`, chooses an
integer build higher than the highest result, creates the TestFlight group prerequisites, archives,
exports and validates the IPA, uploads Crashlytics dSYMs, uploads to App Store Connect, waits for
processing, assigns the build to `External Testing`, and submits Beta App Review. Concurrent uploads
are serialized rather than cancelled, preventing two runs from selecting the same build number.

The workflow creates an empty `Internal Prerequisite` group only when Apple requires one before an
external group can exist. It never assigns builds or testers to that group. Actual distribution is
only through the private `External Testing` group.

Owner actions that remain outside the build workflow:

1. Make the local signing/API files and protected values available so their repository secrets can
   be installed.
2. Complete final legal export-compliance, content-rights, privacy, and age-rating answers.
3. After Beta App Review approval, add external tester email addresses to the private group.
4. Run physical-device TestFlight QA before treating the build as release-ready.

Exact portal paths and expected states are in `EXTERNAL_TESTFLIGHT.md`.

## Firebase and privacy-sensitive configuration

Android links Analytics, Crashlytics, Messaging, and Firestore. iOS links Firebase Core,
Analytics-without-advertising-ID support, Crashlytics, Messaging, and In-App Messaging. The app has
no ad UI, AdMob/UMP/mediation dependency, Android `AD_ID`, or Privacy Sandbox advertising-ID/
attribution permission in this release. Android Firebase Analytics still resolves its documented
transitive `play-services-ads-identifier` module; the supported
`google_analytics_adid_collection_enabled=false` control and default ad-personalization-off control
are set, and the identifier permissions are removed. CI inspects the merged release manifest for
permission regressions.

Real Firebase files are credentials/configuration in the sense that they identify the production
project, even though mobile API keys are not server secrets. Keep them gitignored, restrict APIs in
the relevant consoles, and never use them as authorization. Firestore rules/authentication—not an
API key—must protect complaint data.

Before public release, reconcile Firebase console retention/collection settings with
`STORE_SUBMISSION_DRAFTS.md`, the app privacy manifest, Google Data Safety, and the website privacy
policy. The complaint blocker in `COMPLAINT_PRODUCTION_DECISION.md` must also be resolved.

## Website and link destinations

The canonical origin is `https://kiramanga.me`:

- guide: `https://kiramanga.me/guide`
- privacy: `https://kiramanga.me/privacy`
- terms: `https://kiramanga.me/terms`
- support: `https://kiramanga.me/support`
- takedown: `https://kiramanga.me/takedown`
- data deletion: `https://kiramanga.me/data-deletion`

The separate top-level `kira-web/` project owns these routes and both association files. It is not
deployed by the app build. Production association handling remains incomplete until DNS/TLS, the
Play app-signing fingerprint, and a physical-device Universal/App Link test are complete.

The app still references the historical `yami_manga_me` Twitter/X and Instagram handles. They were
not guessed or renamed because no confirmed replacement handle exists in the repository. The owner
must confirm whether those profiles are still official; then update both app constants and public
website copy together.

## Source delivery boundary

Production retains bundled source definitions as its recovery floor and also wires an authenticated
backend update path. The release build must supply a credential-free HTTPS backend origin; pinned
Ed25519 public keys are compiled into the app. Invalid, stale, rolled-back, oversized, or unavailable
remote documents fall back to the last verified cache or bundle. See `BUNDLED_SOURCES_RELEASE.md`.

## Deliberate non-actions

- No public deployment, DNS mutation, Play upload, TestFlight upload, or App Store submission was
  performed during this preparation.
- No production backend URL was guessed or deployed; the release pipeline must supply the real origin.
- Desktop remains a shared-code compile/test target, not a product-release target.
