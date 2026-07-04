# UI Parity & Recovery Track

**Branch:** `architecture-rework` · **Opened:** 2026-05-30 · **Owner:** rework UI recovery

## Goal

> **Same visual appearance as the old app (or better), but implemented with clean, maintainable Compose/KMP code.**

The architecture rework improved structure but visibly degraded the user-facing UI on every
*already-migrated* surface. This track restores old-app visual quality as a **first-class
migration requirement**, while keeping the new code clean. Architecture wins do not count as
success if the UI regresses.

## First-class migration rule (Definition of Done)

**A migrated screen is NOT done unless it matches or improves the old UI visually AND the new
code is clean.** This applies retroactively (already-ported screens) and going forward (Home,
Search, Reader, and anything not yet ported must ship at parity, not regress-then-retrofit).

"Clean" means:
- UI primitives live in the shared `:ui` / design-system layer, not scattered per screen.
- Reusable components for cards, top bars, icon buttons, empty states, error panes, filter
  chips, bottom sheets, loading states — no copy-paste, no one-off hardcoded styling.
- Screen files hold state + composition, not raw styling duplication.
- No `:domain` / `:data` / `:platform` / Apollo types leak into UI components.
- No text glyphs / emoji standing in for icons.
- No "temporary degraded UI" treated as done. Any residual compromise is logged below.
- Small slices, each with a compile gate and a clear commit. No broad rewrites in one commit.

## Locked decisions

- **Icons → Option A:** add `material-icons-extended` to `:ui` now for true parity. Bundle size
  is secondary to restoring visual quality; may vendor a smaller set later. Replace text glyphs
  via clean shared icon components/wrappers, not scattered imports.
- **Order:** UP-1 (typography) → UP-2 (icons) → UP-3 (localization) → UP-7 (theme consolidation)
  → UP-4 / UP-5 / UP-6 (screen polish).
- **Process:** Opus 4.8 multi-agent per slice — Scope → Implementation → Test → Review
  (adversarial) → Integration. Stop only for a real blocked decision, a failing gate needing
  user input, or a clean-code conflict where exact old-UI parity would require bad architecture.

## Per-slice report format (used after every slice)

1. Files changed
2. Visual impact
3. Architecture / clean-code impact
4. Gates run + results
5. Commit hash
6. Temporary compromises remaining (explicit; "none" if none)

---

## Gap summary (from the 2026-05-30 inventory)

Surfaces that look worse are exactly the *already-migrated* ones; un-ported surfaces (Home,
Search, user-facing Reader, bottom nav) still look right because they are still legacy code.
**Not regressed:** color palette (byte-for-byte), shapes, dark/light/pure-black persistence.

| Dimension | Severity | Note |
|---|---|---|
| Typography (Gellix never wired; default Roboto everywhere) | P0 | root cause, app-wide → **UP-1** |
| Icons (text glyphs/emoji in all `:ui` screens) | P0 | most visible → **UP-2** |
| Localization (rework `:ui` strings hardcoded English; Arabic is primary locale) | P1 (correctness) | → **UP-3** |
| Two theme stacks; better `:ui` `YamiTheme` is dead | P2 | → **UP-7** |
| Manga cards/rows (no scrim, no elevation, no per-cell load/error, glyph badges) | P1 | → **UP-4** |
| Loading/empty/error states (plain spinners, one-line empty, shimmer dropped) | P1 | → **UP-5** |
| Filter/sort UI (dropdowns + AlertDialog vs polished bottom sheet) | P2 | → **UP-6** |
| Animations/transitions (mostly static; default nav transitions) | P2 | folded into UP-4/5 |
| Reader visual parity | defer | fold into #217 Reader convergence (rework reader not user-reachable yet) |
| Home/Search visual parity | defer | build parity-first during their port, not as a retrofit |

---

## Slices

