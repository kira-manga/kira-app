## Home & Search — gaps

Total: 28 gaps — P0: 4 · P1: 11 · P2: 8 · P3: 5. (Includes DEVIATION(platform) for AdMob/VideoView/OkHttp and KMP-EXTRA notes.)

---

## Home feed (HomeScreen)

### GAP-HOME-01 — Help affordance is unreachable (error screen Help button missing)
- **Screen/surface:** Home feed error state + top bar
- **Type:** MISSING_FEATURE
- **OLD (target):** Home `ErrorScreen` exposes a Help action that sets `showHelpDialog=true`, opening `HelpVideoDialog` (old `HomeScreen.kt:303, 365-377, 465-469`). `ErrorScreen` ships three actions: Retry, Open-in-browser, Help (old `ErrorScreen.kt`, cluster notes :222).
- **KMP (current):** `onHelp` is a `TODO` no-op in the route adapter (kmp `HomeReworkScreenRoute.kt:158`); top bar has no help action (kmp `HomeScreen.kt:182-202`); `HomeIntent.OnHelp`/`HomeEffect.ShowHelp` exist but are never reachable (kmp `HomeIntent.kt:48-49`, `HomeEffect.kt:63-64`). Error state uses `YamiErrorState` with retry only (kmp `HomeScreen.kt:262-266`).
- **Priority:** P1
- **Acceptance criteria:** The Home error state surfaces a Help action that triggers `HomeIntent.OnHelp`; the route adapter wires `onHelp` to emit `HomeEffect.ShowHelp` and show the help dialog/screen (see GAP-HOME-26 for the video player itself). `YamiErrorState` (or its host) must offer Retry + Open-in-browser + Help, matching the legacy 3-action `ErrorScreen`.
- **Notes:** Strings `home_help` already exist but are unwired (kmp cluster notes :89). Reuse `YamiStateViews`/`YamiErrorState`; may need to extend it to accept up to 3 action slots like legacy `ErrorScreen`. See GAP-HOME-02 for the missing open-in-browser action on the error state too.

### GAP-HOME-02 — Error state missing "Open in browser" action
- **Screen/surface:** Home feed error state
- **Type:** MISSING_FEATURE
- **OLD (target):** `ErrorScreen(onRetry, onOpenInBrowser=onOpenInWebView, onHelp=...)` — three actions incl. Open-in-WebView (old `HomeScreen.kt:292-305, 365-377`; cluster notes :222 string `action_open_in_browser`="Open in WebView").
- **KMP (current):** `YamiErrorState` is wired only with retry → `OnRefresh` (kmp `HomeScreen.kt:262-266`). No open-in-browser action on the error surface (top bar still has it, but the legacy error screen offered it inline).
- **Priority:** P2
- **Acceptance criteria:** Home error state offers an "Open in WebView" action alongside Retry (and Help per GAP-HOME-01), wired to `OnOpenWebView`.
- **Notes:** Bundle with GAP-HOME-01 when extending `YamiErrorState` to multi-action.

### GAP-HOME-03 — Native AdMob native-ad interleaving dropped
- **Screen/surface:** Home list + grid feed
- **Type:** DEVIATION(platform)
- **OLD (target):** `mangaItemsState` wrapped via `interleaveAdsCustom(interval = 5, ...)` → every 5th slot is a `NativeAdListItem`; grid mode interleaves ads too (old `HomeScreen.kt:147-158, 405-431, 307-339`).
- **KMP (current):** No ad interleaving; feed renders manga items only (kmp `HomeScreen.kt:279-360`).
- **Priority:** P3 (platform — must NOT be P0 per rules)
- **Acceptance criteria:** Either (a) intentionally drop ads and document the rationale, or (b) provide a cross-platform ad abstraction (expect/actual) with an Android AdMob actual and a no-op desktop/iOS actual, re-introducing every-5th-slot interleaving. Recommended: intentionally dropped for now; revenue/ads are out of scope for parity rework — record as a deferred platform item.
- **Notes:** AdMob is Android-only (Google Mobile Ads SDK). A KMP no-op + Android-only actual is the clean substitute if ads must return. Also affects Search grids (GAP-SRCH-04) and Multi-Search (GAP-MULT-02).

### GAP-HOME-04 — Per-source tab icons not rendered
- **Screen/surface:** Source tabs strip
- **Type:** MISSING_FEATURE
- **OLD (target):** Each tab shows the source icon (`painterResource(repo.ICON)`, fallback `R.drawable.team_x` when ICON==0, 18.dp) before the API label (old `SourcesTabs.kt:92-120`).
- **KMP (current):** `SourceTabsRow` has an `iconForTab` slot but the route adapter never supplies it, so tabs are label-only (kmp `HomeScreen.kt`/`HomeReworkScreenRoute.kt`; kmp audit :26, :38). `SourceTab` carries `iconKey?` (kmp `SourceTab.kt:15-24`).
- **Priority:** P1
- **Acceptance criteria:** Each source tab renders its source icon (18.dp) left of the API label, with a fallback glyph (team_x equivalent) when no icon. Thread `iconForTab` from the route adapter using `SourceTab.iconKey`.
- **Notes:** Requires per-source icon assets to exist as `:ui` Compose resources (see GAP-HOME-25). Verify icon-key → resource mapping path. The slot already exists; this is wiring + assets.

