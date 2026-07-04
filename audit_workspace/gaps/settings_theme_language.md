## Settings, Theme & Language — gaps
Total: 31 gaps (8 P0, 11 P1, 8 P2, 4 P3) across the Settings hub, Theme picker, Language picker, shared dialogs, and the design-token/root-theme system.

---

## Settings hub

### GAP-SET-01 — Settings is NOT a bottom-bar tab in KMP
- **Screen/surface:** SettingsScreen
- **Type:** BEHAVIOR
- **OLD (target):** `Screen.Setting` is a bottom-bar tab — `SideEffect { onBottomBarVisibleChange(true) }` keeps the bottom nav visible (old `NavGraphV2.kt:514-516`; audit old:10-11).
- **KMP (current):** `Screen.SettingsRework` hides the bottom bar — `SideEffect { onBottomBarVisibleChange(false) }` (kmp `App.kt:875`; audit kmp:22). Top bar adds a (legacy-absent) title bar.
- **Priority:** P1
- **Acceptance criteria:** Opening Settings keeps the bottom navigation bar visible; Settings behaves as a primary tab, not a pushed full-screen detail.
- **Notes:** Tied to GAP-SET-02 (reachability). Decide whether the rework Settings is a tab or a pushed screen; native is a tab. If it stays a top-level tab the TopAppBar title may be acceptable, but bottom-bar visibility must match.

### GAP-SET-02 — Settings hub not wired to any user-facing entry
- **Screen/surface:** SettingsScreen
- **Type:** MISSING_FEATURE
- **OLD (target):** Reachable as a bottom-nav tab (old:10-11).
- **KMP (current):** `App.kt` comment says "not surfaced in any user-facing entry yet … reachable via a future developer trigger"; no drawer/menu/bottom-nav wiring of `Screen.SettingsRework` found (audit kmp:24-26, 430-433).
- **Priority:** P0
- **Acceptance criteria:** Settings is reachable from a real user entry point (bottom nav tab per native) without code changes / dev triggers.
- **Notes:** Gates the entire cluster's reachability — Theme/Language/Statistics/About/Downloads/Complaint all hang off the Settings hub nav rows.

### GAP-SET-03 — Header launcher icon image missing from Settings
- **Screen/surface:** SettingsScreen body (top of list)
- **Type:** MISSING_FEATURE
- **OLD (target):** First list item is `Image(ic_launcher_foreground)`, `size(250.dp).padding(vertical=24.dp)`, contentDescription "Header Icon" (old `SettingsScreen.kt:113-121`; audit old:13).
- **KMP (current):** No header image — list starts directly with the General `SectionCard` (audit kmp:32, 78). Not present anywhere in `SettingsList`.
- **Priority:** P1
- **Acceptance criteria:** Settings list renders the 250.dp app launcher foreground image at top with 24.dp vertical padding before the first section.
- **Notes:** Need `ic_launcher_foreground` (or equivalent) as a composeResource drawable. Visual-identity item.

### GAP-SET-04 — Section grouping & order diverge from native (5→7 sections, regroup)
- **Screen/surface:** SettingsScreen sections
- **Type:** VISUAL
- **OLD (target):** 4 groups: General (downloaded-only, incognito, follow-system, dark, pure-black, [testing]) → Download/CBZ → Navigation (feedback/complaint, reading-mode, statistics, language, downloads) → Other (clear-cache, request-feature, about, help) (old:14-17, 42-63).
- **KMP (current):** 7 sections: General (downloaded-only, incognito, [testing]) → Theme (follow-system, dark, pure-black) → Downloads/CBZ → Reading → Navigation → Storage → Feedback (audit kmp:78-113). Theme toggles split into their own section; reading-mode/clear-cache/feedback moved into dedicated sections.
- **Priority:** P2
- **Acceptance criteria:** Section grouping and row order match native: theme toggles live in General; reading-mode/statistics/language/downloads/feedback live where native places them; clear-cache + request-feature + about + help share the "Other" group.
- **Notes:** Partly a deliberate rework restructure. Confirm with design whether the 7-section split is intended; if native parity is mandatory, collapse to the 4 native groups. Pairs with GAP-SET-05/06/07 (rows native has but KMP lacks, and vice-versa).

