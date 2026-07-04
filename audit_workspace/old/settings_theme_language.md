# OLD Native Android Audit — Settings + Theme-Selection + Language Cluster

Source root: `D:/yami manga/yami-manga-apk-main/app/src/main/java/me/manga/yamiapk/`
(package is `me.manga.kira`; folder is `me/manga/yami/`). READ-ONLY audit. Cite `file:line`.

This cluster covers: **Settings hub** (every row + action), **Theme selection** (onboarding picker + permission), **Language selection** (list + request dialog). Shared design-system components used by all three are documented inline and summarized in **Cluster notes**.

---

### SettingsScreen
- **Entry/route:** `Screen.Setting` (object) → `composable<Screen.Setting>` in `NavGraphV2.kt:514` → `SettingsRoute(navController, backStackEntry)` (`NavGraphV2.kt:516`). `SideEffect { onBottomBarVisibleChange(true) }` (`NavGraphV2.kt:515`) — Settings is a **bottom-bar tab** (bottom nav stays visible). The old `SettingsRoute` adapter file was not found under `app/.../navigation/routes/SettingsRoute.kt` (only imported at `NavGraphV2.kt:42`); the screen composable is `SettingsScreen.kt`. (INFERRED: `SettingsRoute` is a thin wrapper that calls `SettingsScreen(navController)`.)
- **Layout & components:** `Scaffold` (Material **M2** `androidx.compose.material.Scaffold`) with `backgroundColor = colorScheme.background` and a `SnackbarHost(snackbarHostState)` (M3) (`SettingsScreen.kt:101-104`). Body is a single `LazyColumn`, `fillMaxSize().background(colorScheme.background).padding(paddingValues).padding(horizontal=16.dp, vertical=8.dp)`, `horizontalAlignment = CenterHorizontally` (`SettingsScreen.kt:105-112`). Content order:
  1. **Header image** — `Image(painterResource(R.drawable.ic_launcher_foreground))`, `size(250.dp).padding(vertical=24.dp)`, contentDescription `"Header Icon"` (`SettingsScreen.kt:113-121`).
  2. **General settings group** — preceded by a `Divider(Color.Gray.copy(alpha=0.3f))` + `Spacer(24.dp)`, wrapped in `ItemsGroup{}` card (`SettingsScreen.kt:124-186`).
  3. **Download/CBZ group** — `Spacer(24.dp)` + `ItemsGroup{}` (`SettingsScreen.kt:189-240`).
  4. **Navigation group** — `Spacer(24.dp)` + `ItemsGroup{}` (`SettingsScreen.kt:244-279`).
  5. **Other group** — `Spacer(24.dp)` + `ItemsGroup{}` (`SettingsScreen.kt:282-311`).
  - `ItemsGroup` (`ItemsGroup.kt:16-29`) = `Column.fillMaxWidth().background(color=surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(horizontal=16.dp, vertical=8.dp)`. Default container color `surfaceContainerHigh`.
  - Inner rows separated by `Divider(color = colorScheme.background.copy(alpha=0.8f))`.
- **Visual:** group cards are 16.dp-rounded `surfaceContainerHigh` panels; outer 16.dp horizontal padding; 24.dp spacers between groups. Header icon 250.dp. Switch rows (`SwitchItem`) use 14.sp title + 12.sp description (alpha 0.5). Nav rows (`SettingsNavigationItem`) use 14.sp title + auto-sized 12→6.sp description (alpha 0.8), 24.dp leading icon + 16.dp gap, trailing `KeyboardArrowRight` chevron. Section dividers are nearly-invisible (background-colored, alpha 0.8).
- **States:** No loading/empty/error skeleton for the screen — it is a static config list. **Cache size** has a transient state: starts as `R.string.calculating` ("Calculating…", `strings.xml:141`), then recomputed off `Dispatchers.IO` on init and after clear (`SettingsViewModel.kt:39,42-45,72-74`). **CBZ conversion** has its own loading/success/error states surfaced via `CbzConversionDialog` (see Dialogs). All toggles are reactive via `collectAsStateWithLifecycle`.
- **Interactions:**
  - Switches: `downloadedOnly`, `incognito`, `followSystem`, `darkMode` (conditional), `pureBlack`, CBZ `useCbz`, plus an admin-only `Testing Mode` switch.
  - Nav rows clickable → navigate; `clearCache` row triggers IO deletion; `feedback` row opens `FeedbackDialog`; `reading mode` row opens `ReadingModeDialog`; `Help` row is **inert** (no `onClick`, `SettingsScreen.kt:307-311`).
  - "Start conversion" `Button` (CBZ) → `cbzViewModel.startConversion()`; disabled while `conversionProgress.isConverting`, shows inline spinner + "Converting".
  - No explicit animations beyond default Compose/ripple and the `AutoSubtitleText` auto-size.
