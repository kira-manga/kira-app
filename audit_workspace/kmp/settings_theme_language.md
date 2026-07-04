# KMP (Rework) Audit — Settings + Theme + Language Cluster

Read-only audit of the architecture-rework KMP app. Scope: Settings hub, Theme picker
(`themepicker`), Language picker, plus the `:ui/theme/` design-token system and the
`:presentation` MVI slices + `:composeApp` route adapters that back them. All citations are
`file:line`. Inferences marked `(INFERRED)`.

Module map for this cluster:
- `:ui` screens — `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/{settings,themepicker,language}/`
- `:ui` design tokens — `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/theme/`
- `:presentation` MVI — `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/{settings,theme,language}/`
- `:composeApp` routes — `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/`
- Resources — `ui/src/commonMain/composeResources/{values,values-ar,font,drawable}/`

---

### SettingsScreen (Settings hub)

- **Entry/route:** `Screen.SettingsRework` → `SettingsReworkScreenRoute(navController, backStackEntry)`
  (`composeApp/.../navigation/routes/SettingsReworkScreenRoute.kt:109-139`), wired in `App.kt:874-880`
  inside `composable<Screen.SettingsRework>`. VM resolved via `koinViewModel()`
  (`SettingsReworkScreenRoute.kt:114`). Bottom bar hidden: `SideEffect { onBottomBarVisibleChange(false) }`
  (`App.kt:875`). Composable entry: `SettingsScreen(viewModel, onNavigate, modifier, isAdmin, initialTestingMode, onToggleTestingMode)`
  (`ui/.../settings/SettingsScreen.kt:206-230`); stateless inner `SettingsScreenContent`
  (`SettingsScreen.kt:232-304`). **NOTE present-but-unwired:** the App.kt comment (`:858-873`) still
  claims "not surfaced in any user-facing entry yet — reachable via `navController.navigate(Screen.SettingsRework)` from a future developer trigger." This audit found NO drawer/menu wiring of `Screen.SettingsRework` in the navigation graph; reachability from a real user entry point is unconfirmed in this scope `(INFERRED)`.

- **Layout & components:** `Scaffold` with `TopAppBar` (title `Res.string.settings`, `FontWeight.SemiBold`)
  + `SnackbarHost` (`SettingsScreen.kt:255-267`). Body is a `Box` filling size, padded by `innerPadding`,
  background `MaterialTheme.colorScheme.background` (`:269-274`). Content is a `LazyColumn`
  (`SettingsList`, `:307-465`) with `contentPadding = PaddingValues(vertical = spacing.md)` and
  `verticalArrangement = Arrangement.spacedBy(spacing.sm)` (`:319-323`). Seven `item`s, each a
  `SectionCard` (`:467-495`): a primary-colored `titleSmall` header + a `Card`
  (`surfaceVariant` container, `RoundedCornerShape(12.dp)`) holding rows separated by `HorizontalDivider`.
  Row composables: `ToggleRow` (`:497-538`), `NavRow` (`:589-615`), `ReadingModeRow` (`:617-646`),
  `CacheRow` (`:648-683`), `CompressExistingRow` (`:547-587`).
- **Visual:** spacing all from `LocalSpacing` (xxs/xs/sm/md/lg). Section header `typography.titleSmall`,
  `colorScheme.primary`, SemiBold (`:479-484`). Row label `bodyLarge` on `onSurface`; description
  `bodySmall` on `onSurfaceVariant` (`:520-531`). Card corners `12.dp`; container `surfaceVariant`
  (`:487-490`). Admin "Testing Mode" label tinted `Color.Red.copy(alpha = 0.5f)` (`:348`).
  Spinner uses default `CircularProgressIndicator` size.
- **States:** loading → centered `CircularProgressIndicator` while `state.isLoading`
  (`:275-276`); else `SettingsList`. No explicit empty/error states — the hub always renders its
  fixed section set. Failures surface as transient snackbars via `SettingsEffect.ShowSnackbar`
  (`:250`). Cache-clear in-flight → trailing spinner on the cache row + row disabled (`:658,:679-681`).
  CBZ "compress existing" in-flight → button spinner + "Converting..." (`:574-585`).
- **Interactions:** every `ToggleRow` is `clickable { onCheckedChange(!checked) }` AND has a `Switch`
  (`:512,:533-536`). Nav rows `clickable(onClick)` (`:600-604`); the Help row passes `onClick = null`
  → no clickable modifier, no ripple (`:442,:600`) — inert placeholder. Reading-mode row opens a
  dialog (`:421`). Cache row `clickable(enabled = !isClearing)` (`:658`). No gesture/animation work
  beyond default Material ripples + Switch thumb animation. Effect collection via single
  `LaunchedEffect(effects)` (`:246-253`).