### GAP-SET-05 — Group card container color: surfaceVariant vs surfaceContainerHigh
- **Screen/surface:** SettingsScreen `SectionCard`
- **Type:** VISUAL
- **OLD (target):** `ItemsGroup` card uses `surfaceContainerHigh`, `RoundedCornerShape(16.dp)`, inner rows separated by `Divider(background.copy(alpha=0.8f))` (old `ItemsGroup.kt:16-29`; audit old:18-20).
- **KMP (current):** `SectionCard` uses `surfaceVariant` container, `RoundedCornerShape(12.dp)`, plain `HorizontalDivider` (audit kmp:33-34, 39, 487-490).
- **Priority:** P2
- **Acceptance criteria:** Group cards use `surfaceContainerHigh`, 16.dp corners, and near-invisible `background.copy(alpha=0.8f)` dividers to match native.
- **Notes:** Three byte-level divergences (container token, corner radius 12 vs 16, divider color). Centralize in a shared `:ui` SectionCard.

### GAP-SET-06 — Native nav rows missing in KMP: "About" + "Help" sit in different group; KMP adds "What's new"/"Feedback Manager" labels
- **Screen/surface:** SettingsScreen Navigation/Other rows
- **Type:** DEVIATION(rework)
- **OLD (target):** Nav group = Feedbacks&complaints, Default reading mode, Statistics, App language, Downloads (old:53-58). Other group = Clear cache, Request feature/bug, About, Help (inert) (old:59-63). No "What's new" row.
- **KMP (current):** Navigation section = Theme, Statistics, Language, About, Feedback Manager (COMPLAINT), **What's new (WHATSNEW)**, Downloads, Help — iterating `SettingsDestination.entries` (audit kmp:99-108). Adds a **Theme** nav row and a **What's new** row not present natively; "Feedbacks & complaints" relabeled "Feedback Manager".
- **Priority:** P1
- **Acceptance criteria:** Navigation rows match native set + labels: "Feedbacks & complaints" (not "Feedback Manager"), "Default reading mode", "Statistics", "App language", "Downloads". Decide fate of extra "Theme" and "What's new" nav rows (KMP-EXTRA — see GAP-SET-22/23).
- **Notes:** "Default reading mode" is a nav-style row opening a dialog in native; KMP made it its own "Reading" section row — reconcile with GAP-SET-04.

### GAP-SET-07 — Native "Auto-convert on Download" toggle vs native CBZ inventory
- **Screen/surface:** SettingsScreen Downloads/CBZ section
- **Type:** KMP-EXTRA
- **OLD (target):** CBZ group has only "Use Yami compressor" toggle + "Compress existing downloads" button (visible when useCbz) (old:50-52).
- **KMP (current):** Adds an **"Auto-convert on Download"** toggle (`AUTO_CONVERT_TO_CBZ`), visible when `useCbzFormat`, between the use-CBZ toggle and the compress-existing action (audit kmp:93-96).
- **Priority:** P2
- **Acceptance criteria:** Confirm whether auto-convert-on-download is an intended new feature. If yes, keep + localize; if parity-only, remove.
- **Notes:** KMP-EXTRA — likely intentional. Flag for product decision; not in native.

### GAP-SET-08 — Cache subtitle string differs ("Used: <size>" vs "Cached: <size>")
- **Screen/surface:** SettingsScreen Clear-cache row
- **Type:** VISUAL
- **OLD (target):** Subtitle = `R.string.cache_used` ("Used: ") + size; transient "Calculating…" (`R.string.calculating`) on init/after clear (old:21, 39, 60).
- **KMP (current):** Subtitle = `Res.string.cached_size` "Cached: %1$s" (audit kmp:110, 402).
- **Priority:** P3
- **Acceptance criteria:** Cache row subtitle prefix matches native ("Used: ") and shows a "Calculating…" transient state while size is being (re)computed off the IO dispatcher.
- **Notes:** Verify KMP shows the calculating placeholder before first size resolves (native does via `R.string.calculating`).

