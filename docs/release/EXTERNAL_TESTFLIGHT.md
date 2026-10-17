# TestFlight upload and external beta release

## Automated release contract

- Git branch: `internal-testing`.
- Workflow: choose `ios` or `both` in `Internal Testing Release` on `internal-testing`;
  shared app tests must pass before either platform upload starts. The parent owns the only
  release push trigger, preventing duplicate uploads. Manual dispatch uses the chosen platform;
  `.github/workflows/testflight.yml` is the reusable implementation. For push-triggered releases,
  use `[skip ios]` for Android-only, `[skip android]` for iOS-only, or `[skip both]` to skip both.
- App Store Connect app: `6792232678`.
- Bundle ID: `me.manga.kira`.
- Apple Team ID: `7CGZ2343AA`.
- Marketing version: `1.0.5`.
- Build number: highest App Store Connect number for `1.0.5` across both upload records and processed
  TestFlight builds, plus one; starts at `1` only when both sources are empty.
- Default distribution: upload and processing only. The workflow does not assign any external group,
  notify external testers, or submit Beta App Review.
- Optional external distribution: the private `External Testing` group and its review metadata are
  used only after a separate explicit approval to run an external Fastlane lane.
- Signing: manual App Store Connect distribution signing in a temporary keychain. The workflow
  checks the certificate, profile, final archive, and final IPA rather than trusting project values.
- Symbols: the Xcode Release build hard-gates on a confirmed Firebase Crashlytics dSYM upload, then
  verifies the app executable UUIDs exactly match `Kira.app.dSYM`.
- Crash diagnostics: internal fatal-crash controls remain disabled in the production-signed
  TestFlight candidate. Crashlytics symbol upload is still a required archive gate.

The workflow does not create a public TestFlight link, add tester addresses, create external groups,
or submit a review. This avoids accidentally distributing an unreviewed build. The separately
invoked external lane creates an empty, non-distributing internal group only if App Store Connect
requires one before it permits the external group.

## Candidate readiness

`Mobile release checks (no Store upload)` can validate a candidate without signing or Store
credentials. Its existing common/Android tests do not qualify Apple native dependencies, signed
archives or physical-device behavior. Do not promote a candidate to `internal-testing` while the
recorded App61 supplier/native dependency or App29 complaint release holds remain unresolved.
The existing pinned toolchain, SwiftPM, signed artifact, Crashlytics and Store checks remain mandatory.
A new upload uses the next Store build number; it does not require an arbitrary marketing-version bump.

## Verified libffi source bootstrap

TestFlight builds the macOS ARM64 host dependency from the **official Kotlin 2.4.0 source recipe**,
not the conflicting JetBrains `libffi-3.3-1-macos-arm64.tar.gz` prebuilt. That download disagrees
with both its published SHA-256 and its detached Kotlin release-key signature. Do not accept it by
changing its checksum. Stable Kotlin 2.4.10/2.4.20/2.4.21 still select the same dependency.

`release/libffi-source.json` pins the upstream libffi 3.3 source archive, the exact Apple ARM patch
named by JetBrains, and the official Kotlin/Native 2.4.0 distribution. The source checksum is also
corroborated by NixOS 20.09's libffi expression. The upstream recipe is
`JetBrains/kotlin@add726ca8c82922b6ab4cb2a27ae738d6a780817:kotlin-native/tools/libffi/build_for_macos.sh`;
the patch is from `Homebrew/formula-patches@06252df03c68aee70856e5842f85f20b259e5250`.
Modern Apple assemblers also require the upstream libffi CFI-label ordering fix
(`libffi/libffi@8308bed5b2423878aa20d7884a99cf2e30b8daf7`, PR857). The manifest authenticates
that upstream patch and the small 3.3 backport, including exact assembly pre/post hashes.
Only three `cfi_startproc` directives move after their entry labels; instructions and unwind
directives are preserved. The no-secrets `libffi-source-check.yml` job qualifies bootstrap
corrections on the issue branch without repeating unrelated app tests or accessing Stores.

Before signing credentials are exposed, the bootstrap verifies the pinned downloads, builds the
static ARM64 library, and runs an actual host calling-convention/closure smoke check. Only a private
verified compiler distribution receives the supported local dependency profile
`libffi-3.3-1-macos-arm64.default=<verified install>`. The inherited `GRADLE_OPTS` native-home setting
also applies to Xcode's Gradle invocation; `KONAN_DATA_DIR` isolates the job's caches. This does not
forge Kotlin extraction markers, edit global caches, switch architecture, or change Kotlin version.