- **Dialogs/sheets/snackbars:**
  - **FeedbackDialog** (`SettingsScreen.kt:316-350`): header `R.string.request_feature_bug`, field label `R.string.enter_your_feedback`; on submit → `complaintViewModel.submit(type, displayName, body, onSuccess, onError)`; success → snackbar `R.string.request_submitted_successfully` (Short); error → snackbar `R.string.request_failed` w/ actionLabel `R.string.retry` (Long).
  - **ReadingModeDialog** (`SettingsScreen.kt:352-365`): driven by `showReadingModeDialog`; `currentMode` from `chaptersViewModel.readingMode`; `onModeSelected → chaptersViewModel.setReadingMode`; apply/dismiss both close.
  - **Adult/M dialog chain** (`dialogState`: `AdultWarning → MStep1 → MStep2 → None`, `SettingsScreen.kt:367-406`) using `AdultConfirmationDialog` + `MConfirmationDialog(images = imgs1/imgs2)`. NOTE: `dialogState` is initialized to `None` and there is **no UI affordance in this file that sets it to `AdultWarning`** (only the admin `Testing Mode` and `Admin.isAdmin` complaint-route branch reference admin). (INFERRED: this dialog chain is effectively dead/unreachable from Settings as written, or triggered elsewhere; flagged.)
  - **CbzConversionDialog** (`SettingsScreen.kt:408-417`): always composed; self-gates on `isConverting || error != null || successMessage != null`; dismiss either stops conversion (if running) or clears error.
  - **Snackbar** host is M3 `SnackbarHostState`.
- **Forms & validation:** Only the FeedbackDialog has a form (category dropdown + min-5-char body, ≤500). See FeedbackDialog section.
- **Data/behavior:**
  - VM: `SettingsViewModel` (`@HiltViewModel`, `AndroidViewModel`) injects `SettingsRepository` + `@ApplicationContext`.
  - **Theme prefs** persist via `SharedPrefsHelper` (NOT DataStore): `KEY_THEME_MODE="ThemeMode"`, `KEY_THEME_SYSTEM="ThemeSystem"`, `KEY_PURE_BLACK="PureBlack"` (`StorageKeys.kt:15,16,18`; `SettingsRepository.kt:36-78`).
  - **General prefs** persist via DataStore (`DataStoreHelper`): `downloadedOnlyFlow`, `incognitoFlow`, `readingModeFlow`, `languageFlow` (`SettingsRepository.kt:23-34`).
  - Defaults: `darkMode` falls back to **system uiMode night-mask** when key absent (`SettingsRepository.kt:46-54`); `pureBlack` default **true** (`:57`); `followSystem` default **true** (`:61`). `downloadedOnly`/`incognito` UI `initial = true` (`SettingsScreen.kt:87,88`).
  - **Cache:** `clearLargeCache()` deletes all files >1 MB (`ONE_MB = 1024*1024L`, `ONE_MB.kt:3`) in `cacheDir` + `externalCacheDir`, prunes empty dirs, then recomputes size (`SettingsViewModel.kt:63-69`; `SettingsRepository.kt:83-94`). Size formatting via `formatSize` → `R.string.gigabytes`/`megabytes`/`kilobytes`/`bytes` (`SettingsRepository.kt:127-137`). Displayed as `R.string.cache_used` ("Used: ") + size (`SettingsScreen.kt:286`).
  - Navigation targets: Complaint (`Screen.ComplaintAdmin` if `Admin.isAdmin` else `Screen.Complaint`), `Screen.Statistics`, `Screen.LanguageScreen`, `Screen.DownloadsScreen`, `Screen.AboutScreen`.
  - Permissions: none directly (notification permission lives in Theme onboarding).
