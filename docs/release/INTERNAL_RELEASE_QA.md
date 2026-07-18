# Internal release QA

Authoritative first-release checklist. Automated checks reduce risk but do not replace the physical
Android/iOS passes below. Record `PASS`, `FAIL`, or `N/A`, build number, device/OS, date, tester, and
an evidence link for every item. Any untriaged crash, data loss, complaint security gap, or signed-
artifact mismatch blocks public release.

## Run metadata

```text
Version/build:
Git commit:
Android artifact SHA-256:
iOS archive UUID / artifact SHA-256:
Firebase project IDs checked:
kiramanga.me deployment ID:
Tester/date:
```

Minimum device matrix:

- Android physical device on API 26 (or oldest available close to minSdk), one current API 36+
  device, and at least one low/mid-memory device.
- iPhone on iOS 15 (or oldest available), one current iOS device, and one lower-memory iPhone.
- Light/dark/AMOLED where available; English and Arabic on both platforms.
- Real networks: normal Wi-Fi, cellular, offline, slow/interrupted, and a source requiring the
  Cloudflare solver.

## A. Artifact and clean-install integrity

- [ ] Install the exact signed Android APK derived from the release/AAB configuration; no R8-only
  crash at startup or during full tap-through.
- [ ] Verify Android package `me.manga.kira`, version, signing certificate, target SDK 36, and no
  `com.google.android.gms.permission.AD_ID`, `ACCESS_ADSERVICES_AD_ID`, or
  `ACCESS_ADSERVICES_ATTRIBUTION` in the final manifest.
- [ ] Install the exact iOS Release/TestFlight archive; verify bundle `me.manga.kira`, version/build,
  production entitlements, privacy manifest, and dSYMs uploaded to Crashlytics.
- [ ] Confirm neither artifact contains placeholder Firebase project values.
- [ ] Confirm release logs do not print API keys, complaint text/user IDs/document
  IDs, cookies, authorization headers, HTML bodies, local paths, or other private payloads.
- [ ] Cold launch, warm launch, background/foreground, device rotation (Android), and memory-pressure
  relaunch complete without crash or navigation duplication.

## B. Upgrade, database migration, and data preservation

- [ ] Install the oldest practically available Kira/Yami-compatible build with a populated library,
  read state, bookmarks, history, source settings, and downloads; upgrade in place to this build.
- [ ] Library metadata, cover URLs/images, chapters, read/bookmark/resume state, history, download
  rows/files, source rows/languages/order/toggles, preferences, and activation state are preserved.
- [ ] Existing data does not implicitly unlock Sources management when `source_access_version = 0`.
- [ ] A previously activated install stays permanently activated across upgrade and restart.
- [ ] Removing a manga still cascade-cleans its chapters/download rows and physically removes files.
- [ ] Automated v1→v11 chain, v9→v10 orphan/FK, and v10→v11 cache tests are green.
- [ ] Android OS cloud/device-transfer restore does not restore partial app state; Kira ZIP remains
  the documented migration mechanism.

## C. Onboarding and activation

- [ ] Fresh install shows Start Reading, not the Sources list.
- [ ] “Continue to Library” completes onboarding, keeps source access locked, and does not re-show
  Start Reading on every launch.
- [ ] A successful full backup or manga-package import also completes onboarding but stays locked.
- [ ] Locked Home/Settings CTA can reopen Start Reading later.
- [ ] Blank, whitespace-only, malformed, and non-Kira links do not crash and show the localized
  “enter a link under our Kira Manga domain” guidance.
- [ ] Leading/trailing whitespace and mixed-case `kiramanga` activate successfully.
- [ ] Successful activation is persisted once and remains unlocked after restart/update.
- [ ] Activation never deletes/resets source rows, language, order, configuration, or toggles.
- [ ] Start Reading copy/layout is correct in all 11 locales; website action sits above social links,
  and Arabic uses correct RTL alignment.

## D. Deep links and route guards

- [ ] The product-owner-provided private activation QA entry point opens the installed app from
  Android and iOS in-app browsers.
- [ ] Its verified HTTPS destination opens through Android App Links for the Play-signed certificate.
- [ ] The same destination opens through iOS Universal Links on a physical device.
- [ ] Cold-start, warm/singleTop, and already-on-Start-Reading activation each navigate exactly once.
- [ ] A locked user cannot reach Sources management through bottom navigation, Settings, old routes,
  typed navigation, app/universal link, notification, or state restoration.