### GAP-SET-09 — Switch row title/description typography & alpha mismatch
- **Screen/surface:** SettingsScreen `ToggleRow` / `NavRow`
- **Type:** VISUAL
- **OLD (target):** Switch rows: 14.sp title + 12.sp description at alpha 0.5; nav rows: 14.sp title + auto-sized 12→6.sp description at alpha 0.8, with `AutoSubtitleText` shrink (old:20, 70-71, 237).
- **KMP (current):** Row label `bodyLarge` (16.sp Bold per token), description `bodySmall` on `onSurfaceVariant`; no auto-shrinking subtitle, no explicit alpha (audit kmp:37-39, 520-531).
- **Priority:** P2
- **Acceptance criteria:** Row titles render at 14.sp (titleMedium), descriptions at 12.sp with native alpha; nav-row descriptions auto-shrink (12→6.sp) for long copy.
- **Notes:** `bodyLarge` is Bold 16.sp in both themes (token), so KMP titles are visibly larger/bolder than native's 14.sp. Port `AutoSubtitleText` (TextAutoSize.StepBased) into `:ui` design system.

### GAP-SET-10 — Adult/M-confirmation dialog chain absent (native present-but-likely-dead)
- **Screen/surface:** SettingsScreen dialogs
- **Type:** MISSING_FEATURE
- **OLD (target):** `dialogState` chain AdultWarning→MStep1→MStep2→None with `AdultConfirmationDialog` + `MConfirmationDialog(imgs)` (old `SettingsScreen.kt:367-406`; audit old:30) — flagged likely dead/unreachable.
- **KMP (current):** Not present (audit kmp has no adult/M dialog).
- **Priority:** P3
- **Acceptance criteria:** Confirm the native Adult/M chain is truly dead (no trigger). If dead, no action; if reachable elsewhere, port it.
- **Notes:** Native audit marks this INFERRED dead. Likely no port needed — verify there's no external trigger before closing.

---

## FeedbackDialog (shared — Settings)

### GAP-SET-11 — Feedback dialog 5-char UI gate vs 8-char use-case requirement
- **Screen/surface:** Settings FeedbackDialog
- **Type:** BEHAVIOR
- **OLD (target):** UI gate body ≥5 chars; helper "minimum 5 characters required"; max 500 (old:33, 163, 169). Native backend accepted ≥5 (no documented mismatch).
- **KMP (current):** UI gate `body.length >= 5` but `SubmitFeedbackUseCase` requires ≥8 → 5–7 char bodies pass UI then fail server-side with an error snackbar. Documented KNOWN MISMATCH (audit kmp:61-64, 422).
- **Priority:** P0
- **Acceptance criteria:** A body that passes the UI gate always submits successfully; UI threshold == use-case threshold; helper text states the real minimum. No silent server-side rejection for accepted input.
- **Notes:** Align both to a single constant. Note the Language request dialog already uses 8 (GAP-LANG-04) — decide canonical minimum (likely 8) and make the UI helper say "minimum 8 characters".

### GAP-SET-12 — Feedback dialog missing subtitle + social-media section
- **Screen/surface:** Settings FeedbackDialog
- **Type:** MISSING_FEATURE
- **OLD (target):** Title block has subtitle `R.string.we_d_love_to_hear_from_you`; body ends with divider + `R.string.connect_with_us_in_social_media` + prompt-response copy + `SocialMediaRow()` (old:161, 164).
- **KMP (current):** Title + category + body field only; no subtitle line, no social-media section/row (audit kmp:130-133, 149).
- **Priority:** P1
- **Acceptance criteria:** Feedback dialog shows the "We'd love to hear from you" subtitle under the title and the social-media section (divider + copy + `SocialMediaRow`) below the body field, matching native.
- **Notes:** Requires porting `SocialMediaRow` into `:ui`. Both Settings + Language dialogs in native carry the social section; reuse.

### GAP-SET-13 — Feedback dialog field-label & success/error snackbar strings differ
- **Screen/surface:** Settings FeedbackDialog
- **Type:** VISUAL
- **OLD (target):** Field label `R.string.your_feedback`; success snackbar `R.string.request_submitted_successfully`; error snackbar `R.string.request_failed` + actionLabel `R.string.retry` (Long duration) (old:28, 163).
- **KMP (current):** Success "Thanks! Your feedback was submitted."; failure "Failed to submit feedback: <cause>" — hardcoded literals, no Retry action, Short duration default (audit kmp:140-141, 56, 414-416).
- **Priority:** P2
- **Acceptance criteria:** Snackbars use the native localized strings; error snackbar offers a "Retry" action and uses Long duration; messages are `stringResource`, not literals.
- **Notes:** Part of the broader localization gap (GAP-SET-21). Add the Retry actionLabel + Long duration to match native.

