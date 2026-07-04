# Manual Test Plan — behaviour changes from the exhaustive review

> **HISTORICAL — do not execute as written (frozen; staleness confirmed 2026-07-04 audit).**
> This plan targets an old diff (base `eef1e547`). Its file citations use the retired
> `me/manga/yamiapk` package root and pre-date the `:shared`-retirement module moves, and some
> described behaviours were later deliberately reversed (e.g. the custom crash-screen
> `UncaughtExceptionHandler` in the first P0 was REMOVED — Firebase's default handler records
> fatals now; see CLAUDE.md "Firebase / Crashlytics"). Treat it as a checklist *template* only;
> verify each item against current code before running it.

91 runtime tests for behaviour changes that compile + unit tests can't prove. Grounded in the actual committed diff (base `eef1e547` → `main`). **P0**=release-blocking, **P1**=important, **P2**=nice-to-have. (Pure cleanups omitted; macOS embedded WebView is known-skipped.)

**Counts:** P0 27 · P1 49 · P2 15

## P0 (27)

### Crash screen (uncaught-exception handler → CrashActivity)  `P0` · Android
_The crash handler now launches CrashActivity SYNCHRONOUSLY from the crashing thread and then kills the process (Process.killProcess + exitProcess(10)), instead of posting to a dead main looper and delegating to the previous handler._  
<sub>app/src/main/java/me/manga/yamiapk/MyApp.kt</sub>

**Steps:**
1. Build/install a debug APK that contains a deliberate crash trigger reachable from the UI (or trigger any reproducible main-thread crash), OR temporarily add a throwing tap target.
2. Cold-launch the app and reach Home.
3. Trigger the crash on the MAIN thread (e.g. tap the throwing element).
4. Observe what appears on screen immediately after the crash.
5. Repeat the trigger on a BACKGROUND thread path (e.g. a crash inside a coroutine on Dispatchers.IO) if one is reachable, to confirm both thread origins reach the screen.

**Expected:** CrashActivity launches in a fresh task and shows the crash screen (stack trace UI) instead of the bare system 'App keeps stopping' dialog or an instant silent disappearance. After the crash screen is handled, the process is gone (app fully terminated, not lingering).  
**Regression looks like:** App vanishes with only the OS 'YamiManga keeps stopping' dialog and the in-app CrashActivity screen never appears (the old looper-post regression); or the process stays alive/zombied after the crash; or a second nested crash dialog appears.

### UMP consent → MobileAds init ordering / EEA gate  `P0` · Android
_MobileAds.initialize() was REMOVED from MyApp.onCreate (no longer unconditional at process start). MobileAds now initializes only inside MainActivity.startConsentFlow() after UMP canRequestAds() is true. Consent/review flows were moved from onCreate to the first onResume (once-guarded) so ActivityHolder is populated._  
<sub>app/src/main/java/me/manga/yamiapk/MainActivity.kt, app/src/main/java/me/manga/yamiapk/MyApp.kt</sub>

**Steps:**
1. On a Play-distributed build with a UMP/consent message configured, force an EEA geography (use a UMP debug geography = EEA test device, or a real EEA region/VPN per your AdMob test-device config).
2. Clear app data so consent is undecided, then cold-launch the app.
3. Wait for the UI to reach the first screen (post-setContent) and observe whether the Google UMP consent form is displayed.
4. Decline / deny ad-personalization in the form.
5. Continue using the app and confirm no ad SDK activity occurs.

**Expected:** The UMP consent form actually appears on launch (it is no longer raced to a null ActivityHolder). If the user declines so canRequestAds()==false, MobileAds is NEVER initialized for that session.  
**Regression looks like:** The consent form silently never shows (ActivityHolder race regression), or MobileAds initializes/serves ads even though the user declined consent (the unconditional-init regression).

### Library refresh on Android 12+ (foreground-service start)  `P0` · Android
_LibraryRefreshWorker.doWork now wraps setForeground(getForegroundInfo()) in a try/catch for IllegalStateException (ForegroundServiceStartNotAllowedException) and continues as ordinary background work instead of failing the whole refresh._  
<sub>app/src/main/java/me/manga/yamiapk/work/LibraryRefreshWorker.kt</sub>

**Steps:**
1. Use a device/emulator running Android 12 (API 31) or newer.
2. Add several manga to the Library, then fully background or close the app.
3. Let the periodic/CONNECTED-constrained library refresh fire while the app is backgrounded (or simulate by toggling connectivity to release a CONNECTED-deferred refresh, or run the worker via WorkManager test trigger while backgrounded).
4. After it runs, reopen the app and check the Library/Updates for newly found chapters.

**Expected:** The refresh runs to completion in the background; new chapters/notifications appear. No crash. Logcat shows 'Foreground promotion rejected; continuing refresh in background' when promotion was disallowed.  
**Regression looks like:** The refresh is marked failed / aborts entirely on Android 12+ when started from background (ForegroundServiceStartNotAllowedException crashes/kills the worker), and no new chapters are found.

### Details — adult-content hard gate on cache-first / in-library open  `P0` · Android+iOS
_Opening an in-library adult manga from cache (Library/History/Updates, where nav-arg genres are empty) now re-classifies from the SAVED genres and arms the AdultWarning hard-block; previously it rendered fully ungated because runFetch (the only old re-classify site) is skipped on a cache-first open._  
<sub>presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/details/DetailsViewModel.kt (applySavedOverlay ~L400-420; onEnter ~L294-340)</sub>

**Steps:**
1. Pick a source with an adult-content blacklist (e.g. 3asq / a source whose genres include a blacklisted tag) and open an adult-genre title from Home/Search.
2. Add it to the library (heart) and let the chapter list save.
3. Leave Details and go to the Library tab (or open the same title from History / Updates so genres are passed empty).
4. Tap the library row to re-open Details — this is a cache-first open (no network fetch).
5. Observe the screen immediately on open.

**Expected:** The adult-content warning / hard-block gate (AdultWarning step) is shown over the Details content before the cover, synopsis and chapters are visible — same gate you'd see opening the title fresh from Search.  
**Regression looks like:** Details renders fully (cover + chapters + genres) with NO adult warning when opened from Library/History/Updates, even though the same title gates correctly when opened from Search.

### Home feed — source enable/disable resync (clamp + reset + refetch)  `P0` · all
_Toggling a source on/off in the Sources screen now re-syncs Home: the active tab index is clamped into range and, when the active source changed under that index, the feed/featured are cleared and refetched (previously the highlighted tab, the shown feed and the siteState gate could all point at the wrong / a removed source)._  
<sub>presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/home/HomeViewModel.kt (resyncFeedForActiveTab; feedApi tracking)</sub>

**Steps:**
1. On Home, scroll the source tab strip and select a source that is NOT the first tab (e.g. the 3rd enabled source); let its feed load.
2. Go to Settings > Sources (or the sources sheet) and DISABLE that currently-active source.
3. Return to Home and observe the tab strip, the highlighted tab, and the feed grid.
4. Now re-enable a source / enable an additional source and return to Home again.
5. Separately: disable ALL sources, return to Home; then re-enable one and return.

**Expected:** After disabling the active source, the highlighted tab clamps to a valid in-range tab and the feed/featured reload to match the now-highlighted source (no stale items from the removed source). With all sources disabled the feed is empty (no leftover cards). Re-enabling a source repopulates Home.  
**Regression looks like:** Tab strip highlight points at a source whose items are not shown (or an out-of-range/blank highlight), the old disabled source's cards linger in the grid, the featured carousel shows a different source than the feed, or Home shows a blank/stuck feed after a toggle that should have refetched.

### Cloudflare 403 → WebView auto-retry latch (Details)  `P0` · Android
_The 403→WebView→auto-retry latch now survives the Details destination leaving composition while the WebView is on top (changed plain remember → rememberSaveable via the shared rememberCloudflareChallengeSolver). Previously the latch state was destroyed, so returning from the solver never re-fired the fetch._  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/CloudflareChallengeSolver.kt; MangaDetailsReworkScreenRoute.kt</sub>

**Steps:**
1. Open a manga on a Cloudflare-protected source whose cookies are stale/absent so Details fetch returns 403 (clear app data first, or use a source currently behind a Cloudflare interstitial).
2. Confirm Details auto-navigates into the in-app WebView showing the Cloudflare challenge page.
3. Wait for the challenge to clear (the page resolves to the real site).
4. Press system Back (or the WebView's close) to return to Details.
5. Observe Details without manually tapping retry.

**Expected:** On returning from the WebView, Details automatically re-fetches once and renders the manga (cover + chapters) with the freshly minted cookies — no manual retry tap needed.  
**Regression looks like:** After the challenge clears and you return, Details still shows the 403/error pane and does nothing until you manually tap Retry (the auto-retry never fires).

### Cloudflare 403 → WebView auto-retry latch (Reader)  `P0` · Android
_The Reader now uses the SAME shared solver as Details (rememberSaveable latch). Previously the Reader had its own remember-based latch that was destroyed while the WebView was on top, so the chapter never re-fetched after solving the challenge._  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/ChapterImagesReworkScreenRoute.kt; CloudflareChallengeSolver.kt</sub>

**Steps:**
1. On a Cloudflare-protected source with stale/absent cookies, open a chapter so the page-image fetch fails with a 403/challenge.
2. Confirm the Reader auto-navigates into the in-app WebView showing the challenge.
3. Let the challenge clear, then press Back to return to the Reader.
4. Observe the Reader without tapping retry.

**Expected:** On return, the Reader automatically re-fetches the chapter pages once and renders the images.  
**Regression looks like:** Reader still shows the error/snackbar after returning from the solved challenge and never reloads the pages without a manual retry.

### Cloudflare WebView host — WebView no longer destroyed/reused on Android  `P0` · Android
_The embedded WebView is no longer destroyed-then-reused on a url/param change (DisposableEffect destroy → AndroidView onRelease; params read via rememberUpdatedState). The Cloudflare solver, header capture (cookie + UA) and source-login WebView all depend on a live WebView._  
<sub>composeApp/src/androidMain/kotlin/me/manga/yamiapk/core/webview/WebViewHost.android.kt</sub>

**Steps:**
1. Trigger the in-app WebView (Cloudflare solve, or Home/Search 'Open in WebView', or a source that needs WebView login).
2. Let a page load, then navigate within the WebView so the loaded URL changes (follow a link / redirect).
3. Observe the page renders and continues working after the URL change.
4. Solve a Cloudflare challenge and back out; confirm cookies were captured (the subsequent source fetch succeeds).

**Expected:** The WebView keeps rendering across URL changes (no blank/white frozen page), and after solving the challenge the captured cookie + user-agent are applied so the source fetch succeeds.  
**Regression looks like:** WebView goes blank/white or unresponsive after a URL change or redirect; Cloudflare cookies are not captured so the source fetch still fails after solving.

### Connectivity gate on Details downloads (Desktop)  `P0` · Desktop
_Desktop connectivity now reads OS NetworkInterface up-state instead of pinging Google /generate_204_  
<sub>platform/src/desktopMain/kotlin/me/manga/yamiapk/platform/connectivity/DesktopConnectivityObserver.kt; gate in presentation/.../details/DetailsViewModel.kt:711/835/930</sub>

**Steps:**
1. Run the Desktop app on a machine that is genuinely online but where www.google.com is NOT reachable (e.g. a network/firewall/region that blocks Google, or add a hosts-file entry 127.0.0.1 www.google.com to simulate a blocked probe host)
2. Open any working source, tap a manga to open Details
3. Tap the download icon on a single chapter (and/or 'Download all')

**Expected:** The chapter actually enqueues and downloads; no 'No connectivity' error appears, because the observer now checks for any non-loopback interface that is up with a bound address rather than reaching Google  
**Regression looks like:** A 'No connectivity'/network-error snackbar fires and nothing enqueues even though the network works — i.e. downloads are falsely blocked offline-but-reachable (the old Google-probe regression)

### Connectivity gate on Details downloads (iOS)  `P0` · iOS
_iOS connectivity now uses NWPathMonitor (OS reachability) instead of a Google /generate_204 HEAD probe_  
<sub>platform/src/iosMain/kotlin/me/manga/yamiapk/platform/connectivity/IosConnectivityObserver.kt</sub>

**Steps:**
1. On the iOS simulator/device with normal working WiFi, open a working source and open a manga's Details
2. Tap download on a single chapter, then 'Download all'
3. Then enable Airplane Mode / turn the network off and tap download again

**Expected:** While online the chapters enqueue with no 'No connectivity' error (does not depend on Google being reachable); while genuinely offline the download paths give immediate 'No connectivity' feedback and do not enqueue  
**Regression looks like:** Online downloads are blocked with a network error (false offline), OR offline taps silently enqueue instead of showing the no-connectivity feedback

### Bottom-nav back stack after onboarding  `P0` · all
_Bottom-nav popUpTo anchor changed from graph findStartDestination() to Screen.Library so first-session tab switches don't stack unboundedly_  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/common/componants/BottomNavigationBar.kt:138</sub>

**Steps:**
1. Fresh install (or clear app data) so onboarding runs: complete Welcome -> Theme -> Sources -> finish into Library
2. Without relaunching, tap bottom-nav tabs repeatedly in a cycle (Library -> Home -> History -> Updates -> back to Library), ~10-15 switches
3. Now press system Back (Android/iOS) or use the back affordance repeatedly

**Expected:** Each tab keeps a single entry (saveState/restoreState engages); a single Back from a tab root exits the app rather than walking back through every previously-visited tab  
**Regression looks like:** Back walks through a long chain of previously-visited tabs one by one (unbounded stack growth) during the first post-onboarding session before the app finally exits

### What's New 'Get Started' dismiss  `P0` · all
_Get-Started/header-X now submit OnMarkSeen and dismiss via safePopBackStack() (was raw popBackStack); a load failure no longer strands an infinite spinner_  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/WhatsNewReworkScreenRoute.kt:60; presentation/.../whatsnew/WhatsNewViewModel.kt:161</sub>

**Steps:**
1. Navigate to the What's New screen (from About -> What's New, the only inbound edge)
2. Page through to the last page and tap 'Get Started' (or tap the header X on any page)
3. Re-open What's New again to confirm the seen-state behavior

