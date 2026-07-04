# Yami Manga KMP — Full App Audit (UI + Logic + Parity)

**Date:** 2026-06-03 · **Branch:** parity-fixes
**Method:** 18 parallel reviewers (9 feature verticals + 9 cross-cutting), every finding adversarially
verified against the actual code and the `native-app/` parity reference before counting.
**Result:** 58 agents, 38 findings → **35 confirmed** (0 critical · 3 high · 8 medium · 24 low), 3 refuted.

> **Coverage gap (honest note):** the dedicated *Compose UI-correctness* cross-cutting reviewer
> (LaunchedEffect keys / `remember` vs `rememberSaveable` / `key()` in lazy lists / recomposition)
> failed to return structured output and is **not** reflected below. UI was still covered indirectly
> by every feature-vertical reviewer (each read its screen), but the focused Compose-correctness sweep
> did not land. Re-runnable on request.

---

## HIGH (3)

1. **Resume FAB jumps to the *newest* unread chapter, not the next-to-read.**
   `presentation/.../details/DetailsState.kt:306-307`
   `firstUnreadChapter = displayChapters.firstOrNull { !it.isRead }` ignores sort direction. Default sort
   is descending, so it returns the **newest** unread chapter; native always resumes at the **oldest**
   unread (true resume point). Fix: `(if (sortAscending) displayChapters else displayChapters.asReversed()).firstOrNull { !it.isRead }`.

2. **Paged reader auto-advances to the next chapter on first composition.**
   `ui/.../reader/ReaderScreen.kt:1528-1532 (HorizontalPager), 1611-1615 (VerticalPager)`
   Both pagers fire `onReachedEnd()` when `currentPage == pages.lastIndex` with no "user actually swiped
   here" guard. So (a) a **1-page chapter** (currentPage 0 == lastIndex 0) skips straight to the next
   chapter on open, and (b) **reopening a chapter you'd finished** (resume seeds the last page) auto-advances
   immediately — and also re-marks it read. This is the *same* defect class that commit `b5f8811a` fixed for
   the WEBTOON/vertical-list modes, but the two pager paths were left unguarded. The default reading mode is
   `VerticalPager`, so this is the common path, not an edge case. Fix: latch on "previous < lastIndex" (real
   settle) and skip when `pages.size <= 1`.