---

## ReadingModeDialog (shared — Settings)

### GAP-SET-14 — ReadingModeDialog interaction model: Apply/Revert vs single-tap-commit
- **Screen/surface:** Settings ReadingModeDialog
- **Type:** BEHAVIOR
- **OLD (target):** `ReadingModeChips` with local `selected`; footer `OutlinedButton(but_revert)` (reset+dismiss) + `Button(but_apply)` with Check icon (commit selected) — explicit Apply/Revert (old:178, 181, 185).
- **KMP (current):** 6 `RadioButton` rows; tapping a row both persists and closes immediately (no Apply/Revert); only a Cancel button (audit kmp:160-168, 175).
- **Priority:** P1
- **Acceptance criteria:** Reading-mode dialog matches native: chips (not radio rows), a Revert button and an Apply button (with check icon) that commits the staged selection; tapping a mode only stages, does not commit.
- **Notes:** Behavioral divergence in commit semantics. Native uses chip UI; KMP uses radio list — reconcile both interaction and component style.

### GAP-SET-15 — ReadingModeDialog surface/elevation/corner differ
- **Screen/surface:** Settings ReadingModeDialog
- **Type:** VISUAL
- **OLD (target):** `Surface(tonalElevation=8.dp, RoundedCornerShape(16.dp), color=surfaceContainerHigh)`; Apply button inverted (`onBackground` container / `background` content) (old:178-179).
- **KMP (current):** `AlertDialog` `RoundedCornerShape(20.dp)`, `surface` container, `tonalElevation 3.dp` (audit kmp:164).
- **Priority:** P2
- **Acceptance criteria:** Dialog uses 16.dp corners, `surfaceContainerHigh`, 8.dp tonal elevation; Apply button uses the inverted color scheme per native.
- **Notes:** Coupled to GAP-SET-14 (Apply button exists only if Apply/Revert restored).

---

## CbzConversionDialog (Settings)

### GAP-SET-16 — CBZ conversion progress dialog detail parity
- **Screen/surface:** Settings CbzConversionDialog
- **Type:** MISSING_FEATURE
- **OLD (target):** Dedicated `CbzConversionDialog` with 3 states — Error (Error icon, message, Close), Success (CheckCircle/Warning if stopped, Done), Converting (Warning icon, `LinearProgressIndicator(progress)`, completed/remaining counts, current manga title + chapter number, spinner, Stop button); dismiss disabled while converting (old:190-201).
- **KMP (current):** No standalone conversion dialog documented; "compress existing" surfaces only as an inline button spinner + "Converting..." label on the row (audit kmp:46, 95-96, 574-585). No progress %, counts, current-item, or Stop affordance.
- **Priority:** P1
- **Acceptance criteria:** A CBZ conversion dialog shows progress bar, converted/remaining counts, current manga title + chapter, a Stop button, and Error/Success terminal states; dismissal blocked while converting.
- **Notes:** Verify whether KMP `CompressExistingDownloadsUseCase` exposes progress (counts/current item). If only inline button exists, this is a real feature gap, not just visual.

---

## ThemeScreen (Theme picker)

### GAP-THM-01 — Theme picker onboarding affordances are dead code on the live route
- **Screen/surface:** ThemeScreen (onboarding)
- **Type:** BEHAVIOR
- **OLD (target):** Onboarding Theme screen wires Continue (→ navigate to Sources) and notification permission; route adapter passes all params and runs `NotificationPermissionRequester` (old:83, 91, 98).
- **KMP (current):** `ThemeReworkScreenRoute` passes NONE of the 3 onboarding params (`ThemeScreen(viewModel)` only) → Continue button + notification grant row are dead code; `AnimatedBackground` + auto-permission lifecycle deferred (audit kmp:190-194, 424-427).
- **Priority:** P0
- **Acceptance criteria:** The onboarding Theme screen wires `onContinue` (navigates to Sources), `hasNotificationPermission`, and `onRequestNotificationPermission`; Continue is gated on permission; affordances are live, not dead code.
- **Notes:** Native uses this as a hard onboarding gate. Deferred to "Phase 7.x.theme.swap" per KDoc. Pairs with GAP-THM-02/03/04.