- **Feature inventory (EVERY row, top→bottom):**
  - *General group:*
    1. **Downloaded only** — `SwitchItem`, title `downloaded_only_title`, desc `downloaded_only_desc`, icon `Icons.Default.CloudOff`, bound `downloadedOnly` ↔ `setDownloadedOnly` (`:129-135`).
    2. **Incognito mode** — title `incognito_mode_title`, desc `incognito_mode_desc`, icon `R.drawable.incognito_svgrepo_com`, bound `incognito` ↔ `setIncognito` (`:138-144`).
    3. **Follow system theme** — title `system_theme`, desc `follow_system_theme`, icon `R.drawable.switchthemes`, bound `isFollowSystem` ↔ `toggleFollowSystem` (`:146-152`).
    4. **Dark mode** — *gated on `!isFollowSystem`* (`:154`); title `theme_title`, desc dynamic `theme_dark`/`theme_light`, icon `R.drawable.ic_day_night`, bound `themeMode` ↔ `toggleDarkMode` (`:156-162`).
    5. **Pure black mode** — title `pure_black_mode_title` (no description), icon `Icons.Outlined.DarkMode`, bound `pureBlack` ↔ `togglePureBlack` (`:166-171`).
    6. **Testing Mode** — *admin-only* (`Admin.isAdmin`), hardcoded title "Testing Mode", red tint `Color.Red.copy(0.5f)`, icon `R.drawable.ic_plus_18`, bound `Admin.testingMode` (`:173-184`).
  - *Download/CBZ group:*
    7. **Use Yami compressor** — `SwitchItem` (no icon), title `use_yami_compressor`, desc `use_yami_compressor_to_reduce_chapter_size...`, bound `useCbz` ↔ `cbzViewModel.setUseCbzFormat` (`:195-200`).
    8. **Compress existing downloads** — *visible only if `useCbz`* (`:202`): `Divider` + title `compress_existing_downloads` (titleMedium) + body `compress_all_previously_downloaded_chapters...` (bodySmall, onSurfaceVariant) + full-width `Button` → `startConversion()` (disabled while converting; spinner + "Converting" / else "Start conversion") (`:202-237`).
  - *Navigation group:*
    9. **Feedbacks & complaints** — `SettingsNavigationItem`, title `feedbacks_and_complaints`, icon `R.drawable.ic_complaint` → `Screen.ComplaintAdmin`/`Screen.Complaint` (`:247-256`).
    10. **Default reading mode** — title `default_reading_mode`, icon `R.drawable.ic_reader_setting` → opens `ReadingModeDialog` (`:258-263`).
    11. **Statistics** — title `statistics`, icon `Icons.Outlined.QueryStats` → `Screen.Statistics` (`:265-270`).
    12. **App language** — title `app_language`, icon `Icons.Outlined.Language` → `Screen.LanguageScreen` (`:272-274`).
    13. **Downloads** — title `downloads`, icon `Icons.Outlined.Download` → `Screen.DownloadsScreen` (`:276-278`).
  - *Other group:*
    14. **Clear cache** — title `clear_cache`, desc `cache_used` + " $cacheSize", icon `R.drawable.cache_cleaner` → `clearLargeCache()` (`:284-290`).
    15. **Request feature / report bug** — title `request_feature_bug_title`, desc `request_feature_bug_desc`, icon `Icons.AutoMirrored.Outlined.Message` → opens FeedbackDialog (`:293-297`).
    16. **About** — title `about`, desc `app_information_and_updates...`, icon `Icons.Outlined.Info` → `Screen.AboutScreen` (`:299-303`).
    17. **Help** — title `help`, icon `Icons.AutoMirrored.Outlined.Help`, **no onClick (inert row)** (`:307-311`).
- **Citations:** `SettingsScreen.kt:70-419`; `SettingsViewModel.kt:1-83`; `SettingsRepository.kt:1-138`; `SettingsNavigationItem.kt:29-89`; `SwitchItem.kt:28-73`; `ItemsGroup.kt:16-29`; `ONE_MB.kt:3`; `NavGraphV2.kt:42,514-518`; `StorageKeys.kt:15,16,18`; `strings.xml:134,141,144,145,753`.

---

### SettingsNavigationItem (shared row component)
- **Entry/route:** Reusable row used throughout Settings nav/other groups (`SettingsNavigationItem.kt`).
- **Layout & components:** `Row.fillMaxWidth()`, clickable only if `onClick != null`, `padding(vertical=16.dp)`, `verticalAlignment=CenterVertically`. Optional leading `Icon` 24.dp + 16.dp spacer; `Column.weight(1f)` with title `Text` (14.sp, `onBackground`) + optional `AutoSubtitleText` description (start-aligned, 12→6.sp auto, alpha 0.8, maxLines default 1, ellipsis); trailing `endIcon` default `Icons.AutoMirrored.Filled.KeyboardArrowRight` (`SettingsNavigationItem.kt:40-88`).
- **Visual:** title 14.sp; description auto-sized 12.sp start, min 6.sp; `iconColor` default `onBackground`; chevron `onBackground`.
- **States:** n/a (stateless row).
- **Interactions:** single click → `onClick`.
- **Dialogs/sheets/snackbars:** none.
- **Forms & validation:** none.
- **Data/behavior:** purely presentational; caller wires nav/dialog.
- **Feature inventory:** leading icon (optional), title, subtitle (optional, auto-shrinking), trailing chevron (overridable/removable via `endIcon=null`).
- **Citations:** `SettingsNavigationItem.kt:29-89`; `AutoSizedText.kt:17-46`.

---

