# Review Backlog — 2026-07-02

Complete backlog from the phased deep review (P1–P10 + gap-closure pass, all read from current
code) plus the production-readiness and feature-by-feature reviews of the same date. Every item
carries: where, root cause, impact, verification status, proposed fix, effort (S/M/L), and a
now/defer recommendation. **Bugs** are defects in shipped behavior; **Improvements** are gaps or
enhancements. **Requires** distinguishes code changes from Firebase-Console / store-config /
device-QA work.

Legend: 🚫 = **RELEASE BLOCKER** · ✅ Confirmed (code-verified) · ❓ Needs verification

---

## 1. Critical / release blockers

### 🚫 C1 — Admin complaint console ships enabled for every user
- **Where:** `core/.../admin/Admin.kt:47` (`isAdmin = true`, zero runtime writers); routed from
  `SettingsReworkScreenRoute` → `Screen.ComplaintAdminRework`; actions in
  `presentation/.../complaint/admin/AdminComplaintViewModel.kt` (changeStatus / addClosureReason /
  adminEditComplaint / adminDeleteComplaint).
- **Root cause:** inherited native-parity defect — the native app hardcodes the same flag; parity
  porting carried it over.
- **Impact:** every user on every platform gets a working admin console with read + PATCH + DELETE
  over **all users' complaints**. Store-release blocker.
- **Status:** ✅ Confirmed (flag, routing, and both write paths read in code).
- **Fix:** default `false` + an owner re-enable mechanism (owner to choose: build flag, hidden
  gesture, remote flag, or authed allow-list). Mechanism decision is the owner's.
- **Effort:** S (flag flip) to M (proper re-enable mechanism). — **Type:** Bug. — **Requires:** Code.
- **Recommendation:** NOW (first code fix once mechanism is decided).

### 🚫 C2 — Firestore security rules unverified (server-side backstop for C1)
- **Where:** Firebase project (not in repo). Client surfaces: Android
  `ComplaintFirestoreDataSource` (Firestore SDK, **no Firebase Auth sign-in anywhere in the app**);
  iOS/Desktop `data/.../complaint/repository/ComplaintFirestoreRestDataSource.kt` (REST,
  **no Authorization header** — PATCH at :155, DELETE at :171). User-side reads are
  client-supplied-`userId`, unauthenticated.
- **Root cause:** the app was built with no auth layer; safety depends entirely on server rules
  that cannot be inspected from the repo.
- **Impact:** if rules are permissive, anyone (not even via the app — raw REST) can read/edit/
  delete the entire complaints collection. This is independent of C1: fixing `isAdmin` does NOT
  close it.
