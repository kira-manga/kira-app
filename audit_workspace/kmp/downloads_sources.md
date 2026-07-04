# KMP (Rework) Audit — Downloads + Sources / RepoSettings Cluster

Read-only audit of the architecture-rework KMP app. Scope: the Downloads list
surface and the Sources / RepoSettings surface (language toggles, repo/source
toggles, request-source dialog, upcoming-languages info card, onboarding
Finish / auto-seed). All citations are `file:line` against the rework tree at
`D:/yami manga/yami-kmp/`.

---

### DownloadsScreen

- **Entry/route:**
  - Composable `DownloadsScreen(viewModel, onBack, modifier)` at
    `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/downloads/DownloadsScreen.kt:161-185`.
  - TWO nav keys render the SAME rework screen + rework `DownloadsViewModel`:
    - `Screen.DownloadsScreen` (legacy key, post-swap §295) via
      `DownloadsScreenRoute` — `composeApp/.../App.kt:550-556`; route at
      `navigation/routes/DownloadsScreenRoute.kt:242-252`.
    - `Screen.DownloadsRework` (rework key) via `DownloadsReworkScreenRoute` —
      `App.kt:572-578`; route at `navigation/routes/DownloadsReworkScreenRoute.kt:97-107`.
  - Both routes resolve a per-`NavBackStackEntry` VM via `koinViewModel()`; the
    underlying repositories are `single`-scoped and shared, so Room state is
    identical across both (`DownloadsScreenRoute.kt:109-119`).
  - User-reachable entry: rework Settings hub Downloads row → `Screen.DownloadsRework`
    (`navigation/routes/SettingsRoute.kt:154`, `SettingsReworkScreenRoute.kt:126`).
    Library nav drawer Downloads link points at `Screen.DownloadsScreen`
    (per `DownloadsScreenRoute.kt:206-209` prose; live).
  - `onBack = { navController.safePopBackStack() }` (both routes).
  - Bottom bar hidden on both routes (`SideEffect { onBottomBarVisibleChange(false) }`,
    `App.kt:551`, `App.kt:573`).

- **Layout & components:**
  - `Scaffold` with `TopAppBar` + `SnackbarHost` (`DownloadsScreen.kt:196-258`).
  - Top bar: title `Text(downloads)` + a **labelled `TextButton` "Back"** in the
    `navigationIcon` slot — NOT an icon (`DownloadsScreen.kt:199-206`). KDoc notes
    icon conversion was deferred (`DownloadsScreen.kt:101-108`).
  - Body: `Box` (background color) → if loading, `YamiLoadingState`; else `Column`
    with a 3-tab `TabRow` + the selected bucket's `LazyColumn`
    (`DownloadsScreen.kt:210-256`).
  - `TabRow` 3 tabs: "Active" / "Failed" / "Completed"
    (`DownloadsScreen.kt:221-237`), `containerColor = background`.
  - Per-bucket list `DownloadBucketList` → `LazyColumn`, contentPadding
    `vertical 8.dp, horizontal 16.dp`, items keyed by `chapterId`, 8.dp `Spacer`
    between rows (`DownloadsScreen.kt:260-283`).
  - Two card variants: `RunningDownloadCard` for RUNNING rows; `DownloadCard` for
    all other states (`DownloadsScreen.kt:274-279`).

- **Visual:** spacing, typography, colors, shapes/elevation
  - Cards: `RoundedCornerShape(12.dp)`, `containerColor = surfaceVariant`, no
    explicit elevation (`DownloadsScreen.kt:291-296`, `389-394`).
  - Inner card padding `spacing.md` (`LocalSpacing`) (`DownloadsScreen.kt:299-301`).
  - Title: `titleMedium`, `FontWeight.SemiBold`, `onSurface`, 1 line + ellipsis
    (`DownloadsScreen.kt:303-310`).
  - Status text: `bodyMedium`; color `error` when FAILED else `onSurfaceVariant`;
    2 lines + ellipsis (`DownloadsScreen.kt:312-322`).
  - Running progress %: `labelMedium`, `onSurfaceVariant` (`DownloadsScreen.kt:415-419`).
  - `LinearProgressIndicator` height `6.dp`, full width (`DownloadsScreen.kt:422-427`).
  - Spacers `spacing.xs` / `spacing.sm` between elements.

