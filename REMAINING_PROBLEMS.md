# Yami Manga — Remaining Problems (as of 2026-06-03)

Consolidated from this session's two audits (`APP_AUDIT_REVIEW.md`, `PLATFORM_FEATURE_MATRIX.md`),
reconciled against everything fixed this session.

**Already fixed this session (NOT remaining):** all 3 High + all 8 Medium audit findings — Resume FAB,
paged-reader auto-advance, full `:ui` translation backfill (all 10 locales), Home pagination deadlock,
multi-repo search loading state, Library "Downloaded only", History/Updates full-identity route,
Arabic RTL flip on iOS/Desktop, and `CancellationException` rethrow across 8 `:data` repos. The iOS
live-language no-op is **mitigated** with a "restart to apply" cue.

What remains falls into 5 buckets.

---

## ✅ Update — fixed since this list was written (Low-item batches, all verified)

Commits `db49876f`, `26163ff5`, `8b28511d`, `25ac3f5e`, `07a7a2c5` (+ earlier `fa0aa9a7`, `f9de2481`,
`5dc7aae1`, `a4c2981d`, `1645340b`):
- **#7 sort-chip order**, **#8 RANDOM seed persistence**, **#9 empty-refresh guard** (Library cluster).
- **#12 iOS/Desktop notification-table write**; **#20/#21 dead-code deletions**; **key-parity CI lint** (E).
- **B23 FeaturedCarousel `itemsIndexed`**; **A11 Updates "mark all read" enablement**.
- **A2 "mark this and below" off-by-one**; **A1 reader mark-read parity** (+ prev-chapter advance).
- **A4 `fetchMore` snapshot write-back**; **A6 save-toggle spinner on add only**.
- **A16 statistics locale-aware number grouping** (expect/actual `formatGroupedNumber`).
- **A3 adult-gate** — relabeled as a deliberate compliance over-block (no behavior change; not suppressed).
- **B24 WhatsNew pager key** — investigated; left as native (no key is crash-proof) — not a real defect.

### ✅ Update 2 — fixed 2026-06-03/04 (batches E–J + adversarial-review follow-ups)
Commits `417c6517` (E), `d6cf2a2c` (F), `26502b2e` (G), `7701abaf` (H), `33bfeeba` (I), `8d719f59` (J),
plus review-driven corrections `d7d0d7cc`, `c54285c1`, `7ffb32e5`, `e6cc5d57`:
- **A15 per-source icons** (E) — `RepoIconResolver` populated (~40 sources) + `LocalSourceIconResolver`
  composition local (provided in `App.kt`, consumed in `SourcesScreen`); follow-up added DilarV2/Tapas
  and native disabled-state tint + 24dp/16dp sizing.
- **A14 onboarding language names** (F) — expect/actual `displayLanguageName` (device-locale display
  name); follow-up restored native's first-letter capitalization on non-English locales.
- **A10 LAST_READ** (G) — `LibraryManga.lastOpenedAt` + sort by it; bump moved to **chapter-open**
  (not Details view) to match native `LibraryMangaRoute.onChapterClick`; regression test added.
- **A19 DIP use-cases** (H) — ReaderVM → `ObservePageProgressUseCase`, DetailsVM → `ResolveChapterIdUseCase`.
- **A17/A18 iOS `LocalAppLocale`** (I) — `current` seeded with the real system tag; original
  `AppleLanguages` preserved in a dedicated key (upgrade-path limitation documented).
- **A13 clear-cache external** (J) — `AppFileSystem.externalCacheDir` (Android) swept by `clearCacheLargerThan`.

### Still open (prioritized for next)
- **Low / invisible / deferred:** A22 complaint edit/reply drops `model`/`osRelease` (UI-invisible; needs a
  `ComplaintSummary` field or a fetch-all round-trip — deferred as costly), A5 save-from-Home no chapters
  (documented tradeoff).
- **Large / blocked:** C1 cross-platform background (library refresh + new-chapter notifications + true
  background downloads + toast + desktop app-version on iOS/Desktop); C2 intentional/blocked (ads,
  consent, in-app update/review, crash/analytics, push); D macOS WebView (upstream KCEF — blocked),
  iOS truly-live language (compose-resources internal API — mitigated with the restart cue).
- **Systemic:** LLM-translated strings not human-reviewed; dormant Firebase/Play SDKs (need real
  `google-services.json`); compose-resources version skew (plugin 1.11.0 vs resources 1.10.3).

---

## A. App-logic / parity nits (Low severity, fixable) — 22 items

Each is a small behavior divergence from native; none crash or lose data.

**Reader / Details**
1. **Reader marks a chapter read on merely viewing the last page** — `presentation/.../reader/ReaderViewModel.kt:474-476`. Eager vs native's advance-only. Decide + document or drop.
2. **"Mark this and below as read" off-by-one** — `presentation/.../details/DetailsViewModel.kt:784-797`. Marks the tapped chapter inclusively; native excludes it → `subList(index+1, size)`.
3. **Adult-content gate applied on the library Details path** — `presentation/.../details/DetailsViewModel.kt:237-271`. Native never gated the library screen (over-blocks; compliance-positive). Suppress when `isInLibrary`, or relabel the KDoc as a deliberate divergence.