### ThemeSelectionScreen (onboarding theme picker)
- **Entry/route:** `Screen.Theme` → `composable<Screen.Theme>` `NavGraphV2.kt:175` → `ThemeSelectionScreenRoute(navController, backStackEntry)` (`:177`), `onBottomBarVisibleChange(false)` (`:176`) — full-screen, no bottom bar. Route adapter `ThemeSelectionScreenRoute.kt:24-65` injects `OnboardingViewModel`, maps follow-system/dark to `AppTheme.System/Dark/Light` (`:33-37`), wires `onThemeSelected` → toggles, and `onContinue` → `navController.navigate(Screen.Sources)` (`:59`). Adapter also unconditionally invokes a second `NotificationPermissionRequester{}` (`:63-64`, `:66-93`) that auto-requests POST_NOTIFICATIONS once via `LaunchedEffect(Unit)`.
- **Layout & components:** `Surface.fillMaxSize()` containing:
  - `AnimatedBackground(fillMaxSize)` (animated onboarding backdrop; see Cluster notes — asset under `onboarding/welcome`).
  - `Box.fillMaxSize` with a vertical gradient overlay: `Brush.verticalGradient([background.copy(0.1f), background.copy(0.3f), background])` (`ThemeSelectionScreen.kt:103-115`).
  - `Column.fillMaxSize.padding(24.dp)`, `verticalArrangement=SpaceBetween`, `horizontalAlignment=CenterHorizontally` (`:116-167`):
    - Title `Text(R.string.choose_your_theme)` styled `headlineMedium.copy(fontSize=24.sp, color=primary)` (`:123-130`).
    - `Spacer(16.dp)`.
    - `ThemeSelector(...)` (the tab + permission card; `ThemeSelector.kt`).
    - `Button(onContinue)` full-width 50.dp, `clip(RoundedCornerShape(26.dp))`, `shape=shapes.medium`, `containerColor=primary`, label `R.string.continue_string` (labelLarge, 16.sp, onPrimary) (`:149-166`).
- **Visual:** 24.dp screen padding; title 24.sp primary; Continue button 50.dp tall, 26.dp clip + medium shape, primary fill / onPrimary text. ThemeSelector card uses `surfaceContainerHigh.copy(alpha=0.4f)` (`ThemeSelector.kt:41`).
- **States:** No loading/empty/error. Two reactive `remember` states: `hasNotificationPermission` (seeded by `hasPostNotificationPermission`) and `autoRequested` guard (`ThemeSelectionScreen.kt:60-64`). **Continue button enabled only when `hasNotificationPermission.value == true`** (`:151`) — gating onboarding on notification grant.
- **Interactions:**
  - **Theme tabs** (`ThemeSelector` `TabRow`): three `Tab`s Light/Dark/System with icons `LightMode`/`DarkMode`/`SettingsBrightness`; selecting → `onThemeSelected(theme)` (`ThemeSelector.kt:48-83`). Indicator color = `primary`, `containerColor` transparent.
  - **Grant permission** button → `onRequestNotificationPermission` → on Tiramisu+ launches `RequestPermission(POST_NOTIFICATIONS)`, else sets `hasNotificationPermission=true` (`ThemeSelectionScreen.kt:138-145`).
  - **Auto-request once** on first composition (`LaunchedEffect(autoRequested)`, Tiramisu+ & not granted) (`:88-96`).
  - **Continue** → `onContinue()` (navigate to Sources).