The private library/configuration is rechecked around native compilation and the signed archive.
Source/build intermediates are removed once no longer needed; the private compiler/library are
removed after the job, retaining the small provenance receipt alongside normal release evidence.
Missing hashes, source drift, ABI smoke failures or unexpected downloaded libffi fail the release.
This replacement requires actual native/archive/signing validation; source tests alone are not a
TestFlight release or closure of the broader App61 dependency campaign.

## Protected local inputs

Place binary files anywhere under the workspace; keep them gitignored. Place one value, with no
extra newline, in each protected file below:

```text
.secrets/ios-certificate-password
.secrets/app-store-connect-key-id
.secrets/app-store-connect-issuer-id
.secrets/testflight-feedback-email
.secrets/beta-review-contact-first-name
.secrets/beta-review-contact-last-name
.secrets/beta-review-contact-email
.secrets/beta-review-contact-phone
```

The binary inputs are an Apple Distribution `.p12`, an App Store Connect distribution
`.mobileprovision`, the production `GoogleService-Info.plist`, and `AuthKey_*.p8`. Originals remain
local. Temporary encodings and decoded copies are deleted after use.

## Export-compliance technical evidence

`Info.plist` currently declares `ITSAppUsesNonExemptEncryption = false`. Most network encryption is
standard HTTPS/TLS supplied by Apple, Ktor, and Firebase. The iOS source implementation also contains
CommonCrypto AES-256/CBC code used to decrypt a source site's manga payload; it is not an
authentication system and does not protect user/private backend data. This custom AES use must be
included in the owner's export-compliance assessment.

The project appears technically consistent with an exempt/mass-market declaration, and no evidence
of proprietary access-control or military cryptography was found. This is not a legal determination.
The Account Holder must confirm that the actual distribution qualifies before retaining `false`.

## Exact manual checks and remaining actions

### Apple Developer capabilities

Path: **Apple Developer → Account → Certificates, Identifiers & Profiles → Identifiers → App IDs →
`me.manga.kira`**.

Inspect **Push Notifications** and **Associated Domains**. Both must be enabled. This blocks archive
signing when absent from the profile. The workflow independently requires `aps-environment =
production` and `applinks:kiramanga.me` in both the embedded profile and final signed app.

### App Store Connect agreements

Path: **App Store Connect → Business → Agreements**.

Inspect every agreement banner/status. Expected: no pending agreement that prevents build uploads or
TestFlight. A pending developer agreement blocks API/upload operations; tax or banking setup can
block paid App Store distribution but normally does not block a free TestFlight beta.

### Beta App Review status

Path: **App Store Connect → Apps → Kira Manga → TestFlight → iOS → 1.0.5 → selected build**.

After the default workflow, confirm the build is processed and available for internal TestFlight
testing, with no Beta App Review submission. Only after a separately approved external lane should
**External Testing** / **Beta App Review** show `Waiting for Review` or `In Review`; the final
external state is `Approved` / `Testing`.

### Add external testers after approval

Path: **App Store Connect → Apps → Kira Manga → TestFlight → External Testing → Testers → +**.

Add the intended tester email addresses (or explicitly enable a public link only if broad access is
desired). Expected: testers appear in the group and the approved build is selected. This is the one
manual distribution action the workflow intentionally does not infer. It blocks availability to
actual external people, not build upload or Beta App Review.

### App privacy

Path: **App Store Connect → Apps → Kira Manga → App Privacy → Manage**.

Inspect the published privacy-policy URL and every collected-data answer. Expected URL:
`https://kiramanga.me/privacy`; answers must cover Firebase Analytics, Crashlytics, Messaging/device
identifiers, and optional complaint content consistently with the signed binary. Incomplete privacy
metadata can block App Store submission and may delay Beta App Review, but does not normally block
binary upload.

### Content rights and age rating

Paths:

- **App Store Connect → Apps → Kira Manga → App Information → Content Rights**.
- **App Store Connect → Apps → Kira Manga → App Information → Age Ratings**.

Inspect that the owner has declared the right to display third-party content and completed the current
age questionnaire for manga/source content. These answers primarily block App Store submission; Apple
may still request clarification during Beta App Review.

### Encryption prompt

Path: **App Store Connect → Apps → Kira Manga → TestFlight → iOS → selected build → Export
Compliance** (only shown when Apple requires an answer).

Expected technical selection: the app does not use **non-exempt** encryption, subject to the Account
Holder's legal confirmation described above. A missing export-compliance answer blocks testing for
that build, though it does not prevent the binary upload itself.

### Production Firebase identity

Path: **Firebase console → intended production project → Project settings → General → Your apps →
iOS app**.

Inspect the bundle ID. Expected: `me.manga.kira`. Also confirm this is the intended production
project and that its APNs authentication key is configured under **Project settings → Cloud
Messaging → Apple app configuration**. The workflow validates the plist bundle ID and rejects
placeholder fields, but only the owner can identify which Firebase project is intended for production.
