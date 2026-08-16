# Store submission drafts

These are implementation-derived drafts, not legal advice and not console submissions. Text marked
`[OWNER]` must be completed by the account owner and reviewed against the final signed artifacts,
Firebase console settings, source/content policy, and target-country requirements.

## Shared product facts

- Name: Kira Manga
- Version: 1.0.5
- Android package / iOS bundle ID: `me.manga.kira`
- Category candidate: Books & Reference
- Website: `https://kiramanga.me`
- Support: `https://kiramanga.me/support`
- Privacy: `https://kiramanga.me/privacy`
- Terms: `https://kiramanga.me/terms`
- Data deletion: `https://kiramanga.me/data-deletion`
- Copyright/takedown: `https://kiramanga.me/takedown`
- Copyright: `2026 KiraManga`
- Account required: No
- Advertising: No advertising UI, ad-serving SDK, or advertising-ID permission in this release;
  Firebase Analytics advertising-ID collection and ad personalization default are disabled
- Shipping platforms: Android and iOS

## Google Play listing draft

Short description:

> Browse, save, download, and read manga from the sources you choose.

Full description:

> Kira Manga is a multilingual reader for building and enjoying your manga library. Choose the
> sources you want to use, browse and search their catalogs, save titles, track reading progress,
> and download chapters for offline reading.
>
> Move your library between devices with Kira backup ZIPs or individually exported Kira manga
> packages. Reader options include continuous webtoon and paged layouts, bookmarks, history,
> updates, statistics, themes, and right-to-left language support.
>
> Kira does not host manga. Availability and content come from third-party sources selected by the
> user and may change or become unavailable. Only access content you are permitted to use.

Required owner inputs:

- `[OWNER]` Developer name, public support email, phone, postal address, and website ownership.
- `[OWNER]` Final app icon, phone/tablet screenshots, feature graphic, countries, price, and rollout.
- `[OWNER]` Content rating questionnaire, target audience, news-app status, and content-access
  declarations based on the actual enabled source catalog.
- `[OWNER]` Copyright/content moderation policy and proof that store-distributed behavior complies
  with Google Play policies.
- `[OWNER]` Data Safety answers after Firebase/complaint decisions are final.
- `[OWNER]` App-access reviewer instructions for any non-obvious functionality.

Reviewer note draft:

> Kira has no account or paywall. From “Start Reading,” reviewers can continue to the Library or
> import a Kira-generated backup/package. Existing and imported manga remain available without an
> account. Please contact [OWNER REVIEW CONTACT] if assistance is required during review.

## Google Play Data Safety working draft

Use a conservative declaration and verify it in the final Play SDK Index/console. Current code can
collect or transmit:

| Data category | Why / implementation | Working answer |
|---|---|---|
| App interactions / product interaction | Firebase Analytics; `app_open`, `manga_open`, source screen and manga title on Android; Firebase automatic events on both platforms | Collected, analytics/app functionality; linked to device identifiers; not sold |
| Device or other IDs | Firebase installation/messaging identifiers; complaint owner ID uses Android ID | Collected, analytics/messaging/app functionality; linked |
| Crash logs and diagnostics | Firebase Crashlytics and diagnostic context in release builds | Collected, analytics/app functionality; linked status `[OWNER VERIFY FIREBASE]` |
| App/device information and performance | Firebase SDK telemetry and complaint metadata | Collected; app functionality/analytics |
| Other user-generated content | Optional complaint subject/body/replies | Collected only when submitted; app functionality/support; linked to persistent device ID |
| Files/documents | Kira backup/package import/export remains local or user-selected sharing; app does not automatically upload backups | Not collected by developer through this flow |
| Advertising data | Ad-serving SDKs and advertising-ID permissions removed; Analytics ad-ID collection disabled | Not collected for advertising |

Additional draft answers:

- Data encrypted in transit: Yes for app-controlled Firebase/HTTPS traffic; third-party manga-source
  transport must be rechecked and any HTTP exception disclosed/remediated.