- **States:** loading / empty / error / success
  - **Loading:** `state.isLoading` (default `true`) → `YamiLoadingState` (centred
    spinner) covers whole content area, early-returns before the TabRow
    (`DownloadsScreen.kt:216-219`; `DownloadsState.kt:68`).
  - **Empty (per-bucket):** when the selected bucket list is empty,
    `YamiEmptyState(title = <empty label>)` — labels "No active downloads" /
    "No failed downloads" / "No completed downloads"
    (`DownloadsScreen.kt:266-268`, `241/247/252`).
  - **Error:** no screen-level error pane. Mutation failures surface as a snackbar
    (see Dialogs/snackbars). The observe upstream has no error state
    (`DownloadsViewModel.kt:53-60`; `DownloadsState` has no `error` field).
  - **Success:** populated `LazyColumn` of cards; success of a mutation is silent —
    Room re-emit drives the row state change (FAILED→QUEUED on retry, row vanishes
    on delete) (`DownloadsViewModel.kt:37-40`; `DownloadsEffect.kt:14-24`).

- **Interactions:** clicks, long-press, gestures, swipe, pull-to-refresh, animations
  - Tab click → `DownloadsIntent.OnTabSelect(index)` (`DownloadsScreen.kt:233`).
  - Per-row action buttons (see Feature inventory). All are labelled `TextButton`s,
    right-aligned (`Arrangement.End`).
  - **No long-press, no swipe-to-dismiss, no pull-to-refresh** — the screen is
    flow-driven; there is no `OnRefresh` intent (reactivity via Room re-emit,
    `DownloadsViewModel.kt:163-184`).
  - **Animation:** running-row progress bar animates via
    `animateFloatAsState(tween(300ms))` (`DownloadsScreen.kt:385-388`, `423`).
  - No row-tap navigation (terminal screen; `DownloadsReworkScreenRoute.kt:20-23`).

- **Dialogs/sheets/snackbars:**
  - `SnackbarHost` anchored to the Scaffold (`DownloadsScreen.kt:208`).
  - `DownloadsEffect.ShowError(message)` collected via
    `LaunchedEffect(viewModel) { effects.collectLatest { ... } }` →
    `snackbarHostState.showSnackbar(effect.message)` (`DownloadsScreen.kt:170-176`).
  - Message = throwable `message` ?: `simpleName` ?: "Unknown error"
    (`DownloadsViewModel.kt:224-231`).
  - **No confirmation dialogs** for delete/cancel (single-tap acts immediately).
    Intent carries the full `DownloadedChapter` so a future "Delete chapter X?"
    dialog could read fields without a re-lookup (`DownloadsIntent.kt:12-21`).

- **Forms & validation:** none — no text input on this screen.

- **Data/behavior:** fetches, side effects, navigation, permissions
  - VM `init {}` collects `ObserveDownloadsUseCase()` and partitions every emission
    into 3 buckets with three `.filter {}` passes (`DownloadsViewModel.kt:163-184`):
    - **Active** = RUNNING ∪ QUEUED ∪ COMPRESSING
    - **Failed** = FAILED
    - **Completed** = SUCCESS
  - `selectedTab` default = **2 (Completed)** — preserves legacy first-open behaviour
    (`DownloadsState.kt:73`, `36-39`).
  - Mutations are fire-and-forget `viewModelScope.launch {}` calls to the matching
    use case; failures call `emitOnFailure` (`DownloadsViewModel.kt:186-231`):
    - `OnRetry` → `RetryDownloadUseCase(chapterId)`
    - `OnCancel` → `CancelDownloadUseCase(chapterId)` (queue-prune)
    - `OnCancelRunning` → `CancelRunningDownloadUseCase(chapterId, mangaId)` (interrupt in-flight)
    - `OnDelete` → `DeleteDownloadUseCase(chapterId)`
  - **No in-flight guard** on mutations — relies on idempotency (cancel-twice no-op,
    etc.) (`DownloadsViewModel.kt:85-92`).
  - No debouncing/sampling on the upstream — relies on Compose `collectAsState`
    natural debounce of structurally-equal emissions (`DownloadsViewModel.kt:73-83`).
  - No permissions requested in this screen.
  - `:data` impl is a strangler-fig over the legacy `DownloadRepository.observeAllDownloads()`
    Room flow (`DownloadsReworkScreenRoute.kt:28-34`).