- [ ] Locked source toggle/language/order mutations are rejected without changing stored rows.
- [ ] Activation reveals the current stored source state immediately without app restart.
- [ ] Non-activation push/deep links still route to Details/Reader/Updates correctly and reject
  untrusted content hosts as designed.

## E. Home and source management

- [ ] Locked + no enabled/usable source shows the narrowly targeted Start Reading/import fallback,
  not “Failed to load: invalid input.”
- [ ] Activated + no enabled source shows the normal no-source/setup state, not an activation CTA.
- [ ] Real network, parse, source-site, WebView, and unexpected failures remain distinguishable and
  are not rewritten into activation guidance.
- [ ] With sources enabled, Home tabs, Featured/Popular, pagination, refresh, new-source badges, and
  source switching work.
- [ ] Enable/disable, language selection, ordering, base configuration, stopped/maintenance states,
  and restart persistence work after activation.
- [ ] Bundled config source labels/icons/hosts match the release document; every config-backed source
  used for release passes all supported verbs.

## F. Search, details, reader, and images

- [ ] Search empty/normal/no-result/error/pagination paths work for representative Arabic and English
  sources; rapid queries do not show stale results.
- [ ] Details opens from Home, Search, Library, Updates, History, notification, and deep link.
- [ ] Details refresh updates metadata/chapters without losing local read/bookmark/download state.
- [ ] Covers and page images load with required headers; broken URLs show bounded error UI.
- [ ] Continuous/webtoon and paged reader modes load, resume exact position, change chapters, mark
  read, bookmark, share, and survive background/foreground.
- [ ] Android tall pages stay sharp; fast scrolling does not cause obvious OOM or hardware-bitmap
  failures.
- [ ] Native iOS reader: fast webtoon fling, paged pinch zoom, chrome auto-hide, scrubber, appended
  chapters, previous/next boundaries, resume, sharing, and Arabic strings all work on a low-RAM phone.
- [ ] Adult-content gate is exercised from every Details entry point, including cache-first opens.

## G. Downloads and background work

- [ ] Download one, several, and “all” chapters; queue order/progress/size/count and duplicate
  prevention are correct.
- [ ] Cancel one/cancel all/retry/delete/remove-from-library leave DB and files consistent.
- [ ] Offline downloaded chapters open after process kill and device restart.
- [ ] Android WorkManager download and library refresh survive backgrounding; notifications and
  Android 13+ permission behavior are correct.
- [ ] Android release build completes foreground-service paths without permission/policy crash.
- [ ] iOS queues 5+ chapters, backgrounds during chapter 1, and advances resolve-ahead under granted
  background windows; force-quit then launch reconciles interrupted rows.
- [ ] iOS Low Power Mode/thermal stress defers foreground compression as designed, resumes after the
  state clears, and never leaves a chapter permanently on “Finalizing…”.
- [ ] iOS completion notification appears only after a readable CBZ exists; cancel during finalizing
  synchronously reverts readable state and later cleans files safely.
- [ ] iOS background URLSession relaunch, BGProcessing fallback, retry bounds, and no duplicate Live
  Activities are verified in Console.app using `KiraBgDownload`.

## H. Cloudflare/WebView and source resilience

- [ ] Trigger a real protected source challenge on Android and iOS; solver opens, completes, stores
  the scoped cookie/User-Agent, and retries the request.
- [ ] Cancel/timeout/repeated challenge paths return usable errors rather than trapping navigation.
- [ ] Cookies/headers never appear in release logs or Kira backup archives.
- [ ] Background iOS challenge failure defers to foreground without spinning or blocking unrelated
  downloads indefinitely.
- [ ] Offline, timeout, 403, 404, 429, malformed HTML/JSON, empty chapters, and empty pages are tested
  on representative sources.

## I. Kira backup and individual package import

- [ ] Export a full library without downloads; import into a clean install and verify manga metadata,
  cover URLs/images, chapter list, read/bookmark/resume state, history, likes/watching state.
- [ ] Export a full library with compressed downloads; import and read restored CBZs offline. Sizes
  and download ledger are correct.
- [ ] Export one manga package from Details; import into a clean install and prove no other manga or
  history is included.
- [ ] Import over an existing older/newer local copy; merge is additive/idempotent and never regresses
  local progress or overwrites an active/local download.
- [ ] Re-import the same archive; no duplicates or state loss.
- [ ] Cancel export/import between mangas; partial result remains consistent and retry converges.
- [ ] Invalid ZIP, missing `backup.json`, future format version, missing/corrupt CBZ entry, oversized
  archive, and insufficient storage produce localized recoverable errors.
