# Phase-3 REVIEW verdict — HOME&SEARCH + LIBRARY clusters

Verifier: Opus 4.8 (read-only). Method: adversarial read of the LIVE committed `:ui` / `:presentation` / route-adapter code, not commit messages. File:line anchors below are into the actual rework sources.

Status legend: CLOSED = implemented in live code · DEFERRED = intentionally not done with a legitimate documented rationale (usually DEVIATION(platform) or accepted UX deviation) · STILL-OPEN = claimed/expected done but not present, or a deferral whose justification I judge unsound.

---

## Cluster 1 — HOME & SEARCH

| GAP-ID | status | evidence (file:line) | note |
|---|---|---|---|
| GAP-HOME-01 Help affordance | CLOSED | `ui/.../home/HomeScreen.kt:332-333`; `YamiStateViews.kt:182-238` (3-action `YamiErrorState`); route `HomeReworkScreenRoute.kt:162-170` wires `onHelp`→WebView | Error state offers Retry+OpenWebView+Help; Help opens help URL in WebView (cross-platform substitute for VideoView). |
| GAP-HOME-02 Open-in-browser on error | CLOSED | `HomeScreen.kt:330-331`; `YamiStateViews.kt:222-229` | OutlinedButton "Open in WebView" present on error surface, wired to `OnOpenWebView`. |
| GAP-HOME-03 AdMob native-ad interleaving | DEFERRED | feed renders manga only `HomeScreen.kt:363-385` | DEVIATION(platform); ads intentionally dropped per gap recommendation. Legitimate. |
| GAP-HOME-04 per-source tab icons | DEFERRED (partial) | `HomeScreen.kt:244-255` supplies `iconForTab` = generic `YamiIcons.Empty` fallback at 18.dp | Slot wired + 18.dp fallback glyph rendered, BUT per-source brand drawables still absent (`:composeApp` RepoIconResolver not reachable from `:ui`). Documented sub-gap; acceptable as fallback-only. |
| GAP-HOME-05 "NEW source" badge | STILL-OPEN | `HomeScreen.kt:236` `showNewBadge = false` hardcoded; VM `HomeViewModel.kt` has no new-source signal | Badge UI exists in `SourceTabsRow.kt:137-148` but is permanently disabled; no VM flow feeds it. P2 not closed. |
| GAP-HOME-06 carousel spring scale + fling-snap | CLOSED | `FeaturedCarousel.kt:66-90` — `rememberSnapFlingBehavior` + `animateFloatAsState(spring(DampingRatioMediumBouncy))` scale | Reproduced cross-target without M3 experimental carousel. |
| GAP-HOME-07 carousel title overlay | CLOSED (parity-met) | `FeaturedCarousel.kt:104-110` cover-only + scrim | Matches OLD cover-only carousel; doc contradiction resolved. |
| GAP-HOME-08 coverModel slot | DEFERRED | route passes `{ it.coverUrl }` `HomeReworkScreenRoute.kt:91`; headers at ImageLoader interceptor | Accepted architecture diff; covers authenticate via singleton ImageLoader (per project memory). |
| GAP-HOME-09 grid min size 160dp | CLOSED | `HomeScreen.kt:366` `Adaptive(minSize = 160.dp)` | Matches legacy. |
| GAP-HOME-10 grid cell 250dp/FillBounds/scrim | CLOSED | `HomeFeedGridCard.kt:61-90` — 250.dp box, FillBounds, bottom 50.dp Black@.3 band, white 10.sp bold centered | Faithful. |
| GAP-HOME-11 Gellix list title | CLOSED | `HomeFeedRow.kt:107` bodyLarge(Gellix 16 Bold); chip `:172-175` 10.sp | Mapped to Gellix per theme. |
| GAP-HOME-12 list card elevation | CLOSED | `HomeFeedRow.kt:85` elevation 8.dp | Matches legacy ~8.dp; custom shadow tint approximated (accepted). |
| GAP-HOME-13 spacer geometry | CLOSED | `HomeScreen.kt:259` (8.dp under tabs), `:418-419` (16.dp above first row) | Token-resolved. |
| GAP-HOME-14 pull-refresh indicator colors | DEFERRED | `HomeScreen.kt:222` M3 `PullToRefreshBox` default colors | M3 default accepted (P3 visual); no explicit color override. Minor. |
| GAP-HOME-15 loading spinner inversePrimary | CLOSED | `HomeScreen.kt:320, 452` `color = inversePrimary` | Matches legacy. |
| GAP-HOME-16 empty-state keeps carousel | CLOSED | `HomeScreen.kt:339` — empty state suppressed when list-mode + featured present, falls to HomeList | Carousel still renders when feed empty but popular exists. |
| GAP-HOME-17 site-status rich screens | CLOSED | `HomeScreen.kt:280-312` 3 variants; `YamiSiteStatusView` `YamiStateViews.kt:256-319` (120dp circular Card, per-state icon+color, title/subtitle/message) | Faithful `SimpleStatusScreen` port. |
| GAP-HOME-18 Lekmanga 403 snackbar+Open | DEFERRED | `HomeViewModel.kt:235-242` generic site-state observe; no Lekmanga raw-OkHttp probe | Folded into generic site-state/Cloudflare recovery (option b); raw-OkHttp probe correctly not ported (platform). Reasonable. |
| GAP-HOME-19 generic 403 recovery | CLOSED (via OpenWebView path) | `HomeScreen.kt:330-331` error-surface OpenWebView doubles as recovery prompt | Recovery surfaced as explicit WebView action rather than auto-retry; acceptable equivalence. |
| GAP-HOME-20 tab change scroll reset | CLOSED | `HomeScreen.kt:357` (grid), `:398` (list) `LaunchedEffect(activeTabIndex){ scrollToItem(0) }` | Both list+grid reset. |
| GAP-SRCH-01 search grid 160dp | CLOSED | `SearchScreen.kt:373` `Adaptive(160.dp)` | Matches legacy. |
| GAP-SRCH-02 search cell height/scale | CLOSED | reuses `HomeFeedGridCard` (250dp/FillBounds) | Shared fix with HOME-10. |
| GAP-SRCH-03 ChipsRow vs TabRow | CLOSED | `SearchScreen.kt:233-273` `SearchModeChipsRow` = FilterChips with Check leading icon | No longer M3 underline TabRow. |
| GAP-SRCH-04 search banner ad | DEFERRED | no ad in `SingleResults` `SearchScreen.kt:367-388` | DEVIATION(platform), grouped with HOME-03. |
| GAP-SRCH-05 submit vs per-keystroke | CLOSED | `SearchScreen.kt:289-328` local query + IME `onSearch` fires + keyboard hide; VM `onQueryChange` now driven only by submit | Per-keystroke fan-out removed at UI layer. |
| GAP-SRCH-06 search field styling | CLOSED | `SearchScreen.kt:299-340` transparent borders/container, rounded-12, leading Search, trailing clear-X, labelLarge, ImeAction.Search | Faithful. |
| GAP-SRCH-07 genre single vs multi-select | CLOSED | `SearchFilterSheet.kt:77,109-120,165` draft single `draftGenre`, re-tap clears, emits `listOfNotNull(draftGenre)` | Reverted to single-select (legacy parity). |
| GAP-SRCH-08 instant vs Apply commit | DEFERRED | `SearchScreen.kt:218-221`; `SearchFilterSheet.kt:164-169` draft+Apply | Accepted UX-improvement deviation (fewer network calls), per gap recommendation. |
| GAP-SRCH-09 type-to-search hint | CLOSED (KMP-EXTRA kept) | `SearchScreen.kt:368-369,403-405` `YamiEmptyState` | Improvement kept. |
| GAP-MULT-01 multi-repo card/section | CLOSED | `SearchScreen.kt:484-526` MultiRepoCard 140×200/r8/elev4/Crop/top-start Black@.6 bottomEnd-4 label; section header titleLarge `:440`; pad 16 `:412`, gap 24 `:413` | Faithful incl. titleLarge + 24dp gap. |
| GAP-MULT-02 multi-search banner ad | DEFERRED | no ad `SearchScreen.kt:397-426` | DEVIATION(platform). |
| GAP-HOME-21 →MangaDetailsRework + KDoc | CLOSED | `HomeReworkScreenRoute.kt:96-136` routes to `MangaDetailsRework`; KDoc `:35-40` corrected to MangaDetailsRework | Intentional target + doc fixed. |
| GAP-HOME-22 MangaHomeCard not ported | CLOSED (parity-met) | live row is `HomeFeedRow` | Native MangaHomeCard was dead; correctly omitted. |
| GAP-HOME-23 chapter quick-jump chips | CLOSED | `HomeFeedRow.kt:119-124,160-180` up to 3 chips, r6, primaryContainer, bare number; reader nav `HomeReworkScreenRoute.kt:138-155` | Bare number label; reader receives chapter args. (Chip is `Surface` r6 primaryContainer; elevation-8 not replicated — cosmetic.) |
| GAP-HOME-24 bookmark vs heart + spinner | CLOSED | `HomeFeedRow.kt:126-151` `YamiIcons.Bookmark`/`BookmarkOutline` + inline spinner on `isSaving`; VM `HomeViewModel.kt:270-280` savingKeys | Bookmark semantics restored; in-flight spinner present. |
| GAP-HOME-25 :ui drawables exist | DEFERRED (partial) | bookmark/edit = Material icons; per-source brand icons absent (tied to HOME-04) | Bookmark+edit covered by Material icons; per-source brand drawables remain the open sub-gap (see HOME-04). |
| GAP-HOME-26 help video player | DEFERRED | `HomeReworkScreenRoute.kt:162-170` WebView fallback | VideoView not portable; WebView substitute is the recommended disposition. |
| GAP-HOME-27 raw OkHttp Lekmanga probe | DEFERRED | not present (tied to HOME-18) | Platform; correctly not ported. |
| GAP-HOME-28 do-not-replicate native quirks | CLOSED | tab a11y `HomeScreen.kt:230,247` real strings; no debug logs / dead code seen | Anti-parity items not carried over. |