- **Feature inventory:** EVERY affordance
  1. Back `TextButton` (top bar) → `onBack` → `safePopBackStack`.
  2. 3 tabs Active / Failed / Completed → `OnTabSelect`.
  3. **RUNNING row** (`RunningDownloadCard`, Active tab): title (weight 1f) + "%"
     label row, animated `LinearProgressIndicator`, **"Cancel" `TextButton`** →
     `OnCancelRunning` (`DownloadsScreen.kt:378-439`).
  4. **QUEUED / COMPRESSING row** (Active tab): title + status label + **"Cancel"
     `TextButton`** → `OnCancel` (`DownloadsScreen.kt:335-345`).
  5. **FAILED row** (Failed tab): title + red status ("Failed: <reason>") +
     **"Retry" `TextButton`** → `OnRetry` and **"Delete" `TextButton`** → `OnDelete`
     (`DownloadsScreen.kt:346-359`).
  6. **SUCCESS row** (Completed tab): title + "Downloaded" status + **"Delete"
     `TextButton`** → `OnDelete` (`DownloadsScreen.kt:360-370`).
  7. Status labels via `statusLabel()`: Queued / Running / Compressing / Downloaded /
     "Failed: <reason>" (reason ?: "unknown") (`DownloadsScreen.kt:441-451`).
  8. Snackbar on mutation failure.
  - **Gap vs legacy (deferred, present-but-text-only):** all row actions and the
    back arrow are labelled text, not the legacy `Icons.Default.Cancel/Refresh/Delete`
    + `ArrowBack` icons; icon conversion deferred (`DownloadsScreen.kt:101-108`;
    `DownloadsScreenRoute.kt:77-82`). No affordance is missing, only the glyph form.
  - **No "Run all" / "Clear all" / "Cancel all" / pause / global affordances** — the
    audit scope mentions "run-all/clear" but the rework screen has NO bulk/global
    action (no `DownloadsIntent` variant for it; only the 5 listed intents exist in
    `DownloadsIntent.kt:59-107`). (INFERRED gap — no bulk control surfaced.)
  - **No download chapter cover thumbnail** on rows (text-only cards).
  - No paging — plain `List<DownloadedChapter>` (`DownloadsScreenRoute.kt:121-125`).

- **Citations:** file:line
  - `ui/.../downloads/DownloadsScreen.kt:161-451` (whole screen)
  - `presentation/.../downloads/DownloadsState.kt:67-74`
  - `presentation/.../downloads/DownloadsIntent.kt:59-107`
  - `presentation/.../downloads/DownloadsEffect.kt:78-86`
  - `presentation/.../downloads/DownloadsViewModel.kt:153-232`
  - `domain/.../model/downloads/DownloadedChapter.kt:107-115`
  - `domain/.../model/downloads/DownloadState.kt:59-74`
  - `composeApp/.../navigation/routes/DownloadsReworkScreenRoute.kt:97-107`
  - `composeApp/.../navigation/routes/DownloadsScreenRoute.kt:242-252`
  - `composeApp/.../App.kt:550-578`

---

### SourcesScreen (also serves RepoSettings + onboarding Sources)

