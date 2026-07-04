# Downloads & Sources — gaps

19 gaps total (8 Downloads, 11 Sources): 4 P0, 7 P1, 5 P2, 3 P3.

---

## Downloads screen gaps

### GAP-DL-01 — Back affordance is a labelled "Back" TextButton, not an ArrowBack icon
- **Screen/surface:** DownloadsScreen — top bar navigationIcon
- **Type:** VISUAL
- **OLD (target):** `TopAppBarCom` renders a back `ArrowBack` icon tinted `onBackground` (`DownloadsScreen.kt:83-144`; old audit lines 18, 41).
- **KMP (current):** A labelled `TextButton` "Back" in the `navigationIcon` slot — not an icon; icon conversion explicitly deferred (`ui/.../downloads/DownloadsScreen.kt:199-206`, `101-108`).
- **Priority:** P1
- **Acceptance criteria:** Top bar shows an `ArrowBack` icon-button (no "Back" text label) tinted to match the theme; tapping it pops the back stack. Visual matches the native top bar.
- **Notes:** Part of the deferred UP-2/UP-4 icon conversion. `:ui` already ships `materialIconsExtended`. Reuse the shared `YamiIcons`/top-bar component if one exists.

### GAP-DL-02 — Row action buttons are text TextButtons, not icon buttons
- **Screen/surface:** DownloadsScreen — Running/Queued/Failed/Completed row actions
- **Type:** VISUAL
- **OLD (target):** Cancel = `Icons.Default.Cancel` (tint `error`); Retry uses a "Retry" TextButton but Delete = `Icons.Default.Delete` (tint `error`); SUCCESS shows inert `Icons.Outlined.Done` (tint `primary`); running-card cancel = `IconButton(Icons.Default.Cancel)` tint error (`DownloadsScreen.kt:339-374`, `428`; old audit lines 27, 37-40, 57-60).
- **KMP (current):** All row actions render as labelled `TextButton`s right-aligned (Cancel/Retry/Delete), icon conversion deferred (`DownloadsScreen.kt:126-141`, `335-370`).
- **Priority:** P1
- **Acceptance criteria:** Cancel and Delete render as icon buttons (Cancel/Delete glyphs, error tint). SUCCESS rows show an inert green Done check icon. Layout/affordance count unchanged; only glyph form converted to match native.
- **Notes:** Same deferral as GAP-DL-01. Note native Retry is itself a TextButton, so Retry-as-text may already be parity — confirm against native before converting Retry to an icon.

### GAP-DL-03 — Card visual style diverges (color, shape, elevation, padding)
- **Screen/surface:** DownloadsScreen — DownloadCard / RunningDownloadCard
- **Type:** VISUAL
- **OLD (target):** Cards use `containerColor = colorScheme.background`, `RoundedCornerShape(8.dp)`, `shadow(elevation=4.dp, ambient/spot=onSurface@0.9f, clip=false)`, outer padding `horizontal 16 / vertical 8` (DownloadItemCard) and `horizontal 6 / vertical 12` (RunningDownloadItemCard); title `AutoSubtitleText` 16.sp Bold `onBackground` (`DownloadsScreen.kt:262-304`, `379-459`; old audit lines 24-27).
- **KMP (current):** Cards use `RoundedCornerShape(12.dp)`, `containerColor = surfaceVariant`, NO explicit elevation/shadow; inner padding `spacing.md`; title `titleMedium` SemiBold `onSurface` (`DownloadsScreen.kt:291-310`, `389-394`).
- **Priority:** P2
- **Acceptance criteria:** Cards match native corner radius (8.dp), background (`background`), 4.dp shadow with onSurface@0.9f ambient/spot, and the running-card's distinct 6/12 outer padding. Title weight reads Bold and color `onBackground`.
- **Notes:** `AutoSubtitleText` is an auto-shrinking text component in native — confirm whether `:ui` has an equivalent or if a fixed 16.sp Bold is acceptable. The shadow params are load-bearing for the native "floating card" look.