**Expected:** The screen dismisses cleanly back to the previous screen (About) every time, including rapid/double taps; the app is never left on a blank/black screen; mark-seen is persisted  
**Regression looks like:** Tapping Get Started blanks the app (pops past the back stack to an empty NavHost), double-tap pops twice, or the screen is stuck on a perpetual loading spinner if features fail to load

### Details — delete a downloaded chapter physically removes files + clears badge  `P0` · Android+iOS+Desktop
_Details delete-download paths now route through the new deleteDownloadedChapter use case: clears saved_chapters.isDownloaded, deletes on-disk files via FileService, AND drops the chapter_downloads row (was a row-only deleteDownload that orphaned files and left the badge lit)._  
<sub>data/src/commonMain/.../DownloadsActionRepositoryImpl.kt (deleteDownloadedChapter); domain/.../usecase/downloads/DeleteDownloadedChapterUseCase.kt; presentation/.../details/DetailsViewModel.kt (onDeleteSelectedDownloads/onDeleteAllDownloads)</sub>

**Steps:**
1. Add a manga to the Library and open Details.
2. Download a single chapter; wait until it shows the 'Downloaded' badge/checkmark.
3. Note free storage (Android: device Settings > Storage app size, or just remember roughly; Desktop: ~/.yami-manga; iOS: app size) — optionally browse to the app files dir for that manga/chapter.
4. Long-press the downloaded chapter to enter multi-select, then tap the trash/delete-downloaded action (or use the top-bar 'delete all downloaded').
5. Observe the chapter row's downloaded badge after deletion.