### GAP-HOME-05 — "NEW source" badge disabled
- **Screen/surface:** Source tabs strip (edit button overlay)
- **Type:** MISSING_FEATURE
- **OLD (target):** `AnimatedNew` "NEW" badge overlays the edit-tabs button when `isNewSource` (from `repoSettingsViewModel.newSources`), animated (old `SourcesTabs.kt:123-149`; string `new_badge`="NEW").
- **KMP (current):** `showNewBadge=false` hardcoded (kmp `HomeScreen.kt:221`); badge is hand-rolled and disabled (kmp `SourceTabsRow.kt:137-148`; audit :85, :95).
- **Priority:** P2
- **Acceptance criteria:** When a new source is available, a "NEW" badge appears over the edit-sources button; wire `showNewBadge` + `newBadgeLabel` (string `home_new_source_badge` exists) from a new-source signal in the VM. Animation optional (P3 polish).
- **Notes:** Requires exposing a "new sources" flow into `HomeViewModel`. Consider reusing `YamiCountBadge` (already imported in `SourceTabsRow.kt:26`) instead of hand-rolled text.

### GAP-HOME-06 — Featured carousel: per-item spring scale + fling-snap animation dropped
- **Screen/surface:** Featured/popular carousel (list mode)
- **Type:** DEVIATION(platform)
- **OLD (target):** `HorizontalUncontainedCarousel` (M3 experimental) with per-item `animateFloatAsState` scale (parallax/zoom via `graphicsLayer`, `spring(DampingRatioMediumBouncy)`) + single-advance fling snapping (old `MangaCarousel.kt:46-83`).
- **KMP (current):** Plain `LazyRow`; per-item spring scale + fling-snap intentionally dropped "for cross-target stability" (kmp `FeaturedCarousel.kt:21-42`).
- **Priority:** P2
- **Acceptance criteria:** Decide explicitly: either re-implement the scale/snap with a cross-platform-safe approach (M3 `HorizontalUncontainedCarousel` is in Compose Multiplatform; a `SnapFlingBehavior` + `graphicsLayer` scale works on all targets), or document the deviation as accepted. For exact parity, restore the snap + subtle scale.
- **Notes:** The dimensions (220.dp tall, 150.dp wide, 16.dp spacing, 12.dp pad, rounded 12, elevation 6) already match. Only the motion differs.

### GAP-HOME-07 — Featured carousel cards missing title overlay
- **Screen/surface:** Featured/popular carousel
- **Type:** VISUAL
- **OLD (target):** Carousel cards are cover-only `AsyncImage(Crop)` in the legacy `MangaCarousel` — **the old audit confirms cover-only, no title overlay** (old `MangaCarousel.kt:74-94`).
- **KMP (current):** Also cover-only with bottom scrim (kmp `FeaturedCarousel.kt:44-85`). However the kmp audit's own "Absent" note (:50) claims "legacy showed title" — this contradicts the OLD audit which shows no carousel title.
- **Priority:** P3
- **Acceptance criteria:** Resolve the contradiction: per the OLD audit (`MangaCarousel.kt:74-94`) the legacy carousel is cover-only, so KMP is already at parity — keep cover-only and correct the kmp audit's "Absent: title label" note. **(INFERRED: no action needed beyond doc correction; flag for verification against the real native source.)**
- **Notes:** Open question — confirm against native `MangaCarousel.kt`. If native truly has no carousel title, close this as parity-met.

### GAP-HOME-08 — Featured carousel `coverModel` slot not threaded (source-aware covers)
- **Screen/surface:** Featured carousel
- **Type:** REFACTOR
- **OLD (target):** Covers built per-source via `buildImageRequest(ctx, url, api)` (old `HomeRoute.kt:227-229`, cluster notes :238).
- **KMP (current):** `coverModel` slot exists but route adapter passes only `{ it.coverUrl }`; per-source headers attach at the singleton `ImageLoader` via `CoilSourceHeaderInterceptor` instead (kmp audit :91, :50; `HomeReworkScreenRoute.kt:87`).
- **Priority:** P3
- **Acceptance criteria:** Confirm covers load correctly for all sources via the interceptor path; if any source needs per-request differentiation, thread `coverModel`. Otherwise close as an accepted architecture difference (interceptor handles headers).
- **Notes:** Per project memory, source headers are correctly handled at the ImageLoader level. Likely no functional gap — verify covers render for header-gated sources, then close.

### GAP-HOME-09 — Grid adaptive min size differs (160.dp → 120.dp)
- **Screen/surface:** Home grid layout (and reused in Search grid)
- **Type:** VISUAL
- **OLD (target):** `LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 160.dp))` (old `HomeScreen.kt:307-339`; cluster :32, :36).
- **KMP (current):** `GridCells.Adaptive(minSize = 120.dp)` (kmp `HomeScreen.kt:294`).
- **Priority:** P1
- **Acceptance criteria:** Home grid (and Search single-result grid, GAP-SRCH-01) use `Adaptive(minSize = 160.dp)` to match legacy column count/cell size, OR a deliberate decision is recorded with rationale. At 120.dp the KMP grid shows more, smaller columns than native — a visible difference.
- **Notes:** Search reuses `HomeFeedGridCard` at `Adaptive(120.dp)` too (kmp `SearchScreen.kt:267-303`), so fixing one likely needs both. Confirm desired cell height too (legacy search cell 250.dp — GAP-SRCH-02).