### GAP-DL-04 — Title string format differs ("Ch N - Title " vs titleMedium chapter title)
- **Screen/surface:** DownloadsScreen — row title
- **Type:** VISUAL
- **OLD (target):** Title text = `"Ch ${item.number} - ${item.mangaTitle} "` (note trailing space), single line, Bold (`DownloadsScreen.kt:297-304`; old audit line 25).
- **KMP (current):** Title renders via `titleMedium` SemiBold, 1 line + ellipsis; exact composed string not cited as `"Ch N - Title"` format (`DownloadsScreen.kt:303-310`).
- **Priority:** P2
- **Acceptance criteria:** Row title displays `"Ch <number> - <mangaTitle>"` matching native composition exactly.
- **Notes:** Verify the KMP `DownloadedChapter` exposes `number` + `mangaTitle`; trivial format alignment if so. Possibly already matching — confirm the exact KMP string builder.

### GAP-DL-05 — COMPRESSING bucketed to Active vs native showing it as "Downloaded"
- **Screen/surface:** DownloadsScreen — tab partitioning + status label
- **Type:** BEHAVIOR
- **OLD (target):** Active = `[RUNNING, QUEUED]` only; COMPRESSING is NOT in the Active state list. COMPRESSING status text maps to the "Downloaded" label (`DownloadViewModelv2.kt:58-60`; `DownloadsScreen.kt:306-337`; old audit lines 26, 48).
- **KMP (current):** Active = RUNNING ∪ QUEUED ∪ **COMPRESSING**; COMPRESSING gets its own "Compressing" status label (`DownloadsViewModel.kt:163-184`; `DownloadsScreen.kt:441-451`).
- **Priority:** P2
- **Acceptance criteria:** Decide and document the canonical placement. For strict parity: COMPRESSING items do not appear under Active (native enqueues them via `[RUNNING,QUEUED]` only) and their status label reads "Downloaded". If the rework intentionally improves this, record as accepted DEVIATION.
- **Notes:** This is a genuine behavioral divergence, not just labels. Native's `observeDownloadsByStatePaged([RUNNING,QUEUED])` means a COMPRESSING row would fall out of Active entirely. Confirm desired product behavior before changing — the rework's "Compressing" label is arguably better UX.

### GAP-DL-06 — Native uses Paging 3 (cachedIn); KMP loads a plain List
- **Screen/surface:** DownloadsScreen — list data source
- **Type:** DEVIATION(platform)
- **OLD (target):** Per-tab paged flows via `observeDownloadsByStatePaged(states).cachedIn(viewModelScope)`; paging append loading spinner + "Error loading more items" text (`DownloadViewModelv2.kt:58-60`; `DownloadsScreen.kt:177-200`, `233-256`; old audit lines 30-31, 48).
- **KMP (current):** Plain `List<DownloadedChapter>` partitioned into 3 buckets; no paging, no append spinner, no append-error text (`DownloadsScreenRoute.kt:121-125`; `DownloadsViewModel.kt:163-184`).
- **Priority:** P3
- **Acceptance criteria:** Acceptable as a platform deviation IF download lists are bounded (typically small). Document the decision. If lists can grow unbounded, add lazy paging. No append-error pane needed for an in-memory list.
- **Notes:** Paging 3 is Android-only; KMP common code can't use it directly. The append-loading/append-error states (old audit 30-31) have no KMP equivalent and are correctly dropped with paging. Treat as DEVIATION(platform), low priority.