3. **Rework `:ui` strings untranslated in all 9 non-Arabic locales (~343 keys each).**
   `ui/src/commonMain/composeResources/values-{de,es,fr,in,it,ja,pt,ru,tr}/`
   The rework added hundreds of keys to default `values/` but never to the locale dirs (each has ~5 files /
   173 keys vs default's 48 / 586). compose-resources falls back to English (no crash), so a German user sees
   English on most rework screens (History/Downloads/Sources/Details/Settings/Statistics/Complaint/Reader).
   Native shipped full per-locale parity. The gap is in **`:ui`** (its `Res` is internal — `:composeApp`'s
   translations can't cover it; `:composeApp` itself is ~fully translated). Fix: backfill per-cluster
   `strings_np_*.xml`/`strings_pfix_*.xml` in each locale dir + add a key-parity CI lint.

---

## MEDIUM (8 → 7 distinct; one bug found by two reviewers)

4. **HomeViewModel pagination deadlock on refresh/tab-switch race.** *(found twice — home-search + concurrency)*
   `presentation/.../home/HomeViewModel.kt:134-233`
   `onEndReached` and `fetchHome` share `homeFetchJob`. A refresh/tab-switch cancels the in-flight pagination
   coroutine before its `onSuccess/onFailure` resets `isLoadingNextPage`, leaving it stuck `true` → permanent
   bottom spinner + dead infinite-scroll for the screen's life. Most reliable trigger: tab switch mid-load.
   Fix: separate pagination job, or reset `isLoadingNextPage=false` in the `fetchHome(reset=true)` state update (line 204).

5. **Multi-repo search shows "No results found" for the whole loading window.**
   `ui/.../search/SearchScreen.kt:514-530` + `data/.../SearchRepositoryImpl.kt:113-118`
   `toResult()` collapses per-repo `Loading → Success(emptyList())`, so a still-fetching source persistently
   renders the empty-text instead of native's per-section spinner; and `MultiRepoResults` shows `YamiEmptyState`
   before the first repo responds. Fix: surface `UiState.Loading` (don't collapse it) so RepoSection's spinner
   branch lights up; add a `multiLoading` flag / seed the map so the empty panel isn't shown mid-fan-out.

6. **Library ignores the global "Downloaded only" setting.**
   `presentation/.../library/LibraryViewModel.kt:670-717`
   The VM never observes `downloadedOnly`, so the Settings toggle has zero effect on the grid (native narrows to
   `downloadedCount > 0` regardless of filter chip). Fix: inject `ObserveSettingsUseCase`, project the bool into
   state, pre-filter to `hasDownloads` in `applyView`. (Defaults false → default users unaffected.)

7. **History & Updates open Details via URL-only route (empty-flash regression).**
   `composeApp/.../routes/HistoryReworkScreenRoute.kt:83-90`, `UpdatesReworkScreenRoute.kt:122-129`
   These use `Screen.MangaDetails(url, api)` instead of the full-identity `Screen.MangaDetailsRework(...)` that
   Library/Home use, so **in-library** rows flash empty title/cover/heart and force a network open — the exact
   regression Library already fixed. The entry objects already carry `mangaTitle/mangaImageUrl/language`. Fix:
   widen `onNavigateToDetails` to the full tuple and route to `MangaDetailsRework`.

8. **Arabic localization incomplete for rework screens (~161 keys).**
   `ui/src/commonMain/composeResources/values-ar/`
   Flagship RTL locale: 24 files / 411 keys vs default 48 / 586. ~161 referenced keys fall back to English.
   Backfill `values-ar` to key parity (highest priority given the Arabic-sources audience).

9. **Arabic (RTL) doesn't flip layout direction on iOS/Desktop.**
   `composeApp/.../App.kt:400-408`
   Only Android derives `LocalLayoutDirection` (via the `LocalConfiguration` override). iOS/Desktop leave the
   layout LTR when Arabic is selected. Fix: provide `LocalLayoutDirection = Rtl` for RTL codes at the App root,
   keyed on `appLanguage`.

10. **`runCatching` swallows `CancellationException` across 9 strangler repositories.**
    `data/.../FeedbackRepositoryImpl.kt:135-147` (+ ComplaintAction, AdminComplaintAction, ComplaintList,
    AdminComplaintList, DownloadsAction, Settings, WhatsNew, LibraryPrefs)
    stdlib `runCatching` captures cancellation as `Result.failure`, violating the rethrow-cancellation boundary
    contract → spurious `ShowError` snackbars on navigate-away. Fix: shared `runCatchingCancellable {}` helper
    (rethrow `ce` first) at the suspend-wrapping sites. The correct pattern already exists in
    `LibraryRepositoryImpl.runCatchingStorage`.

---

## LOW (24)

**Reader / Details parity**
- **Reader marks chapter read on merely viewing the last page** — `presentation/.../reader/ReaderViewModel.kt:474-476` — eager vs native's advance-only; decide+document, keep consistent with #2's fix.
- **"Mark this and below as read" off-by-one** — `presentation/.../details/DetailsViewModel.kt:784-797` — marks the tapped chapter inclusively; native excludes it. Fix `subList(index+1, size)`. (Earlier "opposite-set/direction" claim was refuted — display order is correct.)
- **Adult-content gate also applied on the library Details path** — `presentation/.../details/DetailsViewModel.kt:237-271` — native never gated the library screen; over-blocks (compliance-positive). Suppress when `isInLibrary`, or relabel KDoc from "native parity" to a deliberate divergence.

**Home / Search**
- **`fetchMore` never updates its accumulated snapshot** — `data/.../HomeFeedRepositoryImpl.kt:124-131` — latent data-contract incoherence (VM dedup compensates; no user-visible loss). Mirror `fetchHome`'s write-back.
- **Saving from Home doesn't fetch chapters** — `presentation/.../home/HomeViewModel.kt:282-292` — library row shows 0 chapters until Details opens (documented tradeoff).
- **Home save-toggle spins on removal too** — `presentation/.../home/HomeViewModel.kt:282-292` — native only spun on add; gate `savingKeys` on `key !in savedKeys`.

**Library**
- **Sort-options chips render in wrong order** — `domain/.../library/LibrarySort.kt:40-74` — positions 2–5 differ from native `SortType`; reorder to ALPHABETIC, TOTAL_CHAPTERS, LAST_READ, UNREAD_COUNT, DATE_ADDED, RANDOM (by-name persistence makes it safe).
- **RANDOM sort seed not persisted** — `LibraryViewModel.kt:148-157, 706-710` — grid reshuffles on every re-emission/restart (native persists `KEY_SEED`); add `observeRandomSeed/setRandomSeed`.
- **PTR on empty library still enqueues refresh** — `domain/.../library/RefreshLibraryUseCase.kt:47-51` — missing native's empty-library guard + "No manga yet" message (no spinner — worker unported); short-circuit in the VM's `OnRefresh`.
- **LAST_READ sort uses chapter read-date, not manga last-open** — `data/.../LibraryMappers.kt:73`, `LibraryViewModel.kt:703-705` — diverges from native's `lastOpenTimestamp`; for parity expose `lastOpenedAt` and bump it in `DetailsViewModel.OnEnter`.

**History / Updates / Downloads**
- **Updates "Mark all read" disabled when all read** — `ui/.../updates/UpdatesScreen.kt:316-319` — native keeps both bulk actions enabled on non-emptiness (sibling delete-all already matches native); use `enabled = visibleItems.isNotEmpty()` or document.
- **iOS/Desktop download completion doesn't write `localImagePaths/isDownloaded` to the notification table** — `shared/.../clean/CoroutineDownloadRepositoryImpl.kt:338-340` — Updates-screen download button reverts to enabled once the queue row is evicted; inject `NotificationDao`, mirror Android's `addLocalImagePathByChapterId`. (Reader still reads local files fine — it uses `saved_chapters`.)

**Settings / Sources**
- **Clear-cache skips Android external cache** — `shared/.../settings/domain/SettingsRepository.kt:103-105` — clears only the okio `cacheDir`. **Near-zero real impact** (this app's cache lives in internal `cacheDir`; displayed size is already measured internal-only) — fix for completeness only.
- **Onboarding language headers use hardcoded English names** — `ui/.../sources/SourcesScreen.kt:929-944` — loses native's device-locale display name; ship per-locale name resources via `stringResource`.
- **Per-source rows drop the source icon** — `ui/.../sources/SourcesScreen.kt:858-916` — native shows `repo.ICON`; domain `Source` has no icon field (documented deferral); drawables already exist — populate `RepoIconResolver` + expose an icon slot.

**i18n / locale (platform)**
- **iOS mid-session language change needs relaunch** — `composeApp/iosMain/.../LocalAppLocale.ios.kt:15-34` — compose-resources reads `NSLocale.preferredLanguages`, not the provided local/NSUserDefaults write; the switch *persists* but appears to do nothing until relaunch. Surface a "restart to apply" cue, or drive a custom `ComposeEnvironment`. iOS-only.
- **iOS stale system-default capture** — `LocalAppLocale.ios.kt:17-33` — captures the default from the same `AppleLanguages` key it later overwrites; latent (no reachable "system default" picker today). Capture the device locale into a distinct never-overwritten key.
- **iOS `LocalAppLocale.current` returns `""`** — `LocalAppLocale.ios.kt:19-22` — never seeded with the system default unlike Android/Desktop; dead API today (nothing reads `.current`). Seed from `NSLocale.preferredLanguages` or drop it from the `expect`.
- **Statistics counts use hardcoded comma grouping** — `ui/.../statistics/StatisticsScreen.kt:464-475` (+ overview 322/335) — not locale-aware vs native's `%,d` (affects ar/de/fr/ru at 4+ digits); use a `NumberFormat` expect/actual or locale-derived separator.

**Architecture / navigation / misc**
- **ReaderViewModel injects a repository, not a use case** — `presentation/.../reader/ReaderViewModel.kt:239` — DIP-boundary nit (DI module documents the deferred fix); add `ObservePageProgressUseCase`. *Not* the only such VM — `DetailsViewModel` also injects `ChapterIdResolver`.
- **Home bottom-tab reselect is dead** — `composeApp/.../routes/HomeReworkScreenRoute.kt` + `BottomNavigationBar.kt:134-136` — `homeReselectHandler` is read but never assigned, so tapping Home-while-on-Home doesn't scroll-to-top (native does); register a handler in the Home host.
- **Dead `NavigationLock` with a broken no-op guard** — `composeApp/.../navigation/NavigationLock.kt:7-39` — unused and its `finally`-reset defeats the lock; delete it (the live guard is `isReadyForNavigation()`).
- **User-side complaint edit/reply drops device-metadata keys** — `data/.../ComplaintActionRepositoryImpl.kt:88-159` — `ComplaintSummary` has no `model` (the bag actually drops both `model` AND `osRelease`); **UI-invisible** (admin path is correct via re-fetch+copy). Fix by re-fetching the full legacy complaint like the admin path.
- **Leftover unconditional debug log on Home open-in-WebView** — `ui/.../home/HomeScreen.kt:217` — uncommitted `Logger.withTag("onOpenWebView").i {...}` fires on every tap in all builds; remove the line + now-unused `Logger` import. *(This is in your WIP.)*

---

## Themes / systemic patterns

1. **Translation coverage has no enforcement.** Four i18n findings (#3, #8, onboarding names, stats formatting) all come from rework strings never mirrored into locale dirs. A **key-parity CI lint** (fail when any `values-<loc>/` lacks a key referenced in source) kills this whole class at once.
2. **Shared mutable `Job` fields in ViewModels.** The Home pagination deadlock (#4) is one job reused for two purposes. Fix is one-job-per-concern; worth auditing other VMs for the same reuse.
3. **The strangler fold lost native's multi-screen edge behavior.** Folding native's separate `MangaDetailsScreen`/`LibraryMangaScreen` and per-mode reader screens into single screens dropped guards native got "for free": adult gate over-applies (#15), History/Updates lost the library-aware route (#7), the resume FAB ignores sort direction (#1), the pager lost the overlay-item buffer (#2).
4. **Documented-but-unfinished migration shortcuts shipped** (ReaderVM repo injection, RANDOM seed, `:data`→`:shared` bridge). A tracked inventory of these "fill-in-later" seams prevents silent permanence.
5. **`CancellationException` handling at the `:data` boundary is inconsistent** — 5 repos rethrow, 9 don't (#10). One shared helper + a lint banning bare `runCatching` in suspend functions closes it.

## What looks healthy

- **`:composeApp` translation coverage** — ~fully translated (3 missing keys), unlike `:ui`.
- **DI/Koin graph** — no confirmed defects.
- **Android language/RTL path** — correctly mirrors native via the `LocalConfiguration` override.
- **Local-page resolution for downloads** — reader reads local pages from `saved_chapters` on all platforms; the iOS/Desktop notification-table divergence doesn't break opening downloaded chapters.
- **Admin complaint write path** — preserves the full metadata map (only the user-side path regressed).
- **The committed double-tap nav guard** (`isReadyForNavigation()`) is sound; nav defects are dead code + a dropped reselect handler, not the active guard.
- **3 candidate findings were adversarially refuted** and excluded — the verification pass was discriminating, not rubber-stamping.

> **Not covered:** the focused Compose UI-correctness sweep (see coverage note at top) — re-runnable on request.