### GAP-THM-02 — Notification permission flow missing (auto-request, Grant, toast, app-settings redirect, Continue gate)
- **Screen/surface:** ThemeScreen / ThemeSelector
- **Type:** MISSING_FEATURE
- **OLD (target):** Auto-request POST_NOTIFICATIONS once on first composition (Tiramisu+); manual Grant button; Toast `you_need_to_enable_notifications` on denial; `openAppSettings()` redirect on permanent denial; **Continue enabled only when permission granted** (old:83, 93, 96-99, 104, 246).
- **KMP (current):** Notification grant row exists but is unwired (no param passed); no auto-request, no toast, no app-settings redirect; Continue not gated on this route (audit kmp:194, 228-229, 209).
- **Priority:** P0
- **Acceptance criteria:** On onboarding theme screen: auto-request once on Android Tiramisu+, manual Grant button, toast + app-settings redirect on denial, Continue disabled until granted. iOS/Desktop → DEVIATION(platform), no-op gate.
- **Notes:** DEVIATION(platform) for the request mechanics (Android-only POST_NOTIFICATIONS); needs a `NotificationPermission` SPI in `:platform`. Continue gating logic must hold cross-platform.

### GAP-THM-03 — AnimatedBackground + gradient overlay missing on Theme picker
- **Screen/surface:** ThemeScreen (onboarding)
- **Type:** VISUAL
- **OLD (target):** `Surface` holds `AnimatedBackground(fillMaxSize)` (onboarding asset `onboarding/welcome`) + a vertical gradient overlay `Brush.verticalGradient([bg.copy(0.1f), bg.copy(0.3f), bg])` behind the content (old:84-86).
- **KMP (current):** Plain `Scaffold` + `TopAppBar` ("Theme"); no animated background, no gradient overlay (audit kmp:195, 194).
- **Priority:** P1
- **Acceptance criteria:** Onboarding Theme screen renders the animated background and gradient overlay matching native; full-screen (no top app bar in onboarding context).
- **Notes:** Needs the `AnimatedBackground` composable + onboarding asset ported into `:ui`. Native onboarding has NO TopAppBar (full-bleed); KMP adds one.

### GAP-THM-04 — Theme tabs missing icons + title styling differs
- **Screen/surface:** ThemeScreen `ThemePickerColumn` / TabRow
- **Type:** VISUAL
- **OLD (target):** 3 Tabs with icons `LightMode`/`DarkMode`/`SettingsBrightness`; label `AutoSubtitleText` bodyMedium 14.sp primary; indicator color primary, transparent container; screen title `choose_your_theme` styled headlineMedium 24.sp primary (old:88, 95, 115).
- **KMP (current):** Text-only `Tab`s (no icons); title "Choose Your Theme" styled `titleMedium` (14.sp), not 24.sp primary headline (audit kmp:198, 200-201).
- **Priority:** P1
- **Acceptance criteria:** Theme tabs show Light/Dark/System icons; tab labels in primary 14.sp; selection indicator primary on transparent container; screen title at 24.sp primary.
- **Notes:** Title token mismatch is the bigger visual break (14.sp vs 24.sp). Add tab icons.