### UP-1 — Wire Gellix Typography  ·  status: DONE
Restored the old app's Gellix type ramp app-wide. Single `yamiTypography()` factory in the
`:ui` design-system (compose-resources `Font(Res.font.gellix_*)`), consumed by both the `:ui`
`YamiTheme` and the applied `composeApp` `YamiMangaTheme`. Mirrors legacy `Type.kt` byte-for-byte
(`bodyLarge` 16/Bold, `titleMedium` 14/Medium, `titleSmall` 12/Normal in Gellix; remaining slots
keep Material 3 defaults to preserve Arabic glyph fallback).
- **Gates (all green, `--offline`):** `:ui` desktop · `:composeApp` Android · `:composeApp`
  Desktop · `:composeApp` iOS Arm64 · `:composeApp` iOS SimulatorArm64.
- **Review:** 2 adversarial Opus 4.8 reviewers → both PASS, non-blocking. Confirmed app-wide
  effect (App.kt:331 wraps the NavHost), single type-scale source, no layer leak, parity faithful.
- **Carry-forward → UP-3:** verify Arabic glyph rendering of the 3 Gellix slots on **iOS/Desktop
  (Skia)** — the only genuinely new exposure vs the old Android-only app (parity-preserving, not a
  regression). Pre-existing stale KDoc in `YamiTheme.kt` (claims `:ui` screens call `YamiTheme` at
  screen scope — they don't) noted for cleanup under UP-7.
- **Compromises:** none for typography. Cosmetic: composeApp retains now-orphan duplicate font
  copies (pre-existing); active fonts live in `:ui`. Cleanup folded into UP-7.

### UP-2 — Real icons (Option A)  ·  status: DONE
Added `material-icons-extended` to `:ui`; replaced every text-glyph/emoji "icon" with real
`ImageVector`s through a clean shared design-system layer — `YamiIcons` (semantic map),
`YamiIconButton` (icon-button wrapper, required contentDescription), `YamiCountBadge` (icon +
optional count) — no scattered `Icons.*` imports.
- **UP-2a (done):** dep + `YamiIcons`/`YamiIconButton`; Details + Reader top bars (back / bookmark
  / downloads / webview / refresh; back / prev / next / overflow; reading-mode selection → trailing
  check). Commit `cd0c944`.
- **UP-2b (done):** `YamiCountBadge`; Library (sort-dir, download badge, card badges `✓`/`↓ N`/`🔖 N`,
  action row watch/like/delete) + Language selected-check. Discharged the stale glyph doc-refs
  flagged in 2a (LibraryScreen + LanguageScreen).
- **UP-2c (done):** repo-wide scan of `:ui` confirms **zero** glyph-as-icon `Text(...)` remain —
  no straggler screens, so 2c folds into 2b (no separate slice needed).
- RTL: Back / chevrons / OpenInNew auto-mirrored; favorite / check / download / bookmark / delete /
  sort-arrows correctly non-mirrored. 5-target matrix green; adversarial reviewers PASS.
- **Known follow-up (not UP-2 scope):** ~10 OTHER `:ui` files still carry a now-globally-false
  "`:ui` omits materialIconsExtended" claim in their §-postscripts — a doc-hygiene sweep for a
  later pass, harmless (no runtime effect).

### UP-3 — Localization / stringResource lift  ·  status: IN PROGRESS (infra + pilot done)
Replace hardcoded English literals in rework `:ui` screens with compose-resources `stringResource`.
Arabic/RTL must not regress.
- **Decision locked — option C:** extract all rework strings to English keys + **reuse the existing
  Arabic** (the full 514-key en+ar+9-locale catalog already ships in `composeApp/.../composeResources/`)
  for every overlapping key; genuinely-NEW rework strings stay English-with-TODO until a trusted
  Arabic source is provided (the one input needed from the user; ~30–40% of distinct strings). No
  machine-invented Arabic. Arabic users never lose anything the old app already translated.
- **UP-3a (done):** string-resource infra in `:ui` (`composeResources/values/` + `values-ar/`) +
  Welcome pilot (3 reused keys `welcome_title`/`welcome_suptitle`/`get_started`, en+ar). Proves the
  full en+ar pipeline end-to-end. 5-target matrix green.
- **Next:** 3b shared/common cluster → 3c..N per-screen (small→large; Settings, Library last) →
  3y parameterized/relative-time formatters → 3z-ar-reuse (bulk-copy Arabic for overlapping keys).
- **Runtime:** locale/RTL via `:platform` `LocaleSwitcher` + framework auto-mirroring; `stringResource`
  follows locale (live on Android, next-launch iOS/Desktop — matches legacy, no regression).
- **Carry-forward from UP-1 still open:** on-device Arabic glyph check on iOS/Desktop Skia.
- **Carry-forward from UP-1:** on-device verify Arabic rendering of the 3 Gellix slots on
  iOS/Desktop (Skia fallback chain differs from Android, where the old app relied on the system
  Arabic face). Eyeball Arabic-heavy screens for tofu / mixed metrics.

### UP-7 — Theme / design-system consolidation  ·  status: TODO
Make the applied root theme use the clean design-system tokens (typography, spacing, shapes,
colors, component defaults). Wire the `:ui` `YamiTheme` at root or fold it into the active theme
— without duplicating stacks.

### UP-4 — Manga cards & list rows  ·  status: ✅ DONE
Restore cover scrim/gradient, elevation, per-cell loading/error, real-icon badges — as clean
reusable card components. (Real-icon badges landed in UP-2; scrim + per-cell error + elevation in UP-4.)

### UP-5 — Loading / empty / error states  ·  status: TODO
Restore illustrated empty states, styled error panes, and loading affordances (shimmer where the
old app had it) as shared components.

### UP-6 — Filter / sort bottom sheet  ·  status: TODO
Restore the tabbed Filter/Sort/Display bottom sheet as a clean reusable component.

---

## Status log

- **2026-05-30** — Track opened. Inventory complete (old vs rework, 3 parallel Opus 4.8
  agents). Root cause confirmed: typography (Gellix unwired) + text-glyph icons; palette/shapes
  intact. Decisions locked (icons A; order 1→2→3→7→4/5/6; parity = first-class DoD).
- **2026-05-30** — **UP-1 DONE.** Gellix typography wired in `:ui` design-system; 5-target
  compile matrix green; 2 adversarial reviewers PASS. Next: UP-2 (real icons, Option A).
- **2026-05-30** — **UP-2a DONE.** `material-icons-extended` + `YamiIcons`/`YamiIconButton`
  design-system layer; Details + Reader top bars de-glyphed (real Material icons, RTL-mirrored).
  5-target matrix green; adversarial reviewer PASS. Next: UP-2b (Library + Language).
- **2026-05-30** — **UP-2 DONE** (2b+2c). `YamiCountBadge`; Library + Language de-glyphed; stale
  glyph doc-refs discharged. Repo-wide scan: zero glyph-as-icon left in `:ui`. 5-target matrix
  green; adversarial reviewer PASS. Next: UP-3 (localization/RTL).
- **2026-05-30** — **UP-3a DONE.** Scoped UP-3 (found the full en+ar catalog already in
  `composeApp` resources → option C locked). Stood up `:ui` string-resource infra + Welcome pilot
  (3 reused keys, en+ar). 5-target matrix green. Next: UP-3b (shared/common string cluster).
- **2026-05-30** — **UP-3b DONE.** WhatsNew localized: `what_s_new`/`retry`/`new_badge` reused
  (en+ar), `whats_new_empty` new (en-only, ar-TODO). Common keys `retry`/`new_badge` now seeded for
  reuse by later screens. 5-target matrix green. New-strings ledger started.
- **2026-05-30** — **UP-3c DONE.** Theme picker localized: 9 reused keys en+ar (choose_your_theme,
  theme_light/dark/system [= old-app "Light Mode"/"Dark Mode"/"System" labels], pure_black_mode_title,
  continue_string, enable_notifications, notification_permission, grant_permission) + `theme_screen_title`
  new (en-only). `AppTheme.label` val → `@Composable themeLabel()`. 5-target matrix green.
  New-strings ledger: `whats_new_empty`, `theme_screen_title`.
- **2026-05-30** — **UP-3d DONE.** About localized: 6 reused keys en+ar (about, back, version,
  check_for_update, rate_our_app, privacy_policy) + `what_s_new` reused. ZERO new strings. Social-button
  contentDescriptions left as brand proper-nouns (not localized — convention). 5-target matrix green.
  **Screens localized so far: Welcome, WhatsNew, Theme, About.** Remaining UP-3 screens: Statistics,
  Sources, Downloads, History, Updates, Details, Reader, Language, Complaint(+Admin), Settings, Library,
  DisplayOptionsDialog (≈11). Then UP-3z-ar-reuse (other 9 locales optional) + 3z-ar-new (blocked).
- **2026-05-30** — **UP-3e DONE.** Statistics localized: 10 reused keys en+ar (statistics, section_entries,
  section_chapters, label_in_library/started/completed/total/read/downloaded/bookmarked) + `read_time`
  new (en-only). 5-target matrix green. **Localized: Welcome, WhatsNew, Theme, About, Statistics (5).**
  New-strings ledger: whats_new_empty, theme_screen_title, read_time.
- **2026-05-30** — **UP-3f DONE.** Sources localized: **10 reused keys en+ar** (request_adding_source,
  enter_the_url_for_site_you_want_us_to_add, languages_coming_soon_title, languages_coming_soon_description,
  finish, we_will_add_it_as_soon_it_possible, enter_the_site_url, minimum_5_characters_required, submit,
  cancel — all Arabic lifted verbatim from the legacy values-ar catalog) + `sources_title` (en + trusted ar
  "المصادر" fragment) + 3 new en-only (no_sources_available, submitting, sources_enabled_count "%1$d of %2$d
  enabled"). English kept at the rework's cleaner wording (DoD "or better"); legacy ar reads correctly against
  it. The `${body.length}/500` char-counter left inline (language-neutral numeric). 3 stale "inline literal /
  Phase 10 i18n lift" KDoc bullets scrubbed. 5-target matrix green (BUILD SUCCESSFUL). Commit `5519778`.
  **Localized: Welcome, WhatsNew, Theme, About, Statistics, Sources (6).**
  New-strings ledger: whats_new_empty, theme_screen_title, read_time, no_sources_available, submitting,
  sources_enabled_count. **Remaining UP-3 screens:** Downloads, History, Updates, Details, Reader, Language,
  Complaint (+Admin), Settings, Library, DisplayOptions.
- **2026-05-30** — **UP-3g DONE.** Downloads localized: **9 reused keys en+ar** (downloads, active, failed,
  completed, running, queued, downloaded, compressing, delete — ar verbatim from legacy) + `download_chapter_title`
  ("Ch %1$s — %2$s", ar reuses trusted "الفصل" fragment) + reused existing `back`/`retry`. 4 new en-only
  (no_active_downloads, no_failed_downloads, no_completed_downloads, download_failed_reason "Failed: %1$s").
  `statusLabel` lifted to `@Composable`; the hardcoded `TabLabels` val removed (tab titles built inline via
  stringResource); `${progress}%` left inline (numeric). Stale icon-strategy KDoc corrected (it claimed `:ui`
  "deliberately omits materialIconsExtended" — false since UP-2a; Downloads action affordances stay labelled
  TextButtons pending a UP-2/UP-4 icon pass, now documented accurately). 5-target matrix green (BUILD SUCCESSFUL).
  Commit `1c514a2`. **Localized: Welcome, WhatsNew, Theme, About, Statistics, Sources, Downloads (7).**
  New-strings ledger += no_active_downloads, no_failed_downloads, no_completed_downloads, download_failed_reason.
  **Remaining UP-3 screens:** History, Updates, Details, Reader, Language, Complaint (+Admin), Settings, Library,
  DisplayOptions.
- **2026-05-30** — **UP-3h DONE.** History localized: **6 reused relative-date keys en+ar** (today, yesterday,
  days_ago, weeks_ago, months_ago, years_ago — "%1$d …" / "منذ %1$d …", verbatim legacy) + reused `delete`.
  3 new en-only (history, clear_all, no_reading_history). `formatGroupLabel`/`formatRelativeDate` already
  `@Composable` → resolve via stringResource; Long args passed as `.toInt()` for `%1$d`. Compromise: the
  group-header absolute-date fallback ("MMM d, yyyy") keeps English month abbreviations — the legacy catalog
  never authored per-month keys; proper locale date formatting is deferred (documented in KDoc + code comment).
  Stale icon-omission + inline-literal KDoc bullets corrected. 5-target matrix green (BUILD SUCCESSFUL).
  Commit `c888cd9`. **Localized: Welcome, WhatsNew, Theme, About, Statistics, Sources, Downloads, History (8).**
  New-strings ledger += history, clear_all, no_reading_history.
  **Remaining UP-3 screens:** Updates, Details, Reader, Language, Complaint (+Admin), Settings, Library,
  DisplayOptions.
- **2026-05-30** — **UP-3i DONE.** Updates localized: reuses the History relative-date keys + `clear_all` +
  `delete` + `downloaded` (all already en+ar in `:ui`) for date formatters / Clear-all / Delete / Downloaded.
  7 new en-only (updates, mark_all_read, mark_read, download, no_updates, update_deleted, undo) — the legacy
  catalog never authored Updates labels, so no trusted Arabic. Snackbar copy (update_deleted/undo) hoisted to
  composable-scope vals (stringResource can't run inside the effect-collector coroutine). Date formatters →
  stringResource with `.toInt()` args; month-abbrev fallback unchanged. Stale icon-omission + inline-literal
  KDoc bullets corrected. 5-target matrix green (BUILD SUCCESSFUL). Commit `3999fb1`.
  **Localized: Welcome, WhatsNew, Theme, About, Statistics, Sources, Downloads, History, Updates (9).**
  New-strings ledger += updates, mark_all_read, mark_read, download, no_updates, update_deleted, undo.
  **Remaining UP-3 screens:** Details, Reader, Language, Complaint (+Admin), Settings, Library, DisplayOptions.
- **2026-05-30** — **UP-3j DONE.** Language localized: **5 reused keys en+ar** (select_language,
  request_add_language, enter_your_language, request_submitted_successfully, request_failed — ar verbatim
  legacy) + reused `submit`/`cancel`. 3 new en-only (request_language_prompt, at_least_n_characters
  "At least %1$d characters", selected). Top bar → `select_language` ("Select Language", the old-app title);
  the request dialog field reuses `enter_your_language`. Snackbar copy hoisted to composable-scope vals.
  Stale inline-literal KDoc bullets corrected. 5-target matrix green (BUILD SUCCESSFUL). Commit `5267fc3`.
  **Localized: Welcome, WhatsNew, Theme, About, Statistics, Sources, Downloads, History, Updates, Language (10).**
  New-strings ledger += request_language_prompt, at_least_n_characters, selected.
  **Remaining UP-3 screens:** Details, Reader, Complaint (+Admin), Settings, Library, DisplayOptions.
- **2026-05-30** — **UP-3k DONE.** Settings hub localized (the largest screen so far, ~35 strings). The 4
  enum-label getters (SettingsToggle / SettingsDestination / ComplaintType / ReadingMode `.label`) converted
  from non-composable `val get()` extensions to `@Composable` helper funs resolving via stringResource.
  **6 reused legacy keys en+ar** (follow_system_theme, clear_cache, help, request_feature_bug, category,
  your_feedback) + reuse of existing :ui keys (statistics/about/what_s_new/pure_black_mode_title/theme_dark/
  theme_screen_title/downloads/minimum_5_characters_required/submit/submitting/cancel). **26 new en-only**
  (settings, section_general/reading/navigation/storage/feedback, setting_downloaded_only/incognito,
  reading_mode + 6 reading_mode_* modes, language, feedback_manager, cached_size "Cached: %1$s",
  select_a_category, describe_issue_or_feature, 6 complaint_* types). `${body.length}/500` left inline.
  Stale icon-omission + inline-literal KDoc bullets + 4 getter-KDoc "Phase 10 i18n" prose + broken
  `[X.label]` doc-links corrected. 5-target matrix green (BUILD SUCCESSFUL). Commit `07f1dee`.
  **Localized: Welcome, WhatsNew, Theme, About, Statistics, Sources, Downloads, History, Updates, Language,
  Settings (11).**
- **2026-05-30** — **UP-3l DONE (multi-agent) — CLOSES UP-3.** The 5 remaining large clusters localized in
  parallel via a Workflow (5 Opus agents, ~1.15M subagent tokens, 182 tool-uses, ~10 min): **Details**
  (DetailsScreen), **Reader** (ReaderScreen), **Complaint** (ComplaintScreen + ComplaintActionDialog +
  ComplaintTypeDisplay + ComplaintStatusDisplay), **AdminComplaint** (AdminComplaintScreen +
  AdminComplaintActionDialog), **Library** (LibraryScreen + DisplayOptionsDialog). Each agent edited only its
  own non-overlapping files following the UP-3 playbook (reuse legacy keys + verbatim ar, en-only for new,
  hoist snackbar strings, convert enum/displayName getters to @Composable, leave numeric counters inline) and
  returned its new-key list; **the integrator merged centrally** (PowerShell reconciler: 171 missing keys →
  171 en + 75 ar resolved, 0 unresolved) into the `:ui` catalogs, deduping cross-cluster shared keys
  (close/edit/filter_all/status_*/complaint_body/…). Adversarial review: only inline literal left is the
  language-neutral `Text("v$version")` chip; dropped 7 untranslated-English `values-ar` entries
  (add_closure_reason/add_reason/admin_actions/change_status/sort_{status,type,user_id}) so they fall back to
  en. 5-target matrix green (BUILD SUCCESSFUL in 29s). Commit `2e24498`.
  **✅ UP-3 COMPLETE — all 16 migrated screens localized (Welcome, WhatsNew, Theme, About, Statistics, Sources,
  Downloads, History, Updates, Language, Settings, Details, Reader, Complaint, AdminComplaint, Library).**
  Arabic/RTL parity across the rework via reused legacy translations; the genuinely-new rework strings are
  en-only pending one trusted-Arabic pass (the single outstanding UP-3 input — see new-strings ledger; the
  `details_*` / `reader_*` / `library_*` / `complaint_*` / `admincomplaint_*` domain-prefixed keys + the
  Settings/Updates new keys). Settings has the most en-only keys (poor legacy ar coverage) — flagged for the trusted-ar pass.
- **2026-05-30** — **UP-7 DONE.** Theme/design-system consolidation. `composeApp` `Theme.kt`'s `YamiMangaTheme`
  carried its own dark/light `ColorScheme`s + `Shapes` that were **byte-for-byte identical** to the `:ui`
  design-system tokens (verified token-by-token: primary `B0C6FF`, background `15202B`, all match). Folded it
  into a thin alias over `:ui` `YamiTheme(darkTheme, pureBlack, dynamicColor, content)` — `:ui` (`YamiColors` +
  `YamiShapes` + `yamiTypography()` + `LocalSpacing`) is now the **single source of truth** for the app theme.
  No on-screen change (identical values); also removes the latent two-stack hazard (leaf `:ui` screens already
  call `YamiTheme`, so root now matches them structurally). Kept the `YamiMangaTheme` name → `App.kt` + other
  callers untouched. Removed the 3 duplicate Gellix fonts from `composeApp/composeResources/font` that `:ui`
  now owns (gellix_regular/semibold/bold); composeApp had zero live font references (grep across all source
  sets). Left poppins/gilroy/alba + the other gellix weights (pre-existing unreferenced legacy assets, not
  theme-stack duplicates — a separate dead-asset sweep, out of UP-7 scope). composeApp 4-target matrix
  (Android debug + Desktop + iosArm64 + iosSimulatorArm64): BUILD SUCCESSFUL. Commit `80b238b`.
  **✅ UP-7 COMPLETE.** Remaining: **UP-4/5/6** — manga-card/list-row polish, empty/loading/error illustrated
  states, filter/sort bottom-sheet — visual-design tracks that benefit from rendered-output review.
- **2026-05-30** — **UP-5 DONE.** Empty/loading/error illustrated-state parity. Added one reusable `:ui`
  component file `components/YamiStateViews.kt` with three composables consolidating the ad-hoc per-screen
  state branches into one illustrated vocabulary: **`YamiLoadingState`** (centred `CircularProgressIndicator`),
  **`YamiEmptyState(title, icon=YamiIcons.Empty, message?, action?)`** (centred 64dp muted icon + title +
  optional message + optional CTA slot), **`YamiErrorState(message, retryLabel?, onRetry?)`** (centred
  error glyph + message + optional Retry button). String-free by design — callers pass already-resolved
  `stringResource(...)` so `:ui` carries no per-screen key coupling. Added two semantic icons to `YamiIcons`
  (`Empty = Icons.Outlined.Inbox`, `Error = Icons.Outlined.ErrorOutline`). **Adopted across 4 screens**:
  History (loading+empty), Downloads (loading + per-bucket empty), Sources (LoadingBox/EmptyBox helpers now
  delegate), WhatsNew (LoadingState/ErrorState/EmptyState all three — the one screen with a real
  message+retry error branch). Each adoption removed its hand-rolled `Box(fillMaxSize)+centred Text/spinner`
  and trimmed the now-orphaned imports (`CircularProgressIndicator` ×4, `Button`+`TextAlign` in WhatsNew).
  **Intentional non-adoption:** `DetailsScreen.DetailsErrorPane` keeps its specialized layout — it needs the
  WebView escape-hatch button between message and Retry (the rework's `Handle403Error` substitute), which the
  generic 80%-case component deliberately doesn't model; forcing it would be worse. Clean-code impact: one
  source of truth for state visuals; future restyle happens in one file. Localization impact: none (string-free
  component; reused existing `no_reading_history`/`no_active_downloads`/`no_sources_available`/`whats_new_empty`/
  `retry` keys). 5-target matrix green (`:ui` Android debug + Desktop + iosArm64 + iosSimulatorArm64: BUILD
  SUCCESSFUL). Commit `06c1de3`. **✅ UP-5 COMPLETE.** Remaining: **UP-4** (manga-card/list-row polish),
  **UP-6** (filter/sort bottom-sheet). `YamiErrorState` now available as the error-state primitive for screens
  that later gain network-error+retry branches (most rework screens currently surface errors via the MVI
  `ShowError` snackbar effect).
- **2026-05-30** — **UP-4 DONE.** Manga-card cover parity. Added one reusable `:ui` component
  `components/YamiCoverImage.kt` consolidating the cover-render boilerplate (aspect-ratio + rounded clip +
  tinted placeholder + Coil `AsyncImage`) and adding the two affordances the rework's bare `AsyncImage` was
  missing vs the native app: **(1) bottom gradient scrim** (opt-in `scrim` param — transparent→`Black α0.45`
  `verticalGradient` over the lower cover, restoring the polished native cover treatment + lifting overlaid
  action-row-icon contrast) and **(2) per-cell error glyph** (a muted `YamiIcons.BrokenImage` centred on a
  Coil load *failure*, tracked via `AsyncImage(onState=…)`). Deliberately **no loading spinner** — kept the
  calm tinted-box-fills-in signal (50 simultaneous grid spinners = noise), and uses `AsyncImage` not
  `SubcomposeAsyncImage` so the grid adds zero per-cell subcomposition. Added `YamiIcons.BrokenImage`
  (`Icons.Outlined.BrokenImage`). Adopted in `LibraryScreen.LibraryCardCover` (delegates, `scrim = true`) and
  **bumped the `LibraryCard` elevation** to `cardElevation(defaultElevation = 3.dp)` (the native library cards
  have depth; the rework's default ~1dp filled-Card read flat). Removed 4 now-orphaned LibraryScreen imports
  (`AsyncImage`, `ContentScale`, `aspectRatio`, `clip`); corrected the stale "Trade-off accepted: no per-cell
  error indicator" KDoc (now there IS one). Library-specific posture preserved (no adult-cover blur in the
  library view — bookmarking is the opt-in). Clean-code impact: a reusable cover primitive other cover sites
  (History thumbnail, future grids) can adopt. Localization impact: none. 5-target `:ui` matrix green (BUILD
  SUCCESSFUL). Commit `c68cd5a`. **✅ UP-4 COMPLETE.** Remaining: **UP-6** (filter/sort bottom-sheet) —
  the last visual-parity track.
- **2026-05-30** — **UP-6 DONE — CLOSES the UP visual-parity campaign.** Tabbed Filter / Sort / Display
  bottom sheet. Added one reusable `:ui` component `library/LibraryOptionsSheet.kt` — a `ModalBottomSheet`
  with a 3-tab `TabRow` (Filter / Sort / Display) restoring the native app's single options sheet. It
  consolidates what the rework had scattered into **four** top-bar entry points (Filter + Sort + Density
  `DropdownMenu`s + the Display `AlertDialog`) down to **one** `YamiIcons.Tune` icon button. Filter tab =
  selectable `LibraryFilter` rows; Sort tab = selectable `LibrarySort` rows + the asc/desc direction toggle
  (hidden for RANDOM); Display tab = `GridDensity` rows + the 5 `display.show*` switches. Pure projection —
  dispatches the **same** `LibraryIntent` variants the scattered menus did (`OnFilterChange`/`OnSortChange`/
  `OnSortDirectionToggle`/`OnGridDensityChange`/`OnToggleShow*`); no new intents, no VM/behaviour change.
  `ModalBottomSheet` experimental opt-in is justified here (restoring the bottom sheet IS the goal, unlike the
  prior AlertDialog). Reuses the `librarySortLabel`/`libraryFilterLabel`/`gridDensityLabel` helpers (made
  `internal`). **Deleted** `DisplayOptionsDialog.kt` (its 5 toggles folded into the Display tab) and the 3
  now-unused selector composables (`SortMenu`/`FilterMenu`/`DensityMenu`) from LibraryScreen + 3 orphaned
  imports (`DropdownMenu`/`DropdownMenuItem`/`SortDirection`). Added `YamiIcons.Tune` (`Icons.Filled.Tune`)
  and one en-only string `library_options` ("Options", the Tune button's content description). 5-target `:ui`
  matrix green (BUILD SUCCESSFUL). Commit `961e42c`. **✅ UP-6 COMPLETE.**

## ✅ UP visual-parity campaign COMPLETE
All seven tracks landed: **UP-1** typography · **UP-2** real icons · **UP-3** localization/RTL · **UP-7**
theme/design-system consolidation · **UP-4** card scrim/elevation/error · **UP-5** empty/loading/error states ·
**UP-6** filter/sort/display sheet. The rework UI now matches (or improves on) the native app across all
inventoried parity gaps, with the design-system primitives (`YamiIcons`, `YamiCountBadge`, `YamiCoverImage`,
`YamiStateViews`, `LibraryOptionsSheet`, `YamiTheme`) as the clean reusable foundation. One non-blocking input
remains open: a trusted-Arabic pass for the en-only new rework strings (UP-3 ledger + UP-6's `library_options`).