- Data deletion request mechanism: `https://kiramanga.me/data-deletion`, but adequacy is blocked on
  complaint authorization/retention and Firebase owner procedures.
- Data sharing: Firebase acts as a service provider; `[OWNER]` classify Google/Firebase handling and
  any other SDK recipient exactly as Play defines “sharing.”
- Security practices / independent review: `[OWNER]` do not claim an external audit unless one exists.

Public submission is blocked until `COMPLAINT_PRODUCTION_DECISION.md` is resolved and these answers
match the deployed Firebase project.

## Apple App Store listing draft

Subtitle candidate:

> Your multilingual manga reader

Promotional text candidate:

> Build a library, read online or offline, and move your Kira exports between devices.

Description: use the Google full-description draft above, adjusted to the final Apple character
limits and localization set.

Keywords candidate:

```text
manga,reader,webtoon,library,offline,chapters,comics
```

Confirmed configuration and remaining owner inputs:

- App Store ID `6792232678` is configured and is verified by the TestFlight workflow against bundle
  ID `me.manga.kira`.
- `[OWNER]` Seller/legal entity, support contact, age rating, availability, pricing,
  screenshots/previews, review contact, and export-compliance answers.
- `[OWNER]` Content-rights questionnaire and final reviewer instructions for external sources.
- `[OWNER]` Final privacy nutrition-label answers from the signed archive and Firebase console.

App Review note draft:

> Kira does not require an account. Reviewers can continue to the Library from Start Reading or
> import a Kira-generated backup/package; existing and imported manga remain readable independently.
> StoreKit review is requested only from an explicit Rate action. For support contact
> [OWNER REVIEW CONTACT].

## Apple App Privacy working draft

The app-level `PrivacyInfo.xcprivacy` declares data used for app functionality; Firebase packages
also provide SDK privacy manifests. The App Store Connect questionnaire should conservatively cover:

| Apple category | Current reason | Linked? | Tracking? |
|---|---|---:|---:|
| Other User Content | Optional complaint text/replies | Yes, persistent device/vendor ID | No |
| Device ID | Firebase identifiers and complaint owner identifier | Yes | No |
| Product Interaction / Other Usage Data | Firebase Analytics events and automatic analytics | Likely yes; `[OWNER VERIFY]` | No |
| Crash Data | Firebase Crashlytics | `[OWNER VERIFY FIREBASE CONFIG]` | No |
| Other Diagnostic Data / Performance Data | Diagnostic metadata, SDK telemetry | `[OWNER VERIFY]` | No |

No ATT prompt or tracking claim is expected: iOS links
`FirebaseAnalyticsCore`, has no ad SDK, and does not intentionally access IDFA. Verify
the final archive with Xcode's privacy report before answering “No tracking.”

Do not declare complaint data optional/deletable until the production server rules and deletion
process are proven. Do not omit automatic Firebase collection merely because the shared Kotlin iOS
`AnalyticsClient` adapter is a no-op; Firebase Analytics starts when `FirebaseApp.configure()` runs.

## Compliance checklist before submission

- Legal owner approves Privacy, Terms, Takedown, Support, and Data Deletion pages; all highlighted
  placeholders are replaced.
- Website is live over valid HTTPS and all store URLs return 200.
- Complaint public-release gate is resolved.
- Final Android manifest confirms no `AD_ID` or Privacy Sandbox advertising-ID/attribution
  permissions; final iOS archive confirms no AdSupport/IDFA linkage.
- Data Safety and App Privacy match the final binary and production Firebase settings.
- Adult/content rating and source catalog have owner/legal review.
- Store screenshots show real current UI and do not imply hosted/licensed content the owner cannot
  substantiate.
- Reviewer import and core-library steps work on physical devices.
- Signed AAB and iOS archive pass the complete QA checklist and retain symbols/mapping files.