- **Entry/route:**
  - Composable `SourcesScreen(viewModel, modifier, onFinish?, onboardingLanguageTag?)`
    at `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/sources/SourcesScreen.kt:235-252`.
  - ONE rework screen serves THREE nav keys, all backed by the rework `SourcesViewModel`
    (`single`-scoped `SourcesRepository` shared across all):
    - `Screen.Sources` (onboarding step 3) → `SourcesScreenRoute`
      (`App.kt:416-422`; route `navigation/routes/SourcesScreenRoute.kt:155-171`).
      Passes `onboardingLanguageTag = userLanguageCode` (from
      `DataStoreHelper.languageFlow`) AND `onFinish = navigate(Screen.RepoSettings(isFirstOpen=true))`.
    - `Screen.RepoSettings` (onboarding step 4 + in-settings entry from Home) →
      `RepoSettingsScreenRoute` (`App.kt:534-540`; route
      `navigation/routes/RepoSettingsScreenRoute.kt:123-148`). `onFinish` is non-null
      ONLY when `args.isFirstOpen == true` (flips `first_launch` pref → navigate Library
      with full backstack clear); else `null` (no Finish button).
    - `Screen.SourcesRework` (standalone rework key) → `SourcesReworkScreenRoute`
      (`App.kt:712-718`; route `navigation/routes/SourcesReworkScreenRoute.kt:126-133`).
      Passes NO `onFinish` and NO `onboardingLanguageTag` (both null).
  - No `onBack` parameter — relies on system back; no custom nav icon
    (`RepoSettingsScreenRoute.kt:55-63`).

- **Layout & components:**
  - `Scaffold` with `TopAppBar(title = "Sources")`, `SnackbarHost`, and a
    `bottomBar` that renders `FinishButton` iff `onFinish != null`
    (`SourcesScreen.kt:285-297`).
  - Body branches: loading → `LoadingBox`; empty → `EmptyBox("No sources available")`;
    else → `SourcesList` (`SourcesScreen.kt:298-310`).
  - `SourcesList` = `LazyColumn` (`SourcesScreen.kt:331-387`) with, in order:
    1. `item("request-source")`: `RequestSourceRow` + `HorizontalDivider`.
    2. `item("upcoming-languages")`: `UpcomingLanguagesCard` + `HorizontalDivider`.
    3. Per language group: a `LanguageHeader` item (key `header-$language`) then
       per-source `SourceRow` items (key `source-${api}`) each followed by a
       `HorizontalDivider`.
  - `RequestSourceRow`: clickable Row, title "Request adding source" + subtitle
    "Enter the URL for the site…" (`SourcesScreen.kt:389-415`).
  - `UpcomingLanguagesCard`: non-clickable Column, title "Upcoming Languages" +
    multi-line description (with emoji) (`SourcesScreen.kt:440-461`).
  - `LanguageHeader`: Row, language name (parens stripped) + "x of N enabled"
    caption + per-language `Switch` (`SourcesScreen.kt:500-542`).
  - `SourceRow`: Row, `source.api` label (weight 1f) + per-source `Switch`
    (`SourcesScreen.kt:544-568`).
  - `FinishButton`: full-width pill `Button`, `RoundedCornerShape(26.dp)`, height
    `50.dp`, label "Finish" (`SourcesScreen.kt:478-498`).

- **Visual:** spacing, typography, colors, shapes/elevation
  - List rows padded `horizontal = spacing.lg`, `vertical = spacing.md` (LocalSpacing)
    (`SourcesScreen.kt:362-382`).
  - Row titles `titleMedium` SemiBold; subtitles/captions `bodySmall` on
    `onSurfaceVariant` (`SourcesScreen.kt:403-412`, `527-535`).
  - Source label `bodyLarge` (`SourcesScreen.kt:558-561`).
  - `FinishButton` Box padding `horizontal 24.dp / vertical 12.dp`; pill corner 26.dp;
    label `labelLarge`; Material3 default primary colors (`SourcesScreen.kt:478-498`).
  - `HorizontalDivider` between every source row + after the two header items.
  - Material 3 `Switch` (default colors).
  - **No `AnimatedBackground` / gradient overlay** — cosmetic decoration intentionally
    omitted (`SourcesScreen.kt:106-108`, audit-trail at `192-233`).

- **States:** loading / empty / error / success
  - **Loading:** `state.isLoading` (default `true`) → `YamiLoadingState`
    (`SourcesScreen.kt:300`, `321-324`; `SourcesState.kt:75`).
  - **Empty:** `state.isEmpty` (= `!isLoading && items.isEmpty()`) →
    `YamiEmptyState("No sources available")` (`SourcesScreen.kt:301`,
    `SourcesState.kt:82`).
  - **Error:** no screen error state — Room observe doesn't throw; no `error` field
    on state (`SourcesState.kt:10-15`). Submit failures surface via snackbar.
  - **Success:** language-grouped list of toggle rows.