### GAP-HOME-10 — Grid cell uses FillBounds vs legacy 250.dp poster cell
- **Screen/surface:** Home grid card / Search grid card (shared `HomeFeedGridCard`)
- **Type:** VISUAL
- **OLD (target):** Grid cells (`SearchItems`) are `Box(height 250.dp)` with `AsyncImage(contentScale = FillBounds)` and a bottom 50.dp `Black@.3` scrim band, white 10.sp bold centered title (old `MangaSearchItems.kt:77-142`).
- **KMP (current):** `HomeFeedGridCard` uses `YamiCoverImage(scrim = true)` with bottom-centered white bold `labelSmall` title, `RoundedCornerShape(8.dp)`, elevation 2.dp (kmp `HomeFeedGridCard.kt:48-75`). No explicit 250.dp height; YamiCoverImage default content scale is likely Crop, not FillBounds.
- **Priority:** P2
- **Acceptance criteria:** Grid card visually matches legacy: ~250.dp tall poster, title on a translucent bottom band, white ~10sp bold centered, 2-line ellipsis. Decide FillBounds vs Crop (native used FillBounds/stretch for grid cells — note this distinct choice). Match scrim opacity (`Black@.3`).
- **Notes:** `YamiCoverImage` may need a `contentScale` param or a fixed-height variant. Title typography: legacy 10.sp bold white centered vs KMP `labelSmall` bottom-centered — verify alignment (centered vs start).

### GAP-HOME-11 — List row title uses Gellix font; verify KMP parity
- **Screen/surface:** Home list row (`MangaHomeItem` → `HomeFeedRow`)
- **Type:** VISUAL
- **OLD (target):** Title uses `GellixFontFamily` directly, 16.sp Bold, onBackground, maxLines 2, ellipsis, start-aligned; chapter chip labels also Gellix 10.sp (old `MangaHomeItem.kt:47,69-200,121,164`; cluster :230).
- **KMP (current):** Bold `titleSmall` 2-line title; chapter chips in `primaryContainer` (kmp `HomeFeedRow.kt:73-130`). Gellix consolidated into theme typography per memory (UP-1/UP-7) (kmp audit :87).
- **Priority:** P2
- **Acceptance criteria:** List row title renders in Gellix (via theme typography) at ~16.sp Bold, 2-line ellipsis; chapter chip labels in Gellix ~10.sp. Confirm `titleSmall` maps to Gellix 16.sp Bold or override locally.
- **Notes:** Verify the consolidated theme typography actually applies Gellix to `titleSmall`. If `titleSmall` is smaller than 16.sp, this is a visible size gap.

### GAP-HOME-12 — List row cover card elevation/shadow differs
- **Screen/surface:** Home list row
- **Type:** VISUAL
- **OLD (target):** Outer `Card` shadow elevation 8.dp rounded 8.dp, `containerColor=background`, ambient/spot shadow = `onSurface@.9`; inner cover `Card(100×130, elevation 12.dp)` (old `MangaHomeItem.kt:50-200,105`).
- **KMP (current):** `Card` elevation 4.dp, `surface`/`onSurface` colors, 100.dp cover (100f/130f aspect) (kmp `HomeFeedRow.kt:73-130`; audit :20).
- **Priority:** P3
- **Acceptance criteria:** List row card elevation/shadow matches legacy (outer ~8.dp, cover ~12.dp) or a deliberate Material3-elevation decision is recorded. Cover dimensions 100×130 already match.
- **Notes:** Minor; elevation 4 vs 8 is subtle. Custom ambient/spot shadow color (`onSurface@.9`) is hard to replicate cross-platform — acceptable to approximate.

### GAP-HOME-13 — Spacer geometry (under tabs / above first row)
- **Screen/surface:** Home body layout
- **Type:** VISUAL
- **OLD (target):** `Spacer(8.dp)` under `SourcesTabs`; in list mode `Spacer(16.dp)` above the first row (after carousel) (old `HomeScreen.kt:270-276, 397-399`).
- **KMP (current):** Spacing from `LocalSpacing` tokens (`sm`/`md`/`xs`); grid contentPadding `spacing.sm` with `spacedBy(spacing.sm)` (kmp `HomeScreen.kt:294-296`; audit :20). Exact px not confirmed equal.
- **Priority:** P3
- **Acceptance criteria:** Vertical rhythm matches legacy (~8.dp under tabs, ~16.dp above first feed row). Verify `spacing.sm`/`spacing.md` resolve to 8/16.dp.
- **Notes:** Low risk if tokens already map to 8/16. Verify token values.

### GAP-HOME-14 — Pull-refresh indicator colors not confirmed
- **Screen/surface:** Home pull-to-refresh
- **Type:** VISUAL
- **OLD (target):** `PullRefreshIndicator` `backgroundColor = inverseSurface`, `contentColor = background`, aligned TopCenter (old `HomeScreen.kt:455-461`).
- **KMP (current):** `PullToRefreshBox` (M3) wrapping the feed (kmp `HomeScreen.kt:208`); indicator colors not specified — uses M3 defaults.
- **Priority:** P3
- **Acceptance criteria:** Pull-refresh indicator matches legacy color scheme (inverseSurface bg / background content) or accepts M3 defaults with a recorded note.
- **Notes:** M3 `PullToRefreshBox` indicator styling differs from the old `rememberPullRefreshState` API; close approximation acceptable.

