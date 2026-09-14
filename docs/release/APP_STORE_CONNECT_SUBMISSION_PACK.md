# App Store Connect Submission Pack

Audit date: August 15, 2026  
App source audited: main at 8e34895a40cbe8122f1906e10c568f8c97b1db10

Verdict: **NOT READY FOR APP REVIEW**. The listing text is prepared, but the current repository
does not yet contain an acceptable production candidate. No build was uploaded, submitted, or
published during this audit.

## Critical Discrepancies

These must be resolved before selecting an App Store build:

1. The TestFlight workflow forces KIRA_CRASH_DIAGNOSTICS_ENABLED=YES and validates that it remains
   enabled. That artifact is suitable for internal diagnostics, not the public App Store. See
   [write_release_configuration.rb](../../release/ios/write_release_configuration.rb#L16) and
   [validate_artifacts.rb](../../release/ios/validate_artifacts.rb#L108).
2. The public complaint feature writes persistent identifiers and free-form text to Firestore
   without repository-verifiable authentication, authorization rules, App Check, or abuse
   controls. The repository identifies this as a hard public-release blocker in
   [COMPLAINT_PRODUCTION_DECISION.md](COMPLAINT_PRODUCTION_DECISION.md).
3. The live Privacy Policy and copyright/takedown page still display **Draft policy**.
4. No licensing or other content-rights evidence was found for the 12 third-party manga sources.
5. Third-party source retention/tracking behavior has not been audited, preventing a definitive
   privacy label and age rating.
6. There is no verified, diagnostics-disabled signed archive or processed App Store Connect build.
7. The activation validator accepts any text containing kiramanga, including
   kiramanga.evil.com; see
   [SourceAccessState.kt](../../domain/src/commonMain/kotlin/me/manga/kira/domain/model/sources/SourceAccessState.kt#L17)
   and its
   [test](../../domain/src/commonTest/kotlin/me/manga/kira/domain/usecase/sourceaccess/SourceAccessUseCaseTest.kt#L68).

## 1. Verified Release Identity

| Value | Verified result | Source |
|---|---|---|
| Product name | Kira | [project.yml](../../iosApp/project.yml#L43) |
| Display/App Store name | Kira Manga | [Info.plist](../../iosApp/iosApp/Info.plist#L84) |
| Bundle ID | me.manga.kira | [project.yml](../../iosApp/project.yml#L42) |
| Apple ID | 6792232678 | [Fastfile](../../fastlane/Fastfile#L8) |
| Apple Team ID | 7CGZ2343AA | [project.yml](../../iosApp/project.yml#L64) |
| Marketing version | 1.0.0 | [project.yml](../../iosApp/project.yml#L44) |
| Committed build number | 1 | [project.yml](../../iosApp/project.yml#L45) |
| Upload build strategy | Highest uploaded 1.0.0 build plus one | [Fastfile](../../fastlane/Fastfile#L67) |
| Minimum iOS | iOS 15.0 | Generated Xcode project |
| Devices | iPhone and iPad | TARGETED_DEVICE_FAMILY = 1,2 |
| Architectures | arm64 device; arm64 simulator | Project and artifact validator |
| iPhone orientations | Portrait, landscape left/right | [Info.plist](../../iosApp/iosApp/Info.plist#L56) |
| iPad orientations | All four | [Info.plist](../../iosApp/iosApp/Info.plist#L62) |
| Signing | Automatic locally; manual Apple Distribution in CI | [project.yml](../../iosApp/project.yml#L53) |
| Push entitlement | Production APNs in Release | [iosApp.entitlements](../../iosApp/iosApp/iosApp.entitlements#L9) |
| Associated domain | applinks:kiramanga.me | [iosApp.entitlements](../../iosApp/iosApp/iosApp.entitlements#L11) |
| URL scheme | kiramanga | [Info.plist](../../iosApp/iosApp/Info.plist#L25) |
| Export flag | ITSAppUsesNonExemptEncryption = NO | [Info.plist](../../iosApp/iosApp/Info.plist#L86) |
| Firebase project | kira-manga | Protected Firebase plist, safe identity fields only |
| Firebase iOS SDK | 12.15.0 | [project.yml](../../iosApp/project.yml#L27) |
| Backend | https://api.kiramanga.me | Release configuration |
| Catalog | Signed v2 catalog revision 120, key ID prod-2026-01 | Live endpoint |
| Active sources | Exactly 12 generic sources | Live manifest |
| Legacy sources | None active | Live manifest and bundled fallback |
| Release branch | internal-testing | [testflight.yml](../../.github/workflows/testflight.yml#L1) |
| Upload tooling | Xcode archive/export + Fastlane 2.235.0 | Workflow/Gemfile |
| Current source branch | main, synchronized with origin/main | Git audit |
| Current tree | One unrelated untracked owner file: docs/NAVIGATION_IMPLEMENTATION.md | Git audit |

The live universal-link file returned HTTP 200 JSON and identifies
7CGZ2343AA.me.manga.kira for /activate and /activate/*.

The live catalog returned HTTP 200 JSON with an ETag and these active revisions: Azora 2,
Mangamello 9, Mangamello Plus 2, SwatManga 2, Lekmanga 3, Team X 2, DilarV2 2, 3asq 2,
Demonicscans 2, Mangabuddy 2, Zazamanga 2, and Tapas 2.

Mandatory CI passed on the exact main SHA:
[GitHub Actions run 30878143839](https://github.com/kira-manga/kira-app/actions/runs/30878143839).
That run validates compilation, tests, static analysis, Android lint/R8/AAB, and iOS framework
compilation—but not a signed App Store archive.

## 2. Actual Shipping Functionality

Production functionality:

- Three-step onboarding: Welcome, Theme, Start Reading.
- Guest use; no account, registration, authentication, profile, paywall, or demo credentials.
- Library, Home, Updates, History, Settings, search, manga details, chapters, bookmarks, reading
  progress, and source switching.
- Continuous and paged readers.
- Chapter downloads, local offline reading, background transfer reconciliation, and local update
  refresh.
- Import/export of Kira backup ZIPs and manga packages.
- Light, dark, system, and pure-black themes.
- Eleven interface languages, including Arabic RTL.
- Embedded, host-restricted WKWebView for source access and anti-bot recovery.
- Optional push notifications and local download notifications.
- Feedback/complaint submission and management through Firestore.
- Signed backend-managed source catalog with signed cache and bundled fallback.
- No advertising, IAP, subscriptions, payments, chat, social feed, or broadly distributed UGC.

TestFlight-only:

- Crash-diagnostics screen enabled by the current workflow.

Debug-only:

- Admin complaint dashboard, controlled through Platform.isDebugBinary.
- Verbose background-download diagnostics.

No camera, microphone, contacts, health, location-services, or tracking permission is requested.
Notification permission is requested contextually. A Photos add-purpose string is present, but
current export code uses the system share sheet rather than directly writing to Photos; this should
be corrected before submission.

## 3. App Information

| Field | Value |
|---|---|
| App Name | Kira Manga |
| Subtitle | Your multilingual manga reader |
| Primary Language | English (U.S.) |
| Primary Category | Books |
| Secondary Category | Entertainment |
| Bundle ID | me.manga.kira |
| Apple ID | 6792232678 |
| SKU | USER INPUT REQUIRED |
| Made for Kids | No |
| License | Apple Standard EULA |
| Copyright | USER INPUT REQUIRED |
| Applicable permits | No app-specific permit found; storefront-dependent requirements remain |
| DSA trader status | USER INPUT REQUIRED |

### Content Rights

Select **Yes** for “Does your app contain, show, or access third-party content?”

The app directly retrieves, displays, and downloads manga from third-party Internet sources. The
Kira backend hosts configuration, not manga files. Apple requires the necessary rights or another
lawful basis for every country where the app is offered.
[Apple’s Content Rights definition](https://developer.apple.com/help/app-store-connect/reference/app-information/app-information/).

No evidence supporting the final rights attestation was found. Do not certify that rights exist
until evidence is available.

## 4. Product Page Metadata

Apple limits names/subtitles to 30 characters, promotional text to 170, description to 4,000, and
keywords to 100 bytes.
[Apple App Information](https://developer.apple.com/help/app-store-connect/reference/app-information/app-information/)
and
[platform metadata limits](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information).

### Promotional Text

Build your library, read online or offline, and move your Kira exports between devices.

Length: 87 characters.

### Description

Kira Manga is a multilingual reader for organizing and reading manga from sources you choose.

Browse and search configured source catalogs, open title and chapter details, save titles to your
local library, and keep track of reading progress, bookmarks, history, and chapter updates.

Read online in continuous or paged layouts, or download chapters for offline reading. Export and
import Kira backup ZIP files and individual manga packages to move your library and downloaded
content between supported devices.

Choose light, dark, system, or pure-black themes. Kira supports 11 interface languages, including
Arabic with right-to-left layout.

Kira does not require an account, include advertising, or sell subscriptions. Manga files are
retrieved from third-party internet sources selected through the app and are not permanently hosted
by Kira. Source availability and content may change. Only access or download content you are
authorized to use.

Length: 952 characters.

### Keywords

    reader,webtoon,library,offline,chapters,comics,bookmarks,downloads,reading

Length: 74 bytes.

### What’s New

For a first App Store version, Apple does not display a What’s New field.

If App Store Connect treats this as an update, use:

> This release improves source reliability, adds signed backend-managed source updates, refreshes
> app branding and startup, strengthens offline catalog fallback, and fixes library metadata,
> reading, and embedded web-view issues.

### URLs

| Field | URL |
|---|---|
| Marketing | https://kiramanga.me/ |
| Support | https://kiramanga.me/support/ |
| Privacy Policy | https://kiramanga.me/privacy/ |
| Privacy Choices | https://kiramanga.me/data-deletion/ |
| Account Deletion | Not applicable—accounts do not exist |

## 5. App Privacy Audit

Apple requires disclosure of integrated SDKs and restricted web-view traffic, not only first-party
persistence.
[Apple privacy guidance](https://developer.apple.com/app-store/app-privacy-details/).

| Apple data type | Collected | Linked | Tracking | Purpose/component |
|---|---:|---:|---:|---|
| Customer Support | Yes | Yes | No | Complaint subject/body/replies; App Functionality |
| Device ID | Yes | Yes | Not proven | IDFV, app instance ID, FCM installation/token; App Functionality and Analytics |
| Coarse Location | Yes | Yes conservatively | Not proven | Analytics derives general location from masked IP; Analytics |
| Product Interaction | Yes | Yes conservatively | Not proven | Automatic lifecycle, screen/session, notification and FIAM interactions; Analytics |
| Other Usage Data | Yes | Yes conservatively | Not proven | Firebase Sessions/background timing; Analytics |
| Crash Data | Yes | No per SDK manifest | No | Crashlytics stack/application state/device information; App Functionality |
| Other Diagnostic Data | Yes | Yes overall | No | Complaint device/app metadata plus SDK transport/session diagnostics; App Functionality and Analytics |
| Other Data Types | Yes | No per Messaging manifest | No | Firebase Messaging metadata; Analytics |
| Search History | USER INPUT REQUIRED | USER INPUT REQUIRED | USER INPUT REQUIRED | Requests sent to third-party source sites |
| Browsing History | USER INPUT REQUIRED | USER INPUT REQUIRED | USER INPUT REQUIRED | Content/source URLs loaded through networking and restricted WebView |

Google documents that Crashlytics collects stack traces and device/OS information, FCM associates
APNs tokens with installation IDs, FIAM records interactions, and Sessions collects
app/OS/network/background metadata.
[Firebase Apple data collection](https://firebase.google.com/docs/ios/app-store-data-collection).
Analytics automatically assigns an app instance ID, derives general location from masked IP, and
records lifecycle/session events.
[Google Analytics disclosure guidance](https://support.google.com/analytics/answer/10285841?hl=en).

### APP PRIVACY CLICK-BY-CLICK ANSWERS

1. **Do you or your third-party partners collect data?** → Yes.
2. Add:
   - Customer Support → App Functionality → Linked → Not used for tracking.
   - Device ID → App Functionality and Analytics → Linked.
   - Coarse Location → Analytics → Linked conservatively.
   - Product Interaction → Analytics → Linked conservatively.
   - Other Usage Data → Analytics → Linked conservatively.
   - Crash Data → App Functionality → Not linked → Not used for tracking.
   - Other Diagnostic Data → App Functionality and Analytics → Linked → Not used for tracking.
   - Other Data Types → Analytics → Not linked → Not used for tracking.
3. Do not select Contact Info, Financial Info, Health, Fitness, Precise Location, Contacts,
   Photos/Videos, Audio, Purchases, Advertising Data, or Sensitive Info based on the current binary.
4. Final tracking and source-web-traffic selections remain **USER INPUT REQUIRED** pending
   Firebase-console and provider verification.

## 6. Privacy Manifest and Required-Reason APIs

The app manifest declares:

- NSPrivacyTracking=false.
- No tracking domains.
- Other User Content, Device ID, and Other Diagnostic Data.
- UserDefaults → CA92.1.
- File timestamps → C617.1.
- System boot time → 35F9.1.

See [PrivacyInfo.xcprivacy](../../iosApp/iosApp/PrivacyInfo.xcprivacy).

Firebase SDK manifests were found for Crashlytics, Messaging, Installations, GoogleDataTransport,
Core, and Utilities. Google Analytics does not provide an SDK privacy manifest and still requires
manual App Privacy disclosure.

Problems:

- Complaint text should be classified as **Customer Support**, not generic Other User Content.
- The final aggregated archive privacy report has not been generated.
- Analytics collection is active because FirebaseApp.configure() runs and the correct opt-out key,
  FIREBASE_ANALYTICS_COLLECTION_ENABLED=false, is absent.
  [Firebase configuration documentation](https://firebase.google.com/docs/analytics/ios/configure-data-collection).
- IDFV collection is not disabled because GOOGLE_ANALYTICS_IDFV_COLLECTION_ENABLED=false is absent.
- Firebase Ads integrations/data-sharing settings remain unverified.

## 7. Age Rating

| Category | Selection | Evidence |
|---|---|---|
| Parental Controls | No | No parental-control system |
| Age Assurance | No | No age-verification mechanism |
| Unrestricted Web Access | No | Embedded WebView is host-restricted |
| User-Generated Content | No | Support complaints are not broadly distributed |
| Social Media | No | No feed, likes, reposts, or public interaction |
| Messaging and Chat | No | No user-to-user communication |
| Advertising | No | No ad SDK or ad surface |
| Medical or Treatment Information | None | No such functionality |
| Health or Wellness Topics | None | No such functionality |
| Gambling | None | No gambling |
| Simulated Gambling | None | No simulated gambling |
| Contests | None | No contests |
| Loot Boxes | None | No loot boxes |

The frequencies for profanity, horror, alcohol/drugs, mature themes, sexual content/nudity, graphic
sexual content, cartoon violence, realistic violence, prolonged graphic violence, and weapons
cannot be proven from source definitions alone. Ten of the 12 active sources lack meaningful adult
genre blacklists, and third-party catalogs can change.

Therefore, the resulting age rating is **USER INPUT REQUIRED**. Apple’s current scale is 4+, 9+,
13+, 16+, 18+, or Unrated. Any graphic sexual content or prolonged graphic/sadistic realistic
violence produces Unrated and cannot be published.
[Apple age-rating definitions](https://developer.apple.com/help/app-store-connect/reference/app-information/age-ratings-values-and-definitions/).

The website’s minimum age of 13 does not determine Apple’s rating.

## 8. App Review Information

- Sign-in required: No.
- Demo account required: No.
- Multiple roles/accounts: No.
- Review contact values exist in protected local files and GitHub secrets; they are not reproduced
  here.
- Internet is required for catalog synchronization and source browsing.
- Local library/imported Kira content can be tested without an account.
- Notifications are optional.
- Source-management activation is a discoverability step, not authentication.

### App Review Notes

> Kira Manga requires no account, subscription, or payment. On first launch, complete Welcome and
> Theme, then choose Continue to Library.
>
> To evaluate source browsing, open https://kiramanga.me/activate/ on the device. Its universal link
> reveals source management. Select one of the published sources, then use Home or Search to open a
> title, its chapters, the reader, and downloads.
>
> Manga requests go directly to the selected third-party source. The Kira backend supplies a signed
> source catalog and does not host manga files. Network and third-party source availability may
> affect results.
>
> Downloads, library records, history, bookmarks, reading progress, and Kira backup/package imports
> are stored locally. Notification permission is optional and requested contextually.
>
> The app supports the kiramanga URL scheme and the applinks:kiramanga.me associated domain. No
> demo account is required.

Do not use these notes until the activation validator and content-rights blocker are resolved.

## 9. Export Compliance and Encryption

Technically verified:

- HTTPS/TLS is used.
- The app implements standard SHA-256 and Ed25519 signature verification through
  dev.whyoleg.cryptography 0.6.0.
- No proprietary or non-standard cryptography was found.
- Backup ZIPs are not encrypted by the app.
- ITSAppUsesNonExemptEncryption is currently NO.

Likely App Store answers:

- Uses encryption: Yes.
- Proprietary/non-standard algorithms: No.
- Standard algorithms in addition to Apple operating-system encryption: Yes.
- Eligible for exemption: Likely yes, because the non-OS cryptography is used for digital-signature
  and integrity verification and network encryption uses standard TLS.
- Export documentation upload: Likely not required.
- ITSAppUsesNonExemptEncryption: No.

Apple states that NO is appropriate where an app uses only exempt encryption, while some exempt
products may still require an annual self-classification report.
[Apple export-compliance documentation](https://developer.apple.com/documentation/Security/complying-with-encryption-export-regulations).
Final legal confirmation remains required.

## 10. Screenshots and App Previews

Because the binary supports both iPhone and iPad, both sets are required:

- iPhone 6.9-inch: 1260×2736, 1290×2796, or 1320×2868 portrait.
- iPad 13-inch: 2064×2752 or 2048×2732 portrait.
- One to ten screenshots per device class.
- No alpha channel.
- Highest-resolution sets can be scaled for smaller devices.
- App Preview is optional; up to three per device size/language.

References:
[Apple screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications?page_id=111069)
and
[preview requirements](https://developer.apple.com/help/app-store-connect/manage-app-information/upload-app-previews-and-screenshots).

Recommended sequence:

| # | Screen/path | Headline | State |
|---|---|---|---|
| 1 | Onboarding → Continue → Library | Your library, organized | Authorized sanitized titles |
| 2 | Activate → Sources → Home | Browse sources you choose | One approved source enabled |
| 3 | Home → Search | Find your next read | Safe search results |
| 4 | Search → Manga details | Chapters at a glance | Description and chapters visible |
| 5 | Chapter → Reader | Read your way | Real page, reader chrome visible |
| 6 | Details → Download | Keep chapters offline | Completed local download |
| 7 | History or Updates | Pick up where you left off | Sanitized progress/history |
| 8 | Settings → Appearance/Language | Your language, your style | Arabic RTL or dark mode |

Use real shipping UI and content you are authorized to display. Hide complaint identifiers, device
metadata, emails, notifications, provider cookies, and personal library/history. No iOS screenshot
automation currently exists.

## 11. Accessibility

The code provides dark themes, many accessibility descriptions, and Arabic RTL. It does not provide
enough evidence that all common tasks pass Apple’s evaluation criteria with VoiceOver, Voice
Control, 200% Larger Text, contrast, or Reduced Motion.

Current safe App Store Connect selection:

- Do not publish any accessibility capability declarations yet.
- Do not select VoiceOver, Voice Control, Larger Text, Dark Interface, Differentiate Without Color
  Alone, Sufficient Contrast, or Reduced Motion until physical testing is complete.
- Captions: not applicable.
- Audio Descriptions: not applicable.

Apple currently treats these labels as voluntary, but each claimed feature must work across common
tasks.
[Accessibility Nutrition Labels](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/overview-of-accessibility-nutrition-labels/).

## 12. Pricing and Availability

| Field | Recommended result |
|---|---|
| Price | Free |
| IAP/subscriptions | None |
| Tax category | App Store software |
| Pre-order | No |
| Distribution | Public App Store |
| Education/Business | Available without a reduced price |
| Mac availability | Opt out until specifically tested |
| Apple Vision Pro compatibility | Opt out until specifically tested |
| Release method | Manually release this version |
| Countries/regions | USER INPUT REQUIRED |

Apple permits a manual release so approval does not automatically publish the app.
[Release options](https://developer.apple.com/help/app-store-connect/manage-your-apps-availability/select-an-app-store-version-release-option).
Pricing and storefront availability are configured separately.
[Pricing and availability](https://developer.apple.com/help/app-store-connect/reference/pricing-and-availability/app-pricing-and-availability/).

## 13. Agreements and Regulatory Requirements

| Requirement | Classification |
|---|---|
| Active Apple Developer Program membership | REQUIRED FOR CURRENT RELEASE; status must be confirmed |
| Current free-app/App Store agreement | REQUIRED FOR CURRENT RELEASE; status must be confirmed |
| Paid Apps Agreement | REQUIRED ONLY IF MONETIZATION IS ADDED LATER |
| Tax and banking | NOT APPLICABLE for free distribution, except possible DSA trader requirements |
| Digital Services Act declaration | REQUIRED FOR CURRENT RELEASE; USER INPUT REQUIRED |
| Content-rights attestation | REQUIRED FOR CURRENT RELEASE; USER INPUT REQUIRED |
| Age-rating questionnaire | REQUIRED FOR CURRENT RELEASE; partially USER INPUT REQUIRED |
| Export-compliance declaration | REQUIRED FOR CURRENT RELEASE; legal confirmation required |
| Medical-device declaration | NOT APPLICABLE |
| Gambling permits | NOT APPLICABLE |
| China mainland publishing/ICP documentation | REQUIRED only if China mainland is selected |

DSA trader status must be declared even if the app is not distributed in the EU. Apple cannot
determine whether an account holder is a trader.
[Apple DSA guidance](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements/).

If China mainland is selected, Apple states that book/magazine content can require an Internet
Publishing Permit and related documentation.

## 14. Build Selection and Binary Validation

No build can currently be recommended for App Review.

Verified:

- Protected certificate/profile inputs passed the repository’s non-secret signing validation.
- Release entitlements require production APNs and applinks:kiramanga.me.
- Workflow checks arm64-only device code, bundle/team/profile consistency, dSYMs, Crashlytics
  upload, privacy manifest, absence of advertising-identifier APIs, and export compliance.
- Fastlane uploads only to TestFlight and does not submit App Review.
- No successful processed TestFlight upload was found for the current code.
- No final signed IPA/archive was available for inspection.

Before selecting a build:

1. Create a diagnostics-disabled App Store release path.
2. Build from an exact clean, reviewed commit.
3. Query App Store Connect for the next unused build number.
4. Archive and export with Apple Distribution.
5. Validate bundle ID, Team ID, entitlements, provisioning profile, arm64, privacy report, embedded
   frameworks, and dSYM UUIDs.
6. Upload to TestFlight without external distribution.
7. Wait for App Store Connect to report the build as processed.
8. Run physical iPhone and iPad QA.
9. Select only that exact processed build.

## 15. Store Listing Versus Binary Consistency Audit

| Mismatch | Required resolution |
|---|---|
| Workflow enables crash diagnostics | Add a diagnostics-disabled production archive path |
| Complaint path lacks proven authorization | Secure it server-side or disable it in public builds |
| Privacy manifest says Other User Content | Change complaint classification to Customer Support |
| Analytics effectively enabled despite stale plist flags | Decide intended behavior and use supported Firebase keys |
| Privacy and takedown pages say Draft | Obtain approval, remove Draft only after approval, redeploy |
| Activation validator accepts unrelated domains | Parse and allow only the official HTTPS host/path and custom scheme |
| Photos usage text claims direct saving | Remove stale purpose key or implement and verify direct Photos saving |
| Third-party source privacy unknown | Audit source-provider collection, retention, and tracking |
| Content rights not documented | Obtain rights/permission evidence per storefront |
| Adult filters do not cover every source | Complete content audit before answering age rating |
| Inactive raijinscan.co HTTP ATS exception remains | Remove unless a reviewed active source requires it |
| No processed production candidate | Produce and validate exact signed build |
| No screenshot/accessibility device evidence | Capture and test on real supported devices |

## 16. FINAL COPY/PASTE VALUES

### App Information

**APP NAME**  
Kira Manga

**SUBTITLE**  
Your multilingual manga reader

**PRIMARY LANGUAGE**  
English (U.S.)

**PRIMARY CATEGORY**  
Books

**SECONDARY CATEGORY**  
Entertainment

**BUNDLE ID**  
me.manga.kira

**APPLE ID**  
6792232678

**SKU**  
USER INPUT REQUIRED

**MADE FOR KIDS**  
No

**LICENSE AGREEMENT**  
Apple Standard End User License Agreement

**COPYRIGHT**  
USER INPUT REQUIRED

**CONTENT RIGHTS — ACCESSES THIRD-PARTY CONTENT**  
Yes

**CONTENT RIGHTS — NECESSARY RIGHTS OR PERMISSION ATTESTATION**  
USER INPUT REQUIRED

### Product Page

**PROMOTIONAL TEXT**  
Build your library, read online or offline, and move your Kira exports between devices.

**DESCRIPTION**  
Kira Manga is a multilingual reader for organizing and reading manga from sources you choose.

Browse and search configured source catalogs, open title and chapter details, save titles to your
local library, and keep track of reading progress, bookmarks, history, and chapter updates.

Read online in continuous or paged layouts, or download chapters for offline reading. Export and
import Kira backup ZIP files and individual manga packages to move your library and downloaded
content between supported devices.

Choose light, dark, system, or pure-black themes. Kira supports 11 interface languages, including
Arabic with right-to-left layout.

Kira does not require an account, include advertising, or sell subscriptions. Manga files are
retrieved from third-party internet sources selected through the app and are not permanently hosted
by Kira. Source availability and content may change. Only access or download content you are
authorized to use.

**KEYWORDS**  
reader,webtoon,library,offline,chapters,comics,bookmarks,downloads,reading

**WHAT’S NEW — IF THE FIELD APPEARS**  
This release improves source reliability, adds signed backend-managed source updates, refreshes app
branding and startup, strengthens offline catalog fallback, and fixes library metadata, reading,
and embedded web-view issues.

### URLs

**MARKETING URL**  
https://kiramanga.me/

**SUPPORT URL**  
https://kiramanga.me/support/

**PRIVACY POLICY URL**  
https://kiramanga.me/privacy/

**PRIVACY CHOICES URL**  
https://kiramanga.me/data-deletion/

**ACCOUNT DELETION URL**  
Not applicable—no accounts or registration

### App Privacy

**DATA COLLECTED**  
Yes

**CUSTOMER SUPPORT**  
Collected; linked; not used for tracking; App Functionality

**DEVICE ID**  
Collected; linked; App Functionality and Analytics; tracking selection USER INPUT REQUIRED

**COARSE LOCATION**  
Collected; linked conservatively; Analytics; tracking selection USER INPUT REQUIRED

**PRODUCT INTERACTION**  
Collected; linked conservatively; Analytics; tracking selection USER INPUT REQUIRED

**OTHER USAGE DATA**  
Collected; linked conservatively; Analytics; tracking selection USER INPUT REQUIRED

**CRASH DATA**  
Collected; not linked; not used for tracking; App Functionality

**OTHER DIAGNOSTIC DATA**  
Collected; linked; not used for tracking; App Functionality and Analytics

**OTHER DATA TYPES**  
Collected; not linked; not used for tracking; Analytics

**SEARCH HISTORY**  
USER INPUT REQUIRED

**BROWSING HISTORY**  
USER INPUT REQUIRED

### Age Rating

**PARENTAL CONTROLS**  
No

**AGE ASSURANCE**  
No

**UNRESTRICTED WEB ACCESS**  
No

**USER-GENERATED CONTENT**  
No

**SOCIAL MEDIA**  
No

**MESSAGING AND CHAT**  
No

**ADVERTISING**  
No

**MEDICAL OR TREATMENT INFORMATION**  
None

**HEALTH OR WELLNESS TOPICS**  
None

**GAMBLING**  
None

**SIMULATED GAMBLING**  
None

**CONTESTS**  
None

**LOOT BOXES**  
None

**ALL THIRD-PARTY CONTENT FREQUENCY ANSWERS**  
USER INPUT REQUIRED

**EXPECTED RATING**  
USER INPUT REQUIRED

### Export Compliance

**USES ENCRYPTION**  
Yes

**PROPRIETARY OR NON-STANDARD ENCRYPTION**  
No

**STANDARD ENCRYPTION IN ADDITION TO APPLE OS ENCRYPTION**  
Yes

**EXEMPTION**  
Likely exempt — USER INPUT REQUIRED legal confirmation

**ITSAppUsesNonExemptEncryption**  
No

### Accessibility

**PUBLISHED ACCESSIBILITY FEATURES**  
None until physical accessibility validation is complete

**CAPTIONS**  
Not applicable

**AUDIO DESCRIPTIONS**  
Not applicable

### Pricing and Availability

**PRICE**  
Free

**TAX CATEGORY**  
App Store software

**PRE-ORDER**  
No

**DISTRIBUTION METHOD**  
Public App Store

**EDUCATION AND BUSINESS**  
Available for education and business without a reduced price

**MAC AVAILABILITY**  
Do not make available until tested

**APPLE VISION PRO AVAILABILITY**  
Do not make available until tested

**COUNTRIES OR REGIONS**  
USER INPUT REQUIRED

**RELEASE METHOD**  
Manually release this version

### App Review

**SIGN-IN REQUIRED**  
No

**DEMO ACCOUNT REQUIRED**  
No

**MULTIPLE ACCOUNTS OR ROLES REQUIRED**  
No

**CONTACT INFORMATION**  
Use the protected validated App Review contact values; USER INPUT REQUIRED for secure entry

**REVIEW NOTES**  
Kira Manga requires no account, subscription, or payment. On first launch, complete Welcome and
Theme, then choose Continue to Library.

To evaluate source browsing, open https://kiramanga.me/activate/ on the device. Its universal link
reveals source management. Select one of the published sources, then use Home or Search to open a
title, its chapters, the reader, and downloads.

Manga requests go directly to the selected third-party source. The Kira backend supplies a signed
source catalog and does not host manga files. Network and third-party source availability may
affect results.

Downloads, library records, history, bookmarks, reading progress, and Kira backup/package imports
are stored locally. Notification permission is optional and requested contextually.

The app supports the kiramanga URL scheme and the applinks:kiramanga.me associated domain. No demo
account is required.

## 17. USER INPUT REQUIRED

### 1. SKU and Current App Store Record State

- Required: Existing immutable SKU, current primary language/category values, whether 1.0.0 is the
  first App Store version, and the highest uploaded build number.
- Why: Local App Store Connect authentication could not retrieve the current record.
- Obtain from: App Store Connect → Apps → Kira Manga → App Information and TestFlight → Build list.
- Blocks TestFlight: Highest build number does.
- Blocks App Review: Yes.
- Blocks public release: Yes.

### 2. Copyright

- Required: Year and exact person/entity owning the application copyright.
- Why: Not present in executable configuration or approved legal metadata.
- Obtain from: The rights owner.
- Blocks TestFlight: No.
- Blocks App Review: Yes.
- Blocks public release: Yes.

### 3. Third-Party Content Rights

- Required: Evidence that each of the 12 sources may lawfully be accessed, displayed, and
  downloaded in every selected storefront.
- Why: No licenses, permissions, or other legal basis were found.
- Obtain from: Source operators/rights holders and legal review.
- Blocks TestFlight: No.
- Blocks App Review: Yes.
- Blocks public release: Yes.

### 4. Age-Rating Content Audit

- Required: Frequency answers for profanity, horror, substance references, mature themes, sexual
  content, graphic sexual content, violence, and weapons across content users can actually access.
- Why: Third-party catalogs are mutable and the current filters do not cover all sources.
- Obtain from: A reviewed production-catalog content audit.
- Blocks TestFlight: No.
- Blocks App Review: Yes.
- Blocks public release: Yes.

### 5. Firebase and Complaint-Service Production Settings

- Required: Deployed Firestore rules, App Check enforcement, authorization model, abuse limits,
  deletion/retention operations, Analytics Google Signals/Ads links/data-sharing settings, and
  active FIAM campaigns.
- Why: These settings are external to the repository and unavailable through current access.
- Obtain from: Firebase Console for project kira-manga.
- Blocks TestFlight: Not internal TestFlight.
- Blocks App Review: Yes while the complaint feature remains enabled.
- Blocks public release: Yes.

### 6. Third-Party Source Privacy Practices

- Required: Whether each source retains searches, viewed URLs, device/network identifiers, or uses
  them for tracking.
- Why: Apple requires restricted WebView/web-traffic collection to be disclosed; provider behavior
  cannot be inferred from the client.
- Obtain from: Each active source’s privacy policy, network audit, and operator confirmation.
- Blocks TestFlight: No.
- Blocks App Review: Yes.
- Blocks public release: Yes.

### 7. Legal-Policy Approval

- Required: Approval of the Privacy Policy, Terms, takedown process, and data-deletion page;
  authorization to remove “Draft policy.”
- Why: Legal approval cannot be inferred from code.
- Obtain from: The application owner/legal reviewer.
- Blocks TestFlight: No.
- Blocks App Review: Yes.
- Blocks public release: Yes.

### 8. Digital Services Act Status

- Required: Trader or non-trader decision and, if trader, Apple’s required verified
  contact/payment/documentation details.
- Why: Apple explicitly requires owner self-assessment.
- Obtain from: App Store Connect → Business → Agreements → Compliance → Digital Services Act.
- Blocks TestFlight: No.
- Blocks App Review: Yes where required.
- Blocks public release: Yes, especially EU availability.

### 9. Storefront Availability and Regional Permits

- Required: Final country/region selection and whether China mainland will be excluded or supported
  with required publishing/ICP documentation.
- Why: This is a distribution and legal-rights decision.
- Obtain from: App Store Connect and legal/content-rights review.
- Blocks TestFlight: No.
- Blocks App Review: Yes.
- Blocks public release: Yes.

### 10. Export-Compliance Legal Confirmation

- Required: Confirmation that Ed25519/SHA-256 signature verification and TLS qualify for the
  claimed exemption, plus whether an annual self-classification report applies.
- Why: Technical behavior is verified; legal classification is not.
- Obtain from: Qualified export-compliance advice or Apple’s compliance questionnaire.
- Blocks TestFlight: Potentially.
- Blocks App Review: Yes.
- Blocks public release: Yes.

### 11. Review Contact

- Required: Confirm that the protected TestFlight contact is also authorized as the App Review
  contact, then enter it directly in App Store Connect.
- Why: The values exist securely but should not be exposed in this report.
- Obtain from: Protected local release files or GitHub secrets.
- Blocks TestFlight: External TestFlight review only.
- Blocks App Review: Yes.
- Blocks public release: Yes.

### 12. Physical-Device Assets and QA

- Required: Authorized iPhone and iPad screenshots plus VoiceOver, Voice Control, Larger Text,
  contrast, RTL, notification, background-download, deep-link, and offline testing.
- Why: These require the final signed build and physical devices.
- Obtain from: Final candidate installed through TestFlight on supported iPhone/iPad hardware.
- Blocks TestFlight: No.
- Blocks App Review: Screenshots and final QA do.
- Blocks public release: Yes.