---

## Cluster 2 — LIBRARY

| GAP-ID | status | evidence (file:line) | note |
|---|---|---|---|
| GAP-LIB-01 library-details screen | STILL-OPEN | card tap → `Screen.MangaDetailsRework` `LibraryScreenRoute.kt:189-211`; no `LibraryMangaScreen.kt`/`LibraryDetailsViewModel.kt` exist (filesystem-confirmed) | Entire per-manga chapter-management screen absent. Framed as a deliberate "unify on network Details" decision in the route KDoc, but the gap's acceptance criteria (library-scoped chapter screen reading local DB) are NOT met. P0. |
| GAP-LIB-02 per-chapter read toggle (single+bulk) | STILL-OPEN | n/a — no library-details screen | P0. Depends on LIB-01. |
| GAP-LIB-03 per-chapter download/cancel/all | STILL-OPEN | n/a | P0. Depends on LIB-01. |
| GAP-LIB-04 per-chapter bookmark + confirm | STILL-OPEN | n/a | P1. Depends on LIB-01. |
| GAP-LIB-05 delete-all-downloaded | STILL-OPEN | n/a | P1. Depends on LIB-01. |
| GAP-LIB-06 refresh-chapters + NEW badge | STILL-OPEN | n/a | P1. Depends on LIB-01. |
| GAP-LIB-07 Resume FAB | STILL-OPEN | n/a | P1. Depends on LIB-01. |
| GAP-LIB-08 chapter sort+filter | STILL-OPEN | n/a | P1. Depends on LIB-01 (distinct chapter-list enums never built). |
| GAP-LIB-09 library-details header section | STILL-OPEN | n/a | P1. Depends on LIB-01. |
| GAP-LIB-10 chapter multi-select bar | STILL-OPEN | n/a | P1. Depends on LIB-01. |
| GAP-LIB-11 403/Cloudflare on chapter actions | STILL-OPEN | n/a | P2. Depends on LIB-01. |
| GAP-LIB-12 chapter row visuals | STILL-OPEN | n/a | P2. Depends on LIB-01. |
| GAP-LIB-13 animated download indicator | DEFERRED | `LibraryScreen.kt:723-737` `DownloadProgressBadge` = static `YamiCountBadge`, tap→Downloads | DEVIATION(platform); count badge accepted substitute for Lottie (shows N). Justified. |
| GAP-LIB-14 Refresh/Random in overflow | CLOSED | `LibraryScreen.kt:495,569-596` `LibraryOverflowMenu` MoreVert DropdownMenu w/ Refresh + Open Random | Matches native overflow grouping. |
| GAP-LIB-15 per-card delete confirmation | CLOSED | `LibraryScreen.kt:311-316,417-452` `SingleDeleteDialog`; intent `OnSingleDeleteRequest`→`OnSingleDeleteConfirm` `LibraryIntent.kt:344-372` | Data-loss regression fixed; per-card delete now two-step. |
| GAP-LIB-16 items-per-row slider vs 3-step density | STILL-OPEN | `LibraryOptionsSheet.kt:161-167` only 3 `GridDensity` options (96/120/160 Adaptive); no fixed-column / Auto(0) / 1..8 slider | GridDensity kept as substitute but the gap's min-bar ("choose fixed columns 1..8 or Auto") is NOT met; no fixed-column mode exists. Power-user column count still missing. P1. |
| GAP-LIB-17 source brand badge | CLOSED | `LibraryScreen.kt:924-932,1181-1205` `LibrarySourceBadge` on-cover top-start, brand color@80%, "api - language", contrast text; helper `LibrarySourceBrand.kt` | Matches native placement + format. |
| GAP-LIB-18 4 on-cover count badges | CLOSED | `LibraryScreen.kt:937-948,1218-1260` `LibraryCardDetailBadges` List/RemoveRedEye/Download/BookmarkAdd, white, bottom, gated `showDetails` | Native iconography + placement + gating-flag restored. |
| GAP-LIB-19 card aspect/shape | CLOSED | `LibraryScreen.kt:1160-1172` `aspectRatio = 1f/1.5f`; elev 3 (UP-4, documented) | 1:1.5 pinned; elevation-3/scrim accepted design-system choice. |
| GAP-LIB-20 extra text captions (KMP-EXTRA) | DEFERRED (kept) | `LibraryScreen.kt:1065-1140` last-read/added/progress captions under `showDetails` | Kept; now co-gated with the badge set under `showDetails`. Acceptable (gap allowed keep-or-remove). |
| GAP-LIB-21 showCount header label + flag decouple | CLOSED | `LibraryScreen.kt:527-529,546-557` `LibraryItemCountCaption` gated `showCount`; per-card badges re-gated to `showDetails` `:1001,1016,1033` | Both bugs fixed: header "N items" + correct flag wiring. |
| GAP-LIB-22 dead OnToggleInLibrary + state.error | CLOSED | `LibraryIntent.kt` has no `OnToggleInLibrary` (grep-confirmed; only DetailsIntent has its own); no `val error` in LibraryState | Dead surfaces removed. |