- **Interactions:** clicks, long-press, gestures, swipe, pull-to-refresh, animations
  - Tap "Request adding source" row → `OnOpenComplaintDialog` (`SourcesScreen.kt:347`).
  - Per-language `Switch` → `OnToggleLanguage(language, enabled)`
    (`SourcesScreen.kt:367-369`). `checked = sources.any { it.isEnabled }`
    ("ON if any enabled"; flipping OFF bulk-disables the group)
    (`SourcesScreen.kt:508`, `537-540`).
  - Per-source `Switch` → `OnToggleSource(source, enabled)` (`SourcesScreen.kt:377-379`).
  - Tap "Finish" (onboarding only) → `onFinish()` callback.
  - **No long-press, no swipe, no pull-to-refresh** (flow-driven, reactive via Room
    re-emit; no `OnRefresh` intent).
  - **No explicit animations** (`AnimatedBackground` intentionally dropped).

- **Dialogs/sheets/snackbars:**
  - `SnackbarHost` anchored to Scaffold (`SourcesScreen.kt:290`); consumes
    `SourcesEffect.ShowSnackbar` via `LaunchedEffect(effects) { effects.collect {...} }`
    (`SourcesScreen.kt:267-273`).
  - **`RequestSourceDialog`** (`AlertDialog`) shown when `state.complaintDialogOpen`
    (`SourcesScreen.kt:312-318`, `592-698`):
    - `RoundedCornerShape(20.dp)`, `surface` container, tonalElevation 3.dp.
    - Title "Request adding source"; body text "We will add it as soon as possible";
      single `OutlinedTextField` labelled "Enter the site URL" (minLines 4, maxLines 6,
      heightIn min 120.dp).
    - Confirm `Button` (label "Submit" / "Submitting…" while in-flight) →
      `OnSubmitComplaint(body)`; Dismiss `TextButton` "Cancel" → `OnDismissComplaintDialog`.
  - Snackbar messages (English literals, VM-built): success
    "Thanks! Your request was submitted."; failure "Failed to submit request: <cause>"
    (`SourcesViewModel.kt:185`, `191`). (INFERRED: not localized — hardcoded English.)

- **Forms & validation:**
  - `RequestSourceDialog` body field (`SourcesScreen.kt:631-664`):
    - Local `remember { mutableStateOf("") }` — NOT mirrored into MVI state
      (`SourcesScreen.kt:600`).
    - **Length cap 500** — `onValueChange` short-circuits past 500 chars
      (`SourcesScreen.kt:633`).
    - **Min length 5** — `submitEnabled = body.length >= 5 && !isSubmitting`
      (`SourcesScreen.kt:602`); `isError` + "Minimum 5 characters" helper when
      non-empty and < 5 (`SourcesScreen.kt:642-656`); char counter "len/500".
    - **Known UI/use-case mismatch:** the UI gate is 5 but the underlying
      `SendComplaintUseCase` requires ≥ 8 — bodies of 5-7 chars pass the UI then fail
      server-side with a snackbar error (`SourcesScreen.kt:579-583`). (Documented bug
      surface; flagged for Phase 10 reconciliation.)
  - Dialog dismissal gated while submitting: `DialogProperties(dismissOnBackPress =
    !isSubmitting, dismissOnClickOutside = !isSubmitting)`; Cancel disabled; VM also
    guards `OnDismissComplaintDialog` (`SourcesScreen.kt:604-609`;
    `SourcesViewModel.kt:161-164`).