**Home / Search**
4. **`fetchMore` never updates its accumulated snapshot** — `data/.../HomeFeedRepositoryImpl.kt:124-131`. Latent data-contract incoherence (VM dedup compensates; no user-visible loss). Mirror `fetchHome`'s write-back.
5. **Saving from Home doesn't fetch chapters** — `presentation/.../home/HomeViewModel.kt:282-292`. Library row shows 0 chapters until Details opens (documented tradeoff).
6. **Home save-toggle spins on removal too** — `presentation/.../home/HomeViewModel.kt:282-292`. Native only spun on add; gate `savingKeys` on `key !in savedKeys`.

**Library**
7. **Sort-option chips render in the wrong order** — `domain/.../library/LibrarySort.kt:40-74`. Positions 2–5 differ from native `SortType`; reorder (by-name persistence makes it safe).
8. **RANDOM sort seed not persisted** — `presentation/.../library/LibraryViewModel.kt:148-157, 706-710`. Grid reshuffles on every re-emission/restart; native persists `KEY_SEED`.
9. **Pull-to-refresh on an empty library still enqueues a refresh** — `domain/.../library/RefreshLibraryUseCase.kt:47-51`. Missing native's empty-library guard + "No manga yet" message. Short-circuit in the VM's `OnRefresh`.
10. **LAST_READ sort uses chapter read-date, not manga last-open** — `data/.../LibraryMappers.kt:73`, `LibraryViewModel.kt:703-705`. Diverges from native's `lastOpenTimestamp`; would also need `DetailsViewModel.OnEnter` to bump it.

**History / Updates / Downloads**
11. **Updates "Mark all read" disabled when all items are read** — `ui/.../updates/UpdatesScreen.kt:316-319`. Native keeps it enabled on non-emptiness (sibling delete-all already matches). Use `enabled = visibleItems.isNotEmpty()` or document.
12. **iOS/Desktop download completion doesn't write `localImagePaths/isDownloaded` to the notification table** — `shared/.../clean/CoroutineDownloadRepositoryImpl.kt:338-340`. The Updates download button reverts to enabled once the queue row is evicted. Inject `NotificationDao`, mirror Android. (Reader still reads local files fine via `saved_chapters`.)

**Settings / Sources**
13. **Clear-cache skips the Android external cache** — `shared/.../settings/domain/SettingsRepository.kt:103-105`. **Near-zero real impact** (cache lives in internal `cacheDir`; displayed size already internal-only). Completeness only.
14. **Onboarding language headers use hardcoded English names** — `ui/.../sources/SourcesScreen.kt:929-944`. Loses native's device-locale display name; ship per-locale name resources.
15. **Per-source rows drop the source icon** — `ui/.../sources/SourcesScreen.kt:858-916`. Domain `Source` has no icon field; drawables already exist → populate `RepoIconResolver` + expose an icon slot.

**i18n / locale**
16. **Statistics counts use hardcoded comma grouping** — `ui/.../statistics/StatisticsScreen.kt:464-475` (+ overview 322/335). Not locale-aware vs native's `%,d` (affects ar/de/fr/ru at 4+ digits). Use a `NumberFormat` expect/actual.
17. **iOS stale system-default capture** — `composeApp/iosMain/.../LocalAppLocale.ios.kt:17-33`. Captures the default from the same `AppleLanguages` key it later overwrites; latent (no reachable "system default" picker today).
18. **iOS `LocalAppLocale.current` returns `""`** — `LocalAppLocale.ios.kt:19-22`. Never seeded with the system default (unlike Android/Desktop); dead API today.

**Architecture / navigation / misc**
19. **ReaderViewModel injects a repository, not a use case** — `presentation/.../reader/ReaderViewModel.kt:239` (and `DetailsViewModel` injects `ChapterIdResolver`). DIP-boundary nit; add `ObservePageProgressUseCase`.
20. **Home bottom-tab reselect is dead** — `composeApp/.../routes/HomeReworkScreenRoute.kt` + `BottomNavigationBar.kt:134-136`. `homeReselectHandler` is read but never assigned, so tapping Home-while-on-Home doesn't scroll-to-top.
21. **Dead `NavigationLock` with a broken no-op guard** — `composeApp/.../navigation/NavigationLock.kt:7-39`. Unused; its `finally`-reset defeats the lock. Delete it (live guard is `isReadyForNavigation()`).
22. **User-side complaint edit/reply drops device-metadata keys** — `data/.../ComplaintActionRepositoryImpl.kt:88-159`. `ComplaintSummary` lacks `model`/`osRelease`; **UI-invisible** (admin path is correct). Fix by re-fetching the full complaint like the admin path.

## B. Compose UI-correctness nits (Low) — 2 items
23. **FeaturedCarousel uses `items.indexOf(item)`** — `ui/.../home/components/FeaturedCarousel.kt:82,88`. O(n) per item + wrong index if two entries compare equal. Use `itemsIndexed`.
24. **WhatsNew `HorizontalPager` has no `key`** — `ui/.../whatsnew/WhatsNewScreen.kt:489-518`. Harmless today (list is stable); add a key if it ever becomes mutable.

