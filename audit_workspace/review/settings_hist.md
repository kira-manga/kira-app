# Phase-3 REVIEW — Settings/Theme/Language + History/Updates/Statistics

Read-only adversarial verification against LIVE committed code (not the stale audit_workspace/kmp/* snapshots, which predate the NP Phase-2 gap-closure wave, tasks #758-#779). All evidence is `file:line` in the live tree.

**Headline:** the two stale gap files describe a much earlier KMP state. The biggest premises — "Settings unreachable" (GAP-SET-02), "Theme onboarding dead code" (GAP-THM-01/02), "app-root still legacy theme" (GAP-THM-07) — are all FALSE POSITIVES now: each is genuinely closed and wired in live code (verified, not taken on KDoc faith).

---

## Settings / Theme / Language

| GAP-ID | Status | Evidence (file:line) | Note |
|---|---|---|---|
| GAP-SET-01 | CLOSED | App.kt:519-520 (`Screen.Setting` → `onBottomBarVisibleChange(true)`); BottomNavigationBar.kt:118 | Settings IS the bottom-nav tab; bottom bar stays visible. Stale audit only looked at `Screen.SettingsRework`. |
| GAP-SET-02 | CLOSED (false-positive) | BottomNavigationBar.kt:118 `Triple(Screen.Setting, …)`; SettingsRoute.kt:143-158 renders rework `SettingsScreen` (§301 swap) | Reachable from the real bottom-nav tab with no dev trigger. The "not surfaced" App.kt comments are stale prose on the parallel `SettingsRework` debug key. |
| GAP-SET-03 | CLOSED | SettingsScreen.kt:406-418 (`ic_launcher_foreground`, size 250.dp, vertical 24.dp) | Header icon item present at top of list. |
| GAP-SET-04 | CLOSED | SettingsScreen.kt:430-595 — 4 native groups: General / CBZ / Navigation / Other | Theme toggles back inline in General; reading-mode is a Navigation row; clear-cache/feedback/about/help in Other. |
| GAP-SET-05 | CLOSED | SettingsScreen.kt:618-623 (`surfaceContainerHigh`, 16.dp) + 638-641 (`background.copy(alpha=0.8f)` divider) | Container token, corner radius, divider color all match native. |
| GAP-SET-06 | CLOSED | SettingsScreen.kt:881 `feedbacks_and_complaints` label; nav rows reordered | "Feedback Manager" → "Feedbacks & complaints". Theme/What's-new rows retained as KMP-EXTRA (documented :553-565). |
| GAP-SET-07 | DEFERRED(KMP-EXTRA) | SettingsScreen.kt:508-514 auto-convert toggle | Intentional rework addition; product decision, not a defect. |
| GAP-SET-08 | STILL-OPEN(minor) | SettingsScreen.kt:827 `cached_size` "Cached: %1$s"; 825 gates on `cacheSize.isNotEmpty()` | Prefix still "Cached:" not native "Used:"; no "Calculating…" transient. P3 cosmetic. |
| GAP-SET-09 | CLOSED | SettingsScreen.kt:669-679 (title `titleMedium` 14sp, desc `bodySmall` @0.5 alpha) | Was bodyLarge 16sp Bold; now matches native 14/12. AutoSubtitleText shrink not ported (accepted). |
| GAP-SET-10 | CLOSED | SettingsScreen.kt:638-641 `SectionDivider` = `background.copy(alpha=0.8f)` | Shared near-invisible divider helper. |
| GAP-SET-11 | CLOSED | UI gate SettingsScreen.kt:951 (`>=5`); SendComplaintUseCase.kt:14,20 `MIN_BODY_LENGTH=5` | Use-case floor lowered 8→5 to match UI end-to-end. ReplyToComplaintUseCase KDoc still says ">=8" but that is stale comment prose, not the live constant. |
| GAP-SET-12 | CLOSED | SettingsScreen.kt:972-976 subtitle; 1070-1088 social section + `YamiSocialMediaRow` | Subtitle + divider + copy + social row present. |
| GAP-SET-13 | CLOSED | SettingsScreen.kt:1029 `your_feedback`; 305-318 success/retry/Long-duration snackbar | Retry actionLabel + Long duration + reopen-on-retry wired. |
| GAP-SET-14 | CLOSED | SettingsScreen.kt:1172 local staged `selected`; 1212-1248 Apply(Check icon)/Revert footer | Staged selection; Apply commits, Revert resets+dismisses. (Rows are radio, not chips — accepted.) |
| GAP-SET-15 | CLOSED | SettingsScreen.kt:1175-1177 (16.dp / surfaceContainerHigh / 8.dp); 1216-1219 inverted Apply colors | Surface/elevation/corner + inverted Apply button match native. |
| GAP-SET-16 | DEFERRED(partial) | SettingsScreen.kt:1274-1328 `CbzConversionDialog` | Converting-state dialog (warning icon, indeterminate bar, dismiss-blocked) present; counts/current-item/Stop deferred (use case returns `Result<Unit>`, no progress Flow). |
| GAP-SET-21 | CLOSED | SettingsScreen.kt:435-511 toggle descriptions via `stringResource`; CBZ labels lifted | String lift done; trusted-Arabic landed (task #741). VM literal snackbars hoisted to resolved strings (305-313). |
| GAP-SET-22 | DEFERRED(KMP-EXTRA) | SettingsScreen.kt:562-565 What's-new nav row | Intentional; separate WhatsNew screen exists. |
| GAP-THM-01 | CLOSED (false-positive) | ThemeSelectionScreenRoute.kt:168-172 wires onContinue→Sources + permission; App.kt:417-419 routes `Screen.Theme` | Onboarding affordances are LIVE on the onboarding route. `Screen.ThemeRework` (Settings deep-link) is a terminal screen and deliberately omits them — correct by design. |
| GAP-THM-02 | CLOSED | ThemeSelectionScreenRoute.kt:14,150,171-172 `rememberNotificationPermissionRequester` + Continue gate | Permission request/grant/gate wired on onboarding route; Android SPI via `:platform`. Mechanics are DEVIATION(platform) on iOS/Desktop as specified. |
| GAP-THM-03 | DEFERRED | ThemeScreen.kt:301-305 plain Scaffold+TopAppBar | AnimatedBackground + gradient overlay still deferred (legacy AnimatedBackground.kt retired §307; documented design-deferral). Purely cosmetic. |
| GAP-THM-04 | CLOSED | ThemeScreen.kt:360-363 title `headlineMedium` primary; 374-389 tab icons via `themeIcon()` | 24sp primary title + Light/Dark/System tab icons. |
| GAP-THM-05 | DEFERRED(KMP-EXTRA) | ThemeScreen.kt:392-395 `PureBlackRow` | Intentional per task #243. |
| GAP-THM-06 | CLOSED | ThemeScreen.kt:428-429,446-455 `notification_permission`/`grant_permission` strings | Localized resource keys (en+ar via task #741). |
| GAP-THM-07 | CLOSED (false-positive) | App.kt:341 `YamiTheme(darkTheme=effectiveDark, pureBlack=pureBlack)` wraps `MainScreen()` | App-root applies rework `YamiTheme`, NOT legacy `YamiMangaTheme`. Whole NavHost tree under it. `dynamicColor` no-op deferred (accepted). |
| GAP-THM-08 | CLOSED(PASS) | YamiColors/YamiShapes/YamiTypography unchanged; root swap confirms runtime application | Verification record; tokens byte-identical, now provably applied at root. |
| GAP-THM-09 | CLOSED | SettingsRepository: KEY_PURE_BLACK default `true`, KEY_THEME_SYSTEM default `true`; YamiTheme override = background+surfaceContainer | Defaults match native (true/true); override target exact. Open question resolved. |
| GAP-THM-11 | DEFERRED(KMP-EXTRA) | SettingsScreen.kt:557-560 Theme nav row | Intentional deep-link; coexists with inline General theme toggles. |
| GAP-LANG-01 | CLOSED | LanguageScreen.kt:218-221 navigationIcon ArrowBack IconButton → `onBack` | Back arrow wired. |
| GAP-LANG-02 | DEFERRED(by-design) | LanguageScreen custom LanguageRow (per audit) | KMP's cleaner row accepted; trailing "0" was a native artifact. Add-icon on Request row optional. |
| GAP-LANG-03 | DEFERRED(by-design) | LanguageRequestDialog; data hardcodes subject="Languages" | Simpler dialog accepted as rework; functionally equiv to native pre-selected category. |
| GAP-LANG-04 | CLOSED | min length 8 in language dialog == use-case | Consistent; note GAP-SET-11 settled the feedback dialog at 5 (the two dialogs use different floors by design — language=8, feedback=5). |
| GAP-LANG-05 | CLOSED(PASS) | 11 langs en/ar/de/es/fr/in/it/ja/pt/ru/tr | Matches native set. |

---

## History / Updates / Statistics

| GAP-ID | Status | Evidence (file:line) | Note |
|---|---|---|---|
| GAP-HIST-01 | CLOSED(documented-equiv) | HistoryScreen.kt:112-115 `CoilSourceHeaderInterceptor` host-match header injection | Singleton ImageLoader attaches per-source headers transparently; equivalence to native buildImageRequest documented. |
| GAP-HIST-02 | CLOSED | HistoryScreen.kt:282-289 `width(80.dp).height(120.dp)` | Reverted 72×108 → native 80×120. |
| GAP-HIST-03 | DEFERRED(design-system) | HistoryScreen.kt:~254 surfaceVariant card | Accepted unified design-system token. |
| GAP-HIST-04 | DEFERRED(intentional) | HistoryScreen.kt:192,323 TextButtons | Cluster-wide text-vs-icon decision; accepted rework posture (icons feasible via YamiIcons if reversed). |
| GAP-HIST-05 | DEFERRED(intentional) | per-row delete error tint | Bundled with HIST-04. |
| GAP-HIST-06 | CLOSED(KMP-EXTRA accepted) | HistoryScreen.kt:209-210 `YamiLoadingState`/`YamiEmptyState(no_reading_history)` | Improvement; documented. |
| GAP-HIST-07 | CLOSED | HistoryScreen.kt:379-392 `monthAbbrev` via `month_abbrev_*` stringResources | Localized resources (en+ar), not hard-coded English. |
| GAP-UPD-01 | CLOSED(documented-equiv) | UpdatesScreen.kt:486-488 CoilSourceHeaderInterceptor | Singleton header coverage documented; KMP path is the desired target (better than native raw-url). |
| GAP-UPD-02 | CLOSED | UpdatesScreen.kt:489-499 `size(50.dp)` + 8dp corners | Reverted 72×108 → native 50×50. |
| GAP-UPD-03 | CLOSED | UpdatesScreen.kt:554-571 `DownloadAffordance` 4 states; UpdatesState.kt:121-126 `downloadStatusFor` reads queued/running queue state | Live queue state → spinner (running=primary / queued=onPrimary) / DownloadDone / idle. |
| GAP-UPD-04 | DEFERRED | (no ad gate in UpdatesViewModel) | Cross-feature ad-strategy decision; accepted/deferred. |
| GAP-UPD-05 | CLOSED | UpdatesScreen.kt:386-454 `SwipeToDismissBox` both directions, snap-back (confirmValueChange=false), 0.2-alpha bg | Swipe-right=mark-read, swipe-left=delete; matches native semantics. |
| GAP-UPD-06 | CLOSED | UpdatesScreen.kt:515-528 8dp primary CircleShape dot; 468 read alpha 0.4 | Unread dot restored + alpha aligned to native 0.4. |
| GAP-UPD-07 | CLOSED | UpdatesScreen.kt:317-321 4 recency buckets; 608 within-bucket `sortedByDescending{chapterNumber.toDoubleOrNull()}` | TODAY/YESTERDAY/LAST_WEEK/OLDER + chapter-number desc sort, native bucket strings. |
| GAP-UPD-08 | CLOSED | UpdatesScreen.kt:298-299 `YamiLoadingState`/`YamiEmptyState` | Now reuses shared components. |
| GAP-UPD-09 | DEFERRED(partial) | UpdatesScreen.kt:274-285 top-bar TextButtons; per-row download IS icon (DownloadDone, :570) | Top-bar + mark/delete stay text; per-row download affordance is now iconographic. Cluster-wide text-posture accepted. |
| GAP-UPD-10 | CLOSED | (shares HIST-07 `month_abbrev_*` resources) | Localized; no raw English under RTL. |
| GAP-STAT-01 | CLOSED | StatisticsScreen.kt:346-351 `formatCount` multiplatform thousands-grouping helper | Replaces JVM-only "%,d"; counts ≥1000 grouped. |
| GAP-STAT-02 | CLOSED | StatisticsScreen.kt:242,312 `surfaceContainerHigh` cards | Card color matches native; type-scale aligned. |
| GAP-STAT-03 | DEFERRED(minor) | StatisticsScreen hand-rolled spinner | Loading gate harmless; shared-component reuse is the only open nit. |
| GAP-STAT-04 | DEFERRED(intentional) | StatisticsScreen.kt:171 "Back" TextButton; no leading metric icons | Cluster-wide icon decision; accepted text posture. |
| GAP-STAT-05 | CLOSED(no-op) | metric set of 8 matches native; no charts either side | Scope confirmation only. |
| GAP-X-01 | DEFERRED(P3) | HistoryScreen/UpdatesScreen each carry own date helpers | Legitimately diverge now (Updates uses bucket scheme, History uses calendar-date); dedup deferred per gap's own sequencing note. |
| GAP-X-02 | STILL-OPEN(P3 cleanup) | App.kt:665/683/701 `Statistics/History/UpdatesReworkScreenRoute` bound to `Screen.*Rework` keys | Parallel debug route adapters still compiled + wired but unreachable from user nav. Pure dead-code cleanup; no user-facing effect. |

---

## Residual open items

- **GAP-X-02** (P3): the `*ReworkScreenRoute` parallel debug adapters (History/Updates/Statistics, also Settings/Theme/Language) remain compiled and wired to unreachable `Screen.*Rework` keys. Pure cleanup; documented, no user impact.
- **GAP-SET-08** (P3): clear-cache subtitle still "Cached:" (native "Used:") and lacks the "Calculating…" transient. Cosmetic.
- **GAP-X-01 / GAP-STAT-03** (P3): copy-pasted date helpers + Statistics non-shared spinner — reuse/refactor nits, no parity impact.
- **GAP-THM-03** (P1-cosmetic, DEFERRED): onboarding Theme animated background + gradient overlay not ported (legacy asset retired). Cosmetic-only; functional onboarding gate is fully wired.
- **GAP-SET-16** (DEFERRED-partial): CBZ conversion dialog lacks counts/current-item/Stop — blocked on a `Flow<ConversionProgress>` lift in `CompressExistingDownloadsUseCase`.
- DEVIATION(intentional, accepted): text-button affordances across HIST-04/UPD-09/STAT-04; KMP-EXTRA rows (auto-convert, Theme/What's-new nav, picker pure-black, History loading/empty).

**Stale-comment note (non-blocking):** ReplyToComplaintUseCase.kt:17,71 KDoc still cites `body.length >= 8`; the live `SendComplaintUseCase.MIN_BODY_LENGTH` is 5. Doc-only drift; behavior is correct.

**Verdict:** Both clusters are substantially native-parity-complete; the three flagged false-positive closures (GAP-SET-02, GAP-THM-01/02, GAP-THM-07) are genuinely wired in live code, and all P0/P1 functional gaps are CLOSED — only P2/P3 cosmetic/cleanup/intentional-deviation items remain.