- **Data/behavior:** fetches, side effects, navigation, permissions
  - VM `init {}` collects `ObserveSourcesUseCase()` → `items` snapshot
    (`SourcesViewModel.kt:143-149`). No `catch{}` (Room observe doesn't throw).
  - `groupedByLanguage` = `items.groupBy { it.language }` (insertion-ordered;
    `:ui` consumes directly) (`SourcesState.kt:99-100`).
  - `enabledCount` derived getter (surfaced but not currently rendered as a
    top-level header line) (`SourcesState.kt:106`).
  - Toggle intents = fire-and-forget `viewModelScope.launch {}` to
    `SetSourceEnabledUseCase(api, enabled)` / `SetLanguageEnabledUseCase(language, enabled)`;
    no failure handling (UPDATE is infallible) (`SourcesViewModel.kt:152-158`).
    Per-source fan-out for language toggle lives in `:data` impl.
  - **Onboarding auto-seed:** `LaunchedEffect(onboardingLanguageTag)` fires
    `OnSeedDefaultLanguage(tag)` when the tag is non-null (no-op for null, i.e. the
    standalone/in-settings entries) (`SourcesScreen.kt:279-283`). VM →
    `EnableDefaultLanguageSourcesUseCase(tag)` (use case owns uppercase+parens +
    EN-fallback) (`SourcesViewModel.kt:166-168`). Idempotent (Room no-re-emit on
    unchanged row) (`SourcesScreen.kt:93-97`).
  - **Submit complaint:** `handleSubmitComplaint` re-entry guarded by
    `isSubmittingComplaint`; sets flag true; `SubmitFeedbackUseCase(type=SITES_ADD,
    subject=type.name, body)`; on success closes dialog + success snackbar; on failure
    keeps dialog open (preserves typed text) + error snackbar
    (`SourcesViewModel.kt:172-194`).
  - **Navigation:** onboarding step 3 Finish → `Screen.RepoSettings(isFirstOpen=true)`
    (`SourcesScreenRoute.kt:167-169`); step 4 (RepoSettings) Finish → flip `first_launch`
    pref + `navigate(Screen.Library)` with `popUpTo(start){inclusive} + launchSingleTop`
    (`RepoSettingsScreenRoute.kt:134-146`). Onboarding chain: Welcome → Theme → Sources →
    RepoSettings → Library (`SourcesScreenRoute.kt:45-52`).
  - **No permissions** requested by this screen.
  - **No outbound nav** from the standalone `Screen.SourcesRework` entry
    (`SourcesReworkScreenRoute.kt:16-19`).

- **Feature inventory:** EVERY affordance
  1. "Request adding source" header row (title + subtitle) → opens dialog.
  2. "Upcoming Languages" info card (title + description, non-interactive).
  3. Per-language section header: stripped language name + "x of N enabled" caption +
     per-language `Switch` (any-enabled semantics, bulk toggle).
  4. Per-source row: `api` label + per-source `Switch`.
  5. `HorizontalDivider`s between rows.
  6. Onboarding "Finish" pill button (only when `onFinish != null`).
  7. `RequestSourceDialog`: URL text field (5-500 char validation, char counter),
     "Submit"/"Submitting…" confirm, "Cancel" dismiss, dismissal-gating while submitting.
  8. Success/failure snackbars.
  - **No source tabs** — the rework groups by language sections in a single
    `LazyColumn`; there is NO tabbed source UI (audit scope mentions "source tabs" but
    none exist here). (INFERRED gap vs scope expectation.)
  - **No per-source priority badge, no cover, no chapter info** — intentional
    (`SourcesScreen.kt:133-135`).
  - **No site-state (WORKING/STOPPED) indicator** — `Source` is a 4-field model
    (api/language/priority/isEnabled); siteState/baseUrl/etc. left on the legacy entity
    (`Source.kt:13-20`, `86-95`).
  - **No search/filter** of the source list.
  - Upcoming-languages card and Request-source subtitle/body strings ARE localized
    (en + ar) via `stringResource`; submit-result snackbars are hardcoded English (gap).

- **Citations:** file:line
  - `ui/.../sources/SourcesScreen.kt:235-698` (whole screen)
  - `presentation/.../sources/SourcesState.kt:74-107`
  - `presentation/.../sources/SourcesIntent.kt:99-195`
  - `presentation/.../sources/SourcesEffect.kt:63-78`
  - `presentation/.../sources/SourcesViewModel.kt:133-195`
  - `domain/.../model/sources/Source.kt:86-95`
  - `composeApp/.../navigation/routes/SourcesScreenRoute.kt:155-171`
  - `composeApp/.../navigation/routes/RepoSettingsScreenRoute.kt:123-148`
  - `composeApp/.../navigation/routes/SourcesReworkScreenRoute.kt:126-133`
  - `composeApp/.../App.kt:416-422, 534-540, 712-718`
  - Resources: `ui/.../composeResources/values/strings.xml` (request_adding_source,
    languages_coming_soon_title/description, enter_the_site_url,
    minimum_5_characters_required, sources_enabled_count, finish, submit, submitting,
    no_sources_available, etc.) + values-ar mirror.

---

### Cluster notes

- **Single rework screen, three Sources entries.** `SourcesScreen` is a parameterized
  one-file surface that covers the legacy onboarding-Sources, RepoSettings, and the
  standalone rework key. The only behavioral difference between entries is the nullable
  `onFinish` (Finish button gate) and nullable `onboardingLanguageTag` (auto-seed gate).
  Legacy `RepoSettingsScreen.kt` / onboarding `SourcesScreen.kt` are retired (§307/§353);
  no legacy Sources/RepoSettings UI is user-reachable.

- **Single rework screen, two Downloads entries.** `Screen.DownloadsScreen` (legacy key,
  post-swap) and `Screen.DownloadsRework` both render the identical rework `DownloadsScreen`
  + VM with shared `single`-scoped repos. User reaches it via the rework Settings hub
  Downloads row (→ `Screen.DownloadsRework`) and the Library drawer (→ `Screen.DownloadsScreen`).
  Legacy Downloads UI + `DownloadViewModelv2` are retired (§352/§439).

- **Icon-vs-text deferral (Downloads).** Every Downloads row action (Cancel/Retry/Delete)
  and the back arrow render as labelled `TextButton`s, not icons. No affordance is missing
  — only the glyph form differs from the legacy `Icons.Default.*`. Tracked under the
  UP-2/UP-4 icon work; `:ui` ships `materialIconsExtended` but this screen wasn't part of
  the conversion set (`DownloadsScreen.kt:101-108`).

- **No bulk/global Downloads controls (likely gap vs native).** The audit scope lists
  "run-all/clear" but the rework Downloads screen has NO Run-all / Clear-all /
  Cancel-all / pause affordance, and no corresponding `DownloadsIntent`. Only per-row
  Cancel/Retry/Delete exist. (INFERRED gap — verify against OLD app audit.)

- **No "source tabs" in rework Sources (likely gap vs native).** The rework Sources uses a
  single language-grouped `LazyColumn`, not a tabbed source browser. The scope mention of
  "source tabs" has no rework counterpart on this surface (a separate legacy `SourcesTabs.kt`
  exists in the Home/Search cluster, not here). (INFERRED — out-of-cluster.)

- **Validation mismatch bug (Sources request dialog).** UI min-length gate is 5 chars but
  the underlying use case requires ≥ 8 (`SendComplaintUseCase`), so 5-7 char bodies pass UI
  validation then fail server-side with an error snackbar
  (`SourcesScreen.kt:579-583`). Real, documented, not yet reconciled.

- **Hardcoded English snackbars (Sources).** Submit success/failure snackbar literals are
  built in the VM in English ("Thanks! Your request was submitted." / "Failed to submit
  request: …") — not `stringResource`, so they bypass the ar locale
  (`SourcesViewModel.kt:185,191`). Most other Sources copy IS localized. (Gap.)

- **No confirmation dialogs on destructive Downloads actions.** Delete / Cancel act on a
  single tap with no "Are you sure?" — the intent already carries the full
  `DownloadedChapter` so a future confirm dialog is a low-friction add
  (`DownloadsIntent.kt:12-21`). (Present-but-unbuilt extension hook.)

- **No pull-to-refresh on either surface.** Both are purely Room-flow-driven and reactive;
  neither exposes a manual refresh. Consistent with the rework History/Updates/Statistics
  posture.

- **KDoc/source ratio.** Both clusters carry very large §253 audit-trail postscripts (the
  Downloads/Sources screens are ~75% KDoc by line count); these are historical lineage
  records and do not affect runtime behavior.