- **Dialogs/sheets/snackbars:** **FeedbackDialog** (`:289-295`, def `:781-939`) — `AlertDialog`,
  `RoundedCornerShape(20.dp)`, `surface` container, `tonalElevation 3.dp`. **ReadingModeDialog**
  (`:297-303`, def `:984-1043`). Snackbar host owned by the screen (`:244,:267`), `SnackbarDuration.Short`
  default (no `withDismissAction`).
- **Forms & validation:** FeedbackDialog has a category `ExposedDropdownMenuBox` (readonly
  `OutlinedTextField` + 6 `ComplaintType` items, `:825-860`) and a body `OutlinedTextField`
  (multiline, `minLines = 4, maxLines = 6, heightIn(min = 120.dp)`, `:869-902`). Validation:
  `submitEnabled = selectedType != null && body.length >= 5 && !isSubmitting` (`:794`); body capped at
  500 chars (`:871`), char counter `${body.length}/500` (`:896`); `isError` + "minimum 5 characters
  required" helper below 5 (`:880,:887-888`). **KNOWN MISMATCH** (documented in KDoc `:765-769`): UI
  gate is 5 chars but `SubmitFeedbackUseCase` requires ≥8, so 5–7 char bodies fail server-side and
  surface an error snackbar. Local state only (`selectedType`, `body`, `expanded` are
  `remember{mutableStateOf}`, `:790-792`) — payload rides with `OnSubmitFeedback`.
- **Data/behavior:** state from `SettingsViewModel.state` (`:219`); intents via `viewModel::submit`.
  Persistence: toggles → `UpdateSettingsToggleUseCase` (fire-and-forget,
  `SettingsViewModel.kt:184-186`); cache → `ClearCacheUseCase` with re-entrance guard
  (`SettingsViewModel.kt:223-239`); reading mode → `SetReadingModeUseCase` (`:241-244`); feedback →
  `SubmitFeedbackUseCase` with `subject = type.name` (`:246-267`); CBZ compress →
  `CompressExistingDownloadsUseCase` with guard (`:205-221`). Navigation is an effect:
  `OnNavigate` → `SettingsEffect.NavigateTo(destination)` → route adapter maps to `Screen.<X>Rework`
  (`SettingsReworkScreenRoute.kt:117-129`). Admin gating: `isAdmin`/`testingMode` supplied by the
  adapter from the `:shared` `Admin` runtime object (`SettingsReworkScreenRoute.kt:135-137`); COMPLAINT
  destination routes to `ComplaintAdminRework` vs `ComplaintRework` on `Admin.isAdmin`
  (`:123-124`). No permissions requested by this screen.