### GAP-DL-07 — Loading/empty state model differs (whole-screen loader + per-bucket empty vs none)
- **Screen/surface:** DownloadsScreen — states
- **Type:** KMP-EXTRA
- **OLD (target):** NO whole-screen loading state; NO empty-state composable for any tab — empty list renders a blank `LazyColumn` (old audit lines 32, 185).
- **KMP (current):** `state.isLoading` (default true) shows whole-content `YamiLoadingState` early-returning before the TabRow; each empty bucket shows `YamiEmptyState` ("No active/failed/completed downloads") (`DownloadsScreen.kt:216-219`, `266-268`).
- **Priority:** P3
- **Acceptance criteria:** Keep as an accepted improvement (empty/loading states are better UX than native's blank list). Record as KMP-EXTRA; no action unless strict pixel-parity is required. Verify the initial-loading gate doesn't flash the spinner indefinitely if Room emits an empty list.
- **Notes:** The whole-screen loader hiding the TabRow on first frame is a behavior native does not have — ensure `isLoading` flips false on first Room emission (even empty) so tabs appear. The empty-state labels need en+ar localization (see GAP-DL-08).

### GAP-DL-08 — Per-bucket empty-state labels need localization audit
- **Screen/surface:** DownloadsScreen — YamiEmptyState titles
- **Type:** BEHAVIOR
- **OLD (target):** N/A (native has no empty state). New copy introduced by KMP.
- **KMP (current):** Empty titles "No active downloads" / "No failed downloads" / "No completed downloads" (`DownloadsScreen.kt:241/247/252`, `266-268`).
- **Priority:** P2
- **Acceptance criteria:** The three empty-state strings resolve via `stringResource` with en + ar entries (not hardcoded English literals).
- **Notes:** Confirm whether these are already `stringResource`-backed; the KMP audit doesn't assert it. If hardcoded, add keys + Arabic verbatim per the UP-3 localization playbook.

---

## Sources / RepoSettings screen gaps

### GAP-SRC-01 — Request dialog min-length UI gate (5) vs use-case requirement (8)
- **Screen/surface:** SourcesScreen — RequestSourceDialog validation
- **Type:** BEHAVIOR
- **OLD (target):** FeedbackDialog gates submit on `body.length >= 5`; supporting text "Minimum 5 characters required"; submit enabled when `selectedType != null && body.length >= 5`. Native min is 5 end-to-end (`FeedbackDialog.kt:33-231`; old audit line 93).
- **KMP (current):** UI gate `body.length >= 5` (helper "Minimum 5 characters") BUT the underlying `SendComplaintUseCase` requires `>= 8`, so 5-7 char bodies pass the UI then fail server-side with an error snackbar (`SourcesScreen.kt:579-583`, `602`, `642-656`).
- **Priority:** P0
- **Acceptance criteria:** UI gate and use-case requirement agree. Pick one threshold (native = 5) and make the use case accept `>= 5`, OR raise the UI gate + helper text to 8. A body that passes the UI must always pass the use case. Add a test asserting the two thresholds match.
- **Notes:** Documented bug flagged for "Phase 10 reconciliation". Native parity = 5, so prefer lowering the use case to `>= 5`. Verify no other caller of `SendComplaintUseCase` depends on the 8-char floor.

### GAP-SRC-02 — Submit success/failure snackbars hardcoded English (bypass ar locale)
- **Screen/surface:** SourcesScreen — request-complaint snackbars
- **Type:** BEHAVIOR
- **OLD (target):** onSuccess snackbar = `R.string.request_submitted_successfully`; onError = `R.string.request_failed` with `R.string.retry` action — both localized (`RepoSettingsScreen.kt:178-209`; old audit line 92, 194).
- **KMP (current):** VM builds English literals: "Thanks! Your request was submitted." / "Failed to submit request: <cause>" — not `stringResource`, bypassing ar (`SourcesViewModel.kt:185`, `191`).
- **Priority:** P1
- **Acceptance criteria:** Both snackbar messages resolve from string resources (en + ar). Arabic strings present. Failure snackbar SHOULD also offer a "Retry" action label to match native (see GAP-SRC-03).
- **Notes:** Snackbar strings must be hoisted out of the VM into the composable layer (UP-3 playbook: VMs emit a key/effect, `:ui` resolves via `stringResource`). Reuse legacy keys `request_submitted_successfully` / `request_failed` / `retry`.

### GAP-SRC-03 — Failure snackbar missing the "Retry" action
- **Screen/surface:** SourcesScreen — request-complaint failure snackbar
- **Type:** MISSING_FEATURE
- **OLD (target):** onError snackbar uses `actionLabel = R.string.retry`, duration Long (`RepoSettingsScreen.kt:178-209`; old audit line 92, 109).
- **KMP (current):** Failure surfaces a plain message snackbar with no action label (`SourcesViewModel.kt:191`; `SourcesScreen.kt:267-273`).
- **Priority:** P2
- **Acceptance criteria:** On submit failure the snackbar shows a "Retry" action (Long duration) that re-attempts the submission with the preserved body text.
- **Notes:** KMP already keeps the dialog open + preserves typed text on failure, so a Retry action wiring back into `OnSubmitComplaint(body)` is low-friction. Pairs with GAP-SRC-02 localization.

### GAP-SRC-04 — AnimatedBackground + gradient overlay dropped on onboarding Sources
- **Screen/surface:** SourcesScreen — onboarding (Screen.Sources) entry decoration
- **Type:** MISSING_FEATURE
- **OLD (target):** Onboarding SourcesScreen stacks `AnimatedBackground()` + a vertical gradient overlay (`background@0.1f → @0.3f → solid`) behind translucent `ItemsGroup(surfaceContainerHigh@0.4f)` cards, with a centered "Select Your Manga Sources" headline (`SourcesScreen.kt:88-182`; old audit lines 116-125).
- **KMP (current):** No `AnimatedBackground`/gradient — cosmetic decoration intentionally omitted; flat `LazyColumn` (`SourcesScreen.kt:106-108`, `218-219`).
- **Priority:** P1
- **Acceptance criteria:** The onboarding Sources entry (only when `onboardingLanguageTag != null` / onboarding context) shows the animated background + gradient overlay + centered headline + translucent group cards, matching native. The non-onboarding entries (RepoSettings, standalone) need not.
- **Notes:** Decision was "intentionally dropped" in the rework — flag for product to confirm vs strict parity. `AnimatedBackground` may need a KMP port (it's an onboarding-welcome component). If product accepts the flat look, downgrade to accepted DEVIATION.

### GAP-SRC-05 — Centered "Select Your Manga Sources" onboarding headline missing
- **Screen/surface:** SourcesScreen — onboarding header
- **Type:** MISSING_FEATURE
- **OLD (target):** Centered `Text(R.string.select_your_manga_sources)` headlineMedium 24.sp color primary at top of onboarding Sources (`SourcesScreen.kt:112-118`; old audit line 120).
- **KMP (current):** Top bar shows generic `TopAppBar(title = "Sources")`; no centered headline title (`SourcesScreen.kt:285-297`).
- **Priority:** P2
- **Acceptance criteria:** Onboarding Sources entry shows the centered primary-colored "Select Your Manga Sources" headline (localized). Likely bundled with GAP-SRC-04.
- **Notes:** Native uses one title for onboarding ("Select Your Manga Sources") vs the settings title ("Sources Settings", see GAP-SRC-06). The rework's single parameterized screen must vary the title by entry.

### GAP-SRC-06 — Top bar title is "Sources" for all entries vs native "Sources Settings"
- **Screen/surface:** SourcesScreen — RepoSettings entry title
- **Type:** VISUAL
- **OLD (target):** RepoSettingsScreen top bar title = `R.string.title_sources_settings` "Sources Settings" (`RepoSettingsScreen.kt:70-84`; old audit line 70).
- **KMP (current):** `TopAppBar(title = "Sources")` for all three entries (`SourcesScreen.kt:285-297`).
- **Priority:** P2
- **Acceptance criteria:** The in-settings RepoSettings entry shows "Sources Settings" (localized `title_sources_settings`); onboarding shows the centered headline (GAP-SRC-05); standalone can stay "Sources". Title parameterized per entry.
- **Notes:** Single parameterized screen needs a title parameter keyed off the entry/onFinish/onboarding context.

### GAP-SRC-07 — Per-source rows show plain api label without colored source icon, "Enabled/Disabled" caption, or animated reveal
- **Screen/surface:** SourcesScreen — SourceRow (per-source toggle)
- **Type:** MISSING_FEATURE
- **OLD (target):** `RepoToggleItem` per source shows the source's own colored icon (`ImageVector.vectorResource(repo.ICON)`, tint Unspecified when checked), title 14.sp, desc = `R.string.enabled`/`R.string.disabled` ("Enabled"/"Disabled"), m2 `Checkbox`. The per-source list only appears inside `AnimatedVisibility(fadeIn+expandVertically / fadeOut+shrinkVertically)` when the language master is on (`RepoToggleItem.kt:24-73`; `LanguageToggleWithAnimation.kt:18-47`; old audit lines 81, 86, 107).
- **KMP (current):** `SourceRow` = plain `source.api` text label (bodyLarge) + Material3 `Switch`; NO source icon, NO Enabled/Disabled caption, NO animated expand/collapse — all source rows always render (`SourcesScreen.kt:544-568`; `324`).
- **Priority:** P1
- **Acceptance criteria:** Per-source rows display the source's colored icon, a localized "Enabled"/"Disabled" caption under the name, and the per-source list animates in/out (expand/collapse + fade) when the language master toggle flips. Source identity matches native (uses repo display name/icon, not raw api code).
- **Notes:** Source icons resolved via `RepoIconResolver` in KMP (per old audit line 187) — confirm it's wired. The animated reveal is a notable UX difference; native hides the per-source list until the language is enabled. KMP currently uses a `Switch` per source vs native `Checkbox` — see GAP-SRC-08. Showing `api` raw code vs a display name is itself a divergence — verify the label.

### GAP-SRC-08 — Per-source control is a Switch vs native Checkbox; language master is Switch in both
- **Screen/surface:** SourcesScreen — per-source control
- **Type:** VISUAL
- **OLD (target):** Per-source control is an m2 `Checkbox` (checked=primary, checkmark=onPrimary); the per-LANGUAGE master is a `Switch` (`RepoToggleItem.kt:24-73`; `SwitchItem.kt`; old audit lines 80-81).
- **KMP (current):** Both the per-language master AND each per-source control are Material3 `Switch`es (`SourcesScreen.kt:508`, `544-568`).
- **Priority:** P2
- **Acceptance criteria:** Per-source control renders as a Checkbox (matching native), language master stays a Switch. If the rework deliberately standardizes on switches, record as accepted DEVIATION with product sign-off.
- **Notes:** Reconcile m2→m3: native mixes m2 controls; the rework correctly moved to m3 (old audit line 182 says "Port should reconcile to m3"). So m3 `Checkbox` is the parity target, not m2.

### GAP-SRC-09 — Language section caption "x of N enabled" vs native has no count caption
- **Screen/surface:** SourcesScreen — LanguageHeader caption
- **Type:** KMP-EXTRA
- **OLD (target):** `LanguageToggle` shows language name + description only; no "x of N enabled" count caption (`LanguageToggle.kt:10-32`; old audit line 80, 85).
- **KMP (current):** `LanguageHeader` adds an "x of N enabled" caption next to the language name (`SourcesScreen.kt:500-542`).
- **Priority:** P3
- **Acceptance criteria:** Accepted improvement; keep unless strict parity required. Ensure the caption is localized (en + ar) and uses the `sources_enabled_count` resource. Record as KMP-EXTRA.
- **Notes:** Harmless additive UX. Only acts as a gap if the audit demands exact native row content. Verify localization of the count string.

### GAP-SRC-10 — Onboarding language names not localized via getLanguageName(); raw stripped label only
- **Screen/surface:** SourcesScreen — LanguageHeader label (onboarding)
- **Type:** BEHAVIOR
- **OLD (target):** Onboarding SourcesScreen displays `getLanguageName(language.removeAllParens().lowercase())` → localized display name (e.g. "English"); RepoSettings uses the raw stripped code (`SourcesScreen.kt:122-182`, `243-254`; old audit line 122, 136).
- **KMP (current):** `LanguageHeader` shows the language name with parens stripped; no `getLanguageName()` localization to a display name is cited (`SourcesScreen.kt:500-542`).
- **Priority:** P2
- **Acceptance criteria:** Onboarding entry resolves language codes to localized display names (e.g. "(EN)" → "English") via a `getLanguageName` equivalent; falls back to the raw code on lookup failure. Non-onboarding entries may keep the stripped code to match native.
- **Notes:** Confirm whether the KMP `LanguageHeader` already maps codes to display names. `Locale(code).getDisplayLanguage` is JVM/Android; needs a KMP-friendly mapping for common platform. Possibly partial-parity already.

### GAP-SRC-11 — RequestSourceRow trailing chevron / SettingsNavigationItem visual parity
- **Screen/surface:** SourcesScreen — RequestSourceRow + UpcomingLanguagesCard
- **Type:** VISUAL
- **OLD (target):** Request row = `SettingsNavigationItem` with leading `Icons.Outlined.AddCircleOutline`, trailing `KeyboardArrowRight` chevron, wrapped in an `ItemsGroup` (surfaceContainerHigh, RoundedCornerShape16, padded). Info card = `SettingsNavigationItem` with leading `Icons.Outlined.Info` (red), endIcon null, 3-line desc, also in an `ItemsGroup` (`RepoSettingsScreen.kt:121-146`; `SettingsNavigationItem.kt:30-89`; old audit lines 75-79).
- **KMP (current):** `RequestSourceRow` = plain clickable Row (title + subtitle), `UpcomingLanguagesCard` = plain Column; rows separated by `HorizontalDivider`; no leading AddCircle/Info icons, no trailing chevron, no rounded `ItemsGroup` card grouping cited (`SourcesScreen.kt:389-461`).
- **Priority:** P2
- **Acceptance criteria:** Request row shows a leading add-circle icon + trailing chevron; info card shows a red Info icon; both grouped in rounded surface cards matching native `ItemsGroup` styling (16.dp corner, surfaceContainerHigh). Divider-vs-card grouping reconciled to native's card look.
- **Notes:** Consider a shared `:ui` `ItemsGroup` / `SettingsNavigationItem` design-system component (the UP campaign produced reusable settings components). This is broad visual polish; bundle with the settings design-system pass.

---

## Cross-cluster / accepted-deviation notes

- **No "Run all / Clear all / Cancel all" on Downloads in EITHER app.** Native VM exposes `cancelDownloads()` (cancel-all) and `clearDownloads()` (clearFailedAndQueued) but neither is wired to any button (old audit line 170). KMP has no bulk affordance or intent either. **No gap** — both apps lack the affordance, so there is parity. Do NOT add a run-all/clear-all button for parity; it would be a KMP-EXTRA divergence FROM native. (Recorded explicitly because the task flagged it.)
- **No "source tabs" on this cluster in EITHER app.** Native source-tab UI (`SourcesTabs.kt`, active-repo selection) lives in Home/Search, not the Sources Settings cluster (old audit line 101). KMP likewise has no tabbed source browser here (kmp audit line 319-321). **No gap on this surface** — out of cluster; track under Home/Search.
- **"any-enabled" master-toggle semantics MATCH.** Native computes master-on as `repos.any { enabled }` (NOT `all`, despite a misleading comment), and a single enabled source flips the master ON (old audit line 192). KMP uses `checked = sources.any { it.isEnabled }`, flipping OFF bulk-disables the group (kmp audit lines 234-236). **No gap** — semantics align; do not "fix" to `all`.
- **Default Downloads tab = Completed (index 2) MATCHES.** Both native (`mutableStateOf(2)`) and KMP (`selectedTab` default 2) open on Completed (old audit line 19, 174; kmp audit line 106). **No gap.**
- **No confirmation dialogs on Downloads destructive actions (delete/cancel) in EITHER app.** Native acts on single tap; KMP acts on single tap (kmp audit lines 94, 390-393). **No gap** — parity. A confirm dialog would be a KMP-EXTRA.
- **m2/m3 reconciliation:** Native RepoSettings mixes m2 `Scaffold`/`Switch`/`Checkbox` with m3 content (old audit line 182, explicitly "Port should reconcile to m3"). The rework's all-m3 approach is the intended target, so m3 controls are parity — the only open item is Checkbox-vs-Switch for per-source rows (GAP-SRC-08).
- **RepoSettings hardcoded "Finish" string (native bug):** Native RepoSettings uses a literal `"Finish"` while onboarding Sources uses `R.string.finish` (old audit line 180). KMP uses one `FinishButton` with a single label path — confirm it's `stringResource(finish)` (localized) in both onboarding entries; if so this is a fix-forward, not a gap.
- **CbzConversionDialog:** Native's CBZ conversion dialog (`CbzConversionDialog.kt`) and its (dead, commented-out) `DownloadSettingsSection.kt` host live in the Settings cluster, not Downloads (old audit lines 151-164). Not covered by the KMP Downloads/Sources audit — track CBZ conversion under the Settings cluster gap file, not here.