- **Dialogs/sheets/snackbars:** No dialogs. On permission denial shows a **Toast** `R.string.you_need_to_enable_notifications` (`:70-71`); if denied + "don't ask again", **redirects to app settings** via `openAppSettings()` (ACTION_APPLICATION_DETAILS_SETTINGS intent) (`:76-84,209-214`).
- **Forms & validation:** none (tab selection only).
- **Data/behavior:**
  - `AppTheme` enum: `Light(theme_light)`, `Dark(theme_dark)`, `System(theme_system)` (`ThemeSelectionScreen.kt:172-176`).
  - Theme persistence via `OnboardingViewModel.toggleFollowSystem/toggleDarkMode` (route adapter) which delegate to the same SharedPrefs keys as Settings. (INFERRED: `OnboardingViewModel` mirrors `SettingsViewModel` theme setters.)
  - **Permission:** `POST_NOTIFICATIONS` (Tiramisu+ only; pre-Tiramisu implicitly granted, `hasPostNotificationPermission` returns true `:189-198`).
  - Side effect: `onContinue` navigates to `Screen.Sources`.
  - **Pure-black/OLED is NOT exposed on this onboarding screen** — only Light/Dark/System tabs. Pure-black lives only on the Settings hub. (Flag for parity: KMP rework added a pure-black toggle to the Theme picker per task #243 — OLD app has none here.)
- **Feature inventory:** Light tab, Dark tab, System tab (TabRow); "Enable notifications" title + body copy; Grant permission button; Continue button (gated). Animated background + gradient overlay. Auto + manual notification permission request; toast + app-settings redirect on denial.
- **Citations:** `ThemeSelectionScreen.kt:51-214`; `ThemeSelector.kt:32-133`; `ThemeSelectionScreenRoute.kt:24-93`; `NavGraphV2.kt:45,175-179`.

---

### ThemeSelector (theme tabs + notification permission card)
- **Entry/route:** Child of `ThemeSelectionScreen` (`ThemeSelector.kt`).
- **Layout & components:** `ItemsGroup(color = surfaceContainerHigh.copy(alpha=0.4f))` → inner `Column.fillMaxWidth().padding(16.dp)` (`:40-47`):
  - `TabRow(selectedTabIndex = themes.indexOf(selected))`, transparent container, custom `Indicator` at selected tab offset, color `primary` (`:48-57`). Each `Tab`: icon (Light/Dark/System) + `AutoSubtitleText` label (bodyMedium 14.sp, primary, maxLines 1) (`:58-83`).
  - `Spacer(24.dp)`.
  - Notification section `Column.fillMaxWidth` (`:88-130`): `AutoSubtitleText(R.string.enable_notifications)` (bodyMedium 14.sp onBackground, maxLines 1), `AutoSubtitleText(R.string.notification_permission)` (bodySmall 12.sp onBackground, maxLines 3), then a `Row(horizontalArrangement=End)` with a `Button(onRequestNotificationPermission)` containing `AutoSubtitleText(R.string.grant_permission)` (bodySmall, onPrimary, minSize 2.sp) (`:111-129`).
- **Visual:** semi-transparent card; tab labels & indicator in `primary`; permission copy in `onBackground`; grant button default M3 primary.
- **States:** stateless (selection passed in).
- **Interactions:** tab click → `onThemeSelected`; grant button → `onRequestNotificationPermission`.
- **Dialogs/sheets/snackbars:** none (parent handles toast/redirect).
- **Forms & validation:** none.
- **Data/behavior:** purely presentational.
- **Feature inventory:** 3-tab theme picker, enable-notifications headline + body, grant-permission button.
- **Citations:** `ThemeSelector.kt:32-133`.

---

### LanguageSelectionScreen
- **Entry/route:** `Screen.LanguageScreen` (object, `NavGraphV2.kt:88`) → `composable<Screen.LanguageScreen>` `NavGraphV2.kt:543` → `LanguageScreenRoute(navController, backStackEntry)` (`:545`), `onBottomBarVisibleChange(false)` (`:544`). Route adapter `LanguageScreenRoute.kt:18-40` reads `R.array.supported_languages` from resources, maps each tag → `LanguageOption(tag, Locale.forLanguageTag(tag).getDisplayLanguage(locale))` (localized endonym), passes list + `onBack = navController.safePopBackStack()`.
- **Layout & components:** M2 `Scaffold` with `topBar = TopAppBarCom(title=R.string.select_language, navigationIcon=back IconButton(ArrowBack))`, `snackbarHost = SnackbarHost`, `contentColor = onBackground` (`LanguageSelectionScreen.kt:74-91`). Body `Column.fillMaxSize().padding(paddingValues).background(background)` → `LazyColumn.fillMaxWidth().padding(horizontal=24.dp)` (`:92-102`):
  - `items(availableLanguages)` → each renders `StatsItem(title=displayName, description=code, icon = Done if selected else null, onClick = selectLanguage(code))` + `Divider(padding vertical=12.dp)` (`:103-111`).
  - Trailing item: `StatsItem(title=R.string.request_language, icon=Icons.Default.Add, onClick → showFeedbackDialog=true)` + `Divider` (`:113-122`).
- **Visual:** 24.dp horizontal list padding; `StatsItem` row = optional 24.dp leading icon + 16.dp gap, title 14.sp + desc 12.sp (alpha 0.8), trailing bold count text (here `value=0` → renders "0" via `R.string.value_count="%,d"`, `:76-81`, `strings.xml:753`). Selected language shows a leading `Icons.Default.Done` check. Dividers 12.dp vertical padding. Top bar 24.sp bold title (`TopAppBarCom.kt:18-44`).
- **States:** No loading/empty/error — synchronous resource-backed list. Selected row reflects `selectedLanguageFlow` (`initial = Locale.getDefault().language`, `:64-66`).
- **Interactions:**
  - Tap language row → `viewModel.selectLanguage(code)` → persists + applies locale immediately (no restart prompt) (`LanguageViewModel.kt:25-37`).
  - Tap "Request language" row → opens FeedbackDialog (pre-selected category `ComplaintType.LANGUAGES`).
  - Back IconButton → `onBack` (pop).
- **Dialogs/sheets/snackbars:**
  - **FeedbackDialog** (`:125-160`): `selectedType = ComplaintType.LANGUAGES`, header `R.string.request_add_language`, field label `R.string.enter_your_language`. Submit → `complaintViewModel.submit(...)`; success snackbar `request_submitted_successfully` (Short); error snackbar `request_failed` + actionLabel `retry` (Long).
  - Snackbar host M3.
- **Forms & validation:** delegated to FeedbackDialog (category required + body ≥5 chars).
- **Data/behavior:**
  - VM `LanguageViewModel` (`@HiltViewModel`) injects `@ApplicationContext` + `SettingsRepository`. `selectedLanguageFlow = settingsRepo.languageFlow` (DataStore) (`LanguageViewModel.kt:23`).
  - `selectLanguage(code)`: `settingsRepo.setLanguage(code)` (DataStore persist) then `updateLocale(code)` → `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))` (`:25-37`). Comment notes activity may need recreate to fully apply (`:36`).
  - Supported tags (`arrays.xml:5-17`): `en, ar, de, es, fr, in, it, ja, pt, ru, tr` (11 listed; array may continue past line 17 — verify full list).
- **Feature inventory:** scrollable language list (display name + code, check on selected), "Request language" row (opens dialog), back nav, request snackbars. Note an **unused** `LanguageOptionItem` (radio-button row) composable exists (`:170-197`) but is NOT used by the screen (the screen uses `StatsItem`).
- **Citations:** `LanguageSelectionScreen.kt:57-197`; `LanguageScreenRoute.kt:18-40`; `LanguageViewModel.kt:15-38`; `LanguageOption.kt:3`; `StatsItem.kt:31-83`; `TopAppBarCom.kt:18-44`; `arrays.xml:5-17`; `NavGraphV2.kt:88,543-547`.

---

### LanguageSwitcher (file)
- **Entry/route:** `LanguageSwitcher.kt` — file contains **only imports, no composable body** (`LanguageSwitcher.kt:1-11`). Effectively dead/stub. Locale switching is implemented inline in `LanguageViewModel.updateLocale` instead.
- **Citations:** `LanguageSwitcher.kt:1-11`.

---

### FeedbackDialog (shared dialog — used by Settings + Language)
- **Entry/route:** Shared `AlertDialog`-based dialog (`FeedbackDialog.kt`).
- **Layout & components:** M3 `AlertDialog`, `RoundedCornerShape(20.dp)`, `containerColor=surface`, `tonalElevation=3.dp` (`:53-57`). Title block = header (`headlineSmall` bold) + subtitle `R.string.we_d_love_to_hear_from_you` (bodyMedium, onSurfaceVariant) (`:58-73`). Body `Column(verticalScroll, spacedBy 20.dp)`:
  - **Category dropdown** — label `R.string.category` (labelLarge SemiBold) + `ExposedDropdownMenuBox` with read-only `OutlinedTextField` (12.dp rounded, ArrowDropDown trailing) listing `ComplaintType.entries` (`:80-135`).
  - **Feedback input** — label `R.string.your_feedback` + multi-line `OutlinedTextField` (minLines 4, maxLines 6, heightIn min 120.dp, 12.dp rounded), error state when 0<len<5, supporting row with `R.string.minimum_5_characters_required` + char counter `"${len}/500"` (`:137-181`).
  - **Social media** — divider + `R.string.connect_with_us_in_social_media` + `R.string.you_ll_receive_a_prompt_response...` + `SocialMediaRow()` (`:183-201`).
  - confirmButton = `Button` enabled when `submitEnabled` (category != null AND body.length ≥ 5), label `R.string.submit` (`:204-218`). dismissButton = `TextButton` `R.string.cancel` (`:219-229`).
- **Visual:** 20.dp dialog corners, 12.dp field corners, 20.dp section spacing.
- **States:** local `selectedTypeState`, `feedbackBody`, `expanded`; `submitEnabled` derived. Early-returns when `!visible`.
- **Interactions:** dropdown select, text input, submit (gated), cancel, social-media taps.
- **Forms & validation:** category required + body ≥5 chars (max 500 shown). Error styling + counter.
- **Data/behavior:** stateless re: persistence — emits `onSubmit(type, body)` to caller; caller invokes `ComplaintViewModel.submit`. Caller passes `headerText`, `textFieldText`, optional pre-`selectedType`.
- **Feature inventory:** category dropdown, validated feedback field with counter, social row, submit/cancel.
- **Citations:** `FeedbackDialog.kt:32-231`.

---

### ReadingModeDialog (reached from Settings "Default reading mode")
- **Entry/route:** Opened by Settings row #10 (`SettingsScreen.kt:352-365`); defined `ReadingModeDialog.kt`.
- **Layout & components:** `Dialog` → `Surface(fillMaxWidth, tonalElevation=8.dp, RoundedCornerShape(16.dp), color=surfaceContainerHigh)` (`:56-64`). Header `Text("Reading mode")` (**hardcoded string**, headlineSmall bold, `:67-74`). Scrollable `Column` with `ReadingModeChips(modes, selectedMode, onModeSelected)` (`:84-92`), `Divider`, footer `Row(End)` with `OutlinedButton(R.string.but_revert)` (resets to currentMode + dismiss) and `Button(R.string.but_apply)` with `Icons.Default.Check` (applies selected + onApply) (`:98-148`).
- **Visual:** 16.dp corners, `surfaceContainerHigh`, 8.dp tonal elevation. Apply button uses `onBackground` container / `background` content (inverted).
- **States:** local `selected` (init `currentMode`).
- **Interactions:** chip select (updates local), Revert (reset + dismiss), Apply (`onModeSelected(selected)` + `onApply`).
- **Dialogs/sheets/snackbars:** is itself a dialog.
- **Forms & validation:** none.
- **Data/behavior:** `ReadingMode` enum (`ReadingMode.kt:7-35`): `DEFAULT`, `RIGHT_TO_LEFT`, `LEFT_TO_RIGHT`, `VERTICAL`, `WEBTOON`, `CONTINUOUS_VERTICAL` — each `@DrawableRes iconRes` + `@StringRes titleRes` (`reading_mode_*`). Persisted via `chaptersViewModel.setReadingMode` → DataStore `readingModeFlow`.
- **Feature inventory:** 6 reading-mode chips, Revert, Apply (with check icon). Note: header text "Reading mode" is hardcoded (not a string resource).
- **Citations:** `ReadingModeDialog.kt:41-155`; `ReadingMode.kt:7-35`; `SettingsScreen.kt:352-365`.

---

### CbzConversionDialog (reached from Settings "Start conversion")
- **Entry/route:** Always composed in Settings (`SettingsScreen.kt:408-417`); self-gating (`CbzConversionDialog.kt:28-32`).
- **Layout & components:** `Dialog` → `Card(fillMaxWidth.padding(16.dp), RoundedCornerShape(16.dp), containerColor=surface)` → centered `Column.padding(24.dp)` with three mutually-exclusive states (`:46-269`).
- **Visual:** 16.dp card corners; 48.dp state icon; progress bar 8.dp tall.
- **States (this dialog IS the loading/success/error surface):**
  - **Error** — `Icons.Default.Error` (error tint), title `R.string.conversion_failed`, error message, full-width `Button(R.string.close)` (`:62-91`).
  - **Success** — `CheckCircle` (or `Warning` if `wasStopped`), title `conversion_complete_`/`conversion_stopped`, message, `Button(R.string.closure_reason_done)` (`:93-133`).
  - **Converting** — `Warning` icon, title `converting_to_cbz`, warning `please_don_t_close_the_app...`, `LinearProgressIndicator(progress)`, completed/remaining counts, current manga title + chapter, `CircularProgressIndicator`, `OutlinedButton(R.string.stop_conversion)` (`:135-266`). Dismiss disabled while converting (`dismissOnBackPress/ClickOutside = !isConverting`, `:42-43`).
- **Interactions:** dismiss/close/stop per state.
- **Data/behavior:** reads `ConversionProgress` (isConverting/error/successMessage/wasStopped/totalChapters/convertedChapters/currentMangaTitle/currentChapterNumber); dismiss stops (if running) or clears error (`SettingsScreen.kt:410-416`).
- **Feature inventory:** progress %, completed/remaining counts, current item, spinner, stop, close/done.
- **Citations:** `CbzConversionDialog.kt:23-271`; `SettingsScreen.kt:408-417`.

---

### Cluster notes

**Theme tokens (`Theme.kt`, `Color.kt`, `Type.kt`) — these govern the WHOLE app:**

- **`YamiMangaTheme(darkTheme, dynamicColor=false, pureBlack=false, content)`** (`Theme.kt:97-134`). `dynamicColor` default **false** (dynamic color exists but is off). Base scheme: dynamic (S+) if enabled, else `DarkColorScheme`/`LightColorScheme`. **Pure-black override:** when `darkTheme && pureBlack`, copies base scheme with `background=Color.Black`, `surfaceContainer=Color.Black` (only those two) (`:117-125`). Applies `Typography` + `Shapes`.

- **DarkColorScheme** (`Theme.kt:17-51`) — KEY values:
  - primary `#FFB0C6FF`, onPrimary `#FF002D6E`, primaryContainer `#FF00429B`, onPrimaryContainer `#FFD7E2FF`
  - secondary `#FFB0C6FF`, onSecondary `#FF002D6E`, secondaryContainer `#FF00429B`, onSecondaryContainer `#FFD7E2FF`
  - tertiary `#FFB8D0FF`, onTertiary `#FF003063`, tertiaryContainer `#FF2C2C2F`, onTertiaryContainer `#FFD6E3FF`
  - **background `#FF15202B`** (Twitter-dim navy), onBackground `#FFE3E2E6`, **surface `#FF15202B`**, onSurface `#FFE3E2E6`, surfaceVariant `#FF44464F`, onSurfaceVariant `#FFC4C6D0`
  - outline `#FF8E9099`, inverseOnSurface `#FF1B1B1F`, inverseSurface `#FFE3E2E6`, inversePrimary `#FF0058CA`
  - error `#FFFFB4AB`, onError `#FF690005`, errorContainer `#FF93000A`, onErrorContainer `#FFFFDAD6`
  - (commented-out alt background `#FF1B1B1F` at `:33`.)

- **LightColorScheme** (`Theme.kt:53-85`) — KEY values:
  - primary `#FF0058CA`, onPrimary `#FFFFFFFF`, primaryContainer `#FFD7E2FF`, onPrimaryContainer `#FF001945`
  - secondary `#FF0058CA`, onSecondary `#FFFFFFFF`, secondaryContainer `#FFD7E2FF`, onSecondaryContainer `#FF001945`
  - tertiary `#FF0061A3`, onTertiary `#FFFFFFFF`, tertiaryContainer `#FF2C2C2F` (note: dark-ish), onTertiaryContainer `#FF001D36`
  - background `#FFFEFBFF`, onBackground `#FF1B1B1F`, surface `#FFFEFBFF`, onSurface `#FF1B1B1F`, surfaceVariant `#FFE3E2EC`, onSurfaceVariant `#FF44464F`
  - outline `#FF757780`, inverseOnSurface `#FFF2F0F4`, inverseSurface `#FF303034`, inversePrimary `#FFB0C6FF`
  - error `#FFBA1A1A`, onError `#FF93000A` (note: not white), errorContainer `#FFFFDAD6`, onErrorContainer `#FF410002`

- **`Color.kt`** (`Color.kt:5-10`) defines Material-template purples (`Purple80/40`, `PurpleGrey80/40`, `Pink80/40`) — **these are NOT used** by either color scheme (dead default tokens). The real palette is the blue scheme inline in `Theme.kt`.

- **Shapes** (`Theme.kt:89-95`): extraSmall 4.dp, small 8.dp, medium 12.dp, large 16.dp, **extraLarge 0.dp**. All `RoundedCornerShape`.

- **Typography** (`Type.kt`): font family **Gellix** — `gellix_regular`(Normal), `gellix_semibold`(Medium), `gellix_bold`(Bold) (`Type.kt:12-16`). Only 3 styles overridden: `bodyLarge` (Bold 16.sp), `titleMedium` (Medium 14.sp), `titleSmall` (Normal 12.sp) — rest are M3 defaults (`Type.kt:18-36`). NOTE: `bodyLarge` is **Bold**, which affects body text app-wide.

- **Component idioms reused across this cluster:**
  - `ItemsGroup` = rounded `surfaceContainerHigh` card, 16.dp radius (`ItemsGroup.kt:16-29`).
  - Section dividers = `colorScheme.background.copy(alpha=0.8f)` (near-invisible).
  - `SettingsNavigationItem` (nav rows, chevron) vs `SwitchItem` (M2 Switch, primary thumb) vs `StatsItem` (used by Language, trailing count) — all 24.dp icon + 16.dp gap, 14.sp title, 12.sp subtitle.
  - `AutoSubtitleText` = `BasicText` with `TextAutoSize.StepBased` (auto-shrinking) (`AutoSizedText.kt:17-46`).
  - `TopAppBarCom` = M3 TopAppBar, 24.sp bold title, background container (`TopAppBarCom.kt:18-44`).

**Persistence split (important parity detail):**
- Theme prefs (`ThemeMode`/`ThemeSystem`/`PureBlack`) → **SharedPreferences** (synchronous, `SharedPrefsHelper`).
- General/language/reading-mode → **DataStore** (`DataStoreHelper`).
- Defaults: pureBlack=true, followSystem=true; darkMode falls back to system night-mode when key absent.

**Notification permission flow** lives in onboarding Theme screen only: auto-request once + manual Grant button + Toast on denial + app-settings redirect when permanently denied. **Continue is gated on grant** — a hard onboarding gate.

**Inferences / anomalies flagged:**
- `SettingsRoute.kt` adapter file not located (only the import); assumed thin wrapper. (INFERRED)
- The `dialogState` Adult/M-confirmation chain in `SettingsScreen.kt:367-406` has no visible trigger in this file → likely dead/unreachable. (INFERRED)
- `LanguageOptionItem` (radio row) and `LanguageSwitcher.kt` are dead/unused.
- `StatsItem` trailing count shows "0" for language rows (value defaults to 0) — a cosmetic artifact of reusing the stats component.
- `OnboardingViewModel` theme setters assumed to mirror `SettingsViewModel`. (INFERRED — not read.)

**Missing assets to verify (referenced drawables/strings):**
- Drawables: `ic_launcher_foreground`, `incognito_svgrepo_com`, `switchthemes`, `ic_day_night`, `ic_plus_18`, `ic_complaint`, `ic_reader_setting`, `cache_cleaner`, reader-mode icons (`ic_reader_continuous_vertical_24dp`, `ic_reader_rtl_24dp`, `ic_reader_ltr`, `ic_reader_vertical_24dp`, `ic_reader_webtoon_24dp`).
- Fonts: `gellix_regular`, `gellix_semibold`, `gellix_bold`.
- String arrays: `R.array.supported_languages` (en/ar/de/es/fr/in/it/ja/pt/ru/tr — confirm full list past `arrays.xml:17`).
- Strings: `choose_your_theme`, `continue_string`, `enable_notifications`, `notification_permission`, `grant_permission`, `you_need_to_enable_notifications`, `select_language`, `request_language`, `request_add_language`, `enter_your_language`, all `reading_mode_*`, `but_revert`/`but_apply`, CBZ strings, `value_count`, `cache_used`, `calculating`, size unit strings.