- [ ] Import completes onboarding when appropriate but does not activate Sources management.
- [ ] Arbitrary external CBZ import is not advertised or accidentally accepted as a Kira package.
- [ ] Automated real-DB/full+individual repository round-trip tests are green.

## J. Navigation, localization, accessibility, and UI quality

- [ ] Every screen's top/back/system gesture works repeatedly in English LTR and Arabic RTL.
- [ ] On iOS Arabic, the default interactive-pop gesture follows RTL direction (left-to-right), has
  the native transition, and no screen becomes stuck after language switching.
- [ ] Android system back/predictive back works from dialogs, nested Details/Reader, imports,
  settings, and activation.
- [ ] Switching language live does not duplicate destinations, lose the selected tab, or leave a
  stale layout direction.
- [ ] All locale parity tests pass; human reviewers inspect at least English, Arabic, Japanese, and
  Russian critical copy. No clipped/overlapping text at large accessibility font sizes.
- [ ] Screen reader labels, touch target sizes, focus order, contrast, reduced-motion behavior, and
  keyboard/dialog dismissal are acceptable on both platforms.
- [ ] Navigation animation between Library, locked Home, and Start Reading is smooth and directionally
  correct; no root graph replacement flicker.

## K. Firebase, notifications, review, and complaint

- [ ] Real Firebase configs point to the intended production/staging project and are absent from git.
- [ ] Android Analytics receives expected `app_open`/`manga_open`; verify no unexpected sensitive
  parameters. iOS automatic Firebase Analytics behavior matches privacy disclosures.
- [ ] Deliberate Android and Kotlin/iOS test crashes appear symbolicated in Crashlytics with the exact
  build; Debug collection remains off where configured.
- [ ] Push permission is requested contextually; token creation/refresh/deletion works.
- [ ] Android FCM and iOS APNs/FCM cold/warm/background notification taps route once. iOS foreground
  download notification presentation is correct.
- [ ] FIAM has either an approved campaign test or is explicitly accepted as inert with no campaign.
- [ ] Android Play review and update flows fail gracefully outside Play and work from an internal
  Play track.
- [ ] iOS Rate uses StoreKit; Check for Updates opens the correct Kira App Store listing, never Google
  Play. Numeric `KIRA_APP_STORE_ID` is set.
- [ ] Complaint create/list/edit/reply/delete works on Android/iOS staging without production personal
  data; logs and errors expose no payload/identifier.
- [ ] Firestore negative tests prove cross-user and non-admin reads/writes are denied server-side.
  Until this passes—or the public feature is disabled—public release remains blocked.

## L. Website, policy, and store metadata

- [ ] `kira-web` production build uses the Play app-signing SHA-256 fingerprint and correct Apple
  team/bundle ID.
- [ ] `/`, `/guide`, `/privacy`, `/terms`, `/support`, `/takedown`, `/data-deletion`, and
  `/whatsnew/35/whatsnew.json` return 200 over valid HTTPS.
- [ ] Association files return direct 200 JSON responses with correct content type and identifiers;
  HTTP redirects to HTTPS; `www` canonicalization is correct.
- [ ] All legal placeholders and support/contact details are owner/legal approved; social profiles
  are confirmed official.
- [ ] Android Data Safety and Apple App Privacy answers match the signed artifacts and production
  Firebase settings; no ad/tracking declaration conflicts with the binaries.
- [ ] Store text, screenshots, content rating, source rights, takedown process, and reviewer
  instructions are owner-approved.
- [ ] Data-deletion requests are operationally tested end to end, including complaint/Firebase data.

## M. Performance and stability soak

- [ ] Scroll a 500+ title library and long chapter list; sorting/filtering/searching stays responsive.
- [ ] Read multiple long chapters, switch apps repeatedly, download in parallel, and monitor memory,
  CPU, battery, thermal state, disk growth, and crash/ANR/watchdog signals.
- [ ] Cold/warm startup and first Home/Library render are measured on low-tier devices; no accidental
  network storm or duplicate source refresh.
- [ ] Leave the app installed for 24+ hours with background work, then inspect queue/notification/DB
  consistency and battery settings.
- [ ] Fill storage near capacity and repeat download, CBZ finalization, backup export, and import; no
  corrupt final artifact or stuck modal remains.

## Release sign-off

```text
Engineering automated gates: PASS / FAIL
Android physical-device suite: PASS / FAIL
iOS physical-device suite: PASS / FAIL
Website/deep-link suite: PASS / FAIL
Firebase/complaint security gate: PASS / FAIL
Privacy/legal/store review: PASS / FAIL
Known accepted non-blockers:
Public blockers remaining:
Owner approval/date:
```