### GAP-HOME-15 — Initial loading spinner color (inversePrimary)
- **Screen/surface:** Home + Search loading states
- **Type:** VISUAL
- **OLD (target):** `LoadingScreen` = centered `CircularProgressIndicator(color = inversePrimary)` (old `LoadingScreen.kt:18-32`).
- **KMP (current):** Home initial load uses a raw `CircularProgressIndicator` (kmp `HomeScreen.kt:259-261`; audit :83 notes it does NOT use `YamiLoadingState`). Spinner color not specified → M3 default (primary).
- **Priority:** P3
- **Acceptance criteria:** Loading spinner uses `inversePrimary` color to match legacy, OR standardize on `YamiLoadingState` everywhere with the correct color.
- **Notes:** Home uses raw spinner + `CenteredSpinner`/`NextPageSpinner` instead of `YamiLoadingState` (kmp audit :83) — consider consolidating to the design-system component for consistency.

### GAP-HOME-16 — Empty Home feed: KMP adds an empty state (KMP-EXTRA)
- **Screen/surface:** Home feed empty state
- **Type:** KMP-EXTRA
- **OLD (target):** No dedicated empty state — `State.Success(emptyList())` renders carousel + spacer with no rows (old `HomeScreen.kt:40` flagged as INFERRED gap; cluster :248).
- **KMP (current):** `YamiEmptyState(home_empty)` shown for empty success (kmp `HomeScreen.kt:267`).
- **Priority:** P3
- **Acceptance criteria:** Keep the KMP empty state — it is a strict UX improvement that does not change native behavior for the non-empty (normal) case. Recommend KEEP. Verify it doesn't suppress the carousel when the feed is empty but popular exists (legacy still showed the carousel).
- **Notes:** Open question: if feed is empty but `popularManga` succeeds, legacy still showed the carousel. KMP may show only the empty state — confirm carousel still renders above/instead-of empty state in list mode.

### GAP-HOME-17 — Site-status screens replaced by generic empty state (rich badge UI lost)
- **Screen/surface:** Maintenance / Stopped / Adult-blocked states
- **Type:** VISUAL
- **OLD (target):** Three rich `SimpleStatusScreen`s: 120.dp circular colored `Card` (rounded 60, elevation 8) with 48.dp icon, headlineMedium Bold title, titleLarge primary site-name subtitle, bodyLarge message; distinct colors per state (Build/primary, Error/error, Error/tertiary) (old `SiteMaintenanceScreen.kt:54-129, 22-146`).
- **KMP (current):** Each site state renders `YamiEmptyState` with the per-source message + `YamiIcons.Error` glyph (kmp `HomeScreen.kt:242-276`; audit :21). Generic, single-icon, no colored circular badge, no per-state color, no site-name subtitle styling.
- **Priority:** P1
- **Acceptance criteria:** Maintenance/Stopped/Adult states render a styled status view: large circular colored icon badge (per-state icon + color — Build/Error/Error and primary/error/tertiary containers), bold title, site-name subtitle, message body. Match the three legacy variants visually.
- **Notes:** Build a `YamiSiteStatusView` (or extend `YamiStateViews`) accepting icon, container color, title, subtitle, message. Strings `home_site_maintenance/stopped/adult` exist. Currently all three look identical (same Error glyph) — clear divergence.

### GAP-HOME-18 — Lekmanga 403 token-refresh snackbar + "Open" action missing
- **Screen/surface:** Home (Lekmanga source) behavior
- **Type:** DEVIATION(platform)
- **OLD (target):** A Lekmanga-only `siteStatusFlow` 403 (probed via raw OkHttp `getSiteStatus`) triggers a snackbar with action "Open" → navigate to `Screen.WebView("https://lekmanga.net/manga/","Lekmanga")` (old `HomeRoute.kt:118-128`, `HomeViewModel.kt:83-126`).
- **KMP (current):** No Lekmanga-specific 403 probe/snackbar. `HomeViewModel` has site-state observation but no raw-OkHttp Lekmanga probe (kmp `HomeViewModel.kt:235-242`).
- **Priority:** P2
- **Acceptance criteria:** Either (a) port the Lekmanga 403 detection to a cross-platform Ktor probe with a snackbar action "Open" → WebView, or (b) fold Lekmanga 403 into the generic Cloudflare/403 recovery already present, or (c) intentionally drop with rationale. The raw `OkHttp3` probe is Android/JVM-only and must be replaced with Ktor if kept.
- **Notes:** Per project memory, broadened Cloudflare recovery + 403 handling already landed (`dae40c0`). Confirm whether generic recovery covers the Lekmanga case; if so, close as superseded. The hardcoded Lekmanga URL is a smell — prefer source-driven.

### GAP-HOME-19 — Generic 403 (Handle403Error) cross-platform parity
- **Screen/surface:** Home feed fetch behavior
- **Type:** BEHAVIOR
- **OLD (target):** `Handle403Error(state, api, baseUrl, onDismiss = getMangaHome after 1s delay)` runs when `siteState==WORKING` — Cloudflare/token-refresh WebView flow woven into Home (old `HomeRoute.kt:135-147`; cluster :244).
- **KMP (current):** Not explicitly described in the Home cluster; site-state gate + error snackbar exist (kmp `HomeScreen.kt:242-276`, `:170`). 403→WebView auto-recovery flow not confirmed in the Home rework path.
- **Priority:** P1
- **Acceptance criteria:** Home feed surfaces a 403/Cloudflare recovery path (auto-retry after WebView token refresh, or a clear recovery prompt) equivalent to legacy `Handle403Error`. Verify against the broadened Cloudflare recovery that landed project-wide.
- **Notes:** Per memory (`dae40c0`, `project_yami_details_offline_fix`), Cloudflare recovery was broadened — confirm it covers the Home feed surface specifically, not just Details. If covered, downgrade to verification-only.