- **Status:** ❓ Needs verification (owner must inspect the rules in the Firebase console).
- **Fix:** lock rules server-side (per-user read/write on own docs keyed by auth or at minimum
  deny client writes to others' docs; admin writes via authed backend or rule-gated custom claim).
  Longer-term: add Firebase Auth (anonymous) so `userId` is not client-asserted.
- **Effort:** S (console rules) / L (adding real auth). — **Type:** Bug (exposure). —
  **Requires:** **Firebase Console** (rules) now; Code later if auth is added.
- **Recommendation:** NOW — do the console verification **today**, before any other work.

### 🚫 C3 — Two local commits not pushed (privacy manifest + CI repair)
- **Where:** local `main`: `0e5c5ac4` (adds `iosApp/iosApp/PrivacyInfo.xcprivacy`) and
  `d91cdece` (fixes the broken CI compile task + adds `:data:download`/`:platform`/`:composeApp`
  desktopTest + `:ui:checkLocaleKeyParity` to CI).
- **Root cause:** awaiting explicit owner push instruction (by agreed workflow).
- **Impact:** the privacy manifest is an **App Store submission requirement** — a build cut from
  `origin/main` today would be rejected/flagged; CI on origin is still red-shaped.
- **Status:** ✅ Confirmed (both verified locally; working tree clean apart from owner WIP files).
- **Fix:** push both commits (no code change needed).
- **Effort:** S. — **Type:** Action item. — **Requires:** push approval only.
- **Recommendation:** NOW.

---

## 2. High priority

### H1 — Ads stack ships with zero consumers (decision required)
- **Where:** `:app` deps (AdMob + facebook/ironsource/vungle/inmobi mediation), UMP consent flow in
  `MainActivity`, `AD_ID` permission, real `ADMOB_*` unit IDs in `gradle.properties`;
  `AdProvider` has **no caller** in `:presentation`/`:ui` (grep-verified).
- **Root cause:** ads infrastructure ported for parity; the ad-rendering surfaces were never wired
  in the rework UI.
- **Impact:** EEA users see a consent dialog for ads that never render; APK weight; Play
  data-safety declarations for SDKs that collect data for nothing. Not a crash, but a real
  user-facing + store-compliance oddity. Blocks the *release decision*, not the build.
- **Status:** ✅ Confirmed.
- **Fix:** owner decision — (a) wire ad placements, or (b) strip the SDKs + consent flow +
  permission + IDs.
- **Effort:** strip = M; wire = L. — **Type:** Improvement/decision. — **Requires:** Code (+ Play
  data-safety form update either way).
- **Recommendation:** decide NOW, implement before release.

### H2 — Signed R8 release on-device smoke with production key
- **Where:** `:app:assembleRelease` output (R8 fullMode + minify + shrink). Local signed build with
  a throwaway key passed clean (4m30s, zero missing-class warnings) after the download/push/FIAM
  code landed.
- **Root cause:** minification issues only fully manifest at runtime; no device run has happened
  with the production keystore since the new subsystems landed.
- **Impact:** last-line protection against R8-only crashes (reflection, serialization, workers).
- **Status:** ❓ Needs device QA (build-time evidence is green).
- **Fix:** install the production-signed release APK on a device; smoke: launch, Home fetch,
  details, read, download, push tap-through, settings.
- **Effort:** S (an hour of QA). — **Type:** Verification. — **Requires:** Device QA.
- **Recommendation:** NOW-ish (before any store upload; after C1/C3).

---

## 3. Medium priority

### M1 — Updates screen: upstream error kills the feed with no retry (real bug)
- **Where:** `presentation/.../updates/UpdatesViewModel.kt` (the `.catch` on `observeUpdates`
  terminates the flow → `loadError` set, collector dead); `ui/.../updates/UpdatesScreen.kt`
  renders the inline error with no retry affordance.
- **Root cause:** `.catch` ends collection by design; no intent exists to resubscribe.
- **Impact:** after one Room/upstream throw, the Updates tab stays dead until the user leaves and
  re-enters. Rare trigger, bad dead-end.
- **Status:** ✅ Confirmed (VM + screen read).
- **Fix:** add `UpdatesIntent.OnRetry` that cancels + relaunches the observe collector (tracked-Job
  pattern already used elsewhere); render a Retry button in the error branch.
- **Effort:** S. — **Type:** Bug. — **Requires:** Code (+ 1 VM test).
- **Recommendation:** NOW (first post-blocker code fix; small and isolated).

### M2 — iOS background library refresh is a no-op
- **Where:** `platform/.../jobs/IosBackgroundJobScheduler` (`schedulePeriodic` logs only; Android
  uses real WorkManager via `LibraryRefreshRepositoryImpl`).
- **Root cause:** BGTaskScheduler periodic refresh was never implemented for the library-refresh
  job (the download subsystem has its own BG tasks; this is the *library* refresh).
- **Impact:** no background new-chapter checks / notifications on iOS; users must open the app or
  pull-refresh. Feature gap vs Android, accepted in `REMAINING_PROBLEMS.md` §C1/C2.
- **Status:** ✅ Confirmed.
- **Fix:** implement a `BGAppRefreshTask` that runs the same inline refresh path the manual
  refresh uses, with a conservative budget.
- **Effort:** M–L (BG scheduling + device QA). — **Type:** Improvement (platform parity). —
  **Requires:** Code + Device QA.
- **Recommendation:** DEFER (post-release; document in store notes).

### M3 — Dead legacy nav destinations still registered
- **Where:** `composeApp/.../App.kt` route table — verified: `Screen.DownloadsScreen` (legacy) has
  no live navigator (all paths use `DownloadsRework`); suspected same for the legacy
  About/Complaint/WhatsNew/Statistics/History/Updates/Sources *rework-twin* routes marked
  "debug-reachable / not surfaced" in their KDocs.
- **Root cause:** strangler-fig coexistence left both route generations registered after the swaps.
- **Impact:** dead code + a set of navigable-but-unmaintained surfaces someone could deep-link
  into; no user-visible harm today (push deep-links only target trusted destinations).
- **Status:** ✅ Confirmed for `DownloadsScreen`; ❓ needs a caller-audit per remaining twin before
  deletion.
- **Fix:** audit navigators per route; delete unreachable `composable<...>` blocks + route keys +
  adapters in one sweep.
- **Effort:** M. — **Type:** Improvement (cleanup). — **Requires:** Code.
- **Recommendation:** DEFER (bundle into a cleanup pass; safe but churny).

---

## 4. Low / cosmetic

### L1 — iOS native reader: full-screen error is English-only
- **Where:** `iosApp/iosApp/NativeReader/ReaderHostViewController.swift:93` ("Couldn't load this
  chapter") and `:98` ("Retry") — literals, while `ReaderStrings.couldntLoadChapter` / `.retry`
  exist and are used everywhere else.
- **Root cause:** `installLoadingAndError()` predates the `ReaderStrings` bridge adoption.
- **Impact:** one unlocalized error surface for non-English users. — **Status:** ✅ Confirmed.
- **Fix:** swap both literals for the `ReaderStrings` accessors. — **Effort:** S. —
  **Type:** Bug (i18n). — **Requires:** Code. — **Recommendation:** NOW (trivial, batch with M1).

### L2 — Paged reader can miss one page-change report
- **Where:** `iosApp/.../PagedReaderViewController.swift` — `reportPage()` fires from
  `scrollViewDidEndDecelerating`/`DidEndScrollingAnimation` only; a drag ending exactly at a page
  boundary with `willDecelerate == false` reports nothing.
- **Root cause:** missing `reportPage()` call in `scrollViewDidEndDragging(_:willDecelerate:false)`.
- **Impact:** resume position stale by one page until the next scroll; needs a pixel-exact lift
  (rare). — **Status:** ✅ Confirmed by code path analysis.
- **Fix:** call `reportPage()` when `!decelerate`. — **Effort:** S. — **Type:** Bug (edge). —
  **Requires:** Code. — **Recommendation:** NOW (one line, batch with L1).

### L3 — iOS image loader: in-flight coalescing ignores decode width
- **Where:** `iosApp/.../ReaderImageLoading.swift` — `inFlight` keyed by URL; memory cache keyed
  by (URL, width).
- **Root cause:** a zoom-triggered sharper re-decode that races the in-flight base decode joins the
  old request and receives the lower-res image once.
- **Impact:** transient blur; self-heals on the next interaction. — **Status:** ✅ Confirmed
  (theoretical race, real mechanism).
- **Fix:** key `inFlight`/`tokenURL` by the same `(url|width)` cache key. — **Effort:** S–M
  (touches cancel bookkeeping). — **Type:** Bug (cosmetic). — **Requires:** Code. —
  **Recommendation:** DEFER.

### L4 — Updates row: QUEUED spinner tinted `onPrimary` on a surface card
- **Where:** `ui/.../updates/UpdatesScreen.kt` `DownloadAffordance` (QUEUED branch).
- **Root cause:** ported native tint choice; on a light-theme `surface` card `onPrimary` is
  near-white-on-white.
- **Impact:** queued indicator may be invisible in light theme. — **Status:** ✅ Confirmed (tint
  values read; needs a visual check to grade severity).
- **Fix:** tint `primary` at reduced alpha or `onSurfaceVariant` (deviating from native
  deliberately). — **Effort:** S. — **Type:** Bug (visual). — **Requires:** Code (+ screenshot
  check). — **Recommendation:** DEFER (verify visually first).

### L5 — iOS Downloads tab UX seams (finalize-pending Cancel; COMPRESSING invisible)
- **Where:** `presentation/.../downloads/DownloadsViewModel.kt` bucket partition +
  `ui/.../downloads/DownloadsScreen.kt`.
- **Root cause:** (a) iOS `DOWNLOADED` (transfer done, finalize pending) rows sit in Active with a
  Cancel affordance; (b) `COMPRESSING` maps to NO tab (native parity) but iOS can defer compression
  minutes (settle/BG window) vs native's seconds — the row "disappears" for that window.
- **Impact:** confusing states during iOS background downloads; engine handles both safely.
- **Status:** ✅ Confirmed. — **Fix:** (a) replace Cancel with a passive "finishing…" affordance;
  (b) show COMPRESSING rows in Active with a distinct label (deliberate native deviation — owner
  call). — **Effort:** S–M. — **Type:** Improvement (UX). — **Requires:** Code. —
  **Recommendation:** DEFER (pair with download device-QA findings).

### L6 — WhatsNew failure fallback returns an empty feature list
- **Where:** `:data` WhatsNew repo (documented TODO); `WhatsNewViewModel` has a real OnRetry.
- **Impact:** on fetch failure the screen shows error/empty instead of a canned default list.
- **Status:** ✅ Confirmed (documented TODO in code). — **Fix:** bundle a static default list. —
  **Effort:** S. — **Type:** Improvement. — **Requires:** Code. — **Recommendation:** DEFER.

### L7 — SourcesViewModel observe collector lacks `.catch` (posture inconsistency)
- **Where:** `presentation/.../sources/SourcesViewModel.kt` (documented Room-no-throw rationale);
  Updates got a `.catch` after audit — the family is inconsistent.
- **Impact:** an upstream throw would hit `onUnhandledError` (logged, swallowed) instead of a
  rendered error state; screen shows stale/empty list. — **Status:** ✅ Confirmed.
- **Fix:** add `.catch` → error state, or document the family-wide rule once. — **Effort:** S. —
  **Type:** Improvement (consistency). — **Requires:** Code. — **Recommendation:** DEFER.

### L8 — Settings feedback-failure effect carries a `cause` string (posture inconsistency)
- **Where:** `presentation/.../settings/SettingsViewModel.kt` (effect carries `e.message`);
  Sources/Language emit payload-free failures.
- **Impact:** none functional (it is trigger data, not rendering data) — consistency only.
- **Status:** ✅ Confirmed. — **Fix:** drop the payload or standardize the family. —
  **Effort:** S. — **Type:** Improvement. — **Requires:** Code. — **Recommendation:** DEFER.

### L9 — Desktop `Main.kt` `initFailed` is a plain `var` written from KCEF callbacks
- **Where:** `desktopApp/.../Main.kt` (async init block).
- **Root cause:** cross-thread visibility relies on KCEF invoking callbacks before `init()`
  returns on a synchronized path — unproven.
- **Impact:** worst case, a failed init is briefly reported as initialized; the fail-safe paths
  still degrade gracefully. — **Status:** ❓ Theoretical.
- **Fix:** `@Volatile`/atomic. — **Effort:** S. — **Type:** Bug (theoretical). — **Requires:**
  Code. — **Recommendation:** DEFER (one-liner; batch with any Desktop touch).

### L10 — `ChapterPagesRepositoryImpl.cleanupLocks` map grows monotonically
- **Where:** `data/.../repository/ChapterPagesRepositoryImpl.kt` (one Mutex per chapter ever
  read/cleaned, app-lifetime singleton).
- **Impact:** negligible memory (tiny objects, bounded by chapters touched per session).
- **Status:** ✅ Confirmed (nit). — **Fix:** none needed; optionally prune after cleanup. —
  **Effort:** S. — **Type:** Improvement (nit). — **Recommendation:** DEFER / won't-fix.

### L11 — Reader share may capture the outgoing page during a transition
- **Where:** `iosApp/.../PagedReaderViewController.swift` `currentImage()` uses
  `visibleCells.first` — two cells can be visible mid-swipe.
- **Impact:** share occasionally grabs the neighboring page. — **Status:** ✅ Confirmed
  (mechanism). — **Fix:** pick the cell nearest the viewport center. — **Effort:** S. —
  **Type:** Bug (cosmetic). — **Requires:** Code. — **Recommendation:** DEFER.

### L12 — Engine exposes the raw `{query}` template var (config-author footgun)
- **Where:** `sources/engine/.../GenericSourceClient.kt` `vars()` (raw alongside `{queryEncoded}`
  / `{queryJson}`); `DefaultSourceConfigValidator` does not flag raw `{query}` in URL templates.
- **Impact:** a future config using `{query}` in a URL breaks on special-character searches
  (correctness only — cannot redirect off-host). No current bundled config verified to misuse it.
- **Status:** ✅ Confirmed (mechanism); ❓ whether any bundled config misuses it.
- **Fix:** validator warning when an endpoint URL contains `{query}`. — **Effort:** S. —
  **Type:** Improvement (guard). — **Requires:** Code. — **Recommendation:** DEFER.

### L13 — WebView sandbox: unparseable main-frame URL is allowed (legacy-parity posture)
- **Where:** `composeApp/.../core/webview/WebViewUrlSandbox.kt:48` (`parse failure → true`,
  documented deliberate).
- **Impact:** a URL ktor cannot parse but the platform WebView can navigate bypasses the host pin.
  Narrow, and cookie capture is host-filtered anyway. — **Status:** ✅ Confirmed (accepted design).
- **Fix (optional hardening):** fail-closed on main-frame parse failure. — **Effort:** S. —
  **Type:** Improvement (hardening). — **Requires:** Code. — **Recommendation:** DEFER (owner
  call — changes legacy behavior).

### L14 — `KiraIcons`-only rule violated by ~25 of 30 `:ui` files
- **Where:** across `:ui` (e.g. `StatisticsScreen` 8 raw `Icons.*`; History/Updates/Home headers).
- **Impact:** design-system governance drift; no user-visible defect. — **Status:** ✅ Confirmed.
- **Fix:** either extend `KiraIcons` + sweep call sites, or relax the rule in CLAUDE.md to match
  reality. — **Effort:** M (sweep) / S (rule change). — **Type:** Improvement. —
  **Requires:** Code (or docs). — **Recommendation:** DEFER (decide rule first).

### L15 — Unlocalized string fragments
- **Where:** `StatisticsRepository` "h/m" duration (TODO at :78), `SettingsRepository` size units
  (:125), a `Text("v$version")` chip, download-notification strings (Android + iOS,
  English-only — known deferral).
- **Impact:** minor i18n gaps in ar + other locales. — **Status:** ✅ Confirmed.
- **Fix:** move formatting into `:ui` with resources (stats/settings need small API changes to
  return typed values instead of strings). — **Effort:** M. — **Type:** Bug (i18n). —
  **Requires:** Code. — **Recommendation:** DEFER (batch as one i18n pass).

### L16 — Stale documentation batch (misleads future work; zero runtime impact)
- **Where / what:** `KcefState.kt` KDoc describes the *inverted* old init strategy;
  `HomeViewModel:231-240` still warns about the refuted fetchMore "latent bug";
  `UpdatesScreen` KDoc's "no DB write on undo" predates the delete-immediately VM;
  CLAUDE.md "non-piloted sources stay legacy on Home" is behind the Phase 5/6 config-only-tabs
  code (`HomeFeedRepositoryImpl`); `iosApp/iOSApp.swift` says native reader "Default OFF" (flag is
  `true`); `MyApp` postscript mentions a removed CrashActivity handler; `proguard-rules.pro` keeps
  nonexistent `me.manga.kira.crash.CrashActivity`; `App.kt` KDoc "no commonMain BackHandler shim
  yet" is stale — `me.manga.kira.ui.util.BackHandler` exists and is used by Home/Search/Library.
- **Status:** ✅ Confirmed (each checked against live code). — **Fix:** one docs-only sweep commit.
- **Effort:** S. — **Type:** Improvement (docs). — **Requires:** Code (comments only). —
  **Recommendation:** NOW-ish (cheap; prevents future mis-decisions; safe batch).

---

## 5. UI/UX & feature improvements (not bugs)

### U1 — Home source-tab strip shows one neutral glyph for every source
- **Where:** `ui/.../home/HomeScreen.kt` `iconForTab` hardcodes `KiraIcons.Empty`;
  `RepoIconResolver` (composeApp) has no per-source mappings for tabs (the Sources screens use it
  via `LocalSourceIconResolver`, wired in `App.kt`).
- **Impact:** visible polish gap on the app's primary screen; native showed brand icons.
- **Status:** ✅ Confirmed. — **Fix:** add multiplatform brand drawables, populate the resolver,
  pass the painter through `HomeReworkScreenRoute` → `iconForTab` (3-step plan already documented
  in the KDoc). — **Effort:** M. — **Requires:** Code (+ assets). — **Recommendation:** DEFER
  (high-visibility polish; schedule soon after blockers).

### U2 — "New sources" tab badge permanently off
- **Where:** `HomeScreen.kt` `showNewBadge = false`; signal exists as legacy DataStore flow
  (`newSourcesFlow`) with no use case observing it.
- **Fix:** `ObserveNewSourcesUseCase` + clear-on-edit + `hasNewSources` in `HomeState`.
- **Status:** ✅ Confirmed. — **Effort:** S–M. — **Requires:** Code. — **Recommendation:** DEFER.

### U3 — FIAM reader suppression — **CLOSED by owner decision** (keep campaigns allowed everywhere,
  including the reader). Listed for completeness; no action unless the decision changes.

### U4 — Package-rename pass for relocated strangler files — **deferred by design** (CLAUDE.md:
  high import churn, zero behavior value). No action.