**Expected:** The 'Downloaded' badge clears immediately, the on-disk chapter files (the chapter_N.cbz or loose page files for that manga/chapter) are gone, and the per-manga download size header drops. Opening that chapter now re-fetches from the network (online).  
**Regression looks like:** Badge stays lit after delete; files remain on disk (storage doesn't drop); or the chapter still opens offline from local files after a 'delete'. Pre-fix behaviour = files orphaned forever.

### Details — 'Download all' / multi-select download skips already-downloaded chapters  `P0` · Android+iOS+Desktop
_EnqueueAllChaptersDownloadUseCase now pre-filters filter{!isDownloaded}, and DetailsViewModel.onDownloadSelected filters out already-downloaded urls. Re-enqueuing a SUCCESS chapter no longer REPLACEs its row with a fresh QUEUED row and re-downloads every page._  
<sub>domain/.../usecase/downloads/EnqueueAllChaptersDownloadUseCase.kt; presentation/.../details/DetailsViewModel.kt (onDownloadSelected)</sub>

**Steps:**
1. Library manga with many chapters; download 3-4 specific chapters fully (badges lit).
2. Tap the top-bar 'Download all' action.
3. Open the Downloads screen / watch the Active tab and the already-downloaded chapters' badges.
4. Repeat with multi-select: long-press to select a RANGE that includes the already-downloaded chapters, then tap the download action.

**Expected:** Only the not-yet-downloaded chapters are enqueued and downloaded. The already-completed chapters keep their 'Downloaded' badge and are NOT demoted to Active/Queued or re-fetched.  
**Regression looks like:** Already-downloaded chapters flip back to Queued/Active and re-download all pages (badge briefly clears then re-fills); the Active count includes chapters that were already complete.

### Downloads — Android worker leaves no chapter stuck RUNNING (terminal-state guarantee)  `P0` · Android
_DownloadWorkerV2 now (a) marks a row FAILED if its source flow completes with no Complete/Error (e.g. a parse failure yielding zero images), (b) on a real exception marks the in-flight row FAILED and drains the rest of the queue, and (c) on cancellation cleans partial files and rethrows (no longer converting cancel to a generic failure or stranding the row)._  
<sub>shared/src/androidMain/.../download/ui/test2/DownloadWorkerV2.kt (doWork sawTerminalState guard + catch branches)</sub>

**Steps:**
1. Queue several chapters for download at once (Download all on a multi-chapter manga).
2. Include at least one chapter from a source likely to fail to parse pages, or interrupt mid-download (toggle network off briefly).
3. Let the queue run to completion and watch the Downloads screen Active/Failed/Completed tabs.
4. Force-stop and relaunch the app, then re-open Downloads.

**Expected:** Every queued chapter ends in a terminal state (SUCCESS or FAILED) — none stay 'Running' indefinitely. A failing chapter is marked Failed and the rest of the queue still drains. After relaunch, the startup reconcile does not endlessly re-queue a chapter that previously couldn't produce images.  
**Regression looks like:** A chapter spinner stays 'Running' forever and blocks the queue; or after relaunch the same un-parseable chapter is re-queued and runs again on every launch (download loop); or a user-cancelled download is reported as a crash/failure.

### Downloads — iOS/Desktop worker survives a DB hiccup and cancel cannot be silently undone  `P0` · iOS+Desktop
_CoroutineDownloadRepositoryImpl: the worker drain loop now catches a throwable from the DAO pull/bookkeeping, logs, and parks (was: one throw killed the lone worker for the process lifetime, silently stopping ALL downloads); processJob now does a conditional QUEUED->RUNNING claim so a cancel that lands between dequeue and claim is honoured instead of being overwritten and downloaded to completion._  
<sub>shared/src/nonAndroidMain/.../download/domain/clean/CoroutineDownloadRepositoryImpl.kt (workerLoop drain try/catch; claimQueuedAsRunning); shared ChapterDownloadDao.claimQueuedAsRunning</sub>

**Steps:**
1. On Desktop or iOS, queue a batch of chapters for download.
2. While the batch is downloading, queue more chapters from a different manga and confirm the worker keeps draining.
3. Cancel a chapter that is QUEUED (not yet running) just as the worker is about to pick it up (rapid: enqueue, then immediately cancel the running/queued chapter or hit 'Stop'/cancel-all).
4. Let the rest of the queue finish.

**Expected:** Downloads keep progressing through the whole queue; a cancelled QUEUED chapter ends Failed/cancelled and is NOT downloaded to completion; the worker continues processing subsequent chapters after any transient error.  
**Regression looks like:** After one chapter, all downloads silently stop and never resume (worker died); or a chapter you cancelled completes anyway and shows 'Downloaded'.

### Downloads — bulk download does not grow memory unbounded (bounded/disk HTTP image cache)  `P0` · Android+iOS+Desktop
_The singleton Ktor download client's HttpCache was changed from the default unbounded in-memory CacheStorage.Unlimited() to disk-backed FileStorage on Android (app cacheDir) and Desktop (~/.yami-manga/cache), and a bounded LRU BoundedCacheStorage (64 entries) on iOS — so cacheable multi-MB chapter-image bodies no longer accumulate in heap for the process lifetime._  
<sub>shared/src/androidMain/.../HttpClientFactory.android.kt; shared/src/desktopMain/.../HttpClientFactory.desktop.kt; shared/src/iosMain/.../HttpClientFactory.ios.kt; shared/src/iosMain/.../BoundedCacheStorage.ios.kt</sub>

**Steps:**
1. Pick a long manga and 'Download all' (or queue 30+ chapters).
2. While the bulk download runs, watch process memory: Android = Android Studio Profiler / adb shell dumpsys meminfo; iOS = Xcode memory gauge / Instruments; Desktop = JVM heap (VisualVM/jconsole).
3. Let the bulk download run for several minutes and many chapters.
4. Reading the just-downloaded chapters offline should still work.

**Expected:** Memory footprint stays roughly bounded over the long download — it doesn't climb monotonically with each chapter image. On iOS the app is not jetsam-killed mid-bulk-download. Downloaded chapters still read correctly offline.  
**Regression looks like:** Heap/RSS grows steadily with the number of downloaded images and never releases; iOS app is terminated under memory pressure during a long download session.

### Downloads — Android streaming download fails the chapter on missing pages instead of marking it Complete with gaps  `P0` · Android
_ChapterDownloadService streaming path now treats paths.size < imageUrls.size (any page that failed to download) as a failure: it deletes the partial pages and throws, instead of marking the chapter Complete with silently missing pages. The OOM loose-files fallback was also made reachable (catch widened from Exception to Throwable)._  
<sub>shared/src/androidMain/.../download/domain/ChapterDownloadService.kt</sub>

**Steps:**
1. Download a chapter where one or more page requests will fail (e.g. flaky network: toggle connectivity briefly during a download, or a source with an occasionally-404 page).
2. Observe the chapter's final state in Downloads.
3. If it shows Complete, open it offline and page through every page to verify no blank/missing pages.

**Expected:** A chapter that couldn't download all its pages ends Failed (and its partial files are cleaned), so the user can retry. A chapter marked Downloaded/Complete contains all its pages with no silent gaps.  
**Regression looks like:** A chapter shows 'Downloaded'/Complete but reading it offline reveals missing/blank pages; or partial page files are left behind for a chapter that failed.

### Paged mode: final page no longer yanked away (dummy Next-Chapter page #14)  `P0` · Android+iOS
_Paged pagers now append a dummy full-screen "Next Chapter" page after the last image; auto-advance fires only when you swipe ONTO that extra page, not on arrival at the last image._  

**Steps:**
1. Open any source, open a manga Details, tap a chapter that has a NEXT chapter available (not the most recent one) to open the Reader.
2. Open the reader settings / reading-mode and pick a PAGED mode (Horizontal LTR or Horizontal RTL or single-page Vertical paged), not webtoon/continuous.
3. Swipe forward page by page until you reach the LAST image of the chapter.
4. Dwell on the last image for a few seconds, then pinch-zoom and pan it.
5. Now swipe ONE more time past the last image.

**Expected:** The last real image stays on screen and is fully readable/zoomable as long as you want. A separate "Next Chapter" boundary card appears only after one extra swipe; the chapter then advances to the next chapter. The next chapter also begins loading promptly when you reach the end.  
**Regression looks like:** The chapter jumps to the next chapter the instant you land on the last image (last page never readable), OR a 1-page chapter auto-skips on open, OR reopening a finished chapter (resumed at last page) immediately skips to the next chapter.

### Horizontal reading-mode direction under Arabic (RTL) UI  `P0` · Android+iOS
_The horizontal pager is now pinned to LayoutDirection.Ltr so RIGHT_TO_LEFT mode always pages right-to-left and LEFT_TO_RIGHT always pages left-to-right, regardless of the app's Arabic RTL locale (previously double-mirrored)._  

**Steps:**
1. Set the app language to Arabic (Settings > Language > العربية) and relaunch if prompted so the whole UI is RTL.
2. Open a manga chapter in the Reader, open reading-mode settings and select Horizontal RIGHT_TO_LEFT (manga style).
3. Swipe to advance pages and note which edge you swipe from to go forward.
4. Switch reading-mode to Horizontal LEFT_TO_RIGHT and advance again.

**Expected:** RIGHT_TO_LEFT advances pages right-to-left (swipe left-to-right region moves you forward, manga-style); LEFT_TO_RIGHT advances pages left-to-right. Each mode reads in the direction its label says. The bottom scrubber and chrome stay in normal Arabic RTL layout.  
**Regression looks like:** Under Arabic UI the two horizontal modes read the OPPOSITE of their labels (RTL pages left-to-right and vice versa), i.e. picking RTL behaves like LTR.

### Duplicate page URL in a chapter no longer crashes the reader  `P0` · Android+iOS+Desktop
_Pager and vertical-list keys changed from the bare page URL to an index-composite key ("$index:$url") so a chapter that legitimately repeats an image URL (credit/recruitment/banner pages, or appended chapters in a continuous feed) no longer throws a duplicate-key crash._  

**Steps:**
1. Pick a source/chapter known to repeat an image (e.g. a scanlation chapter ending with a duplicated credit/recruitment image, or a chapter that reuses a banner URL). If none is known, in webtoon/continuous mode scroll across a chapter boundary so two chapters are appended together.
2. Open that chapter in the Reader and scroll/swipe through ALL pages including the repeated ones.
3. Repeat in each reading mode: webtoon, continuous-vertical, horizontal paged, vertical paged.

**Expected:** All pages render (including duplicates) and the reader scrolls/swipes through the whole chapter without crashing.  
**Regression looks like:** The reader crashes (IllegalArgumentException: key was already used / duplicate key) when the repeated image comes into composition, or the app drops back to Details/Home.

### Share current reader page on iPad (popover anchor — must not crash)  `P0` · iOS
_iOS share now presents UIActivityViewController on the main thread, resolves the root VC via the foreground UIWindowScene, and anchors the iPad popover to the centre of the root view (UIKit raises an uncatchable exception with no anchor)._  

**Steps:**
1. Run the app on an iPad (or iPad simulator), not iPhone.
2. Open a manga chapter in the Reader, tap once to show the chrome/controls.
3. Tap the Share action in the bottom action bar.

**Expected:** The iOS share sheet appears as a popover anchored at the centre of the screen; you can pick a share target or dismiss it. No crash.  
**Regression looks like:** The app crashes immediately on tapping Share (NSException about a popover with no source view/bar button item set), or the share sheet never appears and nothing happens.

### iOS AVIF page rendering at correct size (ImageIO thumbnail rewrite)  `P0` · iOS
_iOS AVIF pages are now decoded via ImageIO CGImageSourceCreateThumbnailAtIndex at the requested target size (bounded by maxBitmapSize) and installed directly into a Skia bitmap, replacing the full-res UIImage->PNG->Skia round-trip; the ftyp sniffer now also accepts mif1-major-brand files listing avif as a compatible brand._  

**Steps:**
1. On an iOS device/simulator, open a source that serves AVIF page images (verify with a known AVIF-serving source).
2. Open a chapter and read through several pages in webtoon/continuous mode (tall strips) and in paged mode.
3. Check pages render sharp and at correct dimensions (no broken-image placeholder, no distortion/upscale blur, not absurdly large).
4. Download the chapter, then open it offline (CBZ read-back) and view the same AVIF pages.

**Expected:** AVIF pages decode and display at the right size and quality, both online and from the downloaded CBZ. No broken-page error slot. Memory stays reasonable while scrolling several tall AVIF strips quickly (no spike/OOM crash).  
**Regression looks like:** AVIF pages show the broken-page / Open-in-WebView error slot (decode returned null), or render at wrong size/blurry, or the app OOM-crashes while preloading multiple tall AVIF strips. Files whose major brand is mif1 (with avif compatible) showing as broken is a regression.

### Continuous/webtoon scroll across a chapter boundary (no snap, active-chapter tracking)  `P0` · Android+iOS
_Vertical-list scrubber-sync now compares in PAGE space not feed space, so scrolling UP onto a boundary row no longer triggers a spurious scrollToItem that snaps a full image height; crossing a boundary updates the active chapter (top bar title/subtitle, bookmark star, history, resume position)._  

**Steps:**
1. Open a chapter that has a next chapter, switch to WEBTOON or CONTINUOUS_VERTICAL mode.
2. Scroll down to the end of the chapter; let the next chapter append and continue scrolling into it.
3. Now scroll back UP across the boundary card slowly and let the fling settle right at the boundary.
4. Scroll down again past the boundary so the next chapter's first images are on screen; show the chrome and read the top-bar title/subtitle.
5. Tap the bookmark star, then back out and reopen the reader to check resume position.

**Expected:** Scrolling up onto/through the boundary is smooth — no sudden full-image-height jump/snap that throws you off. Once the next chapter's pages fill the screen the top-bar chapter number updates to the chapter in view, the bookmark star reflects the in-view chapter, and reopening resumes on the in-view chapter/page.  
**Regression looks like:** Scrolling up near the boundary snaps a whole image height and yanks you off position; OR after crossing the boundary the top bar/bookmark/resume still show the original anchor chapter, not the chapter on screen.

### 403 / Cloudflare auto-recovery survives the WebView round-trip (rememberSaveable)  `P0` · Android+iOS
_The reader route now uses the shared rememberCloudflareChallengeSolver whose pending-retry latch is rememberSaveable (was plain remember, which was destroyed while the WebView destination was on top) so the auto OnRetry actually fires on pop-back; the continuous-append path also now gets the same auto 403->WebView recovery._  

**Steps:**
1. Open a chapter on a Cloudflare/anti-bot-walled source whose pages currently 403 (clear cookies first if needed to force the interstitial).
2. When the reader auto-navigates to the embedded WebView, complete the Cloudflare challenge so the page loads.
3. Press back to return to the Reader.
4. Separately, in webtoon/continuous mode, scroll to append a next chapter that is CF-walled and observe the same flow.

**Expected:** After solving the challenge and popping back, the reader AUTOMATICALLY re-fetches and shows the pages (no manual Retry tap needed). In continuous mode the CF-walled appended chapter also triggers the WebView solve and re-appends after return.  
**Regression looks like:** After returning from the WebView the reader still shows the same error pane and you must tap Retry manually (auto-retry latch lost), OR a CF-walled appended chapter in continuous mode just shows a generic snackbar with no WebView recovery.

### Onboarding suppression on upgrade (first_launch preserved)  `P0` · Android
_New one-time migrateNativeSettingsIfNeeded() copies the native AppPrefs first_launch=false flag into the rework yami_settings store; App.kt reads prefs.getBoolean("first_launch", true) to choose Welcome vs Library start destination._  
<sub>shared/src/androidMain/kotlin/me/manga/yamiapk/di/NativeSettingsMigration.android.kt; composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt:578</sub>

**Steps:**
1. Build/install the ORIGINAL native 'Yami Manga' app at the same applicationId (me.manga.kira) on a clean device/emulator. (Native build: cd native-app, drop a real google-services.json into native-app/app/, ./gradlew assembleDebug, adb install the resulting APK.)
2. Launch the native app and complete onboarding fully (tap through the Welcome screen / first-run flow to the end) so the native app writes first_launch=false into its AppPrefs SharedPreferences file. Force-stop the native app.
3. If you cannot build native: SIMULATE the prior install instead — install the new rework debug build once, force-stop it, then write the native store directly: adb shell 'run-as me.manga.kira sh -c "mkdir -p /data/data/me.manga.kira/shared_prefs"' and push an AppPrefs.xml containing <boolean name="first_launch" value="false"/> into /data/data/me.manga.kira/shared_prefs/AppPrefs.xml, then DELETE the rework store /data/data/me.manga.kira/shared_prefs/yami_settings.xml so the migration is forced to run. (run-as requires the same signing/debuggable app.)
4. WITHOUT uninstalling, install the NEW rework build over the existing install: ./gradlew :app:assembleDebug then adb install -r <new apk> (in-place upgrade, do NOT use -d/uninstall).
5. Cold-launch the upgraded app.

**Expected:** App opens DIRECTLY on the Library (bottom-nav main) screen. The Welcome / onboarding flow (Welcome → Theme → Sources) is NOT shown. The migration runs once silently on the first read of the settings store.  
**Regression looks like:** The Welcome/onboarding screen reappears on an upgraded install (user is treated as first-run), meaning first_launch was not carried over from native AppPrefs (or yami_settings already had native_settings_migrated=true and shadowed it).

### Library contents (saved manga + read history) survive upgrade via Room  `P0` · Android
_Same in-place upgrade scenario: the rework Room DB uses the identical file name 'manga_database' at the same applicationId, so the rework opens the native DB and runs its v8→v10 migrations. (Not the settings copy — but exercised by the SAME upgrade test, and the headline owner requirement is 'do not wipe user data on upgrade'.)_  
<sub>shared/src/androidMain/.../data/local/DatabaseBuilder.android.kt:30; shared/.../MangaDatabase.kt:72 (DATABASE_NAME="manga_database")</sub>

**Steps:**
1. On the OLD native build, add several manga to the Library and read at least one chapter of one of them (so read-history + last-read position are recorded). Force-stop. (This requires a true native build — Room data cannot be faithfully simulated by hand.)
2. Install the new rework build in-place over it (adb install -r, never uninstall).
3. Cold-launch and open the Library tab; open one of the previously-added manga's Details and check its chapter read-state.

**Expected:** All previously-saved manga still appear in the Library; the chapter you read is still marked read and resumes at the saved position. No crash on first launch (Room migrations 8→9→10 apply cleanly on the native DB).  
**Regression looks like:** Library is empty after upgrade, read state is lost, OR the app crashes on launch / shows a DB error (Room migration failed against the native manga_database file). An empty library here is a P0 data-loss regression.

## P1 (49)

### Crashlytics reporting on crash  `P1` · Android
_Crash handler kills the process itself instead of delegating to the previous (default) handler; the explicit CrashReporter.recordException + Crashlytics breadcrumb log writer were intentionally preserved — must verify Crashlytics still receives the crash._  
<sub>app/src/main/java/me/manga/yamiapk/MyApp.kt</sub>

**Steps:**
1. Use a release-style build wired to a REAL Firebase project (not the placeholder google-services.json) so Crashlytics uploads.
2. Cold-launch the app, use it briefly (so breadcrumb logs accumulate), then trigger a reproducible crash.
3. Let the app die, then RELAUNCH it (Crashlytics uploads pending reports on next launch).
4. Wait a few minutes and check the Firebase Crashlytics console for the new crash.

**Expected:** The crash appears in Crashlytics with the stack trace AND the Kermit log breadcrumbs leading up to it. Because we no longer call the previous handler, the report should appear ONCE (no duplicate/triple-counted reports).  
**Regression looks like:** No crash report reaches Crashlytics at all (process killed before upload-on-next-launch could be scheduled), or the same crash is recorded multiple times.

### UMP consent + review flow start timing (onResume once-guard)  `P1` · Android
_startConsentFlow() and startInAppReviewFlow() moved out of onCreate into the first onResume behind an activityScopedFlowsStarted once-guard, so they don't re-run on every resume but do find a non-null foreground Activity._  
<sub>app/src/main/java/me/manga/yamiapk/MainActivity.kt</sub>

**Steps:**
1. Cold-launch the app and let it settle on the first screen.
2. Background the app (Home button) and foreground it again several times.
3. If a consent form or review prompt is eligible, observe how many times it tries to appear across the resume cycles.

**Expected:** Consent and review flows attempt exactly once per Activity instance — they run on the first onResume and do not re-fire on every subsequent foreground.  
**Regression looks like:** The consent form or in-app review prompt re-triggers on every app foreground/resume, or never triggers at all.

### In-app update completion (flexible update install)  `P1` · Android
_MainActivity now registers an InstallStateUpdatedListener that calls completeUpdate() when the flexible update reaches DOWNLOADED, and calls resumeIfDownloaded() on every onResume; the listener is unregistered in onDestroy. Previously completeUpdate() had zero callers so a downloaded update was never installed._  
<sub>app/src/main/java/me/manga/yamiapk/MainActivity.kt, platform/src/androidMain/kotlin/me/manga/yamiapk/platform/update/AndroidAppUpdateClient.kt</sub>

**Steps:**
1. Use Play's internal-app-sharing / internal testing track to make a higher versionCode available so Play Core offers a flexible update (this requires a Play-signed build; FakeAppUpdateManager in a test build is the alternative).
2. Launch the app so the flexible update flow starts and the update begins downloading.
3. While the download is in progress, background the app, then foreground it again (exercises resumeIfDownloaded on onResume).
4. Let the download finish while staying in the app (exercises the DOWNLOADED listener).

**Expected:** When the flexible update finishes downloading, the install is triggered automatically (Play's restart-to-install UI / completion), whether the app was foregrounded the whole time or returned via onResume. The downloaded APK is not left stranded.  
**Regression looks like:** The update downloads but is never installed (no restart-to-install prompt, completeUpdate never fires); or a crash/ANR on onDestroy from a leaked listener; or completeUpdate firing repeatedly.

### Onboarding notification-permission denial toast  `P1` · Android
_The 'you need to enable notifications' toast now fires ONLY from the request result callback on an actual denial, not inferred from hasPermission state — so it no longer fires the instant the system permission dialog opens. request() now takes an onResult(granted) callback._  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/ThemeSelectionScreenRoute.kt, composeApp/src/androidMain/kotlin/me/manga/yamiapk/core/platform/RememberNotificationPermissionRequester.android.kt</sub>

**Steps:**
1. On Android 13+ (API 33+), clear app data so POST_NOTIFICATIONS is undecided.
2. Cold-launch into onboarding and advance to the Theme-selection step (which auto-requests notification permission).
3. When the system permission dialog appears, tap ALLOW.
4. Repeat from clean state, but this time tap DENY on the system dialog.
5. Also tap the in-screen 'Grant permission' button after a denial and deny again.

**Expected:** Tapping ALLOW shows NO denial toast. Tapping DENY shows the 'you need to enable notifications' long toast exactly once, only AFTER the user answered. Re-requesting and re-denying shows the toast again from that request's result.  
**Regression looks like:** The denial toast appears the moment the system dialog opens (before the user answers), or appears even after the user grants permission, or never appears after an actual denial.

### FCM push notification tap opens app  `P1` · Android
_MyFirebaseMessagingService now attaches a contentIntent (PendingIntent → MainActivity with CLEAR_TOP|SINGLE_TOP, FLAG_IMMUTABLE) so tapping the push opens the app and setAutoCancel actually dismisses it. The service is also now exported=false._  
<sub>app/src/main/java/me/manga/yamiapk/firebase_cores/messaging/MyFirebaseMessagingService.kt, app/src/main/AndroidManifest.xml</sub>

**Steps:**
1. On a build wired to a real Firebase project, with notifications permitted, send a test FCM message containing a notification title/body to the device's token (Firebase console 'Cloud Messaging' test, or curl to FCM).
2. With the app backgrounded, observe the notification appears with the message icon.
3. Tap the notification.
4. Confirm it also disappears after tapping.

**Expected:** Tapping the push notification opens the app at its start destination (MainActivity, bringing existing task to front via CLEAR_TOP/SINGLE_TOP), and the notification is auto-dismissed on tap.  
**Regression looks like:** Tapping the notification does nothing (no contentIntent regression) and/or the notification cannot be dismissed by tap (only swipe); or the app crashes on tap due to a PendingIntent mutability error.

### Rewarded ad reward delivery (WeakReference removed)  `P1` · Android
_AndroidAdProvider's rewarded-flow terminal-state holder changed from WeakReference<Array<AdResult?>> to a strongly-held arrayOfNulls<AdResult>(1), so the earned reward recorded in the OnUserEarnedRewardListener can no longer be lost to GC before onAdDismissedFullScreenContent resumes._  
<sub>platform/src/androidMain/kotlin/me/manga/yamiapk/platform/ads/AndroidAdProvider.kt</sub>

**Steps:**
1. On a build where ads are enabled and consent allows ad requests, reach a flow that shows a rewarded ad (the reward-gated feature, e.g. unlocking content).
2. Watch the full rewarded ad to completion (so OnUserEarnedRewardListener fires), then close it.
3. Confirm the reward is granted by the app.
4. Repeat: open the rewarded ad and dismiss it EARLY without earning the reward, and confirm no reward is granted.

**Expected:** Watching the rewarded ad to completion reliably grants the reward (EarnedReward survives until dismissal resumes the coroutine). Dismissing early grants nothing. No double-grant.  
**Regression looks like:** Intermittently the reward is NOT granted after fully watching the ad (the holder was GC'd / EarnedReward lost), or the reward is granted even when the ad was dismissed early.

### Search — genre/sort browse uses the source's real filters (no longer collapsed to plain text on piloted sources)  `P1` · all
_For the 12 piloted (generic) sources, a SORT/GENRES search (or a NORMAL search still carrying sort/genres) is now routed back to the legacy scraper path that honours the source's sortTypes/allGenres, instead of silently dropping the filter and running a plain-text query through the generic engine._  
<sub>data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/SearchRepositoryImpl.kt (isPlainTextSearch branch)</sub>

**Steps:**
1. Set the active source to a piloted source that exposes genres/sort (e.g. Lekmanga, SwatManga, Team X).
2. Open Search, open the filter sheet, pick a GENRE (and/or a SORT type), apply.
3. Compare the returned single-source results to a plain text search on the same source.

**Expected:** Results reflect the chosen genre/sort (a genre browse returns titles in that genre; a sort returns the listing in that order) — not a plain-text query result.  
**Regression looks like:** Selecting a genre/sort returns the same results as an empty/plain query (filter ignored), or returns wrong/unrelated titles for that genre.

### Search — error-pane Retry re-runs the failed genre/sort browse  `P1` · all
_The single-result error pane's Retry now dispatches OnRetrySingle, replaying the exact last single-search params (including the deliberately-blank query of a genre/sort browse). Previously Retry dispatched OnSubmit, which hit the blank-query guard and silently replaced the error with a pristine idle screen._  
<sub>ui/src/commonMain/kotlin/me/manga/yamiapk/ui/search/SearchScreen.kt; presentation/.../search/SearchViewModel.kt (onRetrySingle, lastSingleSearch)</sub>

**Steps:**
1. On the single tab, run a genre/sort browse (filter sheet → pick a genre → apply) against a source/network state where that browse fails (e.g. toggle airplane mode just before applying, or pick a source currently erroring).
2. Confirm the single-result area shows the error pane with a Retry button.
3. Restore connectivity, then tap Retry.
4. Observe the result area.

**Expected:** Retry re-runs the same genre/sort browse (loading spinner, then results or the same error) — it does NOT clear to a blank idle 'start searching' screen.  
**Regression looks like:** Tapping Retry on a failed genre/sort browse blanks the screen to the pristine idle/empty state instead of re-running the search.

### Search — one failing source no longer kills the whole multi-source search  `P1` · all
_Multi-repo search now catches per-repo: a source that throws (during base-URL warm-up or fetch) surfaces as its own error tile instead of cancelling the entire fan-out; and a flow-level throw resolves any still-Loading tiles to an error slot instead of spinning forever._  
<sub>data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/SearchRepositoryImpl.kt (searchAllRepos per-repo catch); presentation/.../search/SearchViewModel.kt (runMultiSearch catch)</sub>

**Steps:**
1. Enable several sources, at least one of which is currently failing/unreachable (or block one host).
2. Open Search, switch to the multi (all sources) tab, and run a plain-text query.
3. Observe each per-source tile.

**Expected:** Healthy sources show their results; the failing source shows its own error state in its tile. No tile is stuck spinning indefinitely, and the whole results list is not blanked by the one bad source.  
**Regression looks like:** One unreachable source blanks/cancels all source results, or a per-source tile shows a spinner that never resolves to results or an error.

### Search — system-back closes the Search overlay  `P1` · Android
_Added a BackHandler in SearchScreenContent that closes the Search overlay (dispatches OnClose) — Search is an overlay swap on the Home back-stack entry, so the HomeScreen BackHandler is unreachable while Search is composed._  
<sub>ui/src/commonMain/kotlin/me/manga/yamiapk/ui/search/SearchScreen.kt (BackHandler { onIntent(OnClose) })</sub>

**Steps:**
1. On Home, open Search (the overlay appears).
2. Optionally type a query / pick a genre.
3. Press the system Back button (hardware/gesture back).

**Expected:** System-back closes the Search overlay and returns to the Home feed (same as tapping the close button) — it does not exit the app or do nothing.  
**Regression looks like:** Pressing back from the Search overlay either does nothing, or backs out of Home/the app entirely instead of just closing Search.

### Home / Search — 'Open in WebView' opens the source's real page (not a blank page)  `P1` · Android
_The Search-results error 'Open in WebView' action now navigates to the active tab's baseUrl (no-op when there is no active tab). Previously it navigated Screen.WebView(url="") and nothing resolved the blank URL, opening a blank page._  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/HomeReworkScreenRoute.kt (onOpenInWebView)</sub>

**Steps:**
1. Make a single-source search fail so the error pane appears (e.g. offline, or an erroring source) with an active source tab selected.
2. Tap 'Open in WebView' on the error pane.
3. Observe the WebView that opens.

**Expected:** The in-app WebView loads the active source's site (its base URL home page), not a blank/empty page.  
**Regression looks like:** 'Open in WebView' opens a blank white page (empty URL).

### Generic (piloted) source follows a host/domain move (base-URL)  `P1` · all
_The generic engine now resolves {baseUrl}/{imageBase} and relative links against the LIVE DB base URL (DbSourceBaseUrlProvider → SourcesDao.getBaseUrlFor) before each verb, instead of the compile-time PILOT_SOURCES_CONFIG_JSON value — so a piloted source whose host moves keeps working like the legacy path does._  
<sub>sources/engine/.../GenericSourceClient.kt (effectiveBaseUrl/refreshBaseUrl); composeApp/.../di/SourcesGenericModule.kt + Stage0Ports.kt (DbSourceBaseUrlProvider); contracts/Ports.kt (SourceBaseUrlProvider)</sub>

**Steps:**
1. On a piloted source whose domain has moved (or trigger a source base-URL update / use the source-update flow that writes a new baseUrl into the sources DB row for that api).
2. From Home, browse the piloted source's feed and open a title's Details; from Details open a chapter in the Reader.
3. Confirm the feed, cover images, details and page images all load.

**Expected:** After the base URL changes in the DB, the piloted source's Home feed, detail/cover images and reader page images all load from the NEW host (same as a legacy source following a domain move).  
**Regression looks like:** After a host/domain move the piloted source still requests the OLD (frozen) host and the feed/images/pages fail to load, while the legacy sources following the same move work.

### Source base-URL rewrite — blank/scheme-less replacement no longer corrupts stored URLs  `P1` · all
_replaceBaseUrl now leaves the original URL untouched when the replacement baseUrl is blank/scheme-less, and the per-row update uses ABORT (bare update) instead of REPLACE — so a bad server-pushed/empty baseUrl can't strip hosts off every stored URL or REPLACE-delete a colliding library row._  
<sub>data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/SourceRegistryRefreshRepositoryImpl.kt (replaceBaseUrl guard; mangaDao.update)</sub>

**Steps:**
1. Have several library entries for a given source (saved manga + chapters).
2. Trigger a source-registry refresh / base-URL update for that source where the incoming server baseUrl is empty or malformed (no scheme).
3. Open the Library and the affected source's saved titles; open one into Details and the Reader.

**Expected:** Stored manga URLs are unchanged when the incoming baseUrl is blank/invalid; library rows survive (none silently deleted), and the saved titles still open and read.  
**Regression looks like:** Saved manga become unopenable (URLs corrupted to host-less paths), or library entries disappear after a base-URL refresh, or images/pages stop loading for previously-saved titles.

### WebView main-frame host pin — lookalike domains blocked  `P1` · Android
_The in-app WebView's main-frame host pin was tightened from a bare endsWith() suffix match to exact-host-or-dot-boundary-subdomain, so a registrable lookalike (e.g. evil-lek-manga.net for lek-manga.net) is no longer allowed to load in the main frame._  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/core/webview/WebViewUrlSandbox.kt</sub>

**Steps:**
1. Open the in-app WebView for a source (e.g. via Open in WebView or a Cloudflare solve).
2. From within that page, follow a link / redirect that points to a genuine sub-domain of the source host (e.g. cdn.<sourcehost>) and confirm it loads.
3. Then attempt navigation to a lookalike host that merely ends with the source host string but is a different registrable domain (e.g. a phishing-style host).

**Expected:** True sub-domains and the source host itself load in the main frame; a lookalike/suffix-only host is blocked from loading in the main frame.  
**Regression looks like:** A lookalike domain whose name merely ends with the pinned host loads in the WebView main frame; OR a legitimate sub-domain (cdn./www.) is wrongly blocked and the page fails to load.

### Details screen opened on a non-library manga that the user adds mid-screen  `P1` · all
_observeSavedDetails is now membership-reactive (observes the saved-manga table and (re)attaches the chapter flow when the (api,title) row appears/disappears). Previously it completed after a single null for a non-library manga, so membership gained while the screen was open never started the saved-details stream._  
<sub>data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/SavedMangaDetailsRepositoryImpl.kt</sub>

**Steps:**
1. Open Details for a manga that is NOT in the library (from Home/Search).
2. While staying on the Details screen, tap the heart to add it to the library.
3. Observe the chapter list / read+download status indicators and the per-chapter actions (mark read, bookmark).

**Expected:** After adding to library while on the screen, saved-details-driven affordances activate live: per-chapter read/bookmark/download status and actions become available without leaving and re-entering Details.  
**Regression looks like:** After adding to library on-screen, the saved chapter state never appears (read/bookmark toggles do nothing or chapters never reflect saved status) until you leave and re-open Details.

### Home — double-tap heart no longer silently undoes the save  `P1` · all
_onSaveToggle now has a re-entry guard (drops the second invocation while a key is in savingKeys). Previously a rapid double-tap fired two concurrent toggleInLibrary calls that undid each other (a double-tap on REMOVE could even re-ADD)._  
<sub>presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/home/HomeViewModel.kt (onSaveToggle savingKeys guard)</sub>

**Steps:**
1. On the Home feed, rapidly double-tap (or triple-tap) the heart on a card that is NOT in the library.
2. Wait for the in-flight spinner to clear; check the heart state and the Library tab.
3. Repeat on a card that IS in the library (double-tap to remove).

**Expected:** A double-tap to ADD ends with the manga saved (heart filled, present in Library). A double-tap to REMOVE ends with it removed (not re-added). The second tap is ignored while the first is in flight.  
**Regression looks like:** Double-tapping leaves the heart in the wrong state — an intended ADD ends up not saved, or an intended REMOVE re-adds the manga to the library.

### Details — delete-downloaded actions fully delete files and clear the downloaded flag  `P1` · Android
_Multi-select delete, top-bar delete-all-downloaded and per-row delete-chapter now call DeleteDownloadedChapterUseCase (clears isDownloaded + deletes on-disk files + drops the queue row); previously they did a row-only deleteDownload that left files orphaned and isDownloaded set. Per-row delete also aborts the chapter-row delete if cleanup fails (no orphaned files)._  
<sub>presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/details/DetailsViewModel.kt (onDeleteSelectedDownloads / onDeleteAllDownloads / onDeleteChapter)</sub>

**Steps:**
1. Add a manga to the library and download a few chapters (let them complete; the download badge/checkmark shows).
2. Use multi-select to delete the downloaded chapters (and separately the top-bar 'delete all downloaded').
3. Note storage usage before/after (or re-check the chapter's downloaded state and try to read it offline).
4. Also test the per-row trash/delete-chapter button on a downloaded chapter.

**Expected:** After delete, the affected chapters show as NOT downloaded, the on-disk files are removed (storage drops), and re-opening offline shows them as needing download. Per-row delete removes the chapter row only after files are cleaned.  
**Regression looks like:** Chapters still show as downloaded after 'delete', files remain on disk (no storage reclaimed / still readable offline), or the chapter row vanishes while its files are orphaned.

### Details / Reader — fire-and-forget actions no longer crash the app on an unexpected throw  `P1` · all
_All bare viewModelScope.launch wrappers around throwing use cases in Details (mark-opened, clear-new-badge, toggle read/bookmark, downloads, delete, mark-down-read, cancel-all) and the Sources toggles were moved to launchSafely so an uncaught throw routes to onUnhandledError instead of crashing the process._  
<sub>presentation/.../details/DetailsViewModel.kt; shared/.../repo_settings/.../SourcesViewModel (launchSafely)</sub>

**Steps:**
1. Exercise the per-chapter and bulk Details actions under flaky conditions: toggle read/bookmark, enqueue and cancel downloads, delete downloaded, mark-this-and-below-read, while toggling connectivity/storage pressure.
2. Toggle sources and languages on/off rapidly in the Sources screen.
3. Watch for the process surviving (no crash dialog) and at most a snackbar/no-op on failure.

**Expected:** Actions either succeed or fail gracefully (snackbar / silent no-op); the app process never crashes to the system 'app keeps stopping' dialog from one of these actions.  
**Regression looks like:** Any of these Details/Sources actions hard-crashes the app instead of failing gracefully.

### iOS app icon on home screen  `P1` · iOS
_AppIcon.appiconset (all required iPhone/iPad/marketing sizes + Contents.json) was added; previously the icon set was absent_  
<sub>iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/Contents.json (+ icon_*.png); ASSETCATALOG_COMPILER_APPICON_NAME=AppIcon in iosApp/project.yml</sub>

**Steps:**
1. Build and install the iosApp scheme on a simulator/device (regenerate the xcodeproj with xcodegen if needed)
2. Background the app and look at the Home screen / App Library icon
3. Open Settings app and check the app's row icon

**Expected:** The Yami app icon (blue 'M' mark) renders at every size with no white/blank placeholder, on both iPhone and iPad idioms and in Settings  
**Regression looks like:** A white/blank/grey default placeholder icon appears on the home screen, or Xcode warns about a missing/unassigned AppIcon image slot at build

### Localized effect snackbars (Complaint / AdminComplaint / Updates / Downloads)  `P1` · all
_VMs now emit semantic effect variants and :ui resolves localized strings (np_complaint_action_*, np_download_enqueue_failed, np_undo_failed, np_complaint_body_copied) instead of embedding English_  
<sub>ui/src/commonMain/composeResources/values-*/strings_pfix_effect_snackbars.xml; presentation/.../complaint, updates, downloads VMs</sub>

**Steps:**
1. Switch app language to Arabic (Settings -> Language -> Arabic; relaunch on iOS)
2. Open a complaint (or admin complaint) and perform actions: update status, add closure reason, send a reply, copy the body, delete a complaint
3. On the Updates screen, swipe-delete an entry to trigger the undo snackbar, and trigger a failed download enqueue if reachable

**Expected:** Each confirmation snackbar renders in Arabic (e.g. 'تم تحديث الحالة', 'تم إرسال الرد', 'تم النسخ إلى الحافظة', 'فشل التنزيل', 'تعذّر التراجع عن الحذف'), not English  
**Regression looks like:** Snackbars still show hardcoded English text in a non-English locale, or show a raw exception message string

### RTL Arabic layout for the 27 newly-translated strings  `P1` · Android+Desktop
_New translated snackbar/dialog strings added across all 10 locales; layout direction flips to RTL on live-locale platforms when Arabic is picked_  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt:472 (layoutDirection); LocalAppLocale.android/desktop</sub>

**Steps:**
1. On Android or Desktop, set app language to Arabic via Settings -> Language
2. Observe the layout mirror immediately (no relaunch on Android/Desktop)
3. Trigger the new snackbars (complaint actions, undo-delete, copy-to-clipboard) and open the complaint action dialogs and Sources 'Request source' dialog

**Expected:** On the same session the whole UI mirrors to RTL: snackbar text, dialog buttons, back arrows and content align right-to-left and the Arabic strings render correctly  
**Regression looks like:** Layout stays LTR after picking Arabic, text is left-aligned/clipped, or buttons/arrows are mirrored inconsistently with the text

### iOS language switch: layout direction waits for relaunch  `P1` · iOS
_On iOS (no live locale switch) the RTL layout flip is gated on isLiveLocaleSwitchSupported so direction no longer flips ahead of the strings_  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt:465-472; composeApp/src/iosMain/.../locale/LocalAppLocale.ios.kt (isLiveLocaleSwitchSupported=false)</sub>

**Steps:**
1. On iOS with English UI, go to Settings -> Language and select Arabic
2. Observe the UI immediately after selecting (do NOT relaunch yet)
3. Force-quit and relaunch the app, then observe

**Expected:** Immediately after picking Arabic the layout stays LTR (matching the still-English strings); after relaunch the strings AND the layout both switch to Arabic/RTL together  
**Regression looks like:** Right after the pick, the layout mirrors to RTL while every string is still English (mixed RTL-layout/LTR-text session), or after relaunch the strings change but layout stays LTR

### Live language switch applies (Android/Desktop)  `P1` · Android+Desktop
_Locale.setDefault is now guarded (only set when differing) during composition; strings resolve off the updated default in the same pass_  
<sub>composeApp/src/androidMain/.../locale/LocalAppLocale.android.kt:27; composeApp/src/desktopMain/.../locale/LocalAppLocale.desktop.kt:29</sub>

**Steps:**
1. On Android or Desktop, open Settings -> Language and pick a different language (e.g. French, then Japanese, then back to system default)
2. Return to the previous screens (Settings, Library, Details) without relaunching

**Expected:** All visible strings switch to the chosen language immediately without a relaunch and without flicker; choosing 'system default' reverts to the device language  
**Regression looks like:** Strings don't change until relaunch, stale strings linger after a switch, or repeated switching corrupts the locale (some screens in one language, others in another)

### Theme picker back affordance (rework Theme screen)  `P1` · Desktop
_Rework Theme picker (reached from Settings -> Theme) now renders a TopAppBar back arrow wired to safePopBackStack(); Desktop has no system back_  
<sub>ui/src/commonMain/kotlin/me/manga/yamiapk/ui/themepicker/ThemeScreen.kt:329; composeApp/.../navigation/routes/ThemeReworkScreenRoute.kt:166</sub>

**Steps:**
1. On Desktop, open Settings and tap the Theme row (navigates to the rework Theme picker)
2. Toggle Light/Dark/System tabs
3. Tap the back arrow in the top app bar

**Expected:** A back arrow is visible in the Theme screen's top bar and tapping it returns to Settings; the user is not stranded on the Theme screen with no way back  
**Regression looks like:** No back arrow appears in the top bar, or tapping it does nothing — leaving Desktop users trapped on the Theme picker (no OS back button to rescue them)

### Coil disk cache location + Settings Clear Cache  `P1` · iOS+Desktop
_Coil disk cache pinned to AppFileSystem.cacheDir/image_cache (was system temp), so Settings cache size includes it and Clear Cache removes it_  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt:393-407 (diskCache directory = cacheDir/image_cache); platform/.../AppFileSystem.kt clearCacheLargerThan</sub>

**Steps:**
1. On iOS or Desktop, browse Home/Library/Details and open several chapters so many cover and page images load (build up cached images)
2. Open Settings and read the cache size figure
3. Tap 'Clear cache', then re-read the cache size and re-browse the same images

**Expected:** Reported cache size reflects the loaded images (non-trivial), Clear cache drops it substantially, and the cleared images re-download on next view; the cache dir lives under the app's own cache directory  
**Regression looks like:** Cache size stays at ~0 regardless of how many images are viewed, or Clear cache leaves images on disk (size unchanged) because the cache still lives in the system temp dir invisible to Settings

### Onboarding default-enabled sources seed from device locale  `P1` · all
_Sources onboarding step now falls back to the platform locale's language when no app language is set (was hardcoded to English-only sources)_  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/SourcesScreenRoute.kt:162-175</sub>

**Steps:**
1. Set the DEVICE/OS language to a non-English supported language (e.g. Arabic) and ensure no in-app language was previously chosen
2. Fresh install (clear app data) and run onboarding to the Sources step
3. Observe which sources are pre-enabled by default

**Expected:** Sources matching the device locale (e.g. Arabic sources for an Arabic device) are pre-enabled, not only English sources  
**Regression looks like:** Only English sources are enabled by default on a non-English device during a fresh onboarding (the user sees no relevant sources)

### Android dark-mode window background (no white flash)  `P1` · Android
_values-night Theme.YamiManga android:windowBackground changed from @color/white to @color/black after the night style was activated_  
<sub>app/src/main/res/values-night/themes.xml:26 (android:windowBackground @color/black)</sub>

**Steps:**
1. On Android set the system (or in-app) theme to Dark
2. Cold-launch the app from the launcher and watch the post-splash window transition
3. Navigate between screens that recreate the window

**Expected:** The window background behind/around content is dark (black) during launch and transitions — no white flash in dark mode  
**Regression looks like:** A white flash/background appears momentarily on launch or screen transitions while in dark mode

### Cover/search/details images load on Cloudflare-gated piloted sources  `P1` · Android
_CoilSourceHeaderInterceptor now lazily hydrates source headers (ensureSiteInitialized) when the matched repo wasn't initialized, and honors request-level headers if already attached_  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt:290-330 (CoilSourceHeaderInterceptor)</sub>

**Steps:**
1. Open a generic-piloted, Cloudflare-gated source (e.g. one requiring a WebView challenge solve)
2. Solve the Cloudflare challenge when prompted, then browse the Home feed / search results / a manga's Details where cover images load
3. Scroll so multiple plain-URL cover thumbnails load

**Expected:** Cover and detail images render (200), reusing the solved Cookie/User-Agent headers; no broken/placeholder images and no 403 image loops after the challenge is solved  
**Regression looks like:** Cover/search/detail images stay broken or 403 on a piloted Cloudflare source even after the challenge was solved (headers not attached to plain-URL image loads)

### Details — per-row 'delete chapter' aborts row delete if file cleanup fails (no orphans)  `P1` · Android+iOS+Desktop
_onDeleteChapter now cleans the download FIRST (clear flag + files + queue row) and only deletes the saved_chapters row if cleanup succeeded — so a failed file cleanup can no longer orphan files under a deleted referencing row._  
<sub>presentation/.../details/DetailsViewModel.kt (onDeleteChapter)</sub>

**Steps:**
1. Library manga, Details open, with at least one DOWNLOADED chapter.
2. Tap the per-row trash/delete-chapter button on that downloaded chapter.
3. Confirm the chapter disappears from the list and its files are removed.
4. On a source-backed manga, pull-to-refresh / re-enter Details and confirm the chapter can be re-discovered cleanly.

**Expected:** Chapter row is removed from Details, its files are deleted, no orphaned chapter_N.cbz or page files remain in that manga's folder. A later refresh can re-add the chapter normally.  
**Regression looks like:** Deleting the row leaves files on disk; or an error during deletion still removes the chapter row leaving unreferenced files; or a snackbar error appears but the row vanishes anyway.

### Details — offline gate on single, all, and multi-select downloads  `P1` · Android+iOS+Desktop
_The offline gate (emit NoConnectivity, skip enqueue) was extended to the multi-select download path (onDownloadSelected) to match the single-chapter and download-all paths; the iOS/Desktop connectivity source was also rewritten from a Google probe to real OS reachability (see separate item)._  
<sub>presentation/.../details/DetailsViewModel.kt (onDownloadChapter/onDownloadAllClick/onDownloadSelected); platform connectivity observers</sub>

**Steps:**
1. Library manga, Details open.
2. Turn the device fully offline (airplane mode / disable Wi-Fi+cellular; Desktop: disconnect network).
3. Try: (a) single chapter download, (b) 'Download all', (c) multi-select a range then download.
4. Re-enable network and retry one download.

**Expected:** While offline, all three actions show an immediate 'no connectivity' error snackbar and enqueue nothing (no rows appear Active/Queued). Once back online, downloads enqueue and run normally.  
**Regression looks like:** An offline multi-select download silently enqueues chapters that sit stuck Queued forever with no feedback; or a download starts a doomed fetch instead of being gated.

### Downloads — Stop / cancel-all deletes the partial pages of the in-flight chapter (iOS/Desktop)  `P1` · iOS+Desktop
_cancelAllDownloads now deletes the on-disk partial pages of the chapter that was mid-download before marking all rows FAILED (mirrors Android worker cleanup); previously those partial files orphaned in the chapter dir until the whole manga was purged._  
<sub>shared/src/nonAndroidMain/.../CoroutineDownloadRepositoryImpl.kt (cancelAllDownloads cancelledChapterId cleanup)</sub>

**Steps:**
1. On Desktop/iOS, start downloading a large chapter (many pages) and let it get partway.
2. Tap the top-bar 'Stop' / cancel-all action on Details (or cancel-all on Downloads).
3. Inspect that manga/chapter's folder on disk (Desktop: ~/.yami-manga; iOS: app container).

**Expected:** The cancelled in-flight chapter's partially-downloaded page files are removed from disk; the row shows Failed/cancelled.  
**Regression looks like:** Half-written page files (image_0.*, image_1.* …) remain on disk for the cancelled chapter, consuming storage with no referencing row.

### Reader — downloaded loose-file chapter re-derives page paths and falls back to network if files are gone  `P1` · iOS+Desktop+Android
_ChapterPagesRepositoryImpl loose-file branch now re-derives each stored page path under the CURRENT chapter dir (guarding the iOS app-container-UUID change that invalidates absolute paths) and, if NONE of the resolved files exist, returns null so the reader falls back to a network fetch instead of handing Coil broken file:// URLs. File URLs are also now properly built/percent-encoded (RFC 8089), fixing Windows/spaces paths._  
<sub>data/src/commonMain/.../ChapterPagesRepositoryImpl.kt (localPagesOrNull loose branch, toFileUrl/encodePathSegment)</sub>

**Steps:**
1. iOS: download a chapter that stores LOOSE page files (not CBZ). Background/relaunch the app several times (or reinstall over an upgrade) so the iOS data-container UUID changes.
2. Open the downloaded chapter offline, then online.
3. Desktop on Windows (packageMsi) or a user profile path with spaces/non-ASCII: download a chapter and open it offline.
4. Page through the whole chapter.

**Expected:** All pages render. After a container-UUID change, the reader either resolves the moved local files or transparently falls back to the network (when online) — never shows a screen of broken-image placeholders. Windows/space paths load from disk correctly.  
**Regression looks like:** Downloaded chapter shows broken-image placeholders for every page after an iOS container change (no network fallback); or Desktop pages fail to load from a path with spaces/backslashes.

### Downloads — action failures (download/cancel/delete) now surface a user-facing error  `P1` · Android+iOS+Desktop
_DetailsViewModel download/cancel/delete handlers now wrap a failure Throwable into AppError.Unexpected and always emit ShowError (was `if (error is AppError)` which was always false on kotlin.Result failures, so failures never reached the user). Downloads screen errors moved to a typed ShowActionFailed effect mapped to a localized generic message._  
<sub>presentation/.../details/DetailsViewModel.kt (onDownloadAll/onCancelChapter/onDeleteChapter/etc); presentation/.../downloads/DownloadsEffect.kt + DownloadsViewModel.kt; ui/.../downloads/DownloadsScreen.kt</sub>

**Steps:**
1. Force a download/delete action to fail (e.g. trigger 'Download all' offline so the enqueue path can error, or delete a chapter whose Room row is in an odd state).
2. On the Downloads screen, force a cancel/retry/delete to fail (e.g. cancel a row mid-transition).
3. Watch for a snackbar after each failing action.

**Expected:** A failing download/cancel/delete action shows an error snackbar (Details: localized error for the AppError; Downloads: generic localized 'error occurred'). No action failure is swallowed silently.  
**Regression looks like:** A download/cancel/delete that clearly failed produces no feedback at all (silent no-op), leaving the user unsure whether it worked.

### Library — removing a manga cancels its in-flight download before purging (no orphan CBZ)  `P1` · Android+iOS+Desktop
_purgeManga now looks up active (RUNNING/COMPRESSING/QUEUED) downloads for the manga and routes them through cancelARunningChapter BEFORE deleting rows + the on-disk dir, so the engine can't keep writing and recreate the manga dir with an orphan CBZ after removal._  
<sub>data/src/commonMain/.../LibraryRepositoryImpl.kt (purgeManga); shared ChapterDownloadDao.getActiveDownloadChapterIdsForManga</sub>

**Steps:**
1. Add a manga to the Library and start downloading a chapter (let it be actively RUNNING).
2. While that download is in flight, remove the manga from the Library (un-heart / remove).
3. After a few seconds, inspect storage for that manga's former directory.

**Expected:** The in-flight download is cancelled, the manga's directory and files are fully removed, and no orphan chapter_N.cbz / page files are recreated under a now-deleted manga id.  
**Regression looks like:** After removing the manga mid-download, a leftover manga/<id>/ folder with a completed-but-orphaned CBZ remains on disk.

### Connectivity — iOS/Desktop offline detection uses real OS reachability (download gate not falsely offline)  `P1` · iOS+Desktop
_IosConnectivityObserver was rewritten from a Google generate_204 HEAD probe to NWPathMonitor (OS reachability); DesktopConnectivityObserver from a Google HEAD probe to NetworkInterface up-check. The Details download gate keys off this, so users on Google-blocked / filtered networks are no longer reported permanently offline._  
<sub>platform/src/iosMain/.../IosConnectivityObserver.kt; platform/src/desktopMain/.../DesktopConnectivityObserver.kt</sub>

**Steps:**
1. On iOS or Desktop connected to a working network that cannot reach google.com (corporate/filtered network, or block google.com via hosts/DNS for the test).
2. Open a Library manga's Details.
3. Attempt to download a chapter.

**Expected:** The app correctly reports online and the download proceeds (the gate does not block). Toggling the device truly offline still correctly blocks with a no-connectivity message.  
**Regression looks like:** On a Google-blocked-but-otherwise-working network the app shows permanently offline and refuses to start downloads; or a genuinely offline device is reported online and downloads spin forever.

### Paged mode: terminal (latest) chapter shows last-chapter message, not an advance  `P1` · Android+iOS
_On the terminal chapter (canGoNext == false) no dummy page is appended; instead the floating NextChapterOverlay shows the "last chapter" message on the final image._  

**Steps:**
1. Open a manga and open its MOST RECENT chapter (the one with no newer chapter) in a PAGED reading mode.
2. Swipe to the last image and try to swipe further forward.

**Expected:** No extra dummy page and no chapter advance. A non-clickable "last/latest chapter" overlay message is shown over the final image; the page stays put.  
**Regression looks like:** A dummy "Next Chapter" page appears with nothing to go to, or the reader tries to advance and errors, or no last-chapter affordance shows at all.

### Share current reader page on iPhone (still works, main-thread hop)  `P1` · iOS
_Share presentation moved onto Dispatchers.Main and the root view controller is resolved via the active foreground window scene (with a keyWindow fallback) instead of the deprecated keyWindow only._  

**Steps:**
1. Run on an iPhone (or iPhone simulator).
2. Open a chapter in the Reader, show the chrome, tap Share.

**Expected:** The system share sheet slides up; the shared image is the current page. Dismiss returns to the reader. No popover-anchor needed on iPhone.  
**Regression looks like:** Share silently does nothing (no sheet) or the app hangs/crashes; log shows "no root view controller (no foreground window scene)".

### Continuous mode: empty appended next chapter doesn't dead-end the feed  `P1` · Android+iOS
_When an appended next chapter resolves to zero pages, it is recorded as loaded (so the following append targets the chapter after it) and a non-blocking "This chapter returned no pages" snackbar shows, instead of silently dead-ending and re-marking it read on every scroll._  

**Steps:**
1. Find a series where one chapter between two readable chapters returns no pages (or a known-empty chapter). In webtoon/continuous mode, read to the end of the chapter before the empty one and keep scrolling.
2. Continue scrolling/triggering further appends.

**Expected:** A brief snackbar notes the empty chapter; the feed skips past it and appends the chapter AFTER the empty one rather than getting stuck retrying the empty one. The empty chapter is not repeatedly re-marked read.  
**Regression looks like:** The feed dead-ends at the empty chapter and never advances, or the empty chapter is retried/re-marked-read repeatedly on each scroll, or no feedback at all leaves the user staring at a stuck feed.

### Cloudflare auto-route is suppressed on macOS Desktop (no embedded WebView)  `P1` · Desktop
_The Cloudflare solver callback is now gated on isEmbeddedWebViewAvailable(); on macOS Desktop (KCEF hard-skipped) it is a no-op so the user sees the error pane instead of being auto-navigated up to 2x into a dead WebView placeholder._  

**Steps:**
1. Run the Desktop app on macOS.
2. Open a chapter on a Cloudflare-walled source that returns 403.

**Expected:** The reader shows its error pane with the Open-in-browser/WebView fallback; it does NOT auto-navigate to a non-functional embedded WebView screen.  
**Regression looks like:** On macOS the reader auto-navigates into a blank/dead embedded WebView placeholder (one or twice) on a 403. (Windows/Linux Desktop, which DO have KCEF, should still auto-route — out of scope here.)

### OnEnter re-entry idempotence: rotation / pop-back doesn't reset to the original chapter  `P1` · Android+iOS
_The OnEnter intent now only runs the full fetch when state.chapter == null; a second OnEnter replaying the original nav-args (rotation, pop-back from WebView) is ignored so Next/Prev navigation isn't discarded. Internal Next/Prev navigation bypasses the guard._  

**Steps:**
1. Open a chapter, then tap Next-chapter (or read to the next chapter) so you are now on a DIFFERENT chapter than the one you opened.
2. Rotate the device (Android) / background and foreground the app, or open and pop back from the embedded WebView.
3. Observe which chapter the reader shows.

**Expected:** The reader stays on the chapter you navigated to; rotation/return does not reset it to the originally-opened chapter.  
**Regression looks like:** After rotation or pop-back the reader jumps back to the chapter you first opened, losing your Next/Prev navigation.

### Next/Prev chapter re-entrance guard while loading  `P1` · Android+iOS
_onNextChapter/onPrevChapter now drop the request while state.isLoading is true, so a rapid double-tap of the chapter-step pill can't advance twice and skip a chapter._  

**Steps:**
1. Open a chapter and show the chrome.
2. On a slow source/network, rapidly double-tap the chapter-advance pill (the LEFT seekbar pill) before the next chapter finishes loading.

**Expected:** Exactly one chapter advance happens; you do not skip past the intended next chapter.  
**Regression looks like:** A fast double-tap skips a chapter (advances two chapters), landing you on the chapter after the one you wanted.

### Bottom seekbar chapter-step pill semantics (left=Next, right=Prev)  `P1` · Android+iOS+Desktop
_The seekbar pills were swapped to match native: the LEFT pill (prev-chevron glyph) now steps to the NEXT chapter and is disabled on the last chapter; the RIGHT pill steps to the PREVIOUS chapter and is disabled on the first chapter. Slider snapping uses roundToInt._  

**Steps:**
1. Open a mid-series chapter (has both a prev and a next chapter), show the chrome.
2. Tap the LEFT pill in the bottom seekbar; note which chapter you land on. Go back and tap the RIGHT pill.
3. Open the FIRST chapter of a series and check the right pill; open the LAST chapter and check the left pill.
4. Drag the page slider slowly and release between two page values.

**Expected:** Left pill advances to the NEXT chapter; right pill goes to the PREVIOUS chapter. Left pill is disabled on the last chapter; right pill is disabled on the first chapter. The slider snaps to the nearest page on release.  
**Regression looks like:** Left pill goes backward / right pill goes forward (swapped wrong), or the wrong pill is disabled at series ends, or the slider truncates downward to the wrong page.

### Reading downloaded (CBZ) chapters after dispatcher swap  `P1` · Android+iOS+Desktop
_DefaultCbzReader now does zip page-count/extract/delete/cleanup on the injected DispatcherProvider.io instead of Dispatchers.Default; downloaded-chapter reading and cleanup paths are exercised._  

**Steps:**
1. Download a chapter to the device.
2. Go offline (airplane mode) and open the downloaded chapter in the Reader; scroll through all pages.
3. Back out of the reader and reopen another downloaded chapter, then delete a downloaded chapter.

**Expected:** Downloaded chapter pages extract and render correctly offline; navigating between downloaded chapters and deleting downloads works as before, with no hang or ANR on opening a large CBZ.  
**Regression looks like:** Downloaded chapter shows blank/broken pages, the reader hangs/ANRs when opening a large CBZ, or extracted-cache cleanup leaves temp files growing across sessions.

### Theme preservation on upgrade (dark mode / follow-system / pure-black)  `P1` · Android
_Migration copies native AppPrefs boolean keys ThemeMode (dark on/off), ThemeSystem (follow system), and PureBlack into yami_settings; the rework theme layer reads the same key strings._  
<sub>shared/src/androidMain/kotlin/me/manga/yamiapk/di/NativeSettingsMigration.android.kt:57; native-app/.../core/storage/StorageKeys.kt:15-18</sub>

**Steps:**
1. On the OLD native build (or simulated AppPrefs as above), set a DISTINCTIVE, non-default theme: in native Settings turn OFF 'follow system', turn ON dark mode, and turn ON Pure Black (AMOLED). Confirm the app visibly renders dark + pure-black. Force-stop.
2. (Simulation alternative: write to AppPrefs.xml booleans ThemeMode=true (dark), ThemeSystem=false, PureBlack=true; remove yami_settings.xml to force migration.)
3. Install the new rework build in-place over it (adb install -r, no uninstall).
4. Cold-launch the upgraded app and observe the theme immediately at startup, then open Settings to verify each toggle state.

**Expected:** App launches already in dark + pure-black (true black backgrounds), follow-system OFF. Settings toggles reflect ThemeMode=dark on, follow-system off, Pure Black on — matching what was set in native.  
**Regression looks like:** App launches in the default/light or follow-system theme, or the Settings theme toggles are reset to defaults — indicating ThemeMode/ThemeSystem/PureBlack were not copied from native AppPrefs into yami_settings.

### Language preservation on upgrade  `P1` · Android
_Migration copies the native settings_prefs DataStore string key selected_language into yami_settings; the rework language layer reads the same key._  
<sub>shared/src/androidMain/kotlin/me/manga/yamiapk/di/NativeSettingsMigration.android.kt:78 (copyNativeDataStore); native-app/.../StorageKeys.kt:21</sub>

**Steps:**
1. On the OLD native build, change the app language to a distinctive non-device-default (e.g. Arabic from English) via the in-app language picker. Confirm UI text + layout direction change (Arabic = RTL). Force-stop.
2. (Simulation alternative: native stores this in the DataStore file settings_prefs, NOT in AppPrefs — it is a protobuf, so you cannot hand-edit it; for selected_language the only reliable repro is the true native build. Note the migration copies it from the settings_prefs DataStore, not SharedPreferences.)
3. Install the new rework build in-place (adb install -r).
4. Cold-launch the upgraded app.

**Expected:** Upgraded app launches in the previously-selected language (e.g. Arabic, RTL) without the user re-selecting it; Settings shows the same language selected.  
**Regression looks like:** App launches in device-default/English (LTR) after upgrade, or language picker shows default — meaning selected_language was not copied from the native settings_prefs DataStore.

### Reader reading-mode preservation on upgrade  `P1` · Android
_Migration copies the native settings_prefs DataStore string key reading_mode into yami_settings; the reader reads the same key._  
<sub>shared/src/androidMain/kotlin/me/manga/yamiapk/di/NativeSettingsMigration.android.kt:78; native-app/.../StorageKeys.kt:14</sub>

**Steps:**
1. On the OLD native build, open any chapter in the Reader and change the reading mode to a distinctive non-default value via the reading-mode dialog (e.g. Webtoon or Continuous Vertical instead of the default). Back out so it persists. Force-stop.
2. (reading_mode lives in the settings_prefs DataStore protobuf — use the true native build for a faithful repro.)
3. Install the new rework build in-place (adb install -r).
4. Cold-launch, open any chapter in the Reader.

**Expected:** Reader opens in the previously-selected reading mode (e.g. Webtoon/Continuous Vertical) — the chosen mode is the active one without re-selecting.  
**Regression looks like:** Reader opens in the default reading mode after upgrade, ignoring the prior selection — reading_mode not carried from settings_prefs.

### Source enablement + active-tab preservation on upgrade  `P1` · Android
_Migration copies native AppPrefs keys repo_enabled_<API> (per-source enable booleans) and active_tab (selected Home source-tab int) into yami_settings; Home/Sources read the same keys._  
<sub>shared/src/androidMain/kotlin/me/manga/yamiapk/di/NativeSettingsMigration.android.kt:57; native-app/.../ActiveRepoProvider.kt:29-52</sub>

**Steps:**
1. On the OLD native build, go to source/repo settings and DISABLE at least one specific source (e.g. toggle one off) and leave others on; on Home, switch to a non-first source tab (active_tab != 0). Force-stop.
2. (Simulation alternative: in AppPrefs.xml add e.g. <boolean name="repo_enabled_azora" value="false"/> and <int name="active_tab" value="2"/>; remove yami_settings.xml to force migration.)
3. Install the new rework build in-place (adb install -r).
4. Cold-launch; open the Sources/repo-settings screen and the Home source-tab strip.

**Expected:** The source you disabled in native stays disabled (not present/greyed in the active source list); Home opens on the same active source tab index as before the upgrade.  
**Regression looks like:** All sources are re-enabled to default, or Home resets to the first tab — repo_enabled_*/active_tab not migrated from native AppPrefs.

### Migration runs exactly once and never clobbers rework-written values  `P1` · Android
_migrateNativeSettingsIfNeeded sets native_settings_migrated=true in yami_settings on completion (even on partial failure) and only copies a key when the rework store does not already contain it (target.contains(key) guard)._  
<sub>shared/src/androidMain/kotlin/me/manga/yamiapk/di/NativeSettingsMigration.android.kt:40,52,63,85</sub>

**Steps:**
1. Complete the upgrade test above so settings are migrated.
2. In the upgraded app, CHANGE a migrated setting to a new value (e.g. flip Pure Black off, switch language back to English). Force-stop.
3. Cold-launch the app a second and third time.
4. Optional deep check: adb shell run-as me.manga.kira cat /data/data/me.manga.kira/shared_prefs/yami_settings.xml and confirm a native_settings_migrated=true entry plus your edited values.

**Expected:** Your post-upgrade edits stick across relaunches — the migration does NOT re-run and does NOT overwrite values the rework already wrote. yami_settings.xml contains native_settings_migrated=true.  
**Regression looks like:** After a relaunch your edited settings revert to the old native values (migration re-ran and clobbered), or settings reset entirely — indicating the done-flag/contains guard isn't working.

### Clean install (no native data) shows sane defaults + onboarding once  `P1` · Android
_On a device with no prior native AppPrefs/settings_prefs, both copy helpers early-return (empty stores) and the done-flag is still set; first_launch defaults to true so onboarding shows._  
<sub>shared/src/androidMain/kotlin/me/manga/yamiapk/di/NativeSettingsMigration.android.kt:60,81 (isEmpty early returns); App.kt:578-579</sub>

**Steps:**
1. Fully UNINSTALL any prior app (adb uninstall me.manga.kira) so no AppPrefs.xml, no settings_prefs DataStore, and no manga_database file remain.
2. Install ONLY the new rework build fresh (adb install <apk>).
3. Cold-launch the app.
4. Tap through the Welcome → Theme → Sources onboarding to completion into the Library.
5. Force-stop and cold-launch a second time.

**Expected:** First launch shows the Welcome onboarding flow exactly once (first_launch default true). Theme=follow-system default, default language, default reading mode, empty Library. Second launch goes straight to Library (onboarding does not repeat). No crash from the empty-store migration path.  
**Regression looks like:** App crashes on a fresh install (migration NPE/runBlocking failure on empty stores), OR onboarding repeats on the second launch, OR defaults look wrong (e.g. starts dark/pure-black with no user choice).

## P2 (15)

### Offline pull-to-refresh feedback (Library) — verify behaviour  `P2` · Android
_Library refresh is enqueued with a CONNECTED network constraint; when offline the work stays ENQUEUED (maps to Idle) so nothing visible happens until connectivity returns and the deferred worker fires. Related to the setForeground background-start change above._  
<sub>app/src/main/java/me/manga/yamiapk/work/LibraryRefreshWorker.kt</sub>

**Steps:**
1. Put the device in airplane mode (fully offline).
2. Open the Library and pull to refresh.
3. Observe the spinner / any feedback.
4. Re-enable connectivity and observe whether the deferred refresh then runs and updates the Library.

**Expected:** Refresh while offline does not crash; the deferred CONNECTED refresh fires once connectivity returns and the Library updates then (matching the now-foreground-safe worker). Note the known UX gap: offline gives little immediate feedback.  
**Regression looks like:** A crash on pull-to-refresh; or after connectivity returns the deferred refresh never runs or fails (regression of the foreground-start fix).

### Notification permission re-probed on return from system settings  `P2` · Android
_The Android notification-permission requester now re-probes POST_NOTIFICATIONS on ON_RESUME via a lifecycle observer, so a permission granted in system Settings is reflected when the user returns, without recreating the screen._  
<sub>composeApp/src/androidMain/kotlin/me/manga/yamiapk/core/platform/RememberNotificationPermissionRequester.android.kt</sub>

**Steps:**
1. On Android 13+, with notifications previously DENIED, navigate to the Theme/onboarding screen where the permission state is shown (Grant-permission affordance visible).
2. Use the in-app path to open App settings (or manually open Android Settings → Apps → Yami → Notifications) and ENABLE notifications.
3. Press back to return to the app screen without recreating it.

**Expected:** On returning, the screen reflects that permission is now granted (the Grant-permission affordance disappears / hasNotificationPermission flips true) without needing to leave and re-enter the screen.  
**Regression looks like:** The screen still shows permission as denied after enabling it in system settings and returning, until the screen is recreated.

### Chapter-available notification cover fetch robustness  `P2` · Android
_ChapterNotificationHelper now uses a SupervisorJob scope, a 10s connect/read timeout on the cover-image fetch, closes the HttpURLConnection in a finally, and wraps the whole notify in try/catch so a failed cover fetch can't crash or block the notification._  
<sub>app/src/main/java/me/manga/yamiapk/core/util/notification/ChapterNotificationHelper.kt</sub>

**Steps:**
1. Add manga to the Library whose new-chapter notification would carry a cover URL.
2. Trigger a library refresh that finds at least one new chapter (so a chapter-available notification posts).
3. Best-effort: include at least one manga whose cover URL is slow/unreachable (e.g. by being offline mid-fetch or a dead host) to exercise the timeout/error path.
4. Observe the posted notifications.

**Expected:** The 'New chapter available' notification posts with title/body even if the cover image fails to load (it just shows without a large icon); a slow/dead cover host times out at ~10s rather than hanging; no crash.  
**Regression looks like:** The notification fails to post when the cover URL is slow/unreachable, the app hangs/ANRs fetching a cover, or a crash occurs from one bad cover fetch (no SupervisorJob isolation).

### Encrypted secure-storage resilience after backup restore  `P2` · Android
_AndroidSecureStorage.get() now catches GeneralSecurityException/SecurityException/IOException (corrupted keyset, e.g. after an auto-backup restore brought back prefs without the Keystore master key) and returns null instead of throwing; backup_rules.xml/data_extraction_rules.xml now exclude yami_settings.xml (cf_clearance cookies/UA) from cloud backup and device transfer._  
<sub>platform/src/androidMain/kotlin/me/manga/yamiapk/platform/storage/AndroidSecureStorage.kt, app/src/main/res/xml/backup_rules.xml, app/src/main/res/xml/data_extraction_rules.xml</sub>

**Steps:**
1. Install the app, use it enough to write secure storage (e.g. complete first-open so the in-app review 'first_open_time' is stored).
2. Trigger an auto-backup (adb shell bmgr backupnow), then uninstall and reinstall / restore (adb shell bmgr restore) onto a state where the Keystore master key is gone (clean device or after wipe).
3. Cold-launch the app and use it normally.
4. Separately: confirm via backup inspection that yami_settings.xml is excluded from the backup set.

**Expected:** App launches and runs normally after a restore that corrupted the encrypted keyset — secure-storage reads return null gracefully (no crash). Sessions-bound data (cf_clearance/UA in yami_settings.xml) is not carried across devices via cloud backup or device transfer.  
**Regression looks like:** App crashes at launch or when first reading secure storage after a restore (uncaught GeneralSecurityException/SecurityException); or restored cf_clearance cookies cause immediate repeated 403s on a new device.

### Details — multi-select download skips already-downloaded chapters and gates offline  `P2` · Android
_onDownloadSelected now filters out already-downloaded chapters before enqueuing (so a range covering downloaded chapters doesn't demote completed rows to QUEUED and re-fetch) and applies the same offline gate as the single-chapter path._  
<sub>presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/details/DetailsViewModel.kt (onDownloadSelected)</sub>

**Steps:**
1. In Details for an in-library manga, download a couple of chapters to completion.
2. Multi-select a range that includes both those already-downloaded chapters and some not-yet-downloaded ones, then tap download.
3. Watch the download queue/badges for the already-completed chapters.
4. Separately, go offline and try a multi-select download.

**Expected:** Only the not-yet-downloaded chapters enqueue; the already-completed chapters stay 'downloaded' (not re-queued/re-fetched). Offline, a 'no connectivity' message is shown and nothing enqueues.  
**Regression looks like:** Already-downloaded chapters get re-queued and re-downloaded, or a multi-select download silently enqueues while offline with no feedback.

### Cloudflare solver capability gate on Desktop (Windows/Linux vs macOS)  `P2` · Desktop
_The Cloudflare solver callback is now gated on isEmbeddedWebViewAvailable() — on Windows/Linux it navigates to the KCEF WebView as before; on Desktop where KCEF is unavailable it is a no-op and the error pane (with browser fallback) is shown instead of stranding the user on a dead placeholder._  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/CloudflareChallengeSolver.kt; WebViewCapability.desktop.kt</sub>

**Steps:**
1. On Windows or Linux desktop (KCEF available), trigger a 403/Cloudflare challenge from Details or Reader.
2. Confirm the KCEF WebView opens, solve the challenge, return, and confirm the auto-retry re-fetches.
3. If KCEF is unavailable on the host, confirm the solver does not navigate to a dead screen.

**Expected:** Windows/Linux desktop with KCEF: solver opens the WebView and the auto-retry works on return. Where KCEF is unavailable: the error pane (with open-in-browser fallback) is shown, no dead WebView placeholder.  
**Regression looks like:** On Windows/Linux the solver doesn't open the WebView at all, OR where KCEF is unavailable the app navigates into a non-functional blank WebView placeholder repeatedly.

### Language picker back affordance (rework Language screen)  `P2` · Desktop
_Rework Language picker gained a top-bar back affordance wired to safePopBackStack() (was previously no nav callback)_  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/LanguageReworkScreenRoute.kt:125</sub>

**Steps:**
1. On Desktop open Settings -> Language
2. Tap the back arrow in the top bar

**Expected:** Back arrow returns to Settings; Desktop users are not stranded on the Language picker  
**Regression looks like:** No back affordance / back does nothing, trapping the user on the Language screen

### Sources 'Request source' social-media links open externally  `P2` · all
_Request-Source dialog social row now forwards each brand URL to the platform IntentLauncher (onOpenUrl wired)_  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/SourcesScreenRoute.kt (onOpenUrl)</sub>

**Steps:**
1. Open the Sources screen and open the 'Request source' dialog
2. Tap each social-media brand icon in the dialog

**Expected:** Each tap opens the corresponding URL in the system browser/app  
**Regression looks like:** Tapping a social icon does nothing (no external launch)

### Slow tall-page image loads not aborted mid-stream (Reader)  `P2` · iOS+Desktop
_iOS/Desktop Coil HttpClient now uses 30s connect / 60s socket timeouts with NO whole-request ceiling (was the engine default 15s request timeout)_  
<sub>composeApp/src/{iosMain,desktopMain}/.../images/PlatformNetworkFetcher.{ios,desktop}.kt (HttpTimeout)</sub>

**Steps:**
1. On iOS or Desktop, open the Reader on a source serving very tall/large webtoon pages over a slow connection (throttle the network if possible)
2. Let a large page stream in while watching the progress indicator

**Expected:** A slow-but-progressing page finishes loading rather than being aborted at ~15s; the per-page progress indicator advances  
**Regression looks like:** Large pages fail/abort partway with a timeout on slow connections even though bytes were still arriving

### iOS HTTP (insecure) image load only for raijinscan.co  `P2` · iOS
_Info.plist replaced blanket NSAllowsArbitraryLoads with a single NSExceptionDomains entry for raijinscan.co_  
<sub>iosApp/iosApp/Info.plist:46-58 (NSExceptionDomains raijinscan.co)</sub>

**Steps:**
1. On iOS open the Raijinscan source and browse covers / open a chapter so its images (served over HTTP on raijinscan.co subdomains) load
2. Also open an unrelated HTTPS source and confirm normal image loading

**Expected:** Raijinscan images (and its subdomains) load over insecure HTTP; all other sources continue to load normally over HTTPS  
**Regression looks like:** Raijinscan images fail to load on iOS (ATS blocks the cleartext request), or unrelated insecure requests that used to work now silently behave differently

### Welcome 'Get started' onboarding advance  `P2` · all
_Welcome onboarding now uses safeNavigate(Screen.Theme) (double-tap guarded) instead of raw navigate_  
<sub>composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/WelcomeScreenRoute.kt:135</sub>

**Steps:**
1. Fresh install so onboarding starts at Welcome
2. Rapidly double-tap the 'Get started' button

**Expected:** Exactly one navigation to the Theme step occurs; no duplicate Theme entries are pushed onto the back stack  
**Regression looks like:** Double-tapping pushes two Theme screens (back from Theme lands on another Theme instead of exiting onboarding/Welcome)

### Downloads — failed/cancelled reason shows localized 'Cancelled by user' (iOS/Desktop)  `P2` · iOS+Desktop
_The iOS/Desktop engine now persists a locale-independent sentinel ('__cancelled_by_user__') as the cancel reason, and the Downloads screen maps it to the localized pfix_download_cancelled_by_user string at render time (was: hardcoded English 'Cancelled by user' shown verbatim on localized devices)._  
<sub>domain/.../model/downloads/DownloadedChapter.kt (CANCELLED_BY_USER_SENTINEL); shared/.../CoroutineDownloadRepositoryImpl.kt (CANCELLED_BY_USER); ui/.../downloads/DownloadsScreen.kt (statusLabel)</sub>

**Steps:**
1. Set the device/app language to a non-English locale (e.g. Arabic).
2. On iOS or Desktop, start a download and cancel it (per-chapter cancel or Stop).
3. Open the Downloads screen Failed tab and read the failure reason for that row.

**Expected:** The Failed reason renders in the current app language (localized 'cancelled by user' text), and follows the app locale if it changes — never the raw English string and never the raw '__cancelled_by_user__' sentinel.  
**Regression looks like:** The Failed row shows literal English 'Cancelled by user' on a localized device, or shows the raw '__cancelled_by_user__' token.

### Share-page PNG encode no longer janks the UI  `P2` · Android+iOS+Desktop
_The PNG encode of the captured page now runs on Dispatchers.Default inside the share coroutine instead of synchronously on the main thread, so tapping Share on a tall multi-megapixel page no longer freezes the UI._  

**Steps:**
1. Open a chapter with very tall, high-resolution pages (a webtoon strip), show the chrome.
2. Tap Share and watch for any UI freeze between the tap and the share sheet appearing.

**Expected:** Tapping Share is responsive; the share sheet appears without a visible multi-hundred-ms freeze of the reader.  
**Regression looks like:** The reader UI freezes/stutters for a noticeable beat after tapping Share before the sheet appears.

### Reader top bar overlays page edge-to-edge (no viewport resize on chrome toggle)  `P2` · Android+iOS
_The top bar was moved out of the Scaffold topBar slot into a top-aligned overlay inside the page Box (translucent 0.8 alpha, no own status-bar inset), so the page draws under it and toggling/3s auto-hide no longer resizes the page area._  

**Steps:**
1. Open a chapter, tap to show chrome, then tap to hide it; also wait ~3 seconds for the auto-hide.
2. Watch the page area while the top bar fades/slides in and out.
3. Check the top-bar title (manga name) and subtitle (chapter number) are not clipped by the status bar / notch.

**Expected:** The page stays the same size when the top bar appears/disappears (it overlays translucently over the page, no layout jump); the title text sits below the status bar without double-inset/clipping.  
**Regression looks like:** The page area visibly grows/shrinks (jumps) each time chrome toggles or auto-hides, or the top-bar title is clipped under the status bar / pushed down by a doubled inset.

### Captured Cloudflare headers preserved on upgrade (no re-solve)  `P2` · Android
_Migration copies native settings_prefs DataStore key headers_map_json (captured per-source cf_clearance/Cookie/User-Agent headers) into yami_settings, so previously-solved Cloudflare sources keep working without re-running the WebView solver._  
<sub>shared/src/androidMain/kotlin/me/manga/yamiapk/di/NativeSettingsMigration.android.kt:78; native-app/.../DataStoreHelper.kt:103 (headers_map_json)</sub>

**Steps:**
1. On the OLD native build, browse a Cloudflare-protected source until the in-app WebView solves the challenge and content loads (so cf_clearance/Cookie headers get captured into headers_map_json). Force-stop. (settings_prefs DataStore — true native build required.)
2. Install the new rework build in-place (adb install -r).
3. Cold-launch and immediately open the same Cloudflare-protected source's listing / a manga detail page.

**Expected:** Content loads without re-triggering the Cloudflare WebView challenge (the migrated headers are reused). Worst case it re-solves silently — acceptable but indicates headers were not migrated.  
**Regression looks like:** Every Cloudflare source forces the WebView challenge again on first use after upgrade (captured headers lost). Note: a separate audit finding (code-ap-3) reports the rework now writes per-API hashed header keys, not the aggregate headers_map_json — so the migrated aggregate may not be read back; if re-solve always happens, log it but verify against expected before calling it a regression.

---

## Config-backed sources — generic-only on-device verification (owner-run)

After the legacy-isolation hardening (config-backed sources are generic-ONLY; the legacy scraper is never executed for them), verify each of the **12 config-backed sources** end-to-end on a real device/emulator. Build first: `./gradlew :app:assembleDebug` (Android) / `./gradlew :desktopApp:run` (Desktop) / Xcode `iosApp` (iOS). Sources: **Azora, Mangamello, Mangamello Plus, SwatManga, Lekmanga, Team X, DilarV2, 3asq, Demonicscans, Mangabuddy, Zazamanga, Tapas.**

### Per-source matrix — Home / Search / Details / Chapter Pages / Downloads  `P0` · all
_For EACH of the 12 sources, exercise all five surfaces and confirm content comes through the generic/config-driven engine._

**Steps (per source):**
1. **Home** — select the source as the active tab; confirm the home feed loads (covers, titles, recent-chapter chips).
2. **Search** — run a plain-text search; confirm results appear. Also confirm the multi-source "all" search shows this source's section.
3. **Details** — open a manga; confirm title/cover/description/genres and the full chapter list load.
4. **Chapter Pages** — open a chapter in the reader; confirm all pages render in order (no blanks/gaps), with correct headers (no 403/hot-link block).
5. **Downloads** — download a chapter; confirm it completes and reads offline (loose pages or CBZ per setting).

**Expected:** All five work for all 12 sources. Page order and images match the site. (For CF-gated sources — Lekmanga, Team X, 3asq, Demonicscans — the WebView solver may run once to capture headers, then downloads/pages work.)  
**Regression looks like:** A blank/garbled feed, missing chapters, a blank/partial chapter, or a download that produces no/wrong images.

### Generic failure surfaces as a clear error (no silent legacy)  `P0` · all
_A config-backed source that fails on the generic engine must show a clear error/empty-state — it must NOT silently serve old-scraper content._

**Steps:**
1. Force a generic failure for a config-backed source (e.g. enable airplane mode, or pick a source whose site is temporarily unreachable) and open Home / Details / a chapter / start a download.

**Expected:** A clear failure/error or empty state (retryable). Downloads show the chapter as FAILED.  
**Regression looks like:** Content still appears via the legacy scraper (silent fallback) — this is exactly what the hardening removed; report it if seen.
