# Manual Decisions — items that need YOU

These are the review findings that were **not** auto-fixed because they require your decision, external configuration, new translations, or edits to read-only code. Auto-fixable items have already been applied (see `EXHAUSTIVE_REVIEW_REPORT.md`). Firebase/Firestore-rules items are **excluded per your instruction** (listed at the very end for reference only).

**317 items need you**, grouped below. Severity in brackets. Each has my recommendation.

## E. External configuration / infra (only you can do) (4)

- **[minor]** `app/build.gradle.kts:62` — versionCode 35 / versionName 1.0.35 are identical to the native app being replaced — the KMP build cannot ship as an update to the same applicationId
  - **Decision:** What versionCode/versionName should the KMP :app ship as (it must exceed the native app's 35/1.0.35), and do you want it centralized across Android/Desktop/iOS?
  - *Recommendation:* Bump versionCode/versionName above the native app's shipped values before any release build; consider deriving them from a single gradle.properties entry shared with desktopAppVersion (desktopApp/build.gradle.kts:55) and
- **[minor]** `app/build.gradle.kts:86` — The documented release path-validation flow (-PallowPlaceholderGoogleServices=true) still hard-fails without a production keystore — release signing has no fallback
  - **Decision:** Should release builds fall back to debug signing when the production keystore is absent (build-path validation only), or is the keystore expected to always be supplied via env?
  - *Recommendation:* Fall back to the debug signing config when the release keystore is absent, e.g. `signingConfig = if (signingConfigs.getByName("release").storeFile?.exists() == true) signingConfigs.getByName("release") else signingConfig
- **[minor]** `core/build.gradle.kts:36` — Unused iOS framework declarations in library modules (:core 'core', :data 'data')
  - **Decision:** OK to delete the unused binaries.framework{} blocks from all six library modules (:core/:data/:domain/:presentation/:platform/:ui) once an iOS compile + Xcode build confirms nothing links the standalone frameworks?
  - *Recommendation:* Delete the binaries.framework block from :core and :data (keep the iosArm64()/iosSimulatorArm64() targets), then run the iOS compile gate (:composeApp:compileKotlinIosSimulatorArm64) and an Xcode build to confirm nothing
- **[minor]** `iosApp/iosApp/Info.plist:49` — NSAllowsArbitraryLoads=true disables App Transport Security for ALL hosts — far broader than the Android parity config (one cleartext domain)
  - **Decision:** Replace the global NSAllowsArbitraryLoads with per-host NSExceptionDomains — which scraped sources (beyond raijinscan.co, mirroring Android) actually need cleartext/weak TLS?
  - *Recommendation:* Replace the blanket flag with NSExceptionDomains entries for the specific hosts that need cleartext/weak TLS (at minimum raijinscan.co, mirroring Android), or inventory which of the ~50 sources are HTTP-only before decid

## B. Diverge from native? (parity-vs-better tradeoffs) (30)
_Native does X; the safer/better behaviour is Y. Keep parity (X) or improve (Y)?_

- **[major]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/core/webview/WebViewUrlSandbox.kt:51` — Main-frame host pin is bypassable via bare suffix match; the two correct clauses are dead code
  - **Decision:** Tighten the WebView main-frame host pin to exact-host-or-dot-boundary-subdomain (drop the bare endsWith(host) clause), accepting divergence from the verbatim-legacy native parity (native uses bare endsWith), to close the suffix-spoof bypass?
  - *Recommendation:* Drop the trailing `|| targetHost.endsWith(host)` so only exact host or dot-boundary subdomains pass: `return targetHost == host || targetHost.endsWith(".$host")`. If strict native parity must be kept, at minimum document
- **[major]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/common/componants/BottomNavigationBar.kt:138` — Bottom-nav popUpTo(findStartDestination()) is a no-op for the whole first session after onboarding — tab switches stack up unboundedly and back walks through every visited tab
  - **Decision:** The first-session unbounded tab stack is exact native parity (native uses the identical findStartDestination() popUpTo with a conditional Welcome start). Do you want to diverge from native to fix the back-stack growth (e.g. popUpTo(Screen.Library), or always start at Library and redirect to Welcome)?
  - *Recommendation:* Make the post-onboarding stack root coincide with the popUpTo anchor. Simplest: in BottomNavigationBar use popUpTo(Screen.Library.route) (Library is the root both in normal sessions, as start destination, and in the firs
- **[major]** `shared/src/androidMain/kotlin/me/manga/yamiapk/core/cbz/OptimizedCbzManager.kt:321` — Pages whose decode fails are silently dropped from the CBZ and their source files deleted
  - **Decision:** When a manga page fails to decode during download, should createCbzParallel fail the whole chapter, keep the source bytes, or emit a partial-success state instead of silently dropping the page and deleting its file (deviating from native)?
  - *Recommendation:* Fail createCbzParallel (throw) when any page decodes to an empty chunk list, or at minimum skip deleting the originals for failed pages and propagate a partial-failure signal to ChapterDownloadService.
- **[major]** `shared/src/androidMain/kotlin/me/manga/yamiapk/presentation/features/download/domain/ChapterDownloadService.kt:210` — OOM fallback is unreachable: `e is OutOfMemoryError` inside `catch (e: Exception)` is always false
  - **Decision:** Make the OOM loose-files fallback actually fire in ChapterDownloadService (deviating from native's identical dead test): widen `catch (e: Exception)` to `catch (e: Throwable)`, or add a dedicated `catch (e: OutOfMemoryError)` branch, in both streaming and batch paths?
  - *Recommendation:* Catch `Throwable` (rethrowing CancellationException first) around the createCbzParallel call, or add an explicit `catch (e: OutOfMemoryError)` branch that performs the loose-files fallback, in both downloadChapterStreami
- **[major]** `shared/src/androidMain/kotlin/me/manga/yamiapk/presentation/features/download/domain/ChapterDownloadService.kt:167` — Streaming download marks chapter Complete with silently missing pages
  - **Decision:** Should the streaming download treat a partial page failure (paths.size < imageUrls.size) as a failure / surface a partial-success warning, instead of marking the chapter Complete with pages silently missing (deviating from native)?
  - *Recommendation:* Treat `paths.size < imageUrls.size` as a failure (or emit a distinct partial-success state that the worker maps to FAILED/with-warning) instead of unconditional Complete.
- **[major]** `shared/src/androidMain/kotlin/me/manga/yamiapk/presentation/features/download/ui/test2/DownloadWorkerV2.kt:156` — doWork catch leaves the current row stuck in RUNNING and stalls the rest of the queue; also converts cancellation into Result.failure()
  - **Decision:** In DownloadWorkerV2.doWork's catch, mark the current chapter FAILED (and rethrow CancellationException) — accepting that FAILED rows no longer auto-resume via the reconcile path — vs keeping native's current loop-on-persistent-failure behaviour?
  - *Recommendation:* In the catch, rethrow CancellationException; otherwise mark `currentChapter` FAILED via chapterDownloadDao.updateFailure(chapter.chapterId, e.message) before returning Result.failure().
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/domain/repos/MangaRepository.kt:66` — removeManga keyed by title only — cross-source title collision can delete the wrong manga and its files
  - **Decision:** Change un-heart/removeManga to key on (api, title) instead of title alone to prevent cross-source title-collision data loss, deviating from native's title-only behaviour?
  - *Recommendation:* Owner decision (parity): key removal on (api, title) — a removeManga(api, title) overload backed by MangaDao.getIdByApiAndTitle (which already exists and is LIVE) — and update HomeViewModel's call site.
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/complaint/repository/ComplaintFirestoreRestDataSource.kt:97` — Fetch failures coerced to emptyList — complaint screens show empty state instead of an error
  - **Decision:** Should complaint read failures (offline/timeout/5xx) surface an error+retry state instead of an empty list — restoring native's user-path throw while leaving the admin path swallowing — and via which approach (let getComplaintsByUser throw vs a separate errors-out variant)?
  - *Recommendation:* Let read failures throw (like the write methods) and let the rework runCatchingCancellable map them to Result.failure so the UI can render error + retry; or add an errors-out variant consumed by the rework impls.
- **[minor]** `app/src/main/java/me/manga/yamiapk/MyApp.kt:169` — Unconditional MobileAds.initialize() at process start makes MainActivity's UMP canRequestAds() gate dead code
  - **Decision:** Remove MyApp's unconditional MobileAds.initialize() so the Mobile Ads SDK (and IronSource adapter) only initialize after UMP canRequestAds() is true — diverging from native for GDPR compliance?
  - *Recommendation:* Delete the MobileAds.initialize call from MyApp.onCreate and let the consent-gated init in MainActivity.startConsentFlow() be the only initialization path (single-line removal; ads are currently dormant so there is no be
- **[minor]** `app/src/main/java/me/manga/yamiapk/firebase_cores/messaging/MyFirebaseMessagingService.kt:62` — Push notifications have no contentIntent — tapping them neither opens the app nor dismisses them; no deep-link surface exists at all
  - **Decision:** Add a PendingIntent.getActivity(MainActivity, FLAG_IMMUTABLE) contentIntent so tapping a push opens the app (e.g. at Library), diverging from native which has none?
  - *Recommendation:* Add a PendingIntent.getActivity(MainActivity, FLAG_IMMUTABLE) contentIntent so the tap opens the app at the start destination (Library). Deviates from native; needs owner sign-off.
- **[minor]** `app/src/main/res/xml/backup_rules.xml:8` — backup_rules.xml / data_extraction_rules.xml are unedited IDE templates, so allowBackup=true backs up everything including cf_clearance cookies
  - **Decision:** Edit backup_rules.xml / data_extraction_rules.xml to exclude the DataStore prefs holding cf_clearance cookies + UA (and/or the Room DB) from cloud backup and device-to-device transfer, diverging from native (which uses the unedited templates with full backup)?
  - *Recommendation:* Owner decision: either exclude the DataStore/prefs files holding cf_clearance cookies from cloud-backup/device-transfer rules, or document that full backup is intended. Keep behavior identical to native if strict parity 
- **[minor]** `composeApp/src/androidMain/kotlin/me/manga/yamiapk/presentation/common/componants/images/PlatformNetworkFetcher.android.kt:95` — Hostname verification disabled for *.s3.wasabisys.com on the Coil image client
  - **Decision:** Tighten the *.s3.wasabisys.com hostname-verification bypass (accept only the known *.wasabisys.com SAN-mismatch shape, or pin the Wasabi CA) instead of blanket-accepting any CA-valid cert — diverging from native and risking image loads for those hosts?
  - *Recommendation:* Tighten the bypass: instead of blanket-accepting, verify the presented cert's SANs match *.wasabisys.com (i.e. accept the known wildcard-mismatch shape only), or pin the Wasabi CA for those hosts. Keep behavior change be
- **[minor]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt:424` — iOS: layout direction flips immediately on language pick while strings only change after relaunch — mixed RTL-layout/LTR-text session
  - **Decision:** On iOS (no live locale switch), should the explicit-language RTL flip wait until relaunch (gate on isLiveLocaleSwitchSupported) so layout and strings change together, or keep flipping direction instantly? No native baseline; the gated fix's post-relaunch RTL assumption needs verification.
  - *Recommendation:* Gate the explicit-selection direction override on LocalAppLocale.isLiveLocaleSwitchSupported (keep the platform's current direction when the locale can't move mid-session, so direction and strings flip together on next l
- **[minor]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/core/webview/WebViewUrlSandbox.kt:51` — WebView host sandbox allows suffix-spoofed hosts via bare endsWith(host)
  - **Decision:** Same as code-ab-1: drop the WebView sandbox's bare endsWith(host) clause (stricter than the verbatim-native rule) to block suffix-spoofed hosts like evil-lekmanga.net?
  - *Recommendation:* Remove the trailing '|| targetHost.endsWith(host)' clause (owner decision — documented as verbatim legacy parity).
- **[minor]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/LibraryRepositoryImpl.kt:197` — persistNewChaptersAndNotify resolves each new chapter id with a per-chapter url-only query, outside any transaction
  - **Decision:** Accept a parity divergence and add mangaId-scoped batch id-resolution + a shared @Transaction wrapping chapter+notification inserts, or keep the native-parity url-only/non-transactional path?
  - *Recommendation:* Resolve ids in one query scoped by mangaId (e.g. `SELECT id, url FROM saved_chapters WHERE mangaId = :mangaId AND url IN (:urls)`), build the notifications from that map, and wrap insert-chapters + insert-notifications i
- **[minor]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/LibraryRepositoryImpl.kt:281` — Removing a manga from the library never cancels its in-flight download — on Android the running chapter completes anyway and writes a permanently orphaned CBZ into the just-purged manga directory
  - **Decision:** On library removal, should we cancel a manga's in-flight downloads before purging (cancelARunningChapter pre-purge, or re-delete files after the engine confirms it stopped), diverging from native's fire-and-forget posture?
  - *Recommendation:* Before purging, look up chapter_downloads rows for the mangaId in RUNNING/COMPRESSING and route them through legacy cancelARunningChapter (which cancels the Android worker / iOS-Desktop active job and deletes partial fil
- **[minor]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/UpdatesRepositoryImpl.kt:193` — groupByDate drops notifications dated exactly 7 days ago (and future-dated rows) from observeUpdates — they vanish from the Updates screen for a day
  - **Decision:** OK to deviate from native's strict date bucketing so olderList is the complement (<= lastWeek + clamp future dates), guaranteeing every notifications row lands in exactly one bucket and never vanishes from the Updates screen for a day?
  - *Recommendation:* Make olderList the complement bucket (e.g. `filter { it.notificationDate <= lastWeek }` plus clamp future dates into todayList, or build olderList as `notifications - todayList - yesterdayList - lastWeekList`) so every r
- **[minor]** `platform/src/iosMain/kotlin/me/manga/yamiapk/platform/image/IosScreenshotProvider.kt:55` — UIKit main-thread requirement unenforced (siblings hop dispatchers, iOS doesn't) and deprecated keyWindow can be nil — share silently no-ops
  - **Decision:** Resolve the iOS share/save presenter via connectedScenes (UIWindowScene key window) with a keyWindow fallback, hop both methods to Dispatchers.Main, and log when no root VC is found? Scene-based resolution needs on-device verification before merge.
  - *Recommendation:* Hop to the main dispatcher inside both methods (e.g. `withContext(Dispatchers.Main)`), resolve the presenter via `connectedScenes` key window with keyWindow fallback, and log a warning when no root VC is found so the sil
- **[minor]** `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/reader/ReaderViewModel.kt:723` — Reader Cloudflare auto-route has no WebView-capability gate — macOS users are auto-navigated (up to 2x) into a dead WebView screen
  - **Decision:** Gate the Reader's Cloudflare auto-route on a (new) :platform embedded-WebView-availability flag, falling back to ShowError on macOS — sharing the same facade/placement decision as the Details gate (xcut-ux-5)?
  - *Recommendation:* Gate the auto-route on a WebView-capability flag (e.g. a :platform facade or the existing KcefState) injected at the route-adapter level: when the platform has no working WebView, fall through to the plain ShowError path
- **[minor]** `shared/src/androidMain/kotlin/me/manga/yamiapk/core/cbz/CbzManager.kt:109` — createCbzFromFilesWithSplitting: decode-failed files leak on disk while compress-failed pages are deleted despite being lost
  - **Decision:** On the CBZ migration path, should a decode-failed page be cleaned up vs left on disk, and should a compress-failed page's source be preserved instead of deleted (or the whole migration failed) — deviating from native's identical behaviour?
  - *Recommendation:* On compress failure, skip adding the file to filesToDelete (preserve the source page); decide explicitly whether undecodable files should be cleaned up or surfaced as a migration failure.
- **[minor]** `shared/src/androidMain/kotlin/me/manga/yamiapk/presentation/features/download/ui/test2/DownloadWorkerV2.kt:152` — setForegroundAsync re-posts an identical foreground notification on every collected state; completedCount/errorCount are write-only
  - **Decision:** Reduce DownloadWorkerV2's setForegroundAsync churn (call once / only when content changes) and drop the write-only completedCount/errorCount — acceptable to deviate from the native worker's behaviour here?
  - *Recommendation:* Call setForegroundAsync once when the worker starts (the notification content is static), or only when its content actually changes; drop the dead counters or wire them into the overall notification text.
- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/library/domain/LibraryRepository.kt:117` — insertChapterList swallows all exceptions and returns emptyList — DB write failures indistinguishable from 'no new chapters'
  - **Decision:** Add CancellationException rethrow + Kermit error log (and optionally a failure marker for the refresh worker) to insertChapterList's deliberately-silent catch, changing the parity-mirrored no-log guard?
  - *Recommendation:* Rethrow CancellationException and log the swallowed exception (Logger.e); optionally surface a failure marker to the refresh worker.
- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/repo_settings/domain/SourcesRepository.kt:224` — updateActiveIndex clamps to repos.size — off-by-one and wrong list
  - **Decision:** updateActiveIndex's `coerceIn(0, repos.size)` is native-verbatim but lets the persisted index reach repos.size (out of range) and bounds against the full repo set, not the enabled sublist actually addressed. Tighten the clamp (e.g. validate against the enabled list / coerce to size-1), deviating from native?
  - *Recommendation:* Clamp against the enabled list length minus 1 (or at least `(repos.size - 1).coerceAtLeast(0)`), and/or validate the restored index against the enabled list when it is consumed.
- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/repo_settings/domain/SourcesRepository.kt:122` — activeRepo duplicates activeRepoFlow but snapshots _activeIndex.value — stale for any sustained collector
  - **Decision:** The native-verbatim `activeRepo` flow is a near-duplicate of activeRepoFlow that only works because all four consumers are one-shot (.first()). Delete it and repoint the four call sites at activeRepoFlow (or reimplement as activeRepoFlow), deviating from native?
  - *Recommendation:* Delete activeRepo and point the four `.first()` call sites at activeRepoFlow (identical one-shot semantics), or implement activeRepo as activeRepoFlow.
- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/webview/ui/viewmodel/WebViewViewModel.kt:55` — saveHeaders silently swallows Cloudflare header-persistence failures (empty catch, CE included)
  - **Decision:** saveHeaders' empty catch (native-verbatim) swallows Cloudflare header-persistence failures and CancellationException with no log, and unknown apis silently resolve to EmptyMangaRepository. Add a CE rethrow + warn-level log (and use getOrRepoByName to log unknown apis), deviating from native?
  - *Recommendation:* Rethrow CancellationException and log other failures at warn with the api name; optionally use getOrRepoByName(api) and log when the api is unknown.
- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/whatsnew/data/WhatsNewRemoteDataSource.kt:48` — fetchWhatsNewFeatures(languageCode) parameter is never used
  - **Decision:** Drop the never-used `languageCode` parameter from WhatsNewRemoteDataSource.fetchWhatsNewFeatures (cross-module signature change to its two call sites), or keep and document it as reserved — deviating from native which carries the same unused param?
  - *Recommendation:* Drop the parameter (updating the two call sites in WhatsNewViewModel and WhatsNewRepositoryImpl), or document why it is reserved. Cross-module signature change, so owner sign-off preferred.
- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/whatsnew/viewmodel/WhatsNewViewModel.kt:198` — shouldShowWhatsNew=true is published before isLoading=true, making the LibraryScreenRoute '!isLoading' gate racy; catches also eat CancellationException
  - **Decision:** Set _isLoading=true synchronously before publishing _shouldShowWhatsNew=true to close the LibraryScreenRoute `shouldShowWhatsNew && !isLoading` redirect race (and rethrow CancellationException in loadFeatures/getUserLanguageCode catches), deviating from native's ordering?
  - *Recommendation:* Set `_isLoading.value = true` synchronously before publishing `_shouldShowWhatsNew.value = true` (or make loadFeatures suspend and set isLoading before returning control); rethrow CancellationException in both catch bloc
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/about/AboutScreen.kt:563` — AboutScreen keeps a private duplicate of the shared YamiSocialMediaRow (row + button + 5 URL constants, ~150 lines)
  - **Decision:** Replace AboutScreen's private SocialMediaRow with the shared YamiSocialMediaRow, accepting the shared row's isPressed+150ms press animation over About's collectIsPressedAsState version (or reconcile both to the verified native press behavior first)?
  - *Recommendation:* Replace AboutScreen's private SocialMediaRow call with YamiSocialMediaRow(onOpenUrl = { url -> onIntent(AboutIntent.OnOpenUrl(url)) }) and delete the private composables + duplicate URL constants (keep PRIVACY_POLICY_URL
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/home/components/SourceTabsRow.kt:134` — Edit-sources icon button constrained to 36.dp — below the 48.dp minimum touch target
  - **Decision:** Enlarge the edit-sources touch target to 48.dp (improving accessibility but deviating from native's 36.dp), or keep native parity and accept the small target?
  - *Recommendation:* Drop the size override (keep 48.dp default) or constrain only the inner Icon to 20-24.dp while keeping the button at 48.dp.
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/reader/ReaderScreen.kt:1667` — Horizontal reading modes invert under RTL layout (Arabic UI): RIGHT_TO_LEFT pages left-to-right and vice versa
  - **Decision:** Horizontal reading modes invert under Arabic (RTL) UI — matches native, but the mode labels then describe the opposite direction for the primary audience; pin the reader pager to LTR (diverging from native) or keep native parity?
  - *Recommendation:* Wrap the reader's HorizontalPager (or the whole ReaderPageLayout horizontal branch) in CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { ... } so reverseLayout=true always means manga-style ri

## A. Behaviour / feature decisions (no single native answer) (46)
_How should it behave? Pick an option or say "match native"._

- **[major]** `app/src/main/java/me/manga/yamiapk/MainActivity.kt:83` — Flexible in-app update is started but can never complete: completeUpdate() has zero callers and DOWNLOADED is never handled
  - **Decision:** Extend AppUpdateClient with an install-state callback (or suspend awaitDownloaded()) wired to Play Core's InstallStateUpdatedListener, and call completeUpdate() on DOWNLOADED + on each onResume (mirroring native)? Confirm the SPI extension + the 3 platform actuals are the wanted shape.
  - *Recommendation:* Extend AppUpdateClient with an install-state callback (or a suspend awaitDownloaded()) wired to Play Core's InstallStateUpdatedListener; in MainActivity call completeUpdate() when state hits DOWNLOADED, and on each onRes
- **[major]** `app/src/main/java/me/manga/yamiapk/MyApp.kt:115` — CrashActivity is posted to the main looper inside the uncaught-exception handler, so it (almost) never actually launches
  - **Decision:** Should the uncaught handler launch CrashActivity synchronously from the crashing thread and terminate the process itself (and drop the duplicate CrashReporter.recordException to avoid triple-counting), or keep delegating to the Crashlytics/default handler and accept that the crash screen never shows?
  - *Recommendation:* Start CrashActivity synchronously from the crashing thread (the Intent already carries FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK), then terminate the process explicitly (Process.killProcess(Process.myPid()); exit
- **[major]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt:359` — Coil ImageLoader has no explicit diskCache — on iOS/Desktop the image cache (up to 250MB) lands in the system temp dir, invisible to Settings' cache size and never cleared by Clear Cache
  - **Decision:** Approve rooting Coil's disk cache under AppFileSystem.cacheDir/image_cache on all platforms (injecting AppFileSystem into the App.kt ImageLoader factory), accepting a one-time relocation/orphaning of the existing Android temp cache?
  - *Recommendation:* In the setSingletonImageLoaderFactory block, set an explicit disk cache rooted under the app's own cache dir on every platform, e.g. .diskCache { DiskCache.Builder().directory(appFileSystem.cacheDir / "image_cache").buil
- **[major]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/ThemeSelectionScreenRoute.kt:155` — Onboarding 'enable notifications' toast fires the moment the permission dialog opens, before the user has answered (native toasts only on actual denial)
  - **Decision:** How should NotificationPermissionRequester report a completed denial (suspend request() result vs completion flow vs callback), and should we also wire the native openAppSettings() redirect on permanent denial? Requires extending the :shared interface + 3 actuals.
  - *Recommendation:* Surface the denial from the request result instead of inferring it from state: extend NotificationPermissionRequester (or add a result callback to request()) so the route toasts only when the launcher callback reports gr
- **[major]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/SearchRepositoryImpl.kt:82` — Piloted-source branch silently drops mode/sort/genres for all 12 pilot sources — genre and sort search return wrong results
  - **Decision:** For the 12 piloted sources, route GENRES/SORT searches back to the legacy scraper (per-verb split, contradicting the no-split rule) or extend the sources contract with filter parameters — or accept that genre/sort search on pilots returns plain-query results?
  - *Recommendation:* Route GENRES/SORT searches (mode != NORMAL, or non-empty genres / non-null sort) to the legacy fetchSearchDataF path even for piloted sources, or extend the sources contract with filter parameters. This conflicts with th
- **[major]** `desktopApp/src/jvmMain/kotlin/me/manga/yamiapk/desktop/Main.kt:99` — Windows/Linux first launch blocks on a ~150-200 MB KCEF download with zero UI — the app shows no window for minutes and looks dead
  - **Decision:** Open the Desktop window immediately on Windows/Linux and run KCEF.init asynchronously (WebView surfaces degrade until ready via the KcefState handshake), or keep the blocking init but add a native splash/progress window during the first-run ~150-200 MB download?
  - *Recommendation:* Open the window first and run KCEF.init on a background coroutine on all OSes (the KcefState handshake already makes WebViewHost re-key when init completes), or at minimum show a native splash/progress window during the 
- **[major]** `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/reader/RecordHistoryUseCase.kt:40` — Incognito gate runs a full recursive cache-folder walk on every chapter open / Next / Prev
  - **Decision:** Add a narrow rework SettingsRepository.observeIncognito() (delegating to the legacy incognitoFlow) and depend RecordHistoryUseCase on it — directly or via a new ObserveIncognitoUseCase — to remove the cache walk from the chapter-open hot path?
  - *Recommendation:* Expose a narrow incognito accessor on the rework domain SettingsRepository (e.g. `fun observeIncognito(): Flow<Boolean>` delegating to the legacy `incognitoFlow`) and have RecordHistoryUseCase depend on that (directly or
- **[major]** `platform/src/desktopMain/kotlin/me/manga/yamiapk/platform/connectivity/DesktopConnectivityObserver.kt:47` — iOS/Desktop connectivity is 'can I reach Google', and Details hard-gates downloads on it — false permanent offline on Google-blocked networks
  - **Decision:** How should iOS/Desktop connectivity be determined for the download gate — OS-level reachability (NWPathMonitor / NetworkInterface), probe the active source's host, or fail-open (treat probe failure as 'unknown' and allow the download)?
  - *Recommendation:* Use OS-level reachability instead of a Google probe: NWPathMonitor on iOS, `NetworkInterface.getNetworkInterfaces()` up-check or a probe against the ACTIVE SOURCE's host on Desktop. At minimum treat probe failure as 'unk
- **[major]** `platform/src/iosMain/kotlin/me/manga/yamiapk/platform/image/IosAvifDecoder.kt:51` — iOS AVIF pages are decoded at full resolution TWICE with a full-res lossless PNG buffer in between — multi-hundred-MB transient spikes when the reader decodes several tall AVIF strips concurrently
  - **Decision:** Redesign the iOS AVIF decode path to downsample at decode time (CGImageSourceCreateThumbnailAtIndex with kCGImageSourceThumbnailMaxPixelSize from options.size) instead of the full-res UIImage->PNG->Skia round-trip — and confirm with on-device memory profiling? Which approach (ImageIO thumbnail rewrite vs minimal autoreleasepool+pre-resize)?
  - *Recommendation:* Decode directly at target size with ImageIO instead of the PNG round-trip: CGImageSourceCreateWithData + CGImageSourceCreateThumbnailAtIndex with kCGImageSourceThumbnailMaxPixelSize derived from options.size (and kCGImag
- **[major]** `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/details/DetailsViewModel.kt:908` — Details 'delete downloaded' actions never delete on-disk files or clear isDownloaded (row-only deleteDownload); per-row delete-chapter permanently orphans files
  - **Decision:** Add a delete-downloaded-chapters use case (clear isDownloaded + delete on-disk files, mirroring native LibraryRepository.deleteDownloadedChapters) wired into onDeleteSelectedDownloads / onDeleteAllDownloads / onDeleteChapter — and should onDeleteChapter abort the saved_chapters row delete when the file cleanup fails?
  - *Recommendation:* Add a delete-downloaded-chapters use case (clear isDownloaded + delete chapter files, mirroring legacy LibraryRepository.deleteDownloadedChapters) routed through :data, and call it from onDeleteSelectedDownloads / onDele
- **[major]** `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/home/HomeViewModel.kt:116` — Disabling/enabling a source while Home is cached desyncs feed, highlighted tab and siteState: the tabs collector never resets or refetches the feed when the enabled set shifts under a fixed activeTabIndex
  - **Decision:** When a source toggle shifts the enabled set under Home's active tab, should the VM clamp+reset+refetch the feed/featured (like onTabSelected) — and what is the desired behaviour when the active source itself was disabled vs when the index goes fully out of bounds (blank Home)?
  - *Recommendation:* Track the api the current feed was fetched for; in the tabs collector, when state.activeTab?.api differs from the fetched-feed api (or activeTab becomes null), clamp the index (selectSourceTab) and reset+refetch the feed
- **[major]** `shared/src/desktopMain/kotlin/me/manga/yamiapk/data/remote/ktor/HttpClientFactory.desktop.kt:43` — Desktop singleton HttpClient installs HttpCache with default unlimited in-memory storage (unfixed twin of the known Android finding)
  - **Decision:** Which HttpCache remediation should land uniformly in all three (android/ios/desktop) HttpClientFactory files for the download singleton client: drop HttpCache entirely, disk-bound it (publicStorage(FileStorage(cacheDir))), or route chapter-image downloads through a separate cache-free client?
  - *Recommendation:* Apply whatever remediation is chosen for the Android twin to all three factories: either drop HttpCache from the image-downloading client, bound it (publicStorage(FileStorage(cacheDir))) so cached bodies live on disk wit
- **[major]** `shared/src/iosMain/kotlin/me/manga/yamiapk/data/remote/ktor/HttpClientFactory.ios.kt:42` — iOS singleton HttpClient installs HttpCache with default unlimited in-memory storage (unfixed twin of the known Android finding)
  - **Decision:** Same as r2-res-1: pick the single HttpCache remediation strategy (drop / disk-bound FileStorage / separate cache-free client) to apply uniformly to the android/ios/desktop factories — the iOS singleton client's unbounded in-memory cache can be jetsam-killed mid-download.
  - *Recommendation:* Same as r2-res-1: bound/disk-backed cache storage or remove HttpCache from the client used for image downloads; fix the three per-platform factories together.
- **[major]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/reader/ReaderScreen.kt:1652` — Paged modes auto-advance the chapter on ARRIVAL at the last page — the final page of every chapter is yanked away before it can be read
  - **Decision:** Paged reading modes auto-advance on arrival at the last page (final page can't be dwelt on; scrubber-to-end skips chapter) — restore native's one-extra-swipe (append a terminal NextChapter page) despite the L653-655 owner comment leaving paged behaviour unchanged, or keep current behavior?
  - *Recommendation:* Restore the native one-extra-swipe semantics: append a terminal feed item to the paged layouts (mirror the continuous modes' NextChapterBoundaryCard — pageCount = pages.size + 1, last page renders NextChapterContent) and
- **[minor]** `app/src/main/java/me/manga/yamiapk/work/CbzMigrationWorker.kt:26` — CbzMigrationWorker is registered in Koin but never enqueued anywhere; KDoc claims a Settings trigger that doesn't exist
  - **Decision:** Delete CbzMigrationWorker + its Koin workerOf binding + the me.manga.kira.work.** proguard keep (Settings already runs the migration inline via SettingsRepositoryImpl), or wire the documented Settings OneTimeWorkRequest enqueue? (If kept, also rethrow CancellationException in the catch.)
  - *Recommendation:* Either delete the worker + its workerOf binding (Settings already covers the migration inline), or wire the documented Settings enqueue. If kept, rethrow CancellationException in the catch.
- **[minor]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/RepoSettingsScreenRoute.kt:135` — isFirstOpen=true onboarding branch is unreachable: no caller navigates Screen.RepoSettings(true) anymore
  - **Decision:** Remove the dead isFirstOpen=true onboarding arm (and SharedPrefsHelper injection) from RepoSettingsScreenRoute, or keep it as a deep-link safety net and just correct the stale onboarding-step-4 KDocs?
  - *Recommendation:* Either remove the isFirstOpen=true arm (and the now-unneeded SharedPrefsHelper injection) or keep it but update the KDoc here and in SourcesScreenRoute/App.kt to state the branch is a deep-link-only safety net. Owner cal
- **[minor]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/ThemeSelectionScreenRoute.kt:155` — Onboarding notification-permission 'denied' toast fires immediately on auto-request, before the user has answered the system dialog
  - **Decision:** Surface notification-permission request completion via suspend request() return, a completion flow, or a callback (same decision as code-ac-4)?
  - *Recommendation:* Surface a completion signal from NotificationPermissionRequester (e.g. suspend request() returning the result, or a requestCompleted flow) and show the denial toast only on an actual post-response denial.
- **[minor]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/sources/runtime/LegacyKotlinSourceClient.kt:54` — search() and featured() silently ignore the page parameter
  - **Decision:** For LegacyKotlinSourceClient.search()/featured() with page>1, fail fast with a Validation failure (forcing legacy fallback) or extend the legacy BaseMangaRepository surface to actually paginate?
  - *Recommendation:* Either document the page-1-only contract on the overrides, or fail fast for page > 1 (AppResult.Failure(Validation.Required("page"))) so a future paginating caller gets a visible failure instead of duplicated page-1 data
- **[minor]** `composeApp/src/iosMain/kotlin/me/manga/yamiapk/core/platform/RememberNotificationPermissionRequester.ios.kt:13` — iOS notification authorization is never requested anywhere — two files' KDocs claim 'the iOS app shell triggers it at launch', but iOSApp.swift only bootstraps Koin
  - **Decision:** On iOS, actually request UNUserNotificationCenter authorization at launch and drive hasPermission from real settings, or accept notifications as inert and just correct the misleading KDocs?
  - *Recommendation:* Either add the authorization request to the iOS bootstrap (e.g. in iOSApp.init or MainViewController, call UNUserNotificationCenter.requestAuthorizationWithOptions(alert|sound|badge)) and drive `hasPermission` from getNo
- **[minor]** `composeApp/src/iosMain/kotlin/me/manga/yamiapk/core/webview/WebViewHost.ios.kt:193` — No didFailProvisionalNavigation/didFailNavigation handling — failed page loads leave a silent blank WKWebView
  - **Decision:** On an iOS WKWebView load failure, what should the user see (error callback to the host, inline error page, or message) and how should failure surface through the common WebViewHost contract?
  - *Recommendation:* Implement `webView(_:didFailProvisionalNavigation:withError:)` (and didFailNavigation) on WebViewDelegate to clear loading state via the controller and surface a visible failure (e.g. an error callback up to WebViewCompo
- **[minor]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/LibraryRefreshRepositoryImpl.kt:128` — Desktop/iOS inline library refresh discards the AppResult — pull-to-refresh failure is indistinguishable from 'no new chapters'
  - **Decision:** Extend LibraryRefreshRepository to surface the inline refresh's AppResult<Int> (last-run result flow or suspend refresh) and have LibraryViewModel show an error/count, and how should a fully-failed offline refresh be presented (vs the use case's Success(0)-on-all-failed collapse)?
  - *Recommendation:* Surface the terminal outcome: extend LibraryRefreshRepository with a result flow (e.g. Flow<AppResult<Int>?> of the last run) or change refresh() to suspend returning AppResult<Int> on the inline path, and have LibraryVi
- **[minor]** `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/DownloadsActionRepository.kt:162` — 26 rework :domain port methods return kotlin.Result instead of AppResult/AppError, contradicting contract §9/§10 at rework-module boundaries
  - **Decision:** Amend the contract to bless kotlin.Result on legacy-bridging :domain ports, or migrate the Result<Unit> methods to AppResult<Unit> with mapped AppError subtypes (and fix their VM call sites)?
  - *Recommendation:* Owner decision: amend ARCHITECTURE_REWORK_CONTRACT §9/§10 to explicitly bless kotlin.Result on strangler-bridge ports, or convert these 26 methods to AppResult<Unit> with mapped AppError subtypes (which also unblocks the
- **[minor]** `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/LibraryPrefsRepository.kt:243` — observeLastUpdated read-only rationale is stale: Desktop/iOS now run a real inline library refresh that never writes library_last_updated, so 'Last updated' stays 'Not updated yet' forever after successful refreshes
  - **Decision:** Add a setLastUpdated/markRefreshed member to LibraryPrefsRepository (or write library_last_updated inside LibraryRefreshRepositoryImpl's inline-path success branch) so iOS/Desktop record refresh completion, and update the stale read-only rationale KDoc?
  - *Recommendation:* Add a setLastUpdated(Instant)/markRefreshed() member (or write the cell inside LibraryRefreshRepositoryImpl's inline-path finally-block on success) so all platforms record refresh completion; update the read-only rationa
- **[minor]** `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/PageProgressRepository.kt:173` — clear(url) has zero callers — the documented pruning hook for the process-singleton progress map is never exercised, so the map only ever grows
  - **Decision:** PageProgressRepository.clear is dead — wire it into Reader onCleared/chapter-switch teardown, or delete it (and fix the lifecycle KDoc to say the map intentionally accumulates per process)?
  - *Recommendation:* Either wire clearing into the Reader teardown (e.g. ReaderViewModel clears the previous chapter's page URLs on chapter switch / onCleared, via a small ClearPageProgressUseCase) or remove clear() from the interface and im
- **[minor]** `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/downloads/EnqueueAllChaptersDownloadUseCase.kt:63` — Download-all is an N+1 loop: one url->id Room query plus one entity re-load per chapter
  - **Decision:** Collapse download-all's N+1 by adding a bulk ChapterIdResolver.resolveChapterIds port backed by a chunked `url IN (...)` DAO query, or accept the per-chapter resolution (it runs off-UI on dispatchers.io)?
  - *Recommendation:* Add a bulk resolver port (e.g. suspend fun resolveChapterIds(urls: List<String>): Map<String, Long>) backed by a single 'WHERE url IN (...)' DAO query (chunked to stay under SQLite's 999-variable limit), then loop over t
- **[minor]** `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/home/LoadSearchFiltersUseCase.kt:14` — Untyped suspend boundary: a throw from active-repo resolution escapes raw instead of returning a typed result
  - **Decision:** Make HomeFeedRepository.loadFilters / LoadSearchFiltersUseCase return AppResult<SearchFilters> (and decide whether SearchViewModel keeps prior filters or shows an error on Failure), or just wrap the call in SearchViewModel with typed handling?
  - *Recommendation:* Change HomeFeedRepository.loadFilters / this use case to return AppResult<SearchFilters> (classify via the impl's existing classifyHomeThrowable, rethrowing CancellationException), and have SearchViewModel keep prior fil
- **[minor]** `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/library/RefreshAllLibraryChaptersUseCase.kt:57` — Total-timeout abort is reported as plain Success with no partial-result signal
  - **Decision:** When library refresh hits the 15-min total timeout, should the result carry a partial/completedFully flag (and the UI message it), or just log the truncation via the :core logging SPI (which would mean injecting :core Logger into RefreshAllLibraryChaptersUseCase)?
  - *Recommendation:* Capture whether the timeout fired (e.g. `val completed = withTimeoutOrNull(...) { ... } != null`) and surface it — either return a result type carrying (newChapters, completedFully) or at minimum log the truncation via t
- **[minor]** `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/library/RefreshAllLibraryChaptersUseCase.kt:69` — Desktop/iOS inline library refresh skips the cover-image-change propagation the Android worker performs — saved covers go permanently stale on those platforms
  - **Decision:** Add a rework LibraryRepository.updateCoverIfChanged(key, newCoverUrl) verb (mirroring legacy updateMangaImageUrlEverywhere) and call it from the inline refresh path so Desktop/iOS repair rotated covers — and also from the Details refresh path?
  - *Recommendation:* Add a cover-reconcile step to the shared persist path: extend LibraryRepository (rework) with an updateCoverIfChanged(key, newCoverUrl) verb that mirrors the legacy updateMangaImageUrlEverywhere transaction, and call it 
- **[minor]** `platform/src/commonMain/kotlin/me/manga/yamiapk/platform/cbz/DefaultCbzReader.kt:70` — Blocking ZIP/disk I/O runs on hardcoded Dispatchers.Default instead of an injected IO dispatcher
  - **Decision:** Inject :core DispatcherProvider into DefaultCbzReader and move its blocking ZIP I/O onto dispatchers.io (updating the three PlatformModule Koin bindings) — confirm the constructor/DI shape?
  - *Recommendation:* Inject the :core DispatcherProvider (or a CoroutineDispatcher) through the constructor and use its io dispatcher (mapped to Dispatchers.Default on iOS where IO is unavailable); update the Koin binding accordingly.
- **[minor]** `platform/src/desktopMain/kotlin/me/manga/yamiapk/platform/device/DesktopDeviceTierProbe.kt:23` — DesktopDeviceTierProbe is never bound or constructed — dead on Desktop
  - **Decision:** Bind DesktopDeviceTierProbe and use the tier for Desktop CBZ memory budgeting, or delete the unused Desktop/iOS device-tier probes (which are deliberately staged-but-inert relocations)?
  - *Recommendation:* Owner decision: bind it and use the tier for Desktop CBZ memory budgeting (see code-aq-7), or delete the Desktop/iOS probes until a consumer exists.
- **[minor]** `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/details/DetailsViewModel.kt:615` — Cloudflare-solver auto-routing has no WebView-capability gate — on macOS the user is auto-navigated (up to 2x) into a dead WebView screen
  - **Decision:** Add a :platform embedded-WebView-availability facade and gate the Cloudflare auto-route on it (fall back to the error pane on macOS) — and gate it in the VMs or in the route adapters?
  - *Recommendation:* Expose an `isEmbeddedWebViewAvailable` flag from :platform (false on macOS when KCEF init is skipped), inject it into the two VMs (or check in the route adapters), and fall back to the ShowError path (error pane already 
- **[minor]** `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/home/HomeViewModel.kt:279` — observeMembership creates one Room EXISTS flow per visible feed item (N+1), torn down and rebuilt wholesale on every feed change
  - **Decision:** Replace the per-item library-membership flows with a single observed saved-manga key-set intersected in the reducer (new use case), dropping the per-item flows and the restart-on-append logic — worth the perf refactor, and is the single-flow shape the desired approach?
  - *Recommendation:* Observe the full library key set once (a single Flow<Set<MangaKey>> use case over the saved_manga table, started in onEnter for the VM lifetime) and intersect it with the visible feed keys in the reducer; drop the per-it
- **[minor]** `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/reader/ReaderViewModel.kt:546` — An empty appended chapter silently dead-ends the continuous feed: it is retried forever and chapters beyond it are unreachable
  - **Decision:** On an empty appended chapter in the continuous reader, should we skip-and-advance to the chapter after it, show an error/'no pages' message (new string), or leave a visible error boundary, and at minimum stop re-marking tailUrl read on every retrigger?
  - *Recommendation:* On an empty append, emit ReaderEffect.ShowError (or a dedicated 'chapter has no pages' effect) and either record the chapter as skipped (so the next append targets tailIdx+2) or leave the boundary card in a visible error
- **[minor]** `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/reader/ReaderViewModel.kt:570` — Append-path failures never auto-route Cloudflare challenges to the WebView solver — continuous-mode reading dead-ends with a snackbar
  - **Decision:** Should continuous-mode (append-path) Cloudflare failures auto-route to the WebView solver, given the solve-and-retry loop resets the whole reader feed to the anchor chapter (a disruptive reset that needs handling)?
  - *Recommendation:* Apply the same CHALLENGE_STATUSES + cloudflareAttempts gate in appendChapterPages' Failure branch and emit ReaderEffect.SolveCloudflareChallenge(next.url, manga.api) so the route adapter's solve-and-retry loop also cover
- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/library/domain/LibraryRepository.kt:184` — deleteDownloadedChapters — the only correct full-cleanup path for downloaded chapters (clear isDownloaded + delete files) — has zero callers
  - **Decision:** Wire deleteDownloadedChapters (or a rework equivalent that also removes the chapter_downloads row) into the delete flows, or delete the dead method?
  - *Recommendation:* Owner decision: either wire deleteDownloadedChapters (or a rework equivalent that also removes the chapter_downloads row) into the Details/Downloads delete flows, or delete the dead method once the rework grows its own c
- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/repo_settings/domain/SourcesRepository.kt:199` — Coil header interceptor runs a fresh Room getAllSources query (plus ~150 Ktor Url parses) for every image request whose host misses the in-memory scan — per-cover/per-page on hot scroll paths, never memoized
  - **Decision:** Approve a memoization design for findRepoByHost on the Coil hot path (per-host cache incl. negative entries, invalidated on sources-table change, plus a cached getAllSources snapshot and pre-parsed repo hosts) to stop the per-image Room query + ~150 Url parses?
  - *Recommendation:* Memoize per host: keep a Map<String, BaseMangaRepository?> (with negative entries) invalidated when the sources table changes (the repo already has allSources Flow to hook), and pre-parse each repo's three candidate host
- **[minor]** `shared/src/nonAndroidMain/kotlin/me/manga/yamiapk/presentation/features/download/domain/clean/CoroutineDownloadRepositoryImpl.kt:401` — downloadOnePage buffers each full page image in memory before writing
  - **Decision:** Should downloadOnePage stream the page body in chunks (bodyAsChannel -> okio) instead of buffering the full ByteArray, and which bridge/chunk approach do you want?
  - *Recommendation:* Stream instead of buffering: read response.bodyAsChannel() and copy in chunks into the okio sink (readAvailable into a reusable ByteArray, or ByteReadChannel->okio bridge).
- **[minor]** `shared/src/nonAndroidMain/kotlin/me/manga/yamiapk/presentation/features/download/domain/clean/CoroutineDownloadRepositoryImpl.kt:434` — iOS/Desktop persist the cancel reason as hardcoded English 'Cancelled by user' — shown verbatim in the Failed tab on localized devices, while Android localizes the same path
  - **Decision:** Should the iOS/Desktop cancel path persist a locale-independent sentinel/null and have :ui map it to the existing cancelled_by_user string at render time (fixing both English-on-localized-device and stale-language-on-locale-change)?
  - *Recommendation:* Persist a locale-independent sentinel (or null) for user-cancel and let :ui map it to the localized cancelled-by-user string at render time — also fixes the stale-language problem when the app locale changes after the ro
- **[minor]** `sources/config/src/commonMain/kotlin/me/manga/yamiapk/sources/config/ConfigMerger.kt:42` — Documented 'highest revision wins' is not enforced — stale cache outranks newer bundled, and no anti-rollback for remote
  - **Decision:** For the disabled Stage-1 remote/cache path, should ConfigMerger enforce anti-rollback (drop documents with revision lower than bundled / refuse caching an older remote doc), or should the 'highest revision wins' contract KDocs be rewritten to say precedence+priority decide?
  - *Recommendation:* When remote/cache documents are accepted, drop any whose revision is lower than the bundled document's (and refuse to cache a remote doc older than the current cache). Alternatively update the KDocs to state that precede
- **[minor]** `sources/engine/src/commonMain/kotlin/me/manga/yamiapk/sources/engine/GenericSourceClient.kt:92` — pages() reads the header store twice and parses the response body twice; chapter pagination re-parses every page body
  - **Decision:** Should pages()/chaptersPaginated parse the response body once and share it (and reuse the already-read headers), and if so via a headers param on runRequest or a hoisted ParsedBody handle?
  - *Recommendation:* Pass the already-computed headers into runRequest (or hoist a parsed-document handle): e.g. parse the body once into an internal ParsedBody (Ksoup Document or JsonElement) and derive listScopes/rootScope/locatorValues fr
- **[minor]** `sources/engine/src/commonMain/kotlin/me/manga/yamiapk/sources/engine/GenericSourceClient.kt:164` — Genre blacklist (and recentChapters) ignore per-verb field overrides — a verb with only '<verb>.item.genres' skips blacklist filtering
  - **Decision:** Should the genre blacklist (and recentChapters) resolve fields via verbKey(verb,'item.genres') so per-verb overrides are filtered, even though no bundled config defines one yet?
  - *Recommendation:* Thread the verb into isBlacklistedByGenre and resolve the spec via verbKey(verb, "item.genres") (and likewise consider verbKey for "item.recentChapters"), keeping the null-spec short-circuit.
- **[minor]** `sources/engine/src/commonMain/kotlin/me/manga/yamiapk/sources/engine/GenericSourceClient.kt:456` — Template vars that fail to resolve silently expand to "" and yield plausible-but-dead URLs that defeat both isNavigable and the legacy fallback
  - **Decision:** When a template var resolves empty, should resolveField treat it as a field-resolution failure (return '' so isNavigable drops the item), and which vars (root:__dir, others) are allowed to be legitimately empty?
  - *Recommendation:* In resolveField(), when a template is used, treat any var that resolves empty (excluding the intentionally-optional root:__dir token) as a field-resolution failure and return "" so the item is dropped by the existing isN
- **[minor]** `ui/src/commonMain/composeResources/values/strings_np_settings_theme_lang.xml:74` — 10 more unused string keys scattered across 5 np_* catalog files (verified repo-wide grep)
  - **Decision:** For the 10 unused np_* keys, wire the intended usages (reader page-step a11y labels, Settings 'Other' section) or delete the keys + their 10-locale copies?
  - *Recommendation:* Either wire the intended usages (notably np_reader_previous_page/np_reader_next_page as contentDescriptions on the reader scrubber step buttons, and section_other for the Settings 'Other' group it was authored for) or de
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/complaint/ComplaintActionDialog.kt:440` — Unreachable over-limit error states: onValueChange caps input at maxChars, so isError / error-colored counter / length guards can never trigger
  - **Decision:** For ReplyContent's unreachable over-limit states, drop the dead isError/length-guard branches (diverging from native's mirrored defensive structure), or switch to allow-typing-past-cap Material UX, or leave as-is?
  - *Recommendation:* Either drop the unreachable isError/length-guard branches, or (closer to typical Material UX) allow typing past the cap and rely on the isError + disabled-submit path to communicate the limit.
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/history/HistoryScreen.kt:387` — Numeric stringResource args render Latin digits in the Arabic UI where native renders Arabic-Indic digits (systemic compose-resources divergence)
  - **Decision:** Shape numeric stringResource args to Arabic-Indic digits app-wide via a locale-aware formatter (matching native), or accept Latin digits as a documented KMP-wide parity divergence?
  - *Recommendation:* Route numeric args through a locale-aware digit formatter before interpolation (e.g. reuse/extend the existing formatGroupedNumber expect/actual, or a formatLocalizedInt helper) at the stringResource call sites that feed
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/reader/ReaderScreen.kt:524` — Reader top bar lives in the Scaffold topBar slot, so every chrome toggle (including the 3s auto-hide) resizes the page viewport and the 0.8-alpha translucency never shows the page beneath it
  - **Decision:** Reader top bar in the Scaffold slot resizes the page viewport on every chrome toggle and breaks the intended translucency — restructure it as an overlay inside the page Box (matching native and the bottom chrome)?
  - *Recommendation:* Move the top bar out of the Scaffold slot and render it as a top-aligned overlay inside the page BoxWithConstraints (same pattern as the bottom chrome stack), keeping the page area at a constant size and making the trans

## C. Large deletions (need sign-off) (10)
_Approve deleting these (dead code / unused resources) or keep?_

- **[minor]** `app/src/main/res/drawable:1` — 90 of 92 drawables in app/src/main/res/drawable are unused legacy duplicates (~1.1 MB)
  - **Decision:** Delete the ~89 unused drawable XMLs + 18 unreferenced bitmaps from app/src/main/res/drawable now (keeping ic_message.xml and ic_launcher_foreground.xml), or defer until the legacy :app host is strangled out?
  - *Recommendation:* Delete the 90 unreferenced drawable XMLs from app/src/main/res/drawable, keeping ic_message.xml and ic_launcher_foreground.xml. If the legacy :app host may still grow View-based UI before retirement, defer until :app is 
- **[minor]** `composeApp/src/commonMain/composeResources/values/strings.xml:17` — 205 of 525 composeApp default string keys are unreferenced (plus ~10 locale copies each, ~2200 dead entries)
  - **Decision:** Approve a compile-gated + checkLocaleKeyParity-gated sweep-delete of the ~205 unreferenced composeApp string keys (x10 locale copies) from values/strings.xml, keeping the Android-res-served notification keys? (Also fix the request_add_language stray trailing quote and remove the leftover '//...' text nodes.)
  - *Recommendation:* Sweep-delete the unreferenced keys from values/strings.xml and the 10 locale strings.xml files after a compile gate; keep notification_*/Android-res-served keys only in the Android res trees.
- **[minor]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/Screen.kt:55` — 12 registered routes are unreachable (zero navigate() call sites) and the KDocs claiming Screen.MangaDetails still has 3-4 live callers are false
  - **Decision:** Retire the 12 unreachable nav routes (incl. the dead Screen.MangaDetails URL-only entry path) and correct the false 'still emits Screen.MangaDetails' ADR KDocs, or keep the keys for parity testing and re-point a caller so the URL-only path stays exercised?
  - *Recommendation:* Correct the stale KDocs now; schedule a route-retirement slice for the 12 dead keys + the dead DetailsScreenByUrl/OnEnterByUrl entry shape (or re-point a caller at Screen.MangaDetails if the URL-only entry is meant to st
- **[minor]** `platform/src/commonMain/kotlin/me/manga/yamiapk/platform/storage/DataStoreHelper.kt:87` — headersMapFlow is dead and semantically stale after the #12 per-API key change
  - **Decision:** headersMapFlow is dead and can never reflect post-#12 per-API header captures — delete it (shrinking the deliberately byte-for-byte-preserved API surface), or reimplement it to merge the per-API hashed keys with the legacy aggregate so it stays truthful?
  - *Recommendation:* Either delete headersMapFlow (no consumers exist) or reimplement it to merge the per-API keys with the legacy aggregate so it stays truthful. Owner should pick; deletion shrinks the byte-for-byte-preserved API surface th
- **[minor]** `platform/src/desktopMain/kotlin/me/manga/yamiapk/platform/image/DesktopBase64ImageConverter.kt:21` — DesktopBase64ImageConverter (and the whole Base64ImageConverter facade) has no consumer or binding
  - **Decision:** Delete the orphaned Base64ImageConverter facade (all 4 files), or wire its intended :data consumer (and add WebP-capable validity sniffing if kept on Desktop)?
  - *Recommendation:* Owner decision: delete the orphaned facade (all 4 files) or wire its intended :data orchestrator; if kept for Desktop, add a WebP-capable validity check (e.g. magic-byte sniffing like CbzVerbatimExtension) instead of bar
- **[minor]** `platform/src/desktopMain/kotlin/me/manga/yamiapk/platform/image/DesktopScreenshotProvider.kt:28` — saveBitmapBytesToGallery has zero callers anywhere in the repo
  - **Decision:** Wire the reader's save-screenshot action to saveBitmapBytesToGallery (a feature native does not have — native only shares), or remove the dead method from the ScreenshotProvider SPI and all three platform impls?
  - *Recommendation:* Owner decision: wire the reader's save-screenshot action to saveBitmapBytesToGallery (parity) or remove the method from the ScreenshotProvider SPI and all three actuals.
- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/core/util/data_classes/HandelDataClasses.kt:69` — 13 of 15 HandelDataClasses members are unreferenced; §382 'kept because live' prose is stale
  - **Decision:** Prune the 13 unreferenced HandelDataClasses members (keeping only toChapterEntity/toChapterDownloadEntity) and fix the contradictory 'fresh PK' comment — confirm none are intended for imminent re-wiring in the migration?
  - *Recommendation:* Prune the 13 dead members (and their now-unused imports: kotlinx.datetime.todayIn-only users, ChapterNotification, MangaItem, MangaInfo, ReaderChapters as applicable), keeping toChapterEntity and toChapterDownloadEntity.
- **[minor]** `ui/src/commonMain/composeResources/values-ar/strings.xml:1` — 56 of 589 ui-module default string keys are unreferenced (locale copies in this shard are dead weight)
  - **Decision:** Confirm the exact verified-dead subset of the 56 ui-module keys (excluding live ones like details_add_library_message) to delete from the default + 10 locale catalogs as a dedicated, compile-gated cleanup pass?
  - *Recommendation:* Delete the unreferenced keys from the ui default catalog and all 10 locale catalogs after a compile gate.
- **[minor]** `ui/src/commonMain/composeResources/values/strings.xml:84` — 46 string keys in the base catalog are referenced nowhere in Kotlin source (verified repo-wide grep)
  - **Decision:** Delete the 46 confirmed-unreferenced base-catalog keys (and their 10-locale copies + accessors), or are any reserved for planned parity work?
  - *Recommendation:* Delete the unused keys from values/strings.xml and their copies in the 10 values-*/ dirs (mechanical, behavior-preserving since nothing resolves them). If some are reserved for planned parity work, keep them deliberately
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/theme/YamiShapes.kt:9` — Systemic token bypass in :ui - 125 inline RoundedCornerShape(...) vs 3 MaterialTheme.shapes reads, and LocalSpacing appears in only 25/52 files with dp literals outnumbering token reads 5-30x in the largest screens
  - **Decision:** Systemic design-token bypass in :ui (inline RoundedCornerShape/dp literals dwarf MaterialTheme.shapes/LocalSpacing reads) — adopt-on-touch policy + optional detekt rule, or accept the literals as native-parity values?
  - *Recommendation:* Adopt-on-touch policy: when a screen is edited, replace 4/8/12/16dp inline radii with MaterialTheme.shapes.{extraSmall,small,medium,large} and grid paddings with LocalSpacing; optionally add a lint/detekt rule for new co

## F. Needs new translated strings (10 locales) (16)
_I can add the key + English; you (or a translator) supply the 10-locale text — or approve English-everywhere._

- **[major]** `composeApp/src/commonMain/composeResources/values-in/strings.xml:1` — Indonesian locale (values-in) is unreachable on Desktop (JDK 17+) and iOS - falls back to English
  - **Decision:** To make Indonesian work on Desktop/iOS, ship duplicate values-id resource dirs alongside values-in (keeping both for Android), or normalize the locale code per platform — which approach do you want?
  - *Recommendation:* Owner decision: either (a) duplicate/rename Indonesian dirs to values-id and keep values-in for Android compatibility (compose-resources reads the same folders on Android, where the language code is 'in', so BOTH dirs ar
- **[major]** `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintViewModel.kt:236` — Systemic MVI-contract violation across 4 features: effects carry hardcoded English display text and raw exception messages that :ui renders verbatim in all 10 locales
  - **Decision:** Adopt the semantic-effect + :ui-localized-string pattern across the 4 affected features, adding the new localized snackbar strings to all 10 locales — approve the cross-feature reshape and string additions?
  - *Recommendation:* Replace the message-carrying effect variants with semantic variants per the Sources pattern: e.g. `BodyCopied`, `ActionCompleted(action: ComplaintAction)`, `ActionFailed(action: ComplaintAction)` / `DownloadEnqueueFailed
- **[minor]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/core/util/date/Date.kt:44` — toRelativeString always renders English month names, diverging from native's locale-aware date format in a 10-locale (RTL Arabic-heavy) app
  - **Decision:** How should absolute dates render localized month names across the 10 locales — add per-locale month-name resources, or format via each platform's locale — given kotlinx-datetime has no built-in localized month names?
  - *Recommendation:* Localize the month names — e.g. a per-locale month-name array in compose-resources fed into LocalDate.Format, or format month via the platform locale on each target. Needs a small design decision since kotlinx-datetime h
- **[minor]** `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintViewModel.kt:198` — Hardcoded English snackbar copy and raw exception messages in admin complaint effects/state (same bug class as user-side complaint)
  - **Decision:** Reshape AdminComplaint effects to semantic variants (no embedded English) and add new localized strings for the operator success/error snackbars across all 10 locales — approve the new string set and effect-surface redesign?
  - *Recommendation:* Same reshape as the user-side complaint finding: semantic effect variants + :ui-resolved localized strings.
- **[minor]** `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/downloads/DownloadsViewModel.kt:237` — User-facing snackbar text hardcoded in English inside :presentation effects (Downloads, Updates, Complaint, AdminComplaint) — bypasses i18n and the typed-AppError rule
  - **Decision:** Convert Downloads/Updates effect payloads to typed errors/success kinds resolved to localized strings in :ui, adding the new strings to all 10 locales — approve the string set?
  - *Recommendation:* Convert these effects to carry typed payloads (AppError or a sealed success-kind enum) and let :ui map them to Res.string.* at the call site, as DetailsScreen/ReaderScreen already do with their errorMessages vocabularies
- **[minor]** `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/updates/UpdatesViewModel.kt:249` — Updates Undo restore failure is silent — the row stays permanently deleted while the UI implies the undo succeeded
  - **Decision:** Surface a localized 'undo failed' snackbar when restoreUpdateEntry throws (new string in all 10 locales) — approve the new string, and prefer awaiting the restore + emitting ShowError over overriding onUnhandledError?
  - *Recommendation:* In the OnUndoDelete handler, await the restore and emit an error effect on failure (e.g. wrap in runCatchingCancellable and emit UpdatesEffect.ShowError with a typed/localizable error so the user knows the undo failed), 
- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/statistics/domain/StatisticsRepository.kt:78` — Statistics read-duration is formatted as hardcoded English "${h}h ${m}m" in :shared — native localizes h_m in 11 locales (Arabic, Japanese, Russian...), so all non-English users see an untranslated duration
  - **Decision:** Add a new localized h_m duration string across all 10 locales and format read-duration at the :ui StatisticsScreen call site instead of hardcoding English in :shared?
  - *Recommendation:* Expose the raw minutes (Int) through ReadingStatistics instead of a pre-formatted string, and format in :ui StatisticsScreen via a compose-resources np_* string with the native h_m translations ported across the 10 local
- **[minor]** `ui/src/commonMain/composeResources/values-ar/strings.xml:1` — details_delete_chapter missing from all 10 locale catalogs (English fallback)
  - **Decision:** Provide translations of details_delete_chapter ('Delete chapter') for ar/de/es/fr/in/it/ja/pt/ru/tr so it can be added to every locale catalog.
  - *Recommendation:* Add translated details_delete_chapter to each locale catalog (e.g. values-ar can reuse the existing native deletion wording).
- **[minor]** `ui/src/commonMain/composeResources/values-ar/strings_backfill_parity.xml:1` — Live admin-complaint strings left in English in non-Latin locales (ar, ru, ja)
  - **Decision:** Provide ar/ru translations for the user-facing subset (bk_complaint_type_label, details_chapter_date_full, status labels) and decide whether admin-complaint-only strings are intentionally English-only.
  - *Recommendation:* Translate the user-facing subset (bk_complaint_type_label, details_chapter_date_full, status labels); owner to decide whether admin-only strings stay English.
- **[minor]** `ui/src/commonMain/composeResources/values-de/strings.xml:1` — np_reader_bookmark_not_in_library missing from 9 locales (only ar + default have it)
  - **Decision:** Provide translations of np_reader_bookmark_not_in_library for de/es/fr/in/it/ja/pt/ru/tr (the ar value already exists).
  - *Recommendation:* Add the key to the 9 missing locale catalogs (alongside the existing ar translation in values-ar/strings_np_details_reader.xml:30).
- **[minor]** `ui/src/commonMain/composeResources/values-in/strings_backfill_parity.xml:100` — Indonesian locale ships ~17 raw-English values (admin-complaint cluster)
  - **Decision:** Provide Indonesian translations for the listed admin-complaint values in values-in/strings_backfill_parity.xml (lines 8,9,15,21,100,101,106,112,121,123,124,134,137,138).
  - *Recommendation:* Translate the listed in entries.
- **[minor]** `ui/src/commonMain/composeResources/values-pt/strings_backfill_parity.xml:100` — Portuguese locale ships ~20 raw-English values (admin-complaint cluster + sort labels)
  - **Decision:** Provide Portuguese translations for the listed admin-complaint + sort-label values in values-pt/strings_backfill_parity.xml and values-pt/strings.xml.
  - *Recommendation:* Translate the listed pt entries.
- **[minor]** `ui/src/commonMain/composeResources/values-ru/strings_backfill_parity.xml:100` — Russian locale ships ~22 raw-English values (admin-complaint cluster + sort labels)
  - **Decision:** Provide Russian translations for the listed admin-complaint + sort-label values in values-ru/strings_backfill_parity.xml and values-ru/strings.xml.
  - *Recommendation:* Translate the listed ru entries (the user-side complaint cluster in the same files is already translated and provides the terminology).
- **[minor]** `ui/src/commonMain/composeResources/values/strings_np_details_reader.xml:45` — np_reader_bookmark_not_in_library translated only in Arabic — 9 locales fall back to English
  - **Decision:** Provide professional translations of np_reader_bookmark_not_in_library ('Add the manga to your Library to bookmark chapters') for de/es/fr/in/it/ja/pt/ru/tr?
  - *Recommendation:* Add the 9 missing locale entries for np_reader_bookmark_not_in_library.
- **[minor]** `ui/src/commonMain/composeResources/values/strings_pfix_dlprogress.xml:19` — details_delete_chapter has no translation in ANY of the 10 locales
  - **Decision:** Provide translations of details_delete_chapter ('Delete chapter') for all 10 locales (the sibling details_delete_downloaded already has localized variants to crib from)?
  - *Recommendation:* Author the 10 locale values (the sibling key details_delete_downloaded already has translations in every locale to crib the verb from, e.g. ar/it/pt/ru/tr variants of 'Delete chapter').
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/settings/SettingsScreen.kt:1715` — CBZ conversion error dialog never shows the actual error — body duplicates the title string
  - **Decision:** CBZ "Conversion Failed" dialog shows only the generic title (the error field is an internal "done" sentinel). Add a localized error-detail string (10 locales) to surface the real failure reason?
  - *Recommendation:* Render progress.error (or a localized mapping of it) as the body text in the error branch instead of repeating conversion_failed.

## D. Ambiguous / needs clarification (29)

- **[major]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt:301` — Piloted sources never hydrate the legacy header cache the Coil interceptor reads — covers on captured-header pilot sources load with no Cookie/UA despite valid saved headers
  - **Decision:** Which header-hydration fix for piloted-source image covers: fall back to persisted DataStore headers inside CoilSourceHeaderInterceptor, or have the :data pilot branches call repo.initSite() before delegating to the registry?
  - *Recommendation:* In CoilSourceHeaderInterceptor, when `match.defaultHeaders` is empty, fall back to the persisted headers: either call `(match as? BaseManga)?.ensureSiteInitialized()` (intercept is suspend) and re-read defaultHeaders, or
- **[major]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/AdminComplaintActionRepositoryImpl.kt:180` — Admin (and iOS/Desktop user) complaint mutations misreport every transient fetch failure as 'Complaint <id> not found'
  - **Decision:** Add a throwing get-by-id/getAllOrThrow to the legacy complaint facade for the mutation path, or make the existing getAll/getByUser rethrow (also resolving read-path #350)?
  - *Recommendation:* Make the mutation-path re-fetch failure-transparent: either add a throwing fetch (e.g. a getAllComplaintsOrThrow / getComplaintByIdOrThrow on the legacy repository) used by fetchLegacyById in both action repos, or have t
- **[major]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/ChapterPagesRepositoryImpl.kt:196` — Loose-file downloaded chapters skip the stale-path re-derivation/existence check the CBZ branch got — broken pages with no network fallback after iOS container change
  - **Decision:** Inject AppFileSystem into ChapterPagesRepositoryImpl to re-derive loose page paths under the live chapter dir (matching the CBZ branch) and fall through to network when none of the resolved files exist?
  - *Recommendation:* In the loose branch, re-derive each stored path's filename under the current appFileSystem chapter dir (the layout is fixed: chapterDir(mangaId, id)/image_<n>.<ext>), falling back to the stored path; if none of the resol
- **[major]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/SavedMangaDetailsRepositoryImpl.kt:42` — observeSavedDetails completes after a single null for non-library manga — membership gained mid-screen never starts the saved-details stream
  - **Decision:** Should observeSavedDetails become membership-reactive (switchMap over a mangaDao flow keyed by (api,title) so the stream starts when the row appears), or is the emit-null-and-complete contract intended with DetailsViewModel responsible for re-collecting after add-to-library?
  - *Recommendation:* Derive the stream from a membership-reactive source instead, e.g. switchMap over a `mangaDao` flow keyed by (api,title) so the saved-details stream starts when the row appears (or document that callers must re-collect af
- **[major]** `platform/src/iosMain/kotlin/me/manga/yamiapk/platform/storage/IosSecureStorage.kt:134` — Keychain facade is guaranteed to crash: `NSMutableDictionary as CFDictionaryRef` throws ClassCastException at runtime (verified by K/N repro)
  - **Decision:** Fix the iOS Keychain bridge by hand (CFBridgingRetain/CFBridgingRelease + CFRelease the SecItemCopyMatching result + OSStatus checks) or replace IosSecureStorage with multiplatform-settings' KeychainSettings? Either needs iOS-simulator verification before merge.
  - *Recommendation:* Rewrite the bridge the way multiplatform-settings' KeychainSettings does: wrap dictionary creation in `CFBridgingRetain(nsDict) as CFDictionaryRef` (CFTypeRef IS a CPointer, so that cast is legal), CFRelease the bridged 
- **[major]** `shared/src/androidMain/kotlin/me/manga/yamiapk/data/remote/ktor/HttpClientFactory.android.kt:70` — install(HttpCache) with default unlimited in-memory storage on the singleton client that downloads chapter images
  - **Decision:** Replace the unbounded in-memory HttpCache with a bounded store — disk-backed FileStorage in cacheDir (note: Ktor FileStorage is itself unbounded), a size-capped in-memory store, or remove HttpCache from the download client entirely? Pick one and a size.
  - *Recommendation:* Provide a bounded storage (e.g. Ktor FileStorage in cacheDir for parity with OkHttp's disk cache, or a size-capped storage), or scope HttpCache away from the image-download path.
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/data/local/MangaDatabaseFactory.kt:18` — ForeignKeysOnCallback only applies PRAGMA foreign_keys=ON to the first pooled connection; the writer connection usually runs with FK enforcement OFF
  - **Decision:** Apply a per-connection FK pragma via a SQLiteDriver decorator — OK to enforce foreign_keys=ON during all 9 migrations (after auditing each for FK-violating intermediate steps / defer_foreign_keys), changing FK timing on existing databases?
  - *Recommendation:* Apply the pragma per physical connection instead of (or in addition to) the callback: wrap the driver passed to .setDriver() in a small decorator - `class ForeignKeysDriver(private val delegate: SQLiteDriver) : SQLiteDri
- **[major]** `sources/engine/src/commonMain/kotlin/me/manga/yamiapk/sources/engine/GenericSourceClient.kt:519` — Two base-URL resolution twins drifted: legacy sources follow the server-pushed/user-edited base URL from the sources DB on every launch, but the generic engine's {baseUrl} is frozen at compile time in PILOT_SOURCES_CONFIG_JSON
  - **Decision:** Should the generic path consume the DB-resolved (server/user) base URL — via a new BaseUrlProvider port in :sources:contracts or a composition-root config.copy(baseUrl=sourcesDao.getBaseUrlFor(api)) overlay (with imageBase analogous)?
  - *Recommendation:* Feed the DB-resolved base URL into the generic path - e.g. have the composition root overlay `config.copy(baseUrl = sourcesDao.getBaseUrlFor(api) ?: config.baseUrl)` when building the GenericSourceClient (with imageBase 
- **[major]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/reader/ReaderScreen.kt:420` — After Next/Prev chapter navigation, any composition restart (rotation, return from WebView) re-dispatches OnEnter with the stale nav-args chapter and resets the reader to the originally-opened chapter
  - **Decision:** After Next/Prev navigation, a rotation or return-from-WebView re-runs OnEnter with stale nav-args and yanks the reader back to the originally-opened chapter — make intent-level OnEnter initialize-once per VM (ignore when state.chapter != null)?
  - *Recommendation:* Make the intent-level OnEnter initialize-once per VM instance: in ReaderViewModel.handle, ignore OnEnter when `state.value.chapter != null` (VM-internal onEnter calls from onNextChapter/onPrevChapter bypass the intent pa
- **[major]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/search/SearchScreen.kt:242` — Retry on a failed genre/sort browse dispatches OnSubmit, which clears the error and searches nothing — error pane silently replaced by a blank idle screen
  - **Decision:** Search error-pane Retry dispatches OnSubmit, which for a failed genre/sort browse clears the error and shows a blank idle screen instead of re-running — add an OnRetrySingle intent that re-runs the failed search with recorded params?
  - *Recommendation:* Make retry re-run the failed search: record the last-executed single-search parameters (query + effective SearchMode + sort/genres) in the VM and add a SearchIntent.OnRetrySingle handled by re-invoking runSingleSearch wi
- **[minor]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt:304` — Two image-header mechanisms disagree on precedence: CoilSourceHeaderInterceptor unconditionally overwrites per-request headers that rememberSourceImageRequest already attached
  - **Decision:** Should request-level image headers (rememberSourceImageRequest) win over the loader-level CoilSourceHeaderInterceptor, i.e. skip interceptor injection when the request already carries non-empty httpHeaders?
  - *Recommendation:* In CoilSourceHeaderInterceptor, skip injection when the incoming request already carries non-empty httpHeaders (`req.httpHeaders.asMap().isNotEmpty() -> proceed()`), making request-level attachment authoritative.
- **[minor]** `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/webview/ui/screens/WebViewComposeScreen.kt:227` — WebView top bar shows the URL twice — the page title from native was never ported to the KMP controller
  - **Decision:** Restore native's page-title display by threading pageTitle through WebViewController state + 3 platform actuals, or just drop the duplicated URL subtitle line?
  - *Recommendation:* Add a `pageTitle: String?` field to the WebViewController nav state and populate it from each actual (Android WebChromeClient.onReceivedTitle, iOS WKWebView.title KVO, Desktop CefDisplayHandler.onTitleChange); render it 
- **[minor]** `composeApp/src/desktopMain/kotlin/me/manga/yamiapk/core/webview/WebViewHost.desktop.kt:97` — KCEFClient is allocated per WebViewHost mount but never disposed (native CEF client leak)
  - **Decision:** Dispose the per-mount KCEFClient on unmount (and rewrite the false 'shared client' KDoc), or implement the documented process-wide shared client reused across mounts?
  - *Recommendation:* Either (a) dispose the per-mount client: in the final DisposableEffect's onDispose, after browser.close(true) and uaRouter.dispose(), call client.dispose(); or (b) actually implement the documented design — hold one proc
- **[minor]** `core/src/iosMain/kotlin/me/manga/yamiapk/core/dispatchers/IoDispatcher.ios.kt:9` — iOS io dispatcher routes to Dispatchers.Default on an outdated premise — Dispatchers.IO is public on Kotlin/Native since coroutines 1.7.0
  - **Decision:** On iOS, rebind platformIoDispatcher from Dispatchers.Default to the now-public Dispatchers.IO (elastic 64-thread pool), or keep Default and rewrite the stale rationale comment to a deliberate choice?
  - *Recommendation:* Change the iOS actual to `actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.IO` with `import kotlinx.coroutines.IO`, and update the stale claims in IoDispatcher.kt / DispatcherProvider.kt KDoc (and event
- **[minor]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/AdminComplaintActionRepositoryImpl.kt:180` — Every admin mutation re-fetches the entire complaints collection, and the read-modify-write is unguarded (lost-update window); KDoc undersells the cost as 'one extra Firestore READ'
  - **Decision:** Should the legacy complaint facade gain a Firestore get-by-id (and field-level/transactional updates) so admin mutations stop re-fetching the whole collection and stop racing each other's writes?
  - *Recommendation:* Add a get-by-id read to the legacy complaint facade (Firestore document get) and use it here; correct the KDoc cost claim. Optionally move the mutations to field-level updates (or a transaction) to close the lost-update 
- **[minor]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/ChapterBookmarkRepositoryImpl.kt:42` — observeBookmark completes after a single false when the chapter row doesn't exist — bookmark state never re-binds if the row appears later
  - **Decision:** Add a url-keyed `SELECT isBookmarked FROM saved_chapters WHERE url = ?` Flow to ChapterDao and make observeBookmark membership-reactive (and apply the same shape to the sibling SavedMangaDetails stream), or accept the native id-resolved-once contract and have ReaderViewModel re-collect after the row appears?
  - *Recommendation:* Make the stream membership-reactive: observe a Room flow keyed on the URL (e.g. a `SELECT isBookmarked FROM saved_chapters WHERE url = ?` flow that emits null/false when absent and re-emits when the row is inserted), or 
- **[minor]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/ChapterPagesRepositoryImpl.kt:203` — toFileUrl produces malformed file URLs for Windows paths and never percent-encodes
  - **Decision:** Is Windows Desktop offline reading a supported target, and if so should downloaded-page paths be normalized to proper percent-encoded file:/// URIs or handed raw to Coil's file mapper?
  - *Recommendation:* Normalize via okio Path and build a proper URI (e.g. prefix with `file:///` and convert backslashes / encode segments), or pass the raw path to Coil (Coil 3 JVM resolves plain absolute paths via its file mapper).
- **[minor]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/ChapterPagesRepositoryImpl.kt:211` — Fire-and-forget CBZ-cache cleanup on the app-lifetime scope can race a concurrent re-extract of the same chapter (rapid Next->Prev or exit->reopen)
  - **Decision:** Serialize per-chapter CBZ cleanup against extraction via a per-chapter Mutex, or track in-flight cleanup Jobs and await/cancel them before re-extracting?
  - *Recommendation:* Serialize per-chapter cleanup and extraction (e.g. keep a per-chapter Mutex or a map of in-flight cleanup Jobs in ChapterPagesRepositoryImpl and have localPagesOrNull await/cancel the pending cleanup for that chapter bef
- **[minor]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/HomeFeedRepositoryImpl.kt:86` — Unsynchronized mutable pagination state on a Koin single can be written back stale after a tab switch
  - **Decision:** Guard HomeFeed pagination state with a Mutex, or tag each accumulated snapshot with its source api and discard write-backs whose api no longer matches the active source?
  - *Recommendation:* Guard the pagination state with a Mutex keyed to the fetch methods, or tag the accumulated snapshot with the source api and discard write-backs whose api no longer matches the active source.
- **[minor]** `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/MarkChapterReadRepositoryImpl.kt:72` — Bulk markRead resolves chapter ids with N sequential single-row queries
  - **Decision:** OK to add a chunked `getChapterIdsByUrls(urls): List<Long>` query to the legacy :shared ChapterDao and use it for the bulk mark-read (perf only, no behavior change)?
  - *Recommendation:* Add a chunked `getChapterIdsByUrls(urls: List<String>): List<Long>` DAO query and use it here.
- **[minor]** `platform/src/iosMain/kotlin/me/manga/yamiapk/platform/image/IosAvifDecoder.kt:93` — AVIF sniff matches only the ftyp MAJOR brand 'avif'/'avis' — AVIF files with e.g. 'mif1' major brand fall through to Skia and fail
  - **Decision:** Extend the iOS AVIF ftyp sniff to scan the full ftyp box and accept 'avif'/'avis' in compatible_brands (not just major brand)? Needs the device-smoke the file's NOTE prescribes to confirm it actually decodes mif1-major AVIFs rather than shifting the failure.
  - *Recommendation:* Extend the peek to the full ftyp box (read box size from bytes 0-3, bounded e.g. to 64 bytes) and accept when any 4-byte brand in the box equals 'avif'/'avis' (major or compatible). Keep declining on any read error. Need
- **[minor]** `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/search/SearchIntent.kt:32` — OnApplyFilters has no production dispatch site (dead intent), and its handler clears results for a blank-query genre apply
  - **Decision:** OnApplyFilters has no production dispatch (only a test): delete the dead variant+handler (and its parity test), or keep it and fix the latent blank-query-genre-apply bug?
  - *Recommendation:* Remove the OnApplyFilters variant and handler, or fix the handler to bypass the blank-query guard when genres are selected before any UI adoption.
- **[minor]** `shared/src/androidMain/kotlin/me/manga/yamiapk/core/cbz/OptimizedCbzManager.kt:392` — decodeAvifImageSafely fully decodes large AVIF images twice
  - **Decision:** Rework decodeAvifImageSafely to probe AVIF dimensions via AvifDecoder.getInfo only (one real decode), accepting the loss of the current full-decode feasibility probe that doubles as the null check?
  - *Recommendation:* Probe dimensions via AvifDecoder.getInfo only, then perform a single real decode; or pass the already-decoded bitmap into the split path instead of recycling it.
- **[minor]** `shared/src/nonAndroidMain/kotlin/me/manga/yamiapk/presentation/features/download/domain/clean/CoroutineDownloadRepositoryImpl.kt:265` — Cancelling a QUEUED chapter can be silently undone: processJob unconditionally flips the row to RUNNING
  - **Decision:** OK to add a returning-count conditional RUNNING transition to ChapterDownloadDao (UPDATE ... WHERE chapterId=? AND state='QUEUED') and skip the job when 0 rows flipped, to fence the cancel-vs-pickup race on iOS/Desktop? (Native has the same race; this would make the KMP path stricter.)
  - *Recommendation:* Make the RUNNING transition conditional (UPDATE ... SET state='RUNNING' WHERE chapterId=? AND state='QUEUED', returning affected-row count) and skip the job when 0 rows were updated; requires a small DAO change.
- **[minor]** `shared/src/nonAndroidMain/kotlin/me/manga/yamiapk/presentation/features/download/domain/clean/CoroutineDownloadRepositoryImpl.kt:370` — Both download pipelines bypass the SourceRegistry and always use the legacy parser for page URLs, while the reader uses the generic engine for 12 piloted sources - reading and downloading the same chapter can use different parsers and diverge
  - **Decision:** Should download page-URL resolution be routed through the same SourceRegistry seam the reader uses (requires giving :shared / the download engines access to the registry — a build-graph change), so read and download can never diverge for piloted sources?
  - *Recommendation:* Resolve page URLs for downloads through the same seam the reader uses (inject SourceRegistry, or better: reuse ChapterPagesRepository/the registry's pages() flow) in both download engines, so read and download can never 
- **[minor]** `sources/contracts/src/commonMain/kotlin/me/manga/yamiapk/sources/contracts/Strategies.kt:16` — StrategyRegistry.hasExtraction and knownStrategies have no callers; the css/jsonpath/direct whitelist has no enforcement point
  - **Decision:** For the unused StrategyRegistry.hasExtraction/knownStrategies + extraction set: remove them until a config field references extraction strategies, or wire knownStrategies() into the validator's error messages as the KDoc intends?
  - *Recommendation:* Either remove hasExtraction/knownStrategies (and the extraction set) until a config field actually references extraction strategies, or wire knownStrategies() into validator error messages as the KDoc intends.
- **[minor]** `sources/engine/src/commonMain/kotlin/me/manga/yamiapk/sources/engine/internal/Extractor.kt:123` — Missing script-JSON island silently degrades to '{}' — produces empty Success that suppresses legacy fallback
  - **Decision:** Make Extractor.scriptJson() throw (mapped to Failure -> legacy fallback) when the <script id> island is missing/blank instead of returning "{}", and add a golden test for the missing-island case?
  - *Recommendation:* When the island is absent, throw (e.g. IllegalStateException("script island #<id> not found")) instead of returning "{}" — runRequest() already maps non-cancellation throwables to AppError.Unexpected → Failure → legacy f
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/library/LibraryScreen.kt:306` — Bulk-remove snackbar formats the localized template with a manual String.replace of '%1$d'
  - **Decision:** Library bulk-remove snackbar formats with manual String.replace("%1$d") (fragile but works in all locales today) — accept the fragility, or switch the collector to suspend getString() at the cost of breaking the pre-resolve convention?
  - *Recommendation:* Hoist the count formatting into composable scope is impossible (dynamic), but stringResource(Res.string.library_bulk_removed, count) can be pre-resolved per-effect by holding the effect in a state variable rendered by a 
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/updates/UpdatesScreen.kt:730` — Updates recency-bucketing + chapter-number sort is implemented twice (:data UpdatesRepositoryImpl.groupByDate and :ui UpdatesScreen.groupByRecency) and kept aligned by hand - it has already drifted once
  - **Decision:** Updates recency-bucketing is duplicated in :data and :ui (already drifted once) — make one layer authoritative by having presentation state expose pre-bucketed groups and deleting the :ui re-implementation?
  - *Recommendation:* Make one layer authoritative: have the presentation state expose the already-bucketed structure produced by :data (or move grouping into the ViewModel/State), and delete the :ui re-implementation.

## G. Owner-WIP / protected files (2)

- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/whatsnew/viewmodel/WhatsNewViewModel.kt:197` — Orphaned setting 'new_sources_added': written true on every app-version bump, but nothing in the KMP app ever reads it or clears it (native used it for the Home 'NEW' sources badge)
  - **Decision:** Defer with the P2 Home 'NEW' badge work (HomeScreen.kt is a forbidden owner-WIP path): implement the badge read + clear-on-edit alongside, or gate the orphan setNewSources(true) write until then?
  - *Recommendation:* When the deferred P2 badge work lands: add ObserveNewSourcesUseCase + a clear-on-edit write (setNewSources(false) on HomeIntent.OnEditTabs) and thread hasNewSources through HomeState as the HomeScreen comment prescribes.
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/library/LibraryScreen.kt:1663` — AppError->localized-message mapping copy-pasted across 5 :ui screens; NetworkErrorMessages is byte-identical in Home and Library, with diverging variants in Details/Reader/Search
  - **Decision:** Dedup the 5x-copied NetworkErrorMessages into a shared :ui file — OK to edit HomeScreen.kt (owner-WIP forbidden path) to remove its twin, or leave as-is?
  - *Recommendation:* Move NetworkErrorMessages + rememberNetworkErrorMessages() into one internal :ui shared file (e.g. components/AppErrorMessages.kt) parameterized on the per-screen fallback string; keep per-screen overrides only where the

## H. Bugs in the read-only legacy scrapers `sources_repositry/` (174)

These are real bugs in the per-source scraper classes, but `sources_repositry/` is the **read-only parity spec** (CLAUDE.md: edit only on explicit instruction). They affect specific sources only. **Decision: do you want me to fix these (all, or the 15 majors only)?** They're the kind of thing that breaks one source's home/search/details/reader when that site changes. Top (major) ones:

- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/ar/azora/AasqRepositoryv2.kt:59` — AasqRepositoryv2.initSite() never hydrates saved headers from DataStore — saved Cloudflare/auth cookies for 3asq are silently dropped every app session
  - *Recommendation:* Add `_cachedHeaders = dataStore.getHeadersForApi(API) ?: emptyMap()` at the top of initSite(), matching AzoraRepositoryv2; fix the defaultHeaders KDoc.
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/ar/mangamello/MangamelloRepository.kt:72` — handelLoadMoreUrl builds a malformed host (missing '/' after dropTrailingSlash) — legacy Mangamello pagination can never load
  - *Recommendation:* Remove .dropTrailingSlash() (matching homeUrl/popularUrl construction) or append "/api/v1/mangas?...": return "${baseUrl.ifBlank { mangaSource.BASEURL }.dropTrailingSlash()}/api/v1/mangas?sort_by=updated_at&page=$page". 
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/ar/mangamello/MangamelloRepository.kt:137` — Missing initSite() header-restore override (the documented 'Bug 4' fix its twin has) — persisted headers silently dropped after cold start
  - *Recommendation:* Add the same override as the twin: override suspend fun initSite(): Int { _cachedHeaders = dataStore.getHeadersForApi(API) ?: emptyMap(); return super.initSite() }
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/ar/promanga/ProMangaRepository.kt:165` — Chapter-load parse failure emits nothing — flow completes after only State.Loading (reader stuck loading)
  - *Recommendation:* In the Error branch, emit State.Error(0, state.message) when chapterImages is empty so the reader shows its error/retry UI.
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/ar/promanga/ProchanRepository.kt:148` — Same stuck-Loading error path as ProMangaRepository in the Prochan twin
  - *Recommendation:* Emit State.Error when chapterImages is empty in the Error branch (keep in sync with ProMangaRepository fix).
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/ar/promanga/models/imgs/ProMangaImageCombiner.kt:113` — Phase-8 stub emits only the first piece of each composite map page — readers see page fragments for ProManga/Prochan
  - *Recommendation:* Owner decision: either prioritize the Phase-8 expect/actual (or Skia commonMain) stitcher, or emit ALL pieces of each map in order (readers display them sequentially — imperfect pagination but no lost content), or surfac
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/ar/swatmanga/SwatMangaRepository.kt:226` — Chapters-fetch failure collapsed to Success(emptyList()) — details page silently shows zero chapters on transient errors
  - *Recommendation:* Owner decision: propagate chapter-fetch State.Error (or attach an isPartial flag to MangaInfo) so the UI can show error/retry instead of an empty chapter list.
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/common/NormalSites.kt:100` — logHeaders() dumps full header values (Cookie/cf_clearance/User-Agent) to logs on every details fetch
  - *Recommendation:* Log only header names and counts (or gate value-dumping behind a debug flag), matching the BaseManga diagnostic convention: Logger.withTag(tag).i { "headers: ${keys} count=${size}" }.
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/en/mangabuddy/MangaBuddyRepositoryV2.kt:283` — Invalid ksoup pseudo-selector ':containsf(Year)' throws on every MangaBuddy details parse — details page for this source always fails
  - *Recommendation:* Change ':containsf(Year)' to ':contains(Year)' (one character) — or delete the line entirely, since yearOfProduction is never used in the returned MangaInfo. Either restores the MangaBuddy details flow.
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/en/mangapark/MangaParkRepository.kt:399` — Parse failures swallowed into Success(emptyList()) — reader/search/home show empty results instead of errors on schema drift
  - *Recommendation:* Let the parse exception propagate to fetchData's catch (emitting State.Error), or return null and map null to State.Error like extractMangaDetails does, so schema drift surfaces as a retryable error instead of empty cont
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/fr/manga_origine/MangaOrigineRepository.kt:359` — ajax/chapters fallback swallows ALL exceptions (incl. CancellationException) — failed fetch silently yields 0 chapters
  - *Recommendation:* Re-throw CancellationException, and on other failures propagate the error (let the exception escape so fetchDataWithHeaders maps it to State.Error) instead of silently returning an empty chapter list; at minimum log via 
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/it/mangapark/MangaParkRepositoryIt.kt:7` — Italian MangaPark source missing from KMP app although its stated port blocker is gone (native has it live)
  - *Recommendation:* Uncomment/implement the documented subclass (language="it", API/LANGUAGE/BASE_URL from MangaSource.MANGAPARK_IT), register it in SharedModule like the other MangaPark variants, and refresh the stale KDoc. Owner decision 
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/pt/flowermanga/FlowerMangaRepository.kt:246` — All FlowerManga chapter dates are forced to 'today' — pt-BR date parsing stubbed to null (regression vs native)
  - *Recommendation:* Parse "d de MMMM de yyyy" with a local Portuguese month-name map (the same technique MangaworldItRepository.parseItalianDateText already uses with an Italian map) instead of waiting for a locale-aware formatter; keep tod
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/pt/manhastro/ManhastroRepository.kt:301` — Manhastro details depend on an in-memory home-feed cache; cache miss or search-result URLs make details error out
  - *Recommendation:* On cache miss, fetch the /dados feed (or the single manga endpoint) before failing; for search results, extract the numeric post id from the combined string (substringAfterLast('|')) and build the canonical .../dados/{id
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/pt/sussytoons/SussytoonsRepository.kt:205` — Required 'scan-id' header is permanently lost after the first Cloudflare header refresh
  - *Recommendation:* In refreshHeaders, merge the source's required header back in before caching/persisting: `val merged = newHeaders + mapOf("scan-id" to scanId.toString()); _cachedHeaders = merged; dataStore.saveHeadersForApi(API, merged)

<details><summary>+ 159 minor scraper findings</summary>

- [minor] `BaseMangaRepository.kt:17` — Stale class KDoc claims buildImageRequest/buildItemsImageRequest were removed — they exist 50 lines below
- [minor] `ar/azora/AasqRepositoryv2.kt:156` — genresSearch/sortSearch return `flow { fromCode(0) }` — the Error is constructed and discarded, the flow emits nothing
- [minor] `ar/azora/AasqRepositoryv2.kt:369` — Duplicated condition: `unitWord.contains("دقيقة") || unitWord.contains("دقيقة")` — plural 'دقائق' (minutes) never matched
- [minor] `ar/azora/AasqRepositoryv2.kt:260` — Keyboard-mash debug log tag logs every search URL at INFO in production
- [minor] `ar/azora/AzoraRepositoryv2.kt:115` — genresSearch/sortSearch `flow { fromCode(0) }` never emits (same no-emit bug as AasqRepositoryv2)
- [minor] `ar/azora/AzoraRepositoryv2.kt:73` — Search query interpolated into URL without percent-encoding
- [minor] `ar/azora/AzoraRepositoryv2.kt:188` — Five public helpers have zero callers: parseChapters, buildMangaDetailUrl, buildChapterImagesUrl, extractPostIdFromUrl, extractChapterIdFromUrl
- [minor] `ar/dilar/DilarRepository.kt:379` — parseChapters logs the ENTIRE chapters JSON response at INFO level on every call
- [minor] `ar/dilar/DilarRepository.kt:437` — getSearchResults validates pipe-count on the raw JSON string instead of the encrypted `data.data` field; decode+decrypt sit outside the local try
- [minor] `ar/dilar/DilarRepository.kt:419` — extractMangaInfo decode failure returns an all-blank MangaInfo with api="" and language=""
- [minor] `ar/dilar/models/chapter/Team.kt:7` — DTO Team is unused
- [minor] `ar/dilar/models/chapter/Uploader.kt:6` — DTO Uploader is unused
- [minor] `ar/dilar/models/home/Settings.kt:3` — Empty class Settings has zero usages (and no @Serializable annotation)
- [minor] `ar/dilar/models/payload/Filters.kt:1` — Filters.kt contains only a package declaration
- [minor] `ar/dilar/models/payload/PayLoadDto.kt:7` — Entire file is dead code: SearchPayload/OneShot/IncludeExclude/MinMax/StartEnd have zero references
- [minor] `ar/dilar/v2/DilarV2Models.kt:366` — altNames computed in toMangaInfo but never used
- [minor] `ar/dilar/v2/DilarV2Models.kt:341` — Null cover interpolated into image URL as the literal string 'null'
- [minor] `ar/dilar/v2/DilarV2Models.kt:400` — toChapterItems dedupes by chapter number only, ignoring season/volume — can silently drop chapters of multi-season series
- [minor] `ar/dilar/v2/DilarV2Repository.kt:254` — genresSearch/sortSearch return flow { fromCode(0) } — error State constructed but never emitted (empty Flow)
- [minor] `ar/dilar/v2/DilarV2Repository.kt:141` — Error-level debug logging: search bodies and full parsed chapter lists stringified on every call
- [minor] `ar/dilar/v2/DilarV2Repository.kt:58` — popularUrl = "" — legacy popular/featured fetch always requests an empty URL and fails
- [minor] `ar/lavatoon/LavatoonsParser.kt:9` — LavatoonsParser is dead code and carries a stale base URL that contradicts the live source config
- [minor] `ar/lavatoon/LavatoonsRepositoryv2.kt:238` — genresSearch/sortSearch return flow { fromCode(0) } — empty Flow, error never emitted
- [minor] `ar/mangalek/MangaLekParser.kt:9` — MangaLekParser is dead code (two constants, zero references)
- [minor] `ar/mangamello/MangamelloRepository.kt:67` — ifBlank fallback uses BASE_URL (which already ends in 'api/v1/mangas/') producing a doubled path if ever taken
- [minor] `ar/mangamelloplus/MangamelloPlusRepository.kt:117` — defaultHeaders getter logs the full merged header map (incl. persisted WebView/CF cookies) on every request
- [minor] `ar/mangamelloplus/MangamelloPlusRepository.kt:461` — Stale audit-postscript claim: imgsHeader is NOT orphan — ChapterDownloadService consumes it
- [minor] `ar/mangatuk/MangatukRepository.kt:152` — BASE_URL/homeUrl/popularUrl frozen via lazy while load-more/search URLs recompute — split-brain after a base-URL change
- [minor] `ar/mangatuk/MangatukRepository.kt:460` — runCatching in chapter-pagination loop (and catch(Exception) in AJAX fetch) swallows CancellationException
- [minor] `ar/mangatuk/MangatukRepository.kt:709` — Arabic relative-date parser mishandles dual forms: 'يومين'/'شهرين' parsed as 1 unit, 'ساعتين' matches no branch
- [minor] `ar/mangatuk/MangatukRepository.kt:441` — AJAX chapter fallback gated on data-id presence but the request never uses it — AJAX-only pages without data-id get an empty chapter list
- [minor] `ar/promanga/ProMangaRepository.kt:205` — Chapters with only single images (maps null/empty) yield zero pages — metadata.images ignored in the else branch
- [minor] `ar/promanga/ProMangaRepository.kt:94` — Saved headers never restored: refreshHeaders persists to DataStore but no initSite override reads them back
- [minor] `ar/promanga/ProMangaRepository.kt:449` — toMangaInfo null-stringification: missing published_at dates every chapter 'today', thumbnail can become 'https://cdn2.prochan.netnull', status can be literal 'null'
- [minor] `ar/promanga/ProchanRepository.kt:76` — apiUrl is `by lazy` over mutable baseUrl — frozen at first access, so stored base-URL (domain-move) updates never apply
- [minor] `ar/promanga/models/ProMangaModels.kt:115` — ProMangaSeriesResponse, ProMangaChapterRef, ProMangaChapter (and ProMangaImage) are unused
- [minor] `ar/promanga/models/imgs/ProMangaImageCombiner.kt:26` — Stale KDoc: header claims stub 'emits ImageCombinerState.Error immediately and produces no images' — code does the opposite
- [minor] `ar/promanga/models/imgs/test/test.kt:3` — Entire imgs/test package (Map.kt, Metadata.kt, test.kt) is unused JSON-codegen scratch output
- [minor] `ar/promanga/models/info/Card.kt:4` — info/Card and info/CoverImageApp are unused — nothing in ProInfo/MetadataX references them
- [minor] `ar/swatmanga/SwatMangaRepository.kt:361` — hasBlacklistedGenres is never called and blackListGenres is empty — no repo-level adult-content gating for this source
- [minor] `ar/swatmanga/SwatMangaRepository.kt:448` — Null-stringification in built URLs: chapter URL can become '.../chapters/null/' and item URLs '.../null'
- [minor] `ar/swatmanga/SwatMangaRepository.kt:163` — Entire response bodies logged on every fetch (Info/Debug) with no Kermit minSeverity configured
- [minor] `ar/swatmanga/SwatMangaRepository.kt:182` — Search query interpolated raw into URL without encoding — '&'/'#' in user input truncates or corrupts the query
- [minor] `ar/swatmanga/models/Dataclasses.kt:9` — Entire file (18 @Serializable models, 269 lines) is dead — repository deserializes via the per-folder model families instead
- [minor] `ar/teamx/TeamXRepositoryv2.kt:129` — genresSearch/sortSearch return flow { fromCode(0) } that never emits anything
- [minor] `ar/teamx/TeamXRepositoryv2.kt:255` — Nullable .toString() produces literal "null" strings in chapter labels and PopularManga fields
- [minor] `ar/teamx/TeamXRepositoryv2.kt:175` — extractMangaInfo re-fetches chapter page 1 it was already given, and dumps a 2k document slice to INFO logs
- [minor] `ar/teamx/TeamXRepositoryv2.kt:69` — by-lazy URL properties pin the base URL at first access, defeating mid-session domain hot-swap
- [minor] `ar/teamx/TeamxParser.kt:9` — TeamxParser class is dead code — never instantiated or referenced
- [minor] `ar/user_agents/UserAgents.kt:7` — UserAgents object is dead code in the KMP graph (and its own postscript falsely claims live usage)
- [minor] `common/BaseManga.kt:59` — Keyboard-mash debug log tags in hot request paths across the common bases
- [minor] `common/BaseManga.kt:133` — ensureSiteInitialized latches success even when initSite() reports failure via its Int return code
- [minor] `common/NormalSites.kt:89` — fetchPopularManga GET branch omits defaultHeaders, so saved Cloudflare cookies are not applied to popular requests
- [minor] `common/NormalSitesv2.kt:41` — useGetForSearch toggle is declared but never consumed (here and in SeparatedDetailsSitesv2)
- [minor] `common/SeparatedDetailsSites.kt:108` — Chapters-endpoint failures are silently converted to an empty chapter list with no user signal
- [minor] `common/SeparatedDetailsSitesv2.kt:159` — chaptersFlow branches on useGetForInfo (and uses handelFormBodyMangaInfo) instead of useGetForChapters
- [minor] `en/batcave/BatcaveRepository.kt:133` — initSite logs resolved Cookie header value at INFO level
- [minor] `en/batcave/BatcaveRepository.kt:89` — initSite POSTs to a hardcoded, captured one-time Cloudflare challenge URL (embedded timestamp/nonce)
- [minor] `en/batcave/BatcaveRepository.kt:794` — Copy-paste TAG "DemonicScansRepository" mislabels all Batcave logs; several dead locals
- [minor] `en/batoto_en/BatotoEnRepositoryv2.kt:521` — getChapterImages computes <img>-tag results then discards them; only the JS-array fallback is ever returned
- [minor] `en/batoto_en/BatotoEnRepositoryv2.kt:376` — MangaInfo.status becomes the literal string "null" when the selector misses; uploadStatus is read but never used
- [minor] `en/comick_io/ComickRepository.kt:525` — Private fetchData swallows CancellationException and surfaces raw response bodies as error messages
- [minor] `en/comick_io/ComickRepository.kt:327` — fetchMangaChaptersF hardcodes lang=en for the info endpoint while chapters endpoint uses the open `language` property
- [minor] `en/comick_io/ComickRepository.kt:287` — Entire home JSON payload logged at INFO on every home fetch
- [minor] `en/comick_io/ComickRepository.kt:549` — Dead converter toMangaItems + dead models/home DTO package + unused companion constants and discarded locals
- [minor] `en/comick_io/ComickRepository.kt:485` — Popular feed bypasses the adult-genre blacklist (filter commented out) while home/search enforce it
- [minor] `en/comick_io/ComickRepository.kt:496` — extractChapterImgs: unreachable Elvis branch and ".../null" image URLs for null b2key
- [minor] `en/comick_io/models/info/Links.kt:3` — Dead comick_io model files: Links, Recommendation, Relates (non-serializable, unreferenced) and unused homeV2/Search typealiases
- [minor] `en/comick_io/models/info/MuComics.kt:8` — Non-nullable, non-defaulted fields in MuComics/MuCategories make the whole comick Info decode brittle to API drift
- [minor] `en/demonicscans/DemonicScansRepository.kt:271` — Debug logging remnants: full HTML pages logged at info under keyboard-mashed tags; unused companion TAG
- [minor] `en/demonicscans/DemonicScansRepository.kt:135` — Inconsistent URL composition: home-section chapter URLs keep the base trailing slash while every other URL in the file drops it
- [minor] `en/demonicscans/DemonicScansRepository.kt:178` — split("<br>") on Element.text() can never match — first-line-of-title intent is dead logic
- [minor] `en/demonicscans/DemonicScansRepository.kt:251` — Stale comment claims chapter list is reversed; no reversal happens
- [minor] `en/mangabuddy/MangaBuddyParser.kt:38` — Entire MangaBuddyParser class is dead code, and it silently diverges from the live repository copy
- [minor] `en/mangabuddy/MangaBuddyRepositoryV2.kt:129` — parseChapters uses '!!' on scraped elements — markup drift becomes NPE that is silently converted to an empty chapter list
- [minor] `en/mangabuddy/MangaBuddyRepositoryV2.kt:387` — getChapterImages re-serializes and re-parses the whole chapter document for nothing; stale base-URL comment; no-op mapIndexed
- [minor] `en/mangabuddy/MangaBuddyRepositoryV2.kt:211` — Empty if (hotSection != null) {} block — dead code
- [minor] `en/mangabuddy/MangaBuddyRepositoryV2.kt:253` — extractMangaInfo/getSearchResults compute six values that are never used
- [minor] `en/mangapark/MangaParkRepository.kt:563` — Local fetchData catches Exception without rethrowing CancellationException (eats cooperative cancellation)
- [minor] `en/mangapark/MangaParkRepository.kt:363` — Full JSON response bodies logged at info level on every request, under keyboard-mashed tags
- [minor] `en/manhwatop/ManhwatopParser.kt:36` — Entire ManhwatopParser class is dead code
- [minor] `en/manhwatop/ManhwatopParser.kt:238` — author = authors.toString() renders '[Author A, Author B]' with brackets — and the same bug is live in ManhwatopRepositoryV2
- [minor] `en/manhwatop/ManhwatopParser.kt:209` — selectFirst("a")!! in chapter mapping — NPE on markup drift
- [minor] `en/manhwatop/ManhwatopRepositoryV2.kt:290` — fetchChapters logs full HttpResponse and entire parsed chapter DOM at INFO with keyboard-mash tags
- [minor] `en/manhwatop/ManhwatopRepositoryV2.kt:263` — mangaId extracted in extractMangaInfo but never used
- [minor] `en/manhwatop/ManhwatopRepositoryV2.kt:419` — blackListGenres is never consulted for this source (adult-content filter inert) and lists "Yaoi" three times
- [minor] `en/manhwatop/ManhwatopRepositoryV2.kt:119` — Search query interpolated raw into URL without encoding
- [minor] `en/readcomiconline/ReadComicOnlineRepository.kt:11` — Header KDoc claims Dto.kt holds "BatcaveImages / BatcaveDto" — BatcaveDto does not exist
- [minor] `en/tapastic/TapasticModels.kt:222` — Tapas chapter dates are always 'today': parseDate result discarded; header rationale for keeping it is impossible
- [minor] `en/tapastic/TapasticModels.kt:252` — TapasChapterListDto and both hasNextPage() helpers are unreferenced
- [minor] `en/tapastic/TapasticRepository.kt:422` — Missing pagination total falls back to firing up to 999 page requests, not a sequential walk
- [minor] `en/tapastic/TapasticRepository.kt:407` — Two chapter-list code paths apply different accessibility filters
- [minor] `en/tapastic/TapasticRepository.kt:255` — Mash-tag INFO logs dump full item lists; chapters-page URL logged at ERROR severity
- [minor] `en/tapastic/TapasticRepository.kt:512` — buildChapterUrl / extractEpisodeIdFromUrl unreferenced; extractSeriesId has a redundant branch
- [minor] `en/tapastic/TapasticRepository.kt:353` — Chapter-fetch failures coerced to Success(emptyList()) — indistinguishable from a manga with no chapters
- [minor] `en/tapastic/TapasticRepository.kt:192` — blackListGenres ("BL", "LGBTQ+", "GL") is declared but never applied; postscript claims an inherited filter that does not exist
- [minor] `en/zazamanga/ZazamangaRepository.kt:471` — English date parser is unreachable — parseItalianDateText never throws, so chapter dates collapse to 'today'
- [minor] `en/zazamanga/ZazamangaRepository.kt:626` — Entire parsed HTML document logged at INFO on every search and every chapter load, plus per-item parse logs
- [minor] `en/zazamanga/ZazamangaRepository.kt:707` — getChapterImages Method-3 fallback selects every img[data-src] on the page
- [minor] `en/zazamanga/ZazamangaRepository.kt:298` — 18-entry adult blacklist is inert (only call site commented out) and two entries use underscores that can never match
- [minor] `en/zazamanga/ZazamangaRepository.kt:91` — sortTypes empty (all entries commented out) while the SORT search URL path is implemented; genreMap keys unused with duplicate/typo entries
- [minor] `en/zazamanga/ZazamangaRepository.kt:741` — Element.imgAttr() extension is never called
- [minor] `en/zazamanga/ZazamangaRepository.kt:86` — Search/genre queries interpolated raw into URLs without encoding
- [minor] `es/inmanga/InMangaRepository.kt:101` — filter[take] = "w0" — probable upstream typo for "20" in the home/popular form body
- [minor] `es/inmanga/InMangaRepository.kt:126` — Search pagination is a no-op: page parameter ignored, skip hardcoded to 0
- [minor] `es/inmanga/InMangaRepository.kt:72` — Commented-out initSite means persisted headers are never reloaded; logBig helper is an orphan
- [minor] `es/manhwaweb/ManhwawebEsRepository.kt:111` — Unmapped genre/sort values interpolate the literal string "null" into request URLs
- [minor] `es/manhwaweb/ManhwawebEsRepository.kt:261` — extractMangaInfo parse failure returns an all-empty MangaInfo (api="", url="") as success
- [minor] `es/manhwaweb/ManhwawebEsRepository.kt:76` — Stale Referer-merge comments, duplicated base-URL literal, and copy-pasted 'dilarItems' names
- [minor] `es/manhwaweb/ManhwawebEsRepository.kt:326` — Library/search results expose raw numeric category IDs as genre strings
- [minor] `es/manhwaweb/ManhwawebEsRepository.kt:109` — Search query interpolated raw into buscar= parameter without encoding
- [minor] `es/olympusbiblioteca/OlympusbibliotecaRepository.kt:193` — Home pagination hard-capped at a frozen 51 pages, returning silently with no emission
- [minor] `es/olympusbiblioteca/OlympusbibliotecaRepository.kt:409` — fetchData discards HTTP status and error body — every failure becomes Error(0, "Unexpected error")
- [minor] `es/olympusbiblioteca/OlympusbibliotecaRepository.kt:582` — ~110 lines of dead code: fetchAllChaptersAsyncAdvanced + retry helper, sanitizeSlug, extractMangaChapters, unused TAG
- [minor] `es/olympusbiblioteca/OlympusbibliotecaRepository.kt:486` — fetchMangaChaptersF never checks chapters-flow errors — failures yield Success with an empty chapter list
- [minor] `es/olympusbiblioteca/OlympusbibliotecaRepository.kt:107` — Per-source base-URL override is a no-op: getBaseUrl() stores the DB value but every request uses the hardcoded apiUrl
- [minor] `es/olympusbiblioteca/OlympusbibliotecaRepository.kt:176` — Genre search hits a speculative endpoint the author admits may not exist; queries also unencoded
- [minor] `es/olympusbiblioteca/models/home/Link.kt:7` — Unused DTO: home.Link is referenced nowhere
- [minor] `es/taurusfansub/TaurusFansubEsRepository.kt:350` — Keymash-tag debug log prints every chapter URL at INFO inside the details parse loop
- [minor] `es/taurusfansub/TaurusFansubEsRepository.kt:341` — Per-item !! assertions inside map loops let one malformed card/row fail the whole page
- [minor] `es/taurusfansub/TaurusFansubEsRepository.kt:520` — Duplicate "Smut" entry in blackListGenres and U+2011 hyphen-variant duplicates in allGenres
- [minor] `fr/manga_origine/MangaOrigineRepository.kt:192` — Keymash-tag debug logs print every item's image URL at INFO during home/popular parsing
- [minor] `fr/manga_origine/MangaOrigineRepository.kt:537` — Chapter-date parser only understands Arabic relative dates and English month names on a French site — dates default to today
- [minor] `fr/manga_origine/MangaOrigineRepository.kt:346` — Unused locals and helpers: artist/ratingCount/favoritesCount/yearOfProduction computed then dropped; URL_SEARCH_PREFIX unused; redundant nested img select
- [minor] `fr/raijinscan/RaijinScanRepository.kt:53` — Home feed switches sort order when paginating: page 1 is recently_added, load-more pages are most_viewed
- [minor] `fr/raijinscan/RaijinScanRepository.kt:148` — Chapter parsing fragility: nullable date stringified to "null", dead elvis, per-row !!, and throwing Base64 decode
- [minor] `in/comick_io/ComickRepositoryId.kt:8` — Stale stub KDoc: claims EN ComickRepository base class is not yet ported (it is)
- [minor] `in/komiku/KomikuRepository.kt:225` — Keymash-tag debug log on every details parse
- [minor] `in/komiku/KomikuRepository.kt:70` — Home feed switches ordering when paginating: page 1 orderby=modified, load-more orderby=meta_value_num
- [minor] `it/comick_io/ComickRepositoryIt.kt:35` — Stale stub TODO: 'port EN ComickRepository to commonMain (prerequisite)' already satisfied
- [minor] `it/mangaworld/MangaworldItRepository.kt:7` — Stale KDoc claims Italian month parsing 'returns null -> fallback to today' — the code actually parses Italian months
- [minor] `it/mangaworld/MangaworldItRepository.kt:443` — Dead helpers: extractTotalPages and Element.imgAttr are never called
- [minor] `pt/comick_io/ComickRepositoryPtBr.kt:37` — Stale stub TODO: EN ComickRepository prerequisite already landed
- [minor] `pt/flowermanga/FlowerMangaRepository.kt:397` — Dead helpers: imageFromElement, URL_REGEX and String.getSrcSetImage are never used
- [minor] `pt/manhastro/ManhastroDadosStore.kt:63` — Keymash-tag log in clear()
- [minor] `pt/manhastro/ManhastroRepository.kt:182` — Full response payloads logged at INFO with keymash tags on every chapters/home fetch
- [minor] `pt/manhastro/ManhastroRepository.kt:128` — Dead/misleading members: refererHeader is an Accept header and unused; empty popularUrl makes extractMangaList unreachable; large commented blocks
- [minor] `pt/manhastro/ManhastroRepository.kt:556` — getSearchResults uses per-card !! assertions — one malformed result card fails the whole search
- [minor] `pt/manhastro/models/imgs/ChapterPages.kt:6` — ChapterPages model is unreferenced in the KMP port
- [minor] `pt/mediocretoons/MediocretoonsRepository.kt:137` — Leftover keyboard-mash debug logging dumps the full home payload at INFO
- [minor] `pt/mediocretoons/MediocretoonsRepository.kt:63` — Unused chapterimgUrl; cover-image CDN base hardcoded instead of using imgBaseUrl
- [minor] `pt/sussytoons/SussytoonsRepository.kt:228` — unwrapJsonResponse conflicts with ResultDto's @JsonNames — flat list responses would decode to a silently-empty list
- [minor] `pt/sussytoons/SussytoonsRepository.kt:416` — logJsonRaw pretty-prints and logs the full API payload on every request for all five verbs
- [minor] `pt/sussytoons/SussytoonsRepository.kt:66` — Unused newApiUrl and defaultScanId fields
- [minor] `pt/sussytoons/models/GreenShitModels.kt:150` — Ten unreferenced DTOs from the unported GreenShit login/filter features
- [minor] `ru/desu/DesuRepository.kt:267` — unixToYear is dead since the MangaInfo field prune, but the file KDoc still documents it as the live port
- [minor] `ru/desu/DesuRepository.kt:70` — by-lazy URL properties freeze baseUrl, so a sources-DB host override never reaches home/popular/search/item URLs in-session
- [minor] `ru/desu/models/info/Translator.kt:6` — Translator DTO is referenced nowhere
- [minor] `ru/mangahub/MangahubRepository.kt:242` — extractMangaInfo renders literal "null" description; dead locals and unused import
- [minor] `ru/mangahub/MangahubRepository.kt:65` — defaultHeaders is `by lazy`, so headers refreshed after first use are never applied
- [minor] `ru/senkuro/SenkuroRepository.kt:409` — fetchMoreManga discards the final page of results when hasNextPage is false
- [minor] `ru/senkuro/SenkuroRepository.kt:186` — Full GraphQL response bodies logged on every parse, at error severity, in production
- [minor] `tr/timenaight/TimenaightRepository.kt:179` — Entire parsed HTML document serialized and logged on every details/search/pages parse; dead artist/year locals
- [minor] `tr/webtoonatti/WebtoonhattiRepository.kt:195` — Chapter-date parsing: 'ago' first branch swallows all relative dates; duplicated 'ongoing' condition
- [minor] `tr/webtoonatti/WebtoonhattiRepository.kt:386` — getChapterImages hardcodes the image-host whitelist to current domains
- [minor] `tr/webtoontr/WebtoontrRepository.kt:198` — Same date/status parsing quirks as Webtoonhatti ('ago' branch shadowing, duplicated 'ongoing'), plus full-doc junk-tag logging
- [minor] `tr/webtoontr/WebtoontrRepository.kt:42` — initSite logs the persisted per-source headers (may include cf_clearance cookies) at info level
- [minor] `tr/xzczxczxcv.kt:7` — Empty garbage-named object xzczxczxcv is dead code
- [minor] `ar/dilar/CryptoUtils.ios.kt:112` — aesDecrypt guards empty ciphertext but not empty IV — malformed IV crashes via iv.refTo(0)

</details>

## I. Owner-WIP files (left untouched) (6)

Findings in files you were actively editing (`HomeScreen.kt`, `MangaLekRepositoryv2.kt`, `MangaSource.kt`). Left untouched per the standing rule — fold these into your own WIP if you agree:

- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/ar/mangalek/MangaLekRepositoryv2.kt:609` — String.toMetaKey() returns the literal string "null" for unmapped sort types
  - *Recommendation:* return genreToMetaKeyMap[this] ?: "_latest_update" (or error visibly).
- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/ar/mangalek/MangaLekRepositoryv2.kt:347` — Unused toMangaItemList extension has swapped fields (url=info.api, language=BASEURL)
  - *Recommendation:* Delete the function (preferred), or fix to url = info.url / language = MangaSource.MANGA_LEK.LANGUAGE.Language if it is ever needed.
- **[minor]** `shared/src/commonMain/kotlin/me/manga/yamiapk/sources_repositry/ar/mangalek/MangaLekRepositoryv2.kt:234` — Info-level debug logging stringifies the full home/search result list on every parse (partly uncommitted WIP)
  - *Recommendation:* Drop the debug log lines (owner to handle — file is dirty owner WIP; do not modify).
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/home/HomeScreen.kt:217` — Leftover info-level Kermit logging of WebView URL on every open
  - *Recommendation:* Remove the Logger call (keep the local captures only if still needed for the callback).
- **[minor]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/home/HomeScreen.kt:499` — Grid-mode next-page spinner occupies a single grid cell instead of spanning the row
  - *Recommendation:* Use item(key = "__nextpage__", span = { GridItemSpan(maxLineSpan) }) { NextPageSpinner() }.
- **[major]** `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/home/HomeScreen.kt:468` — Home feed scroll position is reset to top on EVERY re-entry (back from Details, tab return, rotation) — the KMP port dropped native's previousTabIndex guard
  - *Recommendation:* Restore the native guard: `var previousTabIndex by remember { mutableStateOf(state.activeTabIndex) }` and inside the effect only call scrollToItem(0) when `previousTabIndex != state.activeTabIndex` (then update previousT

## (Excluded per your instruction) Firebase / Firestore security rules (3)

Not in your action list — recorded only so nothing is silently dropped:
- **[minor]** `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/AdminComplaintListRepository.kt:175` — Admin-wide complaint read (all users' submissions incl. userIds) is gated only by a client-side navigation check
  - *Recommendation:* Verify the Firestore security rules restrict collection-wide reads (and admin mutations: status change, closure reason, delete, edit) to authenticated admin identities server-side; if they do not, enforce it in rules - c
- **[major]** `shared/src/commonMain/kotlin/me/manga/yamiapk/admin/Admin.kt:47` — Admin.isAdmin defaults to true and is never assigned anywhere — every install gets the admin complaints dashboard
  - *Recommendation:* Default isAdmin to false and gate it on a real signal (e.g. allowlisted UserIdProvider value or remote config), or remove the admin UI from production builds; verify Firestore security rules independently.
- **[critical]** `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/complaint/repository/ComplaintFirestoreRestDataSource.kt:141` — Unauthenticated update/delete of arbitrary complaint documents (admin gating is client-side only)
  - *Recommendation:* Server-side: tighten Firestore rules (owner-scoped writes, admin custom-claim for status changes); client: attach an auth identity. Cannot be fixed client-only.


---

# ⚠️ USER FINAL DECISIONS & EXECUTION PLAN ⚠️
@Agent: I have reviewed the items above. Please read my final decisions below and execute the automatic fixes based strictly on these instructions:

### General Rules:
* **Firebase / Firestore:** IGNORE completely. Do not make any changes.
* **Settings wipe on upgrade:** DO NOT wipe settings. Preserve user settings on app upgrades.

### Group A (UX Decisions):
1.  **In-app update:** Implement native (Android Play Core / iOS StoreKit). Leave Desktop empty.
2.  **CrashActivity:** Start synchronously and terminate explicitly. CRITICAL: Ensure Firebase Crashlytics logging is preserved.
3.  **Coil Disk Cache:** Save explicitly in the app's own directory across all platforms.
4.  **Notification Permission Toast:** Show ONLY after an actual denial from the user.
5.  **Genre Search:** If the source supports genres, use them. If not, fallback to general search.
6.  **KCEF Desktop Loading:** Run asynchronously / show a native progress window. (Use Fable model/sub-agent to ensure perfect Compose Desktop implementation).
7.  **Incognito Check:** Use a direct variable/accessor. Avoid heavy folder reads.
8.  **Connectivity:** Use OS-level network reachability instead of pinging Google.
9.  **iOS AVIF RAM:** Decode directly at target size using ImageIO.
10. **Delete Downloaded Files:** Physically delete files from storage on ALL platforms (match legacy app).
11. **Home Sync on Source Disable:** Clamp index, reset, and refetch feed automatically.
12. **Desktop Web RAM Cache:** Use bounded, disk-backed cache (FileStorage).
13. **iOS Web RAM Cache:** Use bounded, disk-backed cache.
14. **Reader Auto-advance:** Add a dummy "Next Chapter" page. Background load the next chapter when the user reaches the *actual last page* of the current chapter.

### Group B (Parity vs. Improvement):
* **Decision:** Apply the improvements. You do not need strict legacy parity if a better approach exists.

### Group C (Large Deletions):
* **Decision:** APPROVED. Delete all unused assets (drawables, strings, dead routes) to save space.

### Group H (Scrapers):
* **Decision:** FIX the sources using the NEW JSON approach. IGNORE/LEAVE the sources using the old approach (I will replace them myself).

### Group D (Ambiguous) & Group F (Translations):
* [اكتب قراراتك هنا بنفس الطريقة، مثلاً: "For Group F: Replace English text with string resources but leave translations empty for me to fill later"]
* [اكتب قرارك الثاني هنا]

---

# ⚠️ USER FINAL DECISIONS & EXECUTION PLAN ⚠️

### General Rules:
* **Firebase / Firestore:** IGNORE completely. Do not make any changes.
* **Settings wipe on upgrade:** DO NOT wipe settings. Preserve user settings on app upgrades.

### Group A (UX Decisions):
1.  **In-app update:** Implement native (Android Play Core / iOS StoreKit). Leave Desktop empty.
2.  **CrashActivity:** Start synchronously and terminate explicitly. CRITICAL: Ensure Firebase Crashlytics logging is preserved.
3.  **Coil Disk Cache:** Save explicitly in the app's own directory across all platforms.
4.  **Notification Permission Toast:** Show ONLY after an actual denial from the user.
5.  **Genre Search:** If the source explicitly supports genres, use them. If not, fallback to a general text search only.
6.  **KCEF Desktop Loading:** Open the window immediately and run KCEF.init asynchronously (or show a native progress window). IMPORTANT: Delegate the implementation of this specific KCEF task to a sub-agent using the "Fable" model to ensure it is written perfectly without errors for Compose Desktop.
7.  **Incognito Check:** Use a direct variable/accessor. Avoid heavy folder reads.
8.  **Connectivity:** Use OS-level network reachability instead of pinging Google.
9.  **iOS AVIF RAM:** Decode directly at target size using ImageIO.
10. **Delete Downloaded Files:** Physically delete files from storage on ALL platforms (exactly like the legacy app).
11. **Home Sync on Source Disable:** Clamp index, reset, and refetch feed automatically.
12. **Desktop Web RAM Cache:** Use bounded, disk-backed cache (FileStorage).
13. **iOS Web RAM Cache:** Use bounded, disk-backed cache.
14. **Reader Auto-advance:** Add a dummy "Next Chapter" page. Background load the next chapter when the user reaches the *actual last page* of the current chapter (before they swipe to the dummy page).

### Group B (Parity vs. Improvement):
* **Decision:** Apply the improvements. You do not need strict legacy parity if a better approach exists.

### Group C (Large Deletions):
* **Decision:** APPROVED. Delete all completely unused assets (drawables, strings, dead routes) to save space.

### Group H (Scrapers):
* **Decision:** FIX the bugs in the sources using the NEW JSON approach. IGNORE/LEAVE the sources using the old approach exactly as they are (I will replace them myself later).

### Group E (External Config / Infra):
* **Decision:** Update `versionCode` to 36 and `versionName` to "1.0.36" (or equivalent shared properties). Remove unused iOS frameworks. Leave release signing as is.

### Group F (Translations):
* **Decision:** Extract all hardcoded English strings into string resources. Use English as the default. Leave other languages (like Arabic) empty or as placeholders for me to translate later.

### Group D (Ambiguous Items):
* **Decision:** Apply your safest recommended fix for all ambiguous items. Prioritize app stability, memory safety, and modern KMP/Compose best practices.

### Group I (Owner-WIP Files):
* **Decision:** IGNORE completely. Do not touch files I am currently working on (`HomeScreen.kt`, `MangaLek*`, `MangaSou*`, etc.).