### U5 — Desktop: ship the KCEF bundle in installers (no ~150–200 MB first-run download)
- **Where:** `desktopApp/.../Main.kt` `resolveKcefInstallDir()` — the bundled path is implemented;
  packaging inputs are not populated. — **Status:** ✅ Confirmed (prep done, per-OS bundles not
  built). — **Effort:** M (per-OS packaging). — **Requires:** Build/packaging work. —
  **Recommendation:** DEFER (only matters for Desktop distribution).

### U6 — Download progress-stall watchdog (owner decision #2 follow-up)
- **Where:** iOS transfer layer (deliberately no `timeoutIntervalForResource`).
- **Status:** design deferred until after device QA (owner decision). — **Effort:** M. —
  **Requires:** Code + Device QA first. — **Recommendation:** DEFER (after Q1).

### U7 — Sources Stage-1/2: signed remote config delivery, image strategies, minAppVersion gating
- **Where:** `:sources:config` (`remote = null`, `DenyRemoteSignatureVerifier`) — fail-closed by
  design today. — **Status:** documented roadmap, not a defect. — **Effort:** L. —
  **Recommendation:** DEFER (roadmap).

---

## 6. Tests / CI gaps

### T1 — Presentation-VM test gaps (7 features)
- **Where:** no test suites for: sources, downloads, complaint (user + admin), statistics,
  language, theme, whatsnew VMs. (Tested: details/history/home/library/mvi/reader/search/
  settings/updates.)