---

## Residual open items

STILL-OPEN (genuine gaps, not closed):
- **GAP-LIB-01..12 (12 gaps, the entire per-manga library-details / chapter-management screen)** — P0/P1/P2. `LibraryMangaScreen` + `LibraryDetailsViewModel` do not exist; a card tap goes to the network Details screen. Every chapter-level affordance (read/download/bookmark toggles, refresh+NEW, Resume FAB, chapter sort/filter, header section, multi-select, chapter-row visuals, 403 recovery) is absent from the library flow. This is the single largest unaddressed divergence in either cluster. The route-KDoc "unify on Details" framing does not satisfy the acceptance criteria (library-scoped chapter list reading local DB).
- **GAP-LIB-16 items-per-row** — P1. 3-step GridDensity does not provide the fixed-column (1..8) or Auto mode the gap requires; no fixed-column layout path exists.
- **GAP-HOME-05 "NEW source" badge** — P2. `showNewBadge` hardcoded `false`; no VM new-source signal. Badge component exists but is permanently dark.

Deferrals I judge SOUND (no action): HOME-03/04(partial)/08/14/18/25(partial)/26/27, SRCH-04/08, MULT-02, LIB-13/20. All are DEVIATION(platform) ad/video/raw-OkHttp drops, ImageLoader-header architecture, accepted M3-default visuals, or accepted UX improvements — each matches the gap file's own recommended disposition.

## Cluster verdicts
- **HOME & SEARCH:** Strong parity — substantive P0/P1 functional gaps all closed; only one real residual (HOME-05 NEW badge, P2) plus sound platform deferrals. VERDICT: PASS with one minor open item.
- **LIBRARY:** Grid surface (LIB-13..22) is at parity; but the entire per-manga chapter-management screen (LIB-01..12, incl. P0s) is unbuilt and the column-count control (LIB-16) is under-delivered. VERDICT: FAIL for full native parity — the library-details screen is a major outstanding epic, deliberately deferred but not closed.