- **Feature inventory (every row + action):**
  - Section **General** (`Res.string.section_general`, `SettingsScreen.kt:324-356`):
    1. **Downloaded only** toggle (`SettingsToggle.DOWNLOADED_ONLY`), desc "Filters all entries in
       your library" (`:326-332`).
    2. **Incognito mode** toggle (`SettingsToggle.INCOGNITO`), desc "Stop saving your reading
       history." (`:334-340`).
    3. **Testing Mode** toggle — admin-only (`if (isAdmin)`), red-tinted, flips `Admin.testingMode`
       (`:341-354`). Local mirror state (`:318`).
  - Section **Theme** (`Res.string.theme_screen_title`, `:358-385`):
    4. **Follow system theme** toggle (`FOLLOW_SYSTEM_THEME`), desc "Follow System Theme" (`:360-366`).
    5. **Dark/Light Mode** toggle (`DARK_MODE`) — **gated on `!state.followSystemTheme`** (`:367-376`);
       desc switches "Dark Mode"/"Light Mode" on value.
    6. **Pure black** toggle (`PURE_BLACK`, `Res.string.pure_black_mode_title` → "Pure black dark
       mode") — always visible, no description (`:377-383`).
  - Section **Downloads/CBZ** (`Res.string.downloads`, `:387-415`):
    7. **Use Yami Compressor** toggle (`USE_CBZ_FORMAT`, inline literal label + desc) (`:389-397`).
    8. **Auto-convert on Download** toggle (`AUTO_CONVERT_TO_CBZ`) — visible only when
       `useCbzFormat` (`:398-407`).
    9. **Compress Existing Downloads** action (`CompressExistingRow`, full-width Button "Start
       Conversion"/"Converting...") — visible only when `useCbzFormat` (`:409-412`).
  - Section **Reading** (`Res.string.section_reading`, `:417-424`):
    10. **Reading mode** row (subtitle = current mode label) → opens ReadingModeDialog (`:419-422`).
  - Section **Navigation** (`Res.string.section_navigation`, `:426-444`): iterates
    `SettingsDestination.entries` (7 rows) + an inert Help row:
    11. **Theme** → `Screen.ThemeRework`
    12. **Statistics** → `Screen.StatisticsRework`
    13. **Language** → `Screen.LanguageRework`
    14. **About** → `Screen.AboutRework`
    15. **Feedback Manager** (`COMPLAINT`) → admin/user complaint route
    16. **What's new** (`WHATSNEW`) → `Screen.WhatsNewRework`
    17. **Downloads** → `Screen.DownloadsRework`
    18. **Help** row — `onClick = null`, inert placeholder (`:441-442`).
  - Section **Storage** (`Res.string.section_storage`, `:446-454`):
    19. **Clear cache** row + "Cached: <size>" subtitle (`Res.string.cached_size`) (`:448-452`,
        `:666-677`).
  - Section **Feedback** (`Res.string.section_feedback`, `:456-463`):
    20. **Request feature / bug** row → opens FeedbackDialog (`:458-461`).
- **Citations:** `ui/.../settings/SettingsScreen.kt:206-1043`;
  `presentation/.../settings/SettingsViewModel.kt:144-268`;
  `presentation/.../settings/SettingsState.kt:127-147`;
  `presentation/.../settings/SettingsIntent.kt:84-224`;
  `presentation/.../settings/SettingsEffect.kt:61-86`;
  `presentation/.../settings/SettingsDestination.kt:103-111`;
  `composeApp/.../navigation/routes/SettingsReworkScreenRoute.kt:109-139`; `App.kt:874-880`;
  `domain/.../model/settings/SettingsSnapshot.kt:98-133`.

---

### FeedbackDialog (Settings hub modal)

- **Entry/route:** rendered from `SettingsScreenContent` when `state.feedbackDialogOpen`
  (`SettingsScreen.kt:289-295`); opened by `OnOpenFeedbackDialog` from the "Request feature / bug"
  row.
- **Layout & components:** `AlertDialog` (`:796-938`) — title `Res.string.request_feature_bug`
  (`headlineSmall`, Bold); scrollable `Column` (`verticalScroll`) with a "Category" label
  (`labelLarge` SemiBold) + `ExposedDropdownMenuBox` + "Your feedback" label + body `OutlinedTextField`;
  confirm `Button`, dismiss `TextButton`.
- **Visual:** `RoundedCornerShape(20.dp)`, `surface` container, `tonalElevation 3.dp` (`:802-804`);
  fields `RoundedCornerShape(12.dp)`; spacing from `LocalSpacing`.
- **States:** in-flight `isSubmitting` → confirm button label "Submitting…" else "Submit"
  (`:915-919`); dropdown/field disabled while submitting (`:828,:834,:872`).
- **Interactions:** dropdown expand/collapse; row select sets `selectedType`. `@Suppress("DEPRECATION")`
  on the deprecated `.menuAnchor()` overload (`:825,:838`).
- **Dialogs/sheets/snackbars:** on success → snackbar "Thanks! Your feedback was submitted."
  (`SettingsViewModel.kt:258`); on failure → "Failed to submit feedback: <cause>" (`:264`).
- **Forms & validation:** see SettingsScreen Forms (5-char UI gate vs 8-char use-case gate; 500-char
  cap; counter; error helper). Dismissal gated during submit via
  `DialogProperties(dismissOnBackPress/ClickOutside = !isSubmitting)` (`:798-801`); VM-side dismiss
  guard `if (isSubmittingFeedback) return` (`SettingsViewModel.kt:192`).
- **Data/behavior:** submit → `SubmitFeedbackUseCase(type, subject = type.name, body)`
  (`SettingsViewModel.kt:250`). On success closes dialog (`:255`); on failure keeps it open
  (typed text preserved).
- **Feature inventory:** category dropdown (6 `ComplaintType`: Technical, Languages, Sites add, Site
  error, Features, Other — `SettingsScreen.kt:741-748`), body field, char counter, Submit, Cancel.
- **Citations:** `ui/.../settings/SettingsScreen.kt:741-939`;
  `presentation/.../settings/SettingsViewModel.kt:246-267`.

---

### ReadingModeDialog (Settings hub modal)

- **Entry/route:** rendered when `state.readingModeDialogOpen` (`SettingsScreen.kt:297-303`);
  opened by `OnOpenReadingModeDialog` from the Reading-mode row.
- **Layout & components:** `AlertDialog` (`:991-1042`) — title `Res.string.reading_mode` (Bold
  headlineSmall); body `Column` of 6 selectable rows (`RadioButton(onClick = null)` + label,
  the whole `Row` is `clickable { onSelect(mode) }`); empty `confirmButton`, dismiss `TextButton`
  (Cancel).
- **Visual:** `RoundedCornerShape(20.dp)`, `surface`, `tonalElevation 3.dp`; rows spaced `spacing.xs`,
  vertical pad `spacing.xs`; label `bodyLarge` on `onSurface`.
- **States:** none beyond current selection driven by `currentMode` (no loading/error). Single-tap-commits.
- **Interactions:** tapping a row both persists and closes (`SettingsViewModel.kt:241-244` sets
  `readingModeDialogOpen = false` then `setReadingMode(mode)`). No Apply/Revert.
- **Dialogs/sheets/snackbars:** Cancel button (`Res.string.cancel`); back-press/outside-tap dismiss.
- **Forms & validation:** none.
- **Data/behavior:** 6 `ReadingMode` entries (DEFAULT, RIGHT_TO_LEFT, LEFT_TO_RIGHT, VERTICAL,
  WEBTOON, CONTINUOUS_VERTICAL — `domain/.../model/reader/ReadingMode.kt:85-92`); labels via
  `readingModeLabel` (`SettingsScreen.kt:951-958`). Persisted via `SetReadingModeUseCase` (shares the
  same `reading_mode` pref key as the legacy reader path).
- **Feature inventory:** 6 mode radio rows + Cancel.
- **Citations:** `ui/.../settings/SettingsScreen.kt:951-1043`;
  `presentation/.../settings/SettingsViewModel.kt:241-244`.

---

### ThemeScreen (Theme picker — `themepicker`)

- **Entry/route:** `Screen.ThemeRework` → `ThemeReworkScreenRoute(navController, backStackEntry)`
  (`composeApp/.../navigation/routes/ThemeReworkScreenRoute.kt:160-167`); wired `App.kt:736-742`,
  bottom bar hidden (`:737`). `navController`/`backStackEntry` both `@Suppress("UNUSED_PARAMETER")`
  — terminal screen, no outbound nav (`ThemeReworkScreenRoute.kt:162-163`). Composable entry:
  `ThemeScreen(viewModel, modifier, onContinue?, hasNotificationPermission = true,
  onRequestNotificationPermission?)` (`ui/.../themepicker/ThemeScreen.kt:266-282`); stateless inner
  `ThemeScreenContent` (`:286-318`). **NOTE present-but-unwired:** the route adapter passes NONE of
  the 3 onboarding params (`ThemeReworkScreenRoute.kt:166` calls `ThemeScreen(viewModel = viewModel)`
  only), so the Continue button + notification grant row are dead code on this route — they exist for
  a future wizard caller (Phase 7.x.theme.swap), per KDoc (`ThemeScreen.kt:69-93`,
  `ThemeReworkScreenRoute.kt:21-37`). The `AnimatedBackground` gradient overlay + auto-permission-request
  lifecycle from the legacy onboarding picker are DEFERRED `(INFERRED — documented but not implemented)`.
- **Layout & components:** `Scaffold` + `TopAppBar` (title `Res.string.theme_screen_title` → "Theme")
  (`:295-299`). Body: loading `Box` spinner or `ThemePickerColumn` (`:300-317`). `ThemePickerColumn`
  (`:332-385`): a `Column` (padded `spacing.lg` horizontal / `spacing.md` vertical) containing —
  "Choose Your Theme" `titleMedium` text → a `TabRow` of 3 `Tab`s (Light/Dark/System) →
  `PureBlackRow` (Switch) → optional `NotificationPermissionRow` → optional Continue `Button`.
- **Visual:** spacing from `LocalSpacing` (lg/md). TabRow text-only (`Tab` not `LeadingIconTab`,
  no icons — `:360-366`); `selectedTabIndex = selected.indexInPicker` (Light=0/Dark=1/System=2,
  `:476-481`). PureBlackRow label `bodyLarge` (`:404-408`). All Material 3 defaults otherwise.
- **States:** loading → centered `CircularProgressIndicator` in `LoadingBox` while `state.isLoading`
  (`:301-302,:320-330`); else picker. No empty/error states (pure preference flow). Success = live
  tab selection + switch state.
- **Interactions:** tab tap → `ThemeIntent.OnSelectTheme(theme)` (`:363`); pure-black switch →
  `OnTogglePureBlack(it)` (`:370`); both fire-and-forget, upstream re-emits drive UI
  (`ThemeViewModel.kt:137-146`). Continue button gated on
  `onRequestNotificationPermission == null || hasNotificationPermission` (`ThemeScreen.kt:378`).
  Grant button → `onRequest()` (`:461`). No animations beyond Material defaults.
- **Dialogs/sheets/snackbars:** none. `ThemeEffect` is an empty sealed interface — no effect
  collection (`presentation/.../theme/ThemeEffect.kt:59`).
- **Forms & validation:** none.
- **Data/behavior:** state from `ThemeViewModel` (two independent `init{}` collectors —
  `ObserveAppThemeUseCase` + `ObservePureBlackUseCase`, `ThemeViewModel.kt:123-135`). Theme tri-state
  collapses the legacy two-boolean (`darkMode`+`followSystem`) representation; persists via
  `SetAppThemeUseCase` / `SetPureBlackUseCase` to the same `:shared SettingsRepository` SharedPrefs
  keys (`KEY_THEME_MODE`/`KEY_THEME_SYSTEM`/`KEY_PURE_BLACK`). Single source of truth shared with the
  Settings-hub theme toggles (toggling on one reflects on the other). No permissions actually
  requested on this route.
- **Feature inventory:**
  1. **Light** tab → `OnSelectTheme(AppTheme.Light)`
  2. **Dark** tab → `OnSelectTheme(AppTheme.Dark)`
  3. **System** tab → `OnSelectTheme(AppTheme.System)`
  4. **Pure black dark mode** Switch (`PureBlackRow`) → `OnTogglePureBlack` — always interactive,
     no enabled-gate (`:368-371,:395-414`).
  5. **Enable Notifications** grant row (title + body `Res.string.notification_permission` + "Grant
     Permission" button) — only when `onRequestNotificationPermission != null && !hasNotificationPermission`
     (`:372-373,:442-466`) — UNWIRED on this route.
  6. **Continue** button (`Res.string.continue_string`) — only when `onContinue != null`
     (`:375-383`) — UNWIRED on this route.
- **Citations:** `ui/.../themepicker/ThemeScreen.kt:264-494`;
  `presentation/.../theme/ThemeViewModel.kt:114-147`; `presentation/.../theme/ThemeState.kt:80-84`;
  `presentation/.../theme/ThemeIntent.kt:68-98`; `presentation/.../theme/ThemeEffect.kt:59`;
  `composeApp/.../navigation/routes/ThemeReworkScreenRoute.kt:160-167`; `App.kt:736-742`;
  `domain/.../model/theme/AppTheme.kt:74-78`.

---

### LanguageScreen (Language picker)

- **Entry/route:** `Screen.LanguageRework` → `LanguageReworkScreenRoute(navController, backStackEntry)`
  (`composeApp/.../navigation/routes/LanguageReworkScreenRoute.kt:117-124`); wired `App.kt:803-809`,
  bottom bar hidden (`:804`). Both nav params `@Suppress("UNUSED_PARAMETER")` — terminal screen.
  Composable entry: `LanguageScreen(viewModel, modifier)` (`ui/.../language/LanguageScreen.kt:154-167`);
  stateless inner `LanguageScreenContent` (`:169-226`). **NOTE present-but-unwired:** App.kt comment
  (`:799-802`) still says "not surfaced in any user-facing entry yet" — reachable only via the Settings
  hub LANGUAGE nav row in practice `(INFERRED)`.
- **Layout & components:** `Scaffold` + `TopAppBar` (title `Res.string.select_language` → "Select
  Language") + `SnackbarHost` (`:195-200`). Body: loading `Box`/`LanguageList`. `LanguageList`
  (`:240-272`): `LazyColumn` of `LanguageRow`s (key = `language.code`) each followed by
  `HorizontalDivider`, plus a trailing `RequestLanguageRow` item (key `"__request_language__"`).
- **Visual:** spacing from `LocalSpacing` (lg horizontal / md vertical). `LanguageRow` (`:284-324`):
  `Row` with `SpaceBetween` arrangement — left `Column`(displayName `bodyLarge` + code `bodySmall`
  on `onSurfaceVariant`), right fixed-size `Box(24.dp)` reserving the check-icon slot (uniform row
  height regardless of selection). Selected row shows `Icon(YamiIcons.Check, tint = primary)`
  (`:316-320`). `RequestLanguageRow` (`:332-350`): single `bodyLarge` primary-colored row.
- **States:** loading → centered spinner in `LoadingBox` while `state.isLoading`
  (`:202-203,:228-238`); else list. No empty state (the 11-language list is a compile-time constant
  read synchronously at VM construction — `LanguageViewModel.kt:157`). Request-submission failure →
  snackbar (no persistent error). Success = trailing check on the selected row.
- **Interactions:** row tap → `OnSelectLanguage(code)` (`:258`) → `SetLanguageUseCase` (fire-and-forget,
  triggers `applyApplicationLocale` side effect; Android recreates the activity tree, iOS/Desktop
  no-op). Re-tapping current row is a no-op at DataStore level. Request row tap →
  `OnOpenRequestDialog` (`:265`). Effect collection via `LaunchedEffect(effects)` (`:184-193`).
- **Dialogs/sheets/snackbars:** **LanguageRequestDialog** (`:217-225`, def `:371-433`). Snackbars:
  `RequestSubmitted` → "Request submitted successfully" (`Res.string.request_submitted_successfully`),
  `RequestFailed` → "Request failed" (`Res.string.request_failed`); strings resolved before the
  collector coroutine (`:181-182`).
- **Forms & validation:** in the request dialog (below).
- **Data/behavior:** state from `LanguageViewModel`; `languages` set once at construction from
  `GetSupportedLanguagesUseCase` (`LanguageViewModel.kt:157`); `selectedCode` flow-driven from
  `ObserveSelectedLanguageUseCase` (`:160-166`). 11 supported langs (en/ar/de/es/fr/in/it/ja/pt/ru/tr)
  with native endonyms live in the `:data LanguageRepositoryImpl` (single source of truth). No
  permissions.
- **Feature inventory:** N language rows (tap-to-select, trailing check on selected) + 1 "Request a
  language" row. Top bar title only (no actions, no back IconButton — system back only).
- **Citations:** `ui/.../language/LanguageScreen.kt:154-435`;
  `presentation/.../language/LanguageViewModel.kt:151-211`;
  `presentation/.../language/LanguageState.kt:112-119`;
  `presentation/.../language/LanguageIntent.kt:74-132`;
  `presentation/.../language/LanguageEffect.kt:64-86`;
  `composeApp/.../navigation/routes/LanguageReworkScreenRoute.kt:117-124`; `App.kt:803-809`;
  `domain/.../model/language/Language.kt:79-82`.

---

### LanguageRequestDialog (Language picker modal)

- **Entry/route:** rendered when `state.requestDialogVisible` (`LanguageScreen.kt:217-225`); opened
  by `OnOpenRequestDialog` from the "Request a language" row.
- **Layout & components:** `AlertDialog` (`:380-432`) — title `Res.string.request_add_language` →
  "Request a language"; body `Column`: prompt text (`Res.string.request_language_prompt`,
  `bodyMedium` on `onSurfaceVariant`) + `Spacer(12.dp)` + multiline `OutlinedTextField`
  (`minLines = 4, maxLines = 6, heightIn(min = 120.dp)`, label `Res.string.enter_your_language`);
  confirm `Button`, dismiss `TextButton` (Cancel). **NOTE:** unlike the Settings FeedbackDialog,
  this dialog has NO category dropdown — the `:data` impl hardcodes `subject = "Languages"`.
- **Visual:** default `AlertDialog` shape/colors (no custom RoundedCornerShape/tonalElevation, unlike
  the Settings dialogs). Material 3 defaults.
- **States:** in-flight `submitting` → confirm button shows an 18.dp `CircularProgressIndicator`
  (strokeWidth 2.dp) in place of the "Submit" label, button disabled (`:413-425`). TextField stays
  interactive during submit (`:391-409`).
- **Interactions:** typing → `OnRequestTextChange(text)` (per keystroke, `:221`); Submit →
  `OnSubmitRequest` (`:222`); Cancel/scrim → `OnDismissRequestDialog` (`:223`).
- **Dialogs/sheets/snackbars:** success/failure surfaced as snackbars on the underlying screen (see
  LanguageScreen). On success the VM also clears text + closes dialog; on failure keeps dialog open
  with typed text (`LanguageViewModel.kt:194-206`).
- **Forms & validation:** `submitEnabled = !submitting && text.length >= MIN_REQUEST_LENGTH (8)`
  (`:379,:435`). `isError` + "At least 8 characters" helper below threshold
  (`Res.string.at_least_n_characters`, `:400-408`). VM re-entrance guard
  `if (requestSubmitting) return` (`LanguageViewModel.kt:189`).
- **Data/behavior:** submit → `SendLanguageRequestUseCase(body)` (`LanguageViewModel.kt:193`) → writes
  to the same legacy `:shared` complaint/Firestore pipeline as the Settings feedback flow.
- **Feature inventory:** prompt text, body field, Submit (spinner while in flight), Cancel.
- **Citations:** `ui/.../language/LanguageScreen.kt:371-435`;
  `presentation/.../language/LanguageViewModel.kt:188-208`;
  `presentation/.../language/LanguageEffect.kt:64-86`.

---

### Cluster notes — full KMP design-token inventory

The rework design system lives in `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/theme/`. Tokens
copied verbatim from legacy `composeApp/.../theme/Theme.kt` (commit `e0466ce` baseline) per the
behavior-preservation KDocs. The applied root composable is `YamiTheme` (`YamiTheme.kt:88-114`).

**NOTE — app-root theme not yet rewired:** Per `YamiTheme.kt:55-66` KDoc, `App.kt` still applies the
LEGACY `YamiMangaTheme`, not the rework `YamiTheme`. Leaf `:ui` screens use `YamiTheme` at screen
scope but the app-root provider migration is unfulfilled. So feature screens read tokens via
`MaterialTheme.*` / `LocalSpacing`, and the actual `ColorScheme` at runtime comes from the legacy
`YamiMangaTheme` (which holds byte-identical hex values). `dynamicColor` is a no-op stub awaiting a
`DynamicColorProvider` SPI `(INFERRED — both documented as not landed)`.

#### Colors — `YamiColors.kt` (Material 3 `darkColorScheme` / `lightColorScheme`)

**Dark scheme** (`YamiColors.kt:54-86`):
- primary `#B0C6FF`, onPrimary `#002D6E`, primaryContainer `#00429B`, onPrimaryContainer `#D7E2FF`
- secondary `#B0C6FF`, onSecondary `#002D6E`, secondaryContainer `#00429B`, onSecondaryContainer `#D7E2FF`
- tertiary `#B8D0FF`, onTertiary `#003063`, tertiaryContainer `#2C2C2F`, onTertiaryContainer `#D6E3FF`
- background `#15202B` (Twitter-night-blue, deliberate override of M3 `#1B1B1F`), onBackground `#E3E2E6`
- surface `#15202B`, onSurface `#E3E2E6`, surfaceVariant `#44464F`, onSurfaceVariant `#C4C6D0`
- outline `#8E9099`, inverseOnSurface `#1B1B1F`, inverseSurface `#E3E2E6`, inversePrimary `#0058CA`
- error `#FFB4AB`, onError `#690005`, errorContainer `#93000A`, onErrorContainer `#FFDAD6`

**Light scheme** (`YamiColors.kt:88-120`):
- primary `#0058CA`, onPrimary `#FFFFFF`, primaryContainer `#D7E2FF`, onPrimaryContainer `#001945`
- secondary `#0058CA`, onSecondary `#FFFFFF`, secondaryContainer `#D7E2FF`, onSecondaryContainer `#001945`
- tertiary `#0061A3`, onTertiary `#FFFFFF`, tertiaryContainer `#2C2C2F`, onTertiaryContainer `#001D36`
- background `#FEFBFF`, onBackground `#1B1B1F`, surface `#FEFBFF`, onSurface `#1B1B1F`,
  surfaceVariant `#E3E2EC`, onSurfaceVariant `#44464F`
- outline `#757780`, inverseOnSurface `#F2F0F4`, inverseSurface `#303034`, inversePrimary `#B0C6FF`
- error `#BA1A1A`, onError `#93000A`, errorContainer `#FFDAD6`, onErrorContainer `#410002`

**Pure-black / OLED:** applied in `YamiTheme` (`YamiTheme.kt:96-103`) — when `darkTheme && pureBlack`,
`baseScheme.copy(background = Color.Black, surfaceContainer = Color.Black)`. Base palettes stay pure;
no per-screen OLED branch. Drawables/colors resource dirs exist (`composeResources/drawable`) but no
custom `values/colors.xml` was found — colors are pure code tokens `(INFERRED)`.

#### Typography — `YamiTypography.kt` (Gellix)

Font family `gellixFontFamily()` (`YamiTypography.kt:30-34`): Gellix Regular (`FontWeight.Normal`),
Gellix SemiBold (`FontWeight.Medium`), Gellix Bold (`FontWeight.Bold`). Font files:
`composeResources/font/gellix_{regular,semibold,bold}.ttf`.

`yamiTypography()` (`:40-48`) overrides ONLY 3 slots (legacy parity, byte-for-byte), all others
inherit M3 defaults:
- `bodyLarge` = Gellix Bold, 16.sp
- `titleMedium` = Gellix Medium, 14.sp
- `titleSmall` = Gellix Normal, 12.sp

Gellix is Latin-only; the remaining slots intentionally keep the M3 default family to preserve Arabic
glyph fallback (`:23-27`). `(INFERRED)` runtime application of `yamiTypography()` depends on the
`YamiTheme` vs legacy `YamiMangaTheme` root question above.

#### Shapes — `YamiShapes.kt` (M3 `Shapes`)

`YamiShapes` (`YamiShapes.kt:36-42`): extraSmall `RoundedCornerShape(4.dp)`, small `8.dp`,
medium `12.dp`, large `16.dp`, extraLarge `0.dp` (intentional full-bleed for reader surfaces).
NOTE: the settings/feedback dialogs use hardcoded `RoundedCornerShape(20.dp)` (dialog) /
`12.dp` (fields/cards) literals rather than `YamiShapes` slots.

#### Spacing — `Spacing.kt` (8-pt grid via `LocalSpacing`)

`Spacing` data class (`Spacing.kt:50-58`): xxs `2.dp`, xs `4.dp`, sm `8.dp`, md `12.dp`, lg `16.dp`,
xl `24.dp`, xxl `32.dp`. Exposed via `val LocalSpacing = compositionLocalOf { Spacing() }` (`:64`) —
default lambda means previews work without a `YamiTheme` scope. `YamiTheme` installs it via
`CompositionLocalProvider(LocalSpacing provides Spacing())` (`YamiTheme.kt:110`). All three screens
in this cluster consume `LocalSpacing.current.*` rather than `.dp` literals (with the dialog-shape
exceptions noted).

#### Root theme — `YamiTheme.kt`

`YamiTheme(darkTheme, pureBlack = false, dynamicColor = false (no-op), content)` (`:88-114`):
picks dark/light scheme, applies pure-black override, wraps `MaterialTheme(colorScheme, typography =
yamiTypography(), shapes = YamiShapes)` + installs `LocalSpacing`. `dynamicColor` reserved/dormant.

#### Resource strings (relevant keys, `values/strings.xml` — en; `values-ar` exists for reused keys)

`settings`="Settings", `theme_screen_title`="Theme", `theme_light`="Light Mode", `theme_dark`="Dark
Mode", `theme_system`="System", `pure_black_mode_title`="Pure black dark mode", `follow_system_theme`=
"Follow system theme", `setting_downloaded_only`="Downloaded only", `setting_incognito`="Incognito
mode", `reading_mode`="Reading mode", `clear_cache`="Clear cache", `cached_size`="Cached: %1$s",
`help`="Help", `request_feature_bug`="Request feature / bug", `choose_your_theme`="Choose Your Theme",
`continue_string`="Continue", `enable_notifications`="Enable Notifications", `notification_permission`=
"We need this permission to send you the latest chapters…", `grant_permission`="Grant Permission",
`select_language`="Select Language", `request_add_language`="Request a language", `enter_your_language`=
"Enter your language", `request_language_prompt`="Let us know which language you'd like us to support.",
`at_least_n_characters`="At least %1$d characters", `selected`="Selected"
(`ui/.../composeResources/values/strings.xml:28-158`).

**Localization gaps (en-only inline literals, pending trusted Arabic):** the CBZ section labels
("Use Yami Compressor", "Auto-convert on Download", "Compress Existing Downloads", "Start Conversion",
"Converting..."), the General toggle descriptions, the theme-toggle descriptions, "Testing Mode", and
all VM snackbar strings ("Cache cleared", "Conversion complete", "Thanks! Your feedback was
submitted.", "Failed to clear cache: …", etc.) are hardcoded literals, not `stringResource`
(`SettingsScreen.kt:329,337,363,393-404,560-583`; `SettingsViewModel.kt:212,229-235,258,264`).

---

### Cross-cutting notes / parity risks

- **Settings 5-char vs use-case 8-char feedback gate** (`SettingsScreen.kt:794` vs KDoc `:765-769`):
  5–7 char bodies pass UI but fail server-side. Documented known mismatch.
- **ThemeScreen onboarding affordances are dead code on the live route**: Continue button +
  notification grant row + permission-gated Continue are present in the composable but never wired by
  `ThemeReworkScreenRoute` (passes no params). `AnimatedBackground` + auto-permission-request lifecycle
  fully deferred.
- **App-root still on legacy `YamiMangaTheme`**, not rework `YamiTheme` (`YamiTheme.kt:55-66`);
  `dynamicColor` no-op.
- **Reachability**: App.kt comments repeatedly assert these rework routes are "not surfaced in any
  user-facing entry yet." In practice Theme/Statistics/Language/About/Complaint/WhatsNew/Downloads are
  reachable via the Settings hub nav rows; whether the Settings hub itself is user-reachable was not
  confirmed in this scope `(INFERRED)`.