- **Impact:** the Downloads 3-bucket partition and Sources toggle/complaint-retry logic are the
  riskiest untested spots. — **Status:** ✅ Confirmed.
- **Fix:** start with `DownloadsViewModelTest` (bucket partition) + `SourcesViewModelTest`
  (toggle fan-out, complaint retry keeps body). — **Effort:** M (all) / S (top two). —
  **Requires:** Code. — **Recommendation:** top two NOW-ish (pair with M1's test), rest DEFER.

### T2 — Zero UI-layer tests despite the stateless-content design
- **Where:** every screen exposes `XScreenContent(state, effects, onIntent)` for canned-state
  testing; nothing uses it. — **Status:** ✅ Confirmed. — **Effort:** M–L. —
  **Recommendation:** DEFER (value is real but lower than VM coverage).

### T3 — Static analysis is advisory with no baseline
- **Where:** `.github/workflows/ci.yml` `static-analysis` job (ktlint + detekt,
  `continue-on-error: true`; first-run baseline never triaged). — **Status:** ✅ Confirmed.
- **Fix:** run once, triage, add a detekt baseline, flip to blocking. — **Effort:** M. —
  **Requires:** Code/CI. — **Recommendation:** DEFER.

### T4 — CI branch-push runs are OFF (deliberate)
- **Where:** `ci.yml` (`push:` = tags only; PR + manual dispatch active) — chosen during the
  device-testing loop. — **Status:** ✅ Confirmed by design. — **Fix:** restore
  `branches: [main]` when the push-heavy campaign ends. — **Effort:** S. —
  **Recommendation:** DEFER until campaign ends, then NOW.

### T5 — `release-verify` CI job needs secrets to actually run
- **Where:** `ci.yml` release-verify (skips without `KEYSTORE_BASE64`; optional
  `GOOGLE_SERVICES_JSON`). — **Status:** ✅ Confirmed. — **Fix:** set the repo secrets. —
  **Effort:** S. — **Requires:** **GitHub/store config** (no code). — **Recommendation:** NOW-ish
  (one-time setup; makes the release path continuously verified).

---

## 7. Platform-specific gaps

### Android
- **PA1 — No open Android-specific items.** The Android host was the healthiest surface reviewed:
  crash handling (no custom handler, by design), release log floor before the Crashlytics writer,
  UMP-gated ads init, singleTop push routing with tests, backup rules excluding session cookies —
  all ✅ confirmed correct. (Shared items C1/H1/M1/etc. apply on Android too.)

### iOS
- **PI1 — Background library refresh no-op** → see M2.
- **PI2 — In-app language change applies on next launch** (`isLiveLocaleSwitchSupported = false`;
  layout direction correctly gated so RTL doesn't flip early). ✅ Confirmed, documented behavior.
  Improvement would be live locale switching — **Effort:** M, **Recommendation:** DEFER.
- **PI3 — Native reader cosmetics** → L1, L2, L3, L11.
- **PI4 — Real push delivery needs owner console steps** (❗not code): enable Push on the App ID,
  create + upload the APNs `.p8` to Firebase, real `GoogleService-Info.plist`, test on device.
  **Requires:** Apple/Firebase Console + Device QA. — **Recommendation:** NOW-ish if push is a
  launch feature.
- **PI5 — FIAM campaigns must be authored in the Firebase console** before anything shows in-app.
  **Requires:** Console. — **Recommendation:** owner's call.

### Desktop
- **PD1 — macOS embedded WebView unavailable** (KCEF hard-skip; graceful placeholder; upstream
  `icudtl.dat`/JBR bug). ✅ Confirmed, accepted. Revisit only if upstream fixes land. — DEFER.
- **PD2 — Windows secure-storage ACL hardening deferred** (`DesktopSecureStorage`: POSIX 0600 done;
  Windows is DOS-hidden best-effort — documented). — **Effort:** M. — DEFER.
- **PD3 — KCEF bundle shipping** → U5.
- **PD4 — Background jobs / push are no-ops by design** on Desktop. Accepted; no action.

---

## 8. Device-QA / deferred-verification queue (no code until results)

- **Q1 — iOS downloads resolve-ahead scenario:** queue 5+ chapters, background the app after ch1
  starts, verify ch2–4 complete via `prefetch.manifest.written` logs; also exercise pause-on-CF /
  rate-limit behavior. (Primary outstanding download validation.)
- **Q2 — R8 production-key smoke** → H2.
- **Q3 — Feel pass:** fast-scroller drag (list + grid), native reader gestures/zoom/chrome
  auto-hide (both flagged in-code by their authors as needing device verification).
- **Q4 — Push end-to-end on device** (after PI4): cold-start tap, warm tap, onboarding-guard, and
  the host-trust rejection path.
- **Q5 — Distribution-gated logging check:** confirm `BgDownloadLog.VERBOSE` is OFF in an
  App Store build and ON in TestFlight (runtime-enforced by distribution — verify once on device).
- **Q6 — L4 visual check** (queued-spinner visibility in light theme) before coding it.

---

## 9. Refuted during review (explicitly NO action — recorded to prevent re-work)

- ~~HomeFeedRepositoryImpl.fetchMore drops accumulated snapshot~~ — write-back exists with a
  generation guard; the HomeViewModel KDoc is stale (see L16).
- ~~HomeFeedRow items lack stable keys~~ — Home list and grid are keyed by `feedKey()`.
- ~~Library `showButtons` toggle has no `:ui` consumer~~ — the per-card action row consumes it,
  gated on `showButtons && !isInSelectionMode`.

---

## 10. Recommended execution order

**Phase 0 — today, no code:**
1. C2 — verify/lock Firestore rules in the console (blocker, server-side, independent of code).
2. C3 — push `0e5c5ac4` + `d91cdece` (on your word).
3. T5 — set CI release secrets (one-time).
4. H1 — make the ads decision (wire vs strip).

**Phase 1 — blocker code (small, isolated):**
5. C1 — `Admin.isAdmin` default-false + chosen re-enable mechanism.
6. H1 implementation (strip is the smaller path if chosen).

**Phase 2 — quick wins batch (one small PR):**
7. M1 — Updates retry (+ VM test), L1 — reader error strings, L2 — one-line page-report fix,
   L16 — stale-docs sweep, T1-top-two — Downloads/Sources VM tests.

**Phase 3 — pre-release verification (device):**
8. H2/Q2 — R8 production-key smoke; Q1 — resolve-ahead scenario; Q4 — push E2E (after PI4 console
   steps); Q5 — logging distribution check; Q3 — feel pass.

**Phase 4 — post-release / polish:**
9. U1 — Home tab brand icons (highest-visibility polish), U2 — new-sources badge,
   L5 — iOS Downloads UX seams (informed by Q1), M3 — dead-route cleanup, L15 — i18n batch,
   L14 — icons rule decision, M2 — iOS background refresh, T3/T4 — CI hardening, then the L3/L7–L13
   long tail and the U5–U7 roadmap items.

---
*Compiled 2026-07-02 from the accepted review stage.*

---

## Execution log (updated 2026-07-02, second pass)

DONE: C3 (pushed), M1, L1, L2, L11, L9, L12, L16, M3, T1-top-two, QA checklist,
M2 (iOS BGAppRefreshTask — device QA pending), U1 (brand icons via SourceTabsRow +
LocalSourceIconResolver; resolver was already fully populated — no WIP-file change needed),
U2 (full plumbing; ONE owner line remains in WIP HomeScreen.kt: `showNewBadge = state.hasNewSources`),
L3, L7, L8, L14 (rule relaxed).

REFUTED BY CURRENT CODE (no action): L5a — the DOWNLOADED row already renders NO action
(DownloadsScreen `DownloadRowAction` documents it) with the green chip; the P6 note predated
the redesign. Orphan-sweep follow-up to M3 — verified EMPTY (legacy screen trees were already
retired; only live settings/webview/whatsnew remain under features/).

STILL DEFERRED: L4 (gated on Q6 visual check), L5b (COMPRESSING visibility — owner call + QA),
L6 (WhatsNew fallback needs owner copy), L10 (won't-fix: prune would recreate the guarded race),
L13 (owner call), L15 stats/size typed-wire refactor (M churn across the strangler; keys can land
with it) + notifier strings (per-platform res mechanism), U5-U7, T2/T3/T4, M2-Android periodic
(owner decision — native ships it commented out).

EXCLUDED AREAS (untouched, held for the end): ads (H1), Firestore rules (C2),
Admin.isAdmin (C1), CI/release secrets (T5). The `v$version` chips (L15 fragment) live in the
excluded AdminComplaintScreen.

## Execution log (updated 2026-07-03, third pass)

DONE (2026-07-03, commits 0f75e5a9..d8238b85 pushed + e7e915ca..): L15 stats typed wire
(readMinutes Int end-to-end; :ui formats via h_m), L15 cache-size typed wire (cacheSizeBytes;
formatByteSize + strings_pfix_size_units ×11; formatLocalizedTwoDecimals expect/actual restores
native fr/ru/ar units), L15c :data:download notifier strings ×10 locales (values-in for
Indonesian), L4 (onSurfaceVariant; pixel test proved the old onPrimary = 0 visible px — Q6
resolved in code), T2 (:ui desktopTest compose-ui-test infra + 7 tests, in CI), T1 remainder
(statistics/theme/language/whatsnew/complaint/admin — 31 tests) + About (4 tests; every
presentation feature now covered), PI2 (iOS LIVE locale switch — flag flipped on empirical
proof; AppleLanguagesLiveSwitchContractTest pins the OS behavior), C1 (fail-closed debug-only
Admin.isAdmin on all three hosts; AdminDefaultsTest + :core:desktopTest in CI; release now hits
/source/35 not /dev/source), M2-Android (12h periodic WorkManager chain driving the existing
LibraryRefreshWorker; LibraryRefreshSchedulingTest), T3 (ktlint 9,450 + detekt 2,846 findings
baselined under config/; CI static-analysis flipped to BLOCKING, mutation-verified).

U5 — ASSESSED, DELIBERATELY LEFT OPEN (2026-07-03): the code path is complete
(Main.kt resolveKcefInstallDir prefers a shipped `kcef-bundle/` under
compose.application.resources.dir; falls back to the runtime download). What remains is
distribution work, not code: harvest the platform+arch-specific CEF bundle (~150-200 MB each)
by running the app once per target (win-x64/linux-x64/macos-x64/macos-arm64), wire each into
nativeDistributions via appResourcesRootDir, and verify each installer on its OS (helper-path/
icudtl.dat pitfalls per Main.kt KDoc). Requires per-OS hardware and only matters when Desktop
distribution actually ships — do it as part of the Desktop release checklist, not before.
The full procedure is documented at Main.kt's resolveKcefInstallDir KDoc.

C2 REMAINS INTENTIONALLY DEFERRED (owner decision 2026-07-03): with the admin console
debug-gated (C1), Firestore rules work would be discarded by the planned Firebase migration.
Recorded in Admin.kt's KDoc. STILL DEFERRED otherwise: H1 ads (keep as-is — no wiring, no
strip), T4 branch-push CI, T5 release secrets, L5b/L6/L13 (owner input), U6/U7.