### GAP-HOME-20 — Tab change resets scroll position to top
- **Screen/surface:** Home feed scroll behavior on tab switch
- **Type:** BEHAVIOR
- **OLD (target):** `LaunchedEffect(activeTabIndex)` resets both list and grid scroll to item 0 on tab change (old `HomeScreen.kt:114-120`).
- **KMP (current):** Tab tap resets feed + page cursor + re-fetches (kmp `HomeViewModel.onTabSelected:169-188`); scroll-position reset to top on tab change not explicitly confirmed in the audit.
- **Priority:** P2
- **Acceptance criteria:** Switching source tabs scrolls the feed back to the top (item 0) for both list and grid states.
- **Notes:** Verify the KMP scroll state resets on tab change; if the feed data resets but scroll index doesn't, the list may show a blank/offset position. Likely a small `LaunchedEffect(activeTabIndex){ scrollTo(0) }`.

---

## Search (SearchScreen)

### GAP-SRCH-01 — Search single-result grid min size differs (160 → 120)
- **Screen/surface:** Search single-source results grid
- **Type:** VISUAL
- **OLD (target):** `MangaSearchItems` grid = `Adaptive(minSize = 160.dp)` (old `MangaSearchItems.kt:42-75`).
- **KMP (current):** `SingleResults` = `LazyVerticalGrid Adaptive(120.dp)` of `HomeFeedGridCard` (kmp `SearchScreen.kt:267-303`).
- **Priority:** P1
- **Acceptance criteria:** Search grid uses `Adaptive(160.dp)` to match legacy column sizing. (Shared with GAP-HOME-09 — same root cause via shared `HomeFeedGridCard` usage.)
- **Notes:** Fix alongside GAP-HOME-09.

### GAP-SRCH-02 — Search grid cell height/contentScale differs
- **Screen/surface:** Search grid card (shared `HomeFeedGridCard`)
- **Type:** VISUAL
- **OLD (target):** `SearchItems` cell `Box(height 250.dp)`, `AsyncImage(FillBounds)`, bottom 50.dp `Black@.3` scrim, white 10.sp bold centered title (old `MangaSearchItems.kt:77-142`).
- **KMP (current):** `HomeFeedGridCard` (shared with Home) — `YamiCoverImage(scrim=true)`, labelSmall bottom-centered, rounded 8 elevation 2 (kmp `HomeFeedGridCard.kt:48-75`).
- **Priority:** P2
- **Acceptance criteria:** Same as GAP-HOME-10 — 250.dp poster, FillBounds, Black@.3 bottom band, white ~10sp bold centered. (Shared root cause with GAP-HOME-10.)
- **Notes:** Resolve with GAP-HOME-10 (same component).

### GAP-SRCH-03 — Tab selector is M3 TabRow vs legacy filter-chip ChipsRow
- **Screen/surface:** Search Single/Multi tab selector
- **Type:** VISUAL
- **OLD (target):** `ChipsRow(items=[Search, Multi Search])` — two-segment **filter-chip** selector, with a Done leading-icon on the selected chip (old `SearchScreen.kt:128-132`; cluster :145, :220).
- **KMP (current):** 2-tab M3 `TabRow` with default M3 indicator (kmp `SearchScreen.kt:176-187`; audit :56 notes "TabRow default M3 indicator (unlike the Home pills)").
- **Priority:** P1
- **Acceptance criteria:** Search mode selector renders as filter chips (Search / Multi Search) matching legacy `ChipsRow`, with a check/Done leading icon on the selected chip — not an M3 underline TabRow.
- **Notes:** A `ChipsRow`-equivalent may need to be added to `:ui` design system (legacy `flow_chips/ChipsRow.kt`). Keep pager swipe sync.

### GAP-SRCH-04 — Search grid banner ad dropped
- **Screen/surface:** Search single-result grid (top)
- **Type:** DEVIATION(platform)
- **OLD (target):** `MangaSearchItems` shows a fixed `BannerAdView()` above the grid (old `MangaSearchItems.kt:42-75`).
- **KMP (current):** No banner ad in `SingleResults` (kmp `SearchScreen.kt:267-303`).
- **Priority:** P3 (platform)
- **Acceptance criteria:** Same disposition as GAP-HOME-03 — intentionally drop ads (recommended) or provide a cross-platform banner abstraction with Android-only actual. Record decision.
- **Notes:** AdMob `BannerAdView`. Group with GAP-HOME-03 and GAP-MULT-02.

### GAP-SRCH-05 — Search runs per-keystroke (no debounce / submit semantics)
- **Screen/surface:** Search query input behavior
- **Type:** BEHAVIOR
- **OLD (target):** Submit-driven: typing updates `localQuery` (local state); search fires on IME `ImeAction.Search` / submit (`onSearch` → `onSearchChange(localQuery)`), then hides keyboard (old `SearchScreen.kt:95-103, 144`).
- **KMP (current):** Per-keystroke search — `OnQueryChange` triggers a search on every change, no debounce (kmp `SearchScreen.kt:164`, `SearchViewModel.kt:67-70`; audit :60, :96 mark this a deviation).
- **Priority:** P1
- **Acceptance criteria:** Match legacy submit semantics: search fires on IME Search action (and clears keyboard), OR introduce a debounce. Legacy was explicitly submit-driven — per-keystroke fan-out (esp. multi-repo) risks excess network calls. Recommend submit-on-IME to match native exactly.
- **Notes:** The query field already exists (`SearchScreen.kt:238-255`). Add `keyboardActions`/`onSearch` to fire the query and dismiss the keyboard; stop firing on every `OnQueryChange`.