### GAP-THM-05 — KMP adds Pure-black toggle to Theme picker (native has none here)
- **Screen/surface:** ThemeScreen `PureBlackRow`
- **Type:** KMP-EXTRA
- **OLD (target):** Onboarding theme picker exposes ONLY Light/Dark/System tabs; **pure-black/OLED is NOT on this screen** — it lives only on the Settings hub (old:106, 248 flag).
- **KMP (current):** `PureBlackRow` Switch always interactive on the Theme picker (audit kmp:199, 225-226).
- **Priority:** P2
- **Acceptance criteria:** Decide whether pure-black belongs on the Theme picker. For strict native parity, remove it from the picker (keep on Settings hub). If kept (per task #243), document as an intentional rework addition.
- **Notes:** Native audit explicitly flags this as a KMP addition (#243). Product decision; not a defect, but a parity divergence.

### GAP-THM-06 — Notification-section copy differs
- **Screen/surface:** ThemeScreen NotificationPermissionRow
- **Type:** VISUAL
- **OLD (target):** Headline `enable_notifications` + body `notification_permission` (old strings) + Grant button copy `grant_permission`; styled onBackground (old:117).
- **KMP (current):** `notification_permission` = "We need this permission to send you the latest chapters…" + "Grant Permission" button (audit kmp:227-228, 404-405).
- **Priority:** P3
- **Acceptance criteria:** Notification section headline/body/button strings match native verbatim (and Arabic where native has it).
- **Notes:** Verify the en/ar copy matches the native `enable_notifications`/`notification_permission` strings byte-for-byte. Coupled to GAP-THM-02 wiring.

---

## LanguageScreen (Language picker)

### GAP-LANG-01 — Language screen has no back IconButton
- **Screen/surface:** LanguageScreen TopAppBar
- **Type:** MISSING_FEATURE
- **OLD (target):** `TopAppBarCom(title=select_language, navigationIcon = back IconButton(ArrowBack))` → `onBack` pop (old:131, 134, 139).
- **KMP (current):** TopAppBar title only — "no actions, no back IconButton — system back only" (audit kmp:249, 277).
- **Priority:** P1
- **Acceptance criteria:** Language screen top bar shows a back arrow IconButton that pops the back stack, matching native.
- **Notes:** Terminal screen with `@Suppress("UNUSED_PARAMETER")` navController — needs the navController wired to provide back. Low effort.

### GAP-LANG-02 — Language rows: native uses StatsItem (trailing count "0"); KMP omits it
- **Screen/surface:** LanguageScreen `LanguageRow`
- **Type:** VISUAL
- **OLD (target):** Each row is `StatsItem(title=displayName, description=code, icon=Done if selected, trailing bold count "0")`; "Request language" row uses `StatsItem(icon=Add)`; rows separated by `Divider(vertical=12.dp)` (old:132-134, 148).
- **KMP (current):** `LanguageRow` is a custom `Row` (displayName bodyLarge + code bodySmall, right 24.dp check slot); `RequestLanguageRow` is a single primary `bodyLarge` text row (no Add icon); `HorizontalDivider` (audit kmp:253-257, 332-350).
- **Priority:** P2
- **Acceptance criteria:** Decide canonical row style. Native's trailing "0" count is a cosmetic artifact of reusing StatsItem — KMP's cleaner row is arguably better. If strict parity required, match StatsItem layout; otherwise document KMP row as an intentional cleanup and add the Add icon to the Request row.
- **Notes:** Native audit itself flags the "0" as an artifact (old:252). Recommend keeping KMP's cleaner row but adding the leading Add icon to the Request-language row for affordance parity. The KMP fixed 24.dp check slot (uniform row height) is an improvement.

### GAP-LANG-03 — Request-language dialog: no category dropdown + missing social/subtitle (vs Settings feedback dialog)
- **Screen/surface:** LanguageRequestDialog
- **Type:** BEHAVIOR
- **OLD (target):** Language "Request language" reuses the **full FeedbackDialog** with pre-selected `ComplaintType.LANGUAGES`, header `request_add_language`, field `enter_your_language`, plus subtitle + social-media section; submit → complaint pipeline; success/error snackbars with Retry (old:133, 138, 141, 148).
- **KMP (current):** A separate, simpler `LanguageRequestDialog` — prompt text + body field only, NO category dropdown (data layer hardcodes `subject="Languages"`), no subtitle, no social section, default AlertDialog shape/colors (audit kmp:292-297, 298-299).
- **Priority:** P2
- **Acceptance criteria:** Confirm intended UX. The hardcoded `subject="Languages"` is functionally equivalent to native's pre-selected category, so the dropdown omission is acceptable; but custom corner/elevation styling (20.dp/3.dp) and the social/subtitle section should match the Settings dialog for visual consistency.
- **Notes:** The simpler dialog is a reasonable rework. Main visible divergence: default AlertDialog shape vs native's styled dialog, plus missing social row (see GAP-SET-12).

### GAP-LANG-04 — Language request min-length helper differs (8 chars, native dialog says 5)
- **Screen/surface:** LanguageRequestDialog
- **Type:** BEHAVIOR
- **OLD (target):** Request dialog (shared FeedbackDialog) validates body ≥5 chars, helper "minimum 5 characters required" (old:143, 169).
- **KMP (current):** `submitEnabled = text.length >= 8`; helper `at_least_n_characters` = "At least 8 characters" (audit kmp:308-310, 408).
- **Priority:** P2
- **Acceptance criteria:** Min-length is consistent across feedback + language-request dialogs and matches the actual backend gate (likely 8). UI helper states the true minimum.
- **Notes:** KMP language dialog already uses 8 (matching the real use-case), which is MORE correct than the native 5-char UI. Resolve jointly with GAP-SET-11 — settle on one constant app-wide.

### GAP-LANG-05 — Verify full supported-language list parity (11 tags)
- **Screen/surface:** LanguageScreen list
- **Type:** BEHAVIOR
- **OLD (target):** `R.array.supported_languages` = en/ar/de/es/fr/in/it/ja/pt/ru/tr (11; array may continue past `arrays.xml:17` — native audit flags to verify, old:147, 258).
- **KMP (current):** 11 langs en/ar/de/es/fr/in/it/ja/pt/ru/tr in `:data LanguageRepositoryImpl` with native endonyms (audit kmp:273).
- **Priority:** P3
- **Acceptance criteria:** KMP supported-language set exactly equals the full native `supported_languages` array (confirm native array doesn't extend past line 17).
- **Notes:** Both list 11 identical tags; only open item is confirming the native array isn't truncated in the audit. Likely no-op.

---

## Root theme & design tokens

### GAP-THM-07 — App-root applies legacy YamiMangaTheme, not rework YamiTheme
- **Screen/surface:** App root / whole-app theming
- **Type:** REFACTOR
- **OLD (target):** App applies `YamiMangaTheme(darkTheme, dynamicColor=false, pureBlack)` at root (old:209).
- **KMP (current):** `App.kt` still applies LEGACY `YamiMangaTheme`; rework `YamiTheme` is used only at leaf-screen scope; app-root provider migration unfulfilled; `dynamicColor` is a no-op stub awaiting `DynamicColorProvider` SPI (audit kmp:325, 328-332, 393-395).
- **Priority:** P1
- **Acceptance criteria:** App root applies `YamiTheme` (rework). Runtime `ColorScheme`/`Typography`/`Shapes`/`LocalSpacing` all originate from `YamiTheme`; legacy `YamiMangaTheme` no longer the root provider. No visual change (tokens are byte-identical).
- **Notes:** Tokens already verified byte-identical (GAP-THM-08), so the swap should be visually inert. Required to retire the legacy theme and to wire `dynamicColor`. Verify `yamiTypography()` (Bold bodyLarge etc.) actually applies after swap.

### GAP-THM-08 — Theme tokens: byte-level color/typography/shape parity check (PASS, with notes)
- **Screen/surface:** Design tokens (YamiColors/YamiTypography/YamiShapes vs legacy Theme.kt)
- **Type:** VISUAL
- **OLD (target):** Dark/Light schemes, Shapes (xs4/s8/m12/l16/xl0), Typography (Gellix; only bodyLarge Bold 16, titleMedium Medium 14, titleSmall Normal 12 overridden); pure-black copies `background=Black, surfaceContainer=Black` when dark&&pureBlack (old:209-232).
- **KMP (current):** `YamiColors` dark/light hex values match native verbatim; `YamiShapes` 4/8/12/16/0 match; `yamiTypography()` overrides the same 3 slots with Gellix; pure-black `copy(background=Black, surfaceContainer=Black)` matches (audit kmp:336-352, 354-356, 361-378).
- **Priority:** P3
- **Acceptance criteria:** All color hex, shape dp, typography slots, and the pure-black override are byte-identical to native (no divergence). Confirmed PASS in audit.
- **Notes:** No divergence found — all tokens match. One nuance: native pure-black overrides `background` + `surfaceContainer`; KMP matches. Native also carries dead `Color.kt` Material-purple tokens (unused) — KMP correctly omits them. Keep this entry as a verification record; closeable once root swap (GAP-THM-07) confirms runtime application.

### GAP-THM-09 — Pure-black override target verification (background + surfaceContainer only)
- **Screen/surface:** Pure-black / OLED behavior
- **Type:** BEHAVIOR
- **OLD (target):** When `darkTheme && pureBlack`: copy base scheme with ONLY `background=Color.Black` and `surfaceContainer=Color.Black` (old:209, 244 default pureBlack=true).
- **KMP (current):** `YamiTheme` does `baseScheme.copy(background=Color.Black, surfaceContainer=Color.Black)` — matches targets; no per-screen OLED branch (audit kmp:354-356).
- **Priority:** P3
- **Acceptance criteria:** Pure-black flips exactly `background` + `surfaceContainer` to black (nothing else); default pure-black value matches native default (true).
- **Notes:** Logic matches. **Open question:** native default `pureBlack=true` and `followSystem=true`; confirm KMP `SettingsRepository` defaults match (true/true) — audit doesn't state KMP defaults. If KMP defaults to false, first-launch appearance diverges. Flag to verify.

### GAP-THM-10 — Section dividers near-invisible (background alpha 0.8) vs default divider
- **Screen/surface:** Settings group cards (shared idiom)
- **Type:** VISUAL
- **OLD (target):** Inner row dividers = `colorScheme.background.copy(alpha=0.8f)` (near-invisible), reused across the cluster (old:19-20, 236).
- **KMP (current):** Plain `HorizontalDivider` (default outline-variant color) (audit kmp:34).
- **Priority:** P2
- **Acceptance criteria:** Intra-card dividers use `background.copy(alpha=0.8f)` so they read as near-invisible like native, not the default visible divider.
- **Notes:** Subset of GAP-SET-05; tracked separately as the divider idiom is reused beyond Settings. Centralize in shared SectionCard.

---

## Localization

### GAP-SET-21 — Hardcoded English literals across Settings/Theme (pending trusted Arabic)
- **Screen/surface:** SettingsScreen, ThemeScreen, VM snackbars
- **Type:** REFACTOR
- **OLD (target):** All strings via `R.string.*` with `values-ar` Arabic (e.g. CBZ labels, toggle descriptions, theme descriptions, cache strings) (old:259 string inventory).
- **KMP (current):** CBZ section labels, General/theme toggle descriptions, "Testing Mode", and all VM snackbar strings ("Cache cleared", "Conversion complete", "Thanks! Your feedback was submitted", etc.) are hardcoded literals, not `stringResource` (audit kmp:411-416).
- **Priority:** P1
- **Acceptance criteria:** Every user-visible Settings/Theme string is a `stringResource` with en + trusted-Arabic entries; no inline literals.
- **Notes:** Reuse legacy keys + verbatim Arabic per the UP-3 localization playbook (en-only for genuinely new strings). Hoist VM snackbar strings to resources resolved before the collector (Language screen already does this).

---

## KMP-only extras (rework additions)

### GAP-SET-22 — "What's new" nav row (KMP-only)
- **Screen/surface:** SettingsScreen Navigation
- **Type:** KMP-EXTRA
- **OLD (target):** No "What's new" entry in Settings (old:53-63).
- **KMP (current):** `WHATSNEW` → `Screen.WhatsNewRework` nav row (audit kmp:106).
- **Priority:** P3
- **Acceptance criteria:** Confirm "What's new" is an intended new feature; if yes keep + localize, else remove for parity.
- **Notes:** Intentional rework addition (separate WhatsNew screen exists). Product decision.

### GAP-THM-11 — "Theme" nav row inside Settings (KMP-only)
- **Screen/surface:** SettingsScreen Navigation
- **Type:** KMP-EXTRA
- **OLD (target):** Native exposes theme only as inline General toggles; no nav row to a separate theme screen (the Theme screen is onboarding-only) (old:46-48, 82).
- **KMP (current):** Navigation section adds a **Theme** row → `Screen.ThemeRework`, surfacing the (onboarding) theme picker from Settings (audit kmp:101, 363-385 theme section also exists).
- **Priority:** P2
- **Acceptance criteria:** Decide whether Settings should deep-link to the Theme picker. Native keeps theme controls inline only; if parity required, remove the nav row (keep inline toggles). If kept, ensure it doesn't duplicate the inline Theme section toggles confusingly.
- **Notes:** KMP currently has BOTH an inline Theme toggle section AND a Theme nav row — redundant vs native's single inline location. Reconcile with GAP-SET-04/06.