---

## C. Cross-platform capability gaps (from the platform matrix)

### C1 — Genuine parity gaps worth closing on iOS/Desktop
> **Staleness note (2026-07-04 audit):** several items below have SHIPPED since this list was
> written — kept struck-through for the historical record. Current state:
> - ~~Background library refresh~~ — **shipped**: iOS `BGAppRefreshTask` bridge
>   (`IosLibraryRefreshBridge`) + Android periodic WorkManager twin (`MyApp` schedules
>   `LibraryRefreshWorker`, M2 2026-07-03). Desktop remains unwired.
> - ~~True background downloads iOS~~ — **shipped**: the iOS background-URLSession engine
>   (`BackgroundUrlSessionDownloadRepository` + BG task windows) is the iOS default.
> - ~~Toast log-only~~ — **shipped**: iOS/Desktop toasts render via the `ToastRelay` snackbar
>   host in `App.kt`.
- **New-chapter system notifications** — Android-only (`ChapterNotificationHelper`); the cross-platform `NotificationPresenter` has **zero call sites**.
- **Desktop background refresh** — still unwired (iOS + Android now have it; see note above).
- **Desktop app-version** — hardcoded `"1.0.0-desktop"` (stub).

### C2 — Intentional / platform-blocked / not-applicable (Android-only by design)
> **Staleness note (2026-07-04 audit):** **Crashlytics + Analytics** and **remote push
> (FCM/APNs) + in-app messaging** have since shipped on iOS too (see CLAUDE.md "Firebase /
> Crashlytics" and the push deep-link chain) — only Desktop remains a no-op for those.
- **Ads / AdMob**, **Consent / UMP**, **In-app update**, **In-app review** — real only on Android; iOS/Desktop are no-ops/stubs. Most are deliberately deferred (rework contract) or have no desktop equivalent; iOS would need StoreKit wiring.
- **AVIF decode** — native on Android; iOS via system `UIImage` (16+); **Desktop unsupported** (no Skia/ImageIO path).
- **Inline video (What's New)** — poster-only on **every** platform (no `MediaPlayer` SPI); taps open the URL externally.
- **Material You / dynamic color** — **no-op everywhere** (a regression vs native on Android; correctly n/a on iOS/Desktop).

### C3 — Desktop OS differences (Windows vs Mac vs Linux)
- **macOS embedded WebView — unsupported (see D).**
- **Windows secure-storage** uses a weaker `dos:hidden` fallback (POSIX `0600` unavailable).
- **Headless Linux** degrades tray notifications + share/open-URL to log-only (no display).

---

## D. Hard limitations — blocked / won't-fix without larger work
- **macOS embedded WebView (Cloudflare solver)** — *upstream-blocked.* Attempting KCEF init crashes the JVM (SIGABRT) even under a plain JDK: `libjcef.dylib` hard-codes the CEF framework path to `<java.home>/Contents/Frameworks/…`, which no non-JBR JDK ships. The app correctly hard-skips → placeholder. Needs an upstream KCEF 2025.03.23 fix or a shipped, layout-correct CEF bundle. (Open-in-external-browser still works on macOS.)
- **iOS truly-live language switch** — compose-resources' override hook (`ComposeEnvironment`/`ResourceEnvironment`) is `internal` in 1.10.3, and `NSLocale` can't move mid-process. **Mitigated** with the "restart to apply" cue (shipped). A genuinely live switch needs a compose-resources change or a custom localization layer.

---

## E. Systemic / infra
- **No key-parity CI lint** — the i18n gap (and items #14, #16) recur because nothing enforces that every `values-<loc>/` covers every referenced key. The port scripts in `.parity_i18n_tmp/` are a ready basis for this check.
- **LLM-translated residual strings (~2072)** from the backfill are validated for placeholders/escaping/XML but **not human-reviewed** — recommend a native-speaker pass before release (esp. ja/ar/ru).
- **Dormant Firebase/Play SDKs** — ads/consent/review/analytics/Crashlytics are wired but inert until a real `google-services.json` replaces the committed placeholder; several are also not yet invoked by any rework flow.
- **compose-resources version skew** — Compose-MP plugin is `1.11.0` but `components-resources` resolves to `1.10.3`; latent oddity worth aligning.

---

## F. Not a bug to fix here
- **Leftover debug log on Home open-in-WebView** — `ui/.../home/HomeScreen.kt:217` (`Logger.withTag("onOpenWebView").i {…}`). This is in **your uncommitted WIP** — yours to remove before committing.

---

### Suggested next priorities
The highest value-to-effort, contained items: the Library cluster (#7 sort order, #8 RANDOM seed, #9 empty-PTR), the dead-code cleanups (#20, #21), the iOS/Desktop notification-table write (#12, unblocks the Updates button + ties off the download loop), and the **key-parity CI lint** (E) to stop i18n regressions structurally. The cross-platform C1 gaps (background refresh + notifications on iOS/Desktop) are the biggest *feature* gaps but the largest effort.