### GAP-SRCH-06 — Search field styling differs (OutlinedTextField placement/borders)
- **Screen/surface:** Search top app bar
- **Type:** VISUAL
- **OLD (target):** `SearchAppBar`: `OutlinedTextField` as the title, placeholder `searching_placeholder`="Search your manga…", leading Search icon, trailing clear-X when non-blank, transparent borders/containers, rounded 12.dp, `labelLarge` text, single line, `ImeAction.Search` (old `SearchAppBar.kt:34-117`; cluster :144).
- **KMP (current):** `SearchTopBar`: `TopAppBar` with Close nav icon + inline `OutlinedTextField` + `Tune` filter action; `search_hint` placeholder, trailing X when non-empty (kmp `SearchScreen.kt:220-265`).
- **Priority:** P2
- **Acceptance criteria:** Search field matches legacy: transparent borders/container, rounded 12.dp, leading Search icon, trailing clear-X, single line, `labelLarge`, ImeAction.Search. Verify placeholder text parity (`search_hint` vs "Search your manga…").
- **Notes:** Largely aligned structurally; confirm border transparency, corner radius, leading icon, and text style. Note legacy nav icon was Close (matches KMP).

### GAP-SRCH-07 — Filter sheet: genre selection single-select (OLD) vs multi-select (KMP)
- **Screen/surface:** Search filter/sort sheet
- **Type:** BEHAVIOR
- **OLD (target):** Genre chips are **single-select** (`FilterChip` per genre, single-select, Check leading icon) (old `SearchBottomSheet.kt:112-147, 181`); sort single-select.
- **KMP (current):** Genre chips are **multi-select** (toggles a draft set) (kmp `SearchFilterSheet.kt:107-114`; audit :70, :74).
- **Priority:** P1
- **Acceptance criteria:** Match legacy single-select genre semantics, OR confirm multi-genre is a supported strict improvement that the search backend honors. If the source API only supports one genre (legacy `SearchType.GENRES` single), revert to single-select to avoid sending unsupported multi-genre queries.
- **Notes:** Open question — does `SearchSourceUseCase`/source API accept multiple genres? If not, multi-select is a behavior bug (KMP sends a query the source can't honor). If yes, mark KMP-EXTRA keep. Defaulting to parity: single-select unless verified.

### GAP-SRCH-08 — Filter sheet apply behavior: legacy fires on sort-select; KMP commits on Apply
- **Screen/surface:** Search filter/sort sheet
- **Type:** BEHAVIOR
- **OLD (target):** Selecting a sort item immediately fires re-search (`onSortSelected → onSortClick(sortType, query, genre)`); genre chip tap fires `onGenreClicked` (each interaction re-searches); Apply just dismisses (old `SearchBottomSheet.kt:154-205`, `SearchScreen.kt:202-210`).
- **KMP (current):** Draft state committed only on Apply (`onApply(draftSort, draftGenres)` re-runs search + closes); selections are drafts until Apply (kmp `SearchFilterSheet.kt:72-76, 107-114, 156-161`; audit :70).
- **Priority:** P2
- **Acceptance criteria:** Decide: match legacy "instant re-search on each selection" or keep the cleaner draft+Apply model. The draft+Apply model is arguably a UX improvement (avoids spurious searches) — if kept, mark as accepted DEVIATION; otherwise revert to instant-apply for exact parity.
- **Notes:** Recommend KEEP draft+Apply (strict improvement, fewer network calls) and record as accepted deviation — but flag for product decision.

### GAP-SRCH-09 — Search "type to search" initial hint absent (both lack it)
- **Screen/surface:** Search initial/empty state
- **Type:** VISUAL
- **OLD (target):** Initial `searchState` = `State.Success(emptyList())` → empty grid with banner ad, no explicit "type to search" hint (old `SearchScreen.kt:146` flagged INFERRED gap).
- **KMP (current):** Empty success → `YamiEmptyState(search_empty)` (kmp `SearchScreen.kt:275-302`).
- **Priority:** P3
- **Acceptance criteria:** KMP already shows an empty state where legacy showed a blank grid — a strict improvement. Keep `YamiEmptyState`. (KMP-EXTRA, recommend KEEP.) Optionally differentiate "type to search" (no query yet) from "no results" (query returned nothing).
- **Notes:** KMP-EXTRA. Minor enhancement opportunity: distinguish pre-query vs no-results copy.

---

## Multi-source search (MultiRepoResults)

### GAP-MULT-01 — Multi-repo card layout/size parity
- **Screen/surface:** Multi-source search per-section rows
- **Type:** VISUAL
- **OLD (target):** `MultiSearchItem`: `Card(140×200.dp, rounded 8, elevation 4)`, full-bleed `AsyncImage(Crop)`, **top-start** translucent (`Black@.6`, rounded bottomEnd 4) title label, bodySmall white maxLines 1 ellipsis. Section header `titleLarge` primary; `LazyColumn` vertical spacing 24.dp, padding 16.dp; per-section loading spinner in 100.dp box (old `MultiRepoResults.kt:53-192`).
- **KMP (current):** `MultiRepoCard` 140×200.dp; per-source `RepoSection` title `titleMedium`/Bold/primary; sections spaced `spacing.lg`, padded `spacing.md`; per-section Loading 100.dp box, Error inline error text, Success-empty muted "no results" (kmp `SearchScreen.kt:310-397`; audit :55-57).
- **Priority:** P2
- **Acceptance criteria:** Multi-repo card matches legacy: 140×200, rounded 8, elevation 4, Crop, **top-start** title label on `Black@.6` rounded-bottomEnd-4 band, bodySmall white 1-line. Section header `titleLarge` (KMP uses `titleMedium` — size gap). Section gap 24.dp, padding 16.dp.
- **Priority:** P2
- **Notes:** Confirm title-label position (top-start in legacy) and section header size (titleLarge vs titleMedium). KMP adds per-section empty "no results" text — that's a KMP-EXTRA improvement, keep it.

### GAP-MULT-02 — Multi-search banner ad dropped
- **Screen/surface:** Multi-source results (top)
- **Type:** DEVIATION(platform)
- **OLD (target):** Fixed `BannerAdView()` above the multi-repo `LazyColumn` (old `MultiRepoResults.kt:53-80`).
- **KMP (current):** No banner ad (kmp `SearchScreen.kt:310-397`).
- **Priority:** P3 (platform)
- **Acceptance criteria:** Same disposition as GAP-HOME-03/GAP-SRCH-04 — intentionally drop (recommended) or provide cross-platform banner abstraction with Android-only actual.
- **Notes:** Group all three ad gaps (HOME-03, SRCH-04, MULT-02) under one decision.

---

## Cross-cutting / platform / cleanup

### GAP-HOME-21 — Map Home/Search → MangaDetailsRework (verify intentional)
- **Screen/surface:** Navigation from Home/Search
- **Type:** BEHAVIOR
- **OLD (target):** Manga click → `onMangaDetailsClick` → navigate to details (legacy details) (old `HomeRoute.kt:182-184`).
- **KMP (current):** Route adapter forwards "open details" to `Screen.MangaDetailsRework` (full-tuple), NOT legacy `Screen.MangaDetails` (kmp `HomeReworkScreenRoute.kt:96-106, 122-132`; audit :13). Stale KDoc still says `Screen.MangaDetails` (`:35-36`).
- **Priority:** P3
- **Acceptance criteria:** Confirm `MangaDetailsRework` is the intended target (it is, per rework parity goal) and fix the stale KDoc at `HomeReworkScreenRoute.kt:35-36` to say `MangaDetailsRework`.
- **Notes:** Doc-only fix; behavior is correct for the rework. Low priority cleanup.

### GAP-HOME-22 — MangaHomeCard (richer source-badge row) not ported
- **Screen/surface:** Home list row variant
- **Type:** REFACTOR
- **OLD (target):** `MangaHomeCard` (source/language badge colored by `api.COLORS`, AutoSubtitleText auto-sizing title, favorite-heart, "Chapter N" chips) exists but is **NOT referenced** by the live `HomeScreen` (old `MangaHomeCard.kt:58-290`; cluster :116-121 marks it unused).
- **Priority:** P3
- **Acceptance criteria:** Since `MangaHomeCard` was dead/unused in native (live screen uses `MangaHomeItem`), KMP correctly omitting it is parity-met. No action unless the source/language badge is desired as an enhancement. Close as parity-met.
- **Notes:** The per-source color badge (`api.COLORS` + `isDark()`) was only used by the unused card — not a parity requirement. Record as intentionally not ported.

### GAP-HOME-23 — Chapter quick-jump chips: up to 3, behavior parity
- **Screen/surface:** Home list row chapter chips
- **Type:** BEHAVIOR
- **OLD (target):** Up to 3 chapter `Card` chips (rounded 6, primaryContainer, elevation 8), label = `"${chapter.number}"` Gellix 10.sp; tap → `onChapterClick(chapter, item, chapters)` (old `MangaHomeItem.kt:121-200`).
- **KMP (current):** Chapter chips (up to 3) in `primaryContainer`, tap → `OnChapterClick` → Reader (kmp `HomeFeedRow.kt:110-130`; audit :22, :26). Reader chip taps go to `Screen.ChapterImagesFragment` via by-legacy-args adapter (`HomeReworkScreenRoute.kt:134-151`).
- **Priority:** P3
- **Acceptance criteria:** Chips render up to 3, labelled with chapter number (Gellix ~10.sp), rounded 6, primaryContainer, and navigate to the reader with correct chapter+chapter-list args. Verify elevation 8 and label = number only (not "Chapter N").
- **Notes:** Structurally aligned. Verify chip label is bare number (legacy `MangaHomeItem` used `"${number}"`, not the unused `MangaHomeCard`'s "Chapter N"). Verify reader receives the full chapter list.

### GAP-HOME-24 — Save/bookmark icon: drawables vs heart icon
- **Screen/surface:** Home list row save button
- **Type:** VISUAL
- **OLD (target):** Live `MangaHomeItem` uses `R.drawable.ic_bookmark_bold` (saved) / `R.drawable.ic_bookmark` (unsaved) — **bookmark** drawables, not a heart (old `MangaHomeItem.kt:192,110`). Shows `CircularProgressIndicator(inversePrimary)` while loading (`:62-68,184-198`).
- **KMP (current):** Trailing **heart** (`FavoriteFilled`/`FavoriteOutline`) via `YamiIconButton`/`YamiIcons` (kmp `HomeFeedRow.kt:73-130`; audit :84).
- **Priority:** P1
- **Acceptance criteria:** Decide bookmark-vs-heart. Live native used **bookmark** drawables (`ic_bookmark`/`ic_bookmark_bold`); for exact parity the save toggle should be a bookmark icon, not a heart. Either port the bookmark drawables to `:ui` resources and use them, OR get a product decision to standardize on the heart (the unused `MangaHomeCard` used a heart, so there's precedent). Also restore the inline loading spinner (inversePrimary) on the save button during the save round-trip.
- **Notes:** This is a visible icon-semantics difference users would notice (heart vs bookmark). Loading spinner during save: legacy showed a spinner until `isSaved` updated; verify KMP shows in-flight feedback (reactive `savedKeys` may flip instantly, but the chapters-fetch-before-save path had a delay — see legacy `onSaveToggle` fetching chapters first).

### GAP-HOME-25 — Per-source / bookmark / edit / fallback drawables must exist as :ui resources
- **Screen/surface:** Tabs, list row, fallback covers
- **Type:** MISSING_FEATURE
- **OLD (target):** Drawables: `ic_bookmark`/`ic_bookmark_bold` (save), `ic_edit_sur` (edit tabs), `team_x` (source-icon fallback), per-source `repo.ICON` drawables (old cluster :234).
- **KMP (current):** Edit uses `YamiIcons.Edit`; save uses heart (see GAP-HOME-24); per-source tab icons unwired (GAP-HOME-04). Per-source icon assets presence in `:ui` not confirmed (kmp audit :91 — icon-key path exists but unsupplied).
- **Priority:** P1
- **Acceptance criteria:** Confirm/port the required drawables into `:ui` Compose resources: source icons (per repo), fallback source icon (team_x equivalent), bookmark icons (if GAP-HOME-24 → bookmark). Without these, tabs and the save button lose their native iconography.
- **Notes:** Blocks GAP-HOME-04 (tab icons) and GAP-HOME-24 (bookmark). Inventory which `repo.ICON` resources exist; map `SourceTab.iconKey` → resource.

### GAP-HOME-26 — Help video player (Android VideoView) not portable
- **Screen/surface:** Help video dialog
- **Type:** DEVIATION(platform)
- **OLD (target):** `HelpVideoDialog` = full-screen `Dialog` with `AndroidView { VideoView }` streaming `https://yamimanga.me/video/help_video.mp4`, MediaController, loading spinner, 15s timeout, lifecycle-aware async release (old `HelpVideoDialog.kt:50-289`).
- **KMP (current):** No help dialog wired (see GAP-HOME-01); `HomeEffect.ShowHelp` exists but never triggered (kmp `HomeEffect.kt:63-64`).
- **Priority:** P2 (platform — NOT P0 per rules)
- **Acceptance criteria:** Provide a help affordance (GAP-HOME-01 wires the trigger) backed by a cross-platform video player abstraction (expect/actual: Android `VideoView`/`ExoPlayer`, desktop/iOS native player or an in-app browser/web fallback), OR substitute by opening the help video URL in the WebView/external browser as a cross-platform fallback. Recommend: open the help URL in the in-app WebView as the cross-platform substitute (simplest, no new media stack).
- **Notes:** `android.widget.VideoView`/`MediaController` are Android-only. A WebView fallback gives parity-ish behavior on all targets without a KMP media dependency. Remote MP4 URL, not a bundled asset.

### GAP-HOME-27 — Raw OkHttp Lekmanga site probe (platform)
- **Screen/surface:** Home Lekmanga site-status behavior
- **Type:** DEVIATION(platform)
- **OLD (target):** `HomeViewModel.getSiteStatus` uses raw `OkHttp3` (`OkHttpClient`/`Request`/`Headers`) — Android/JVM only, Lekmanga-hardcoded (old cluster :242; `HomeViewModel.kt:83-126`).
- **Priority:** P3 (platform)
- **Acceptance criteria:** If the Lekmanga probe is kept (see GAP-HOME-18), reimplement with Ktor (cross-platform) instead of raw OkHttp. Otherwise drop with the GAP-HOME-18 decision.
- **Notes:** Tied to GAP-HOME-18. The KMP networking stack is Ktor; raw OkHttp would only work on Android/JVM.

### GAP-HOME-28 — Legacy debug logs / literal contentDescription / commented code (native cleanup, do NOT replicate)
- **Screen/surface:** Home route, tabs, multi-search (native source quirks)
- **Type:** REFACTOR
- **OLD (target):** Stray `Log.i` with garbage tags (old `HomeRoute.kt:84,170`, `SourcesTabs.kt:76`), literal `contentDescription="repo.API"` string bug (`SourcesTabs.kt:108`), commented-out `onRepoChange` in `MultiSearchItem` (`MultiRepoResults.kt:150-156`).
- **KMP (current):** N/A — these are native-only quirks; KMP audit does not report them.
- **Priority:** P3
- **Acceptance criteria:** Ensure KMP does NOT replicate these bugs: tab `contentDescription` must be the real API name (not literal "repo.API"), no leftover debug logs, no dead commented code. Verify `SourceTabsRow` content descriptions are meaningful.
- **Notes:** These are anti-parity items — the native quirks are bugs, not features. Goal is to not carry them over; verify KMP's tab a11y labels are correct.
