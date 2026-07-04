## Library — gaps

22 gaps identified across the Library cluster (LibraryScreen grid, LibraryCard, top bar, options sheet, and the entirely-missing LibraryMangaScreen / library_details chapter-management screen).

---

## A. LibraryMangaScreen / library_details (per-manga chapter management) — ENTIRELY MISSING

This is the single largest divergence. The OLD app has a full per-manga chapter-list screen (`LibraryMangaScreen` + `LibraryDetailsViewModel`) reached by tapping a library card. The KMP rework has **no equivalent**: a card tap navigates to `Screen.MangaDetailsRework` (the network/details screen) instead (`kmp/library.md:11,161`; `LibraryScreenRoute.kt:189-211`). Every chapter-level affordance below is therefore absent from the library flow.

### GAP-LIB-01 — Library-details (per-manga chapter list) screen does not exist
- **Screen/surface:** LibraryMangaScreen (per-manga chapter list)
- **Type:** MISSING_FEATURE
- **OLD (target):** `LibraryMangaScreen.kt:67-472`; route `LibraryMangaRoute.kt:40-276`; VM `LibraryDetailsViewModel.kt:40-381` (old/library.md:108-153)
- **KMP (current):** No rework `:ui`/`:presentation` screen mirrors `library_details`; card tap routes to `Screen.MangaDetailsRework` (kmp/library.md:11,161; `LibraryScreenRoute.kt:189-211`)
- **Priority:** P0
- **Acceptance criteria:** Tapping a saved-manga card opens a library-scoped chapter-list screen (not the generic network Details screen) that reads chapters from the local library DB, supports the chapter operations enumerated in GAP-LIB-02..-12, and preserves the saved identity (api/language/title/url/cover/rating/genres). A behavioral test asserts navigation target is the library-details destination and that chapters load from `getChaptersByMangaId`, not a fresh network fetch.
- **Notes:** This is the parent gap; sub-gaps below decompose its surfaces. Decide first whether to build a dedicated `LibraryMangaScreen` rework or extend `MangaDetailsRework` to carry library-scoped chapter management. A dedicated screen matches native architecture and avoids overloading Details. Likely needs a new `:presentation` `LibraryDetailsViewModel` (deps `LibraryRepository`, `SourcesRepository`) and a `:ui` screen. Reuse YamiCoverImage/YamiIcons/YamiStateViews/CustomFilterBottomSheet equivalent (LibraryOptionsSheet) where possible.

### GAP-LIB-02 — Per-chapter read toggle / mark-read (single + bulk) missing
- **Screen/surface:** LibraryMangaScreen → chapter row + selection action bar
- **Type:** MISSING_FEATURE
- **OLD (target):** `LibraryChapterItem.kt:465-619` (RemoveRedEye read toggle); bulk mark-read via `ChapterSelectionActionsRow.kt:33-101`; VM single+bulk toggle/mark read (old/library.md:130,149,152)
- **KMP (current):** Not present (no library-details screen) (kmp/library.md:161)
- **Priority:** P0
- **Acceptance criteria:** A chapter row exposes a read/unread toggle (RemoveRedEye icon) that persists to the library DB; multi-select mode offers mark-all-read and mark-down-read (mark everything below) actions. Toggling read updates unread counts on the library card.
- **Notes:** Native uses `ic_done_down_arrow` for mark-all-down-read on single selection (old/library.md:160). Port that asset.

### GAP-LIB-03 — Per-chapter download / cancel / download-all / custom (selected) download missing
- **Screen/surface:** LibraryMangaScreen → chapter row + DownloadMenu + selection bar
- **Type:** MISSING_FEATURE
- **OLD (target):** `LibraryChapterItem.kt:465-619` (Download/DownloadDone, circular progress + cancel dropdown, AnimatedCompressing Lottie); `DownloadMenu.kt:14-54` (Download all / Custom download / download N selected); downloads delegated to `DownloadViewModelv2` (old/library.md:130,134,140,150)
- **KMP (current):** Not present in library flow (kmp/library.md:161)
- **Priority:** P0
- **Acceptance criteria:** Each chapter row shows a download button (idle), a progress indicator with a cancel affordance (running/queued/compressing), and a done state (downloaded, disabled). Download-all and download-selected work; downloads are gated on connectivity (no-internet → snackbar). Cancel-all stop icon appears in the top bar while downloading.
- **Notes:** Compressing state uses `AnimatedCompressing` Lottie (`filemoving.lottie`). Native shows rewarded-ad on bulk/custom download (old/library.md:150) — ad gating is Android-only; treat the ad as DEVIATION(platform) and no-op it elsewhere, but keep the download itself.

### GAP-LIB-04 — Per-chapter bookmark toggle (single + bulk) + add/remove-from-library confirm missing
- **Screen/surface:** LibraryMangaScreen → chapter row + selection bar + confirm dialogs
- **Type:** MISSING_FEATURE
- **OLD (target):** `LibraryChapterItem.kt:465-619` (BookmarkBorder/BookmarkRemove); bulk bookmark via selection bar; `ConfirmDialogClean` for remove-bookmark (`remove_bookmark_title/message`) and add-to-library (`add_library_title/message`) (`LibraryMangaScreen.kt:185-208`) (old/library.md:130,136,149)
- **KMP (current):** Not present (kmp/library.md:161)
- **Priority:** P1
- **Acceptance criteria:** Chapter row has a bookmark toggle that persists; multi-select offers bookmark-all; remove-bookmark and add-to-library actions show a confirmation dialog with the legacy titles/messages.
- **Notes:** Reuse a shared confirm-dialog component (analogous to `DeleteSelectedDialog` pattern). Reuse legacy string keys.

### GAP-LIB-05 — Delete-all-downloaded chapters (file deletion) missing
- **Screen/surface:** LibraryMangaScreen → top-bar overflow + DownloadMenu
- **Type:** MISSING_FEATURE
- **OLD (target):** overflow "Delete all downloaded chapters" (`MangaTopAppBar.kt:91-96`); VM `deleteDownloadedChapters` performs file I/O deletion (old/library.md:111,149)
- **KMP (current):** Not present (kmp/library.md:161)
- **Priority:** P1
- **Acceptance criteria:** An overflow action deletes all downloaded chapter files for the manga and updates per-chapter download state; download counts on the card reflect the change.
- **Notes:** File deletion path is platform-specific; expose via expect/actual or a platform SPI already in `:platform`.

### GAP-LIB-06 — Refresh-chapters (fetch new from source, flag isNew + NEW badge) missing
- **Screen/surface:** LibraryMangaScreen → pull-to-refresh + overflow Refresh + NEW badge
- **Type:** MISSING_FEATURE
- **OLD (target):** `refreshChapters()` fetches `fetchMangaChaptersF(url)`, updates manga metadata + cover, inserts new chapters as `isNew` (`LibraryDetailsViewModel.kt:246-302`); red "NEW" badge `Card` (`LibraryChapterItem.kt:621-640`) (old/library.md:121,132,148)
- **KMP (current):** Not present (kmp/library.md:161)
- **Priority:** P1
- **Acceptance criteria:** Pull-to-refresh (and overflow Refresh) fetches the source chapter list, inserts genuinely-new chapters flagged isNew, and renders a red "NEW" badge on those rows; opening a chapter clears its isNew flag.
- **Notes:** `new_ani.lottie` (`AnimatedNew`) referenced by native (old/library.md:161). Confirm whether the badge is the static red Card or the Lottie.

### GAP-LIB-07 — Resume FAB (first-unread / "You finished this manga") missing
- **Screen/surface:** LibraryMangaScreen → FAB
- **Type:** MISSING_FEATURE
- **OLD (target):** `AnimatedCircleExtendedFab` PlayArrow, text "Resume {chapter#}" or "You finished this manga", expand/collapse on scroll, click → first unread chapter (`LibraryMangaScreen.kt:225,153-175,234`) (old/library.md:112)
- **KMP (current):** Not present (kmp/library.md:161)
- **Priority:** P1
- **Acceptance criteria:** An extended FAB shows "Resume {n}" when an unread chapter exists (else "You finished this manga"), collapses to icon-only on scroll-down, and opens the reader at the first unread chapter on tap.
- **Notes:** Native FAB text is a hardcoded English literal (old/library.md:166); the rework should use stringResource keys (en + ar). Reuse any shared extended-FAB component if one exists.

### GAP-LIB-08 — Chapter sort + filter (chapter-list variant) missing
- **Screen/surface:** LibraryMangaScreen → CustomFilterBottomSheet (Filter+Sort) + chapter-count sort-direction button
- **Type:** MISSING_FEATURE
- **OLD (target):** Filter chips ALL/DOWNLOADED/UNREAD/READED/BOOKMARKED; Sort chips ID/NUMBER/DATE/LAST_READ_DATE + direction switch (`LibraryDetailsViewModel.kt:132-156`); "N Chapters" row with `KeyboardDoubleArrowDown/Up` toggle (`LibraryMangaScreen.kt:116`) (old/library.md:119,142-144)
- **KMP (current):** Not present (kmp/library.md:161)
- **Priority:** P1
- **Acceptance criteria:** A 2-tab options sheet (Filter + Sort) drives the chapter list; chapter filter has 5 options (incl. READED) and sort has 4 (ID/NUMBER/DATE/LAST_READ_DATE) with a direction toggle. A "N Chapters" header with a sort-direction icon button is present.
- **Notes:** These chapter-list FilterType/SortType enums are DISTINCT from the grid's enums — do NOT share (old/library.md:172). Native SortType display names are hardcoded literals "Id"/"Number"/"Date"/"Last Read Date" (old/library.md:168); rework must localize.

### GAP-LIB-09 — Library-details header section (cover/title/info/size/genres/description/actions/banner ad) missing
- **Screen/surface:** LibraryMangaScreen → LibraryHeaderSection
- **Type:** MISSING_FEATURE
- **OLD (target):** `LibraryHeaderSection.kt:31-100` (cover + title + info + total download size + banner ad + genres/description + actions row + download menu); blurred parallax cover backdrop `ImageWithGradientOverlay` 250.dp blur 14.dp (`LibraryMangaScreen.kt:253-259`); `TotalSizeDisplay.kt:35-79` (old/library.md:113,116,152,153)
- **KMP (current):** Not present (kmp/library.md:161)
- **Priority:** P1
- **Acceptance criteria:** The chapter list is headed by a section with cover, title, info row, total-download-size display, genres/description, and an actions row (download-all / bookmark-all / mark-all-read / open-in-browser). A blurred parallax cover backdrop renders behind the header.
- **Notes:** Banner/rewarded ads are Android-only → DEVIATION(platform): omit the banner ad slot elsewhere. Total-size display requires file-size I/O on a background dispatcher.

### GAP-LIB-10 — Multi-select mode for chapters (long-press → selection action bar) missing
- **Screen/surface:** LibraryMangaScreen → ChapterSelectionActionsRow
- **Type:** MISSING_FEATURE
- **OLD (target):** long-press enters multi-select (`showChaptersCheckBox`, `LibraryMangaScreen.kt:344-346`); `ChapterSelectionActionsRow.kt:33-101` (download/bookmark/mark-read/delete/mark-down-read/cancel); BackHandler exits selection first (`:178-184`) (old/library.md:129,134)
- **KMP (current):** Not present (kmp/library.md:161)
- **Priority:** P1
- **Acceptance criteria:** Long-pressing a chapter enters multi-select; an action bar offers download / bookmark / mark-read / delete / mark-down-read / cancel for the selected set; system back exits selection mode before leaving the screen.
- **Notes:** Mirror the grid's selection model pattern already proven in the rework LibraryScreen (kmp/library.md:169).

### GAP-LIB-11 — 403 / Cloudflare WebView recovery + no-internet snackbars on chapter actions missing
- **Screen/surface:** LibraryMangaScreen → WebView dialog + snackbars
- **Type:** MISSING_FEATURE
- **OLD (target):** site-status check (raw OkHttp GET with source headers), 403 → WebView dialog (`LibraryDetailsViewModel.kt:196-239`; `LibraryMangaRoute.kt:86-98`); no-internet snackbar on download attempt (`:128-138`) (old/library.md:125,147)
- **KMP (current):** Not present in library flow (kmp/library.md:161)
- **Priority:** P2
- **Acceptance criteria:** When the source returns 403 on a reachability check, a WebView recovery dialog opens (reuse the existing rework WebView component); download attempts without connectivity show a no-internet snackbar.
- **Notes:** Reachability + WebView recovery already exist in the rework Details flow per project memory; reuse rather than reimplement.

### GAP-LIB-12 — Chapter row visuals (Card shadow / date + file-size captions) missing
- **Screen/surface:** LibraryMangaScreen → LibraryChapterItem
- **Type:** VISUAL
- **OLD (target):** chapter rows are `Card` with custom shadow (4.dp, onSurface 90%), rounded 8.dp, container `background`; date + file-size captions (`LibraryChapterItem.kt:322-643`) (old/library.md:121,152)
- **KMP (current):** Not present (kmp/library.md:161)
- **Priority:** P2
- **Acceptance criteria:** Chapter rows match native: rounded 8.dp card with subtle shadow, chapter title/number, chapter date caption, and downloaded file-size caption when downloaded.
- **Notes:** Depends on GAP-LIB-01. Use shared spacing tokens.

---

## B. LibraryScreen (grid) — divergences

### GAP-LIB-13 — Animated download indicator (Lottie) replaced by static count badge
- **Screen/surface:** LibraryScreen → top bar download action
- **Type:** DEVIATION(platform)
- **OLD (target):** `AnimatedPreloader` Lottie loop, shown only when `isDownloading`, tappable → Downloads (`LibraryScreen.kt:143-150`; `AnimatedPreloader.kt:20-57`; raw `download_anim.lottie`) (old/library.md:14,60)
- **KMP (current):** `DownloadProgressBadge` = static `YamiCountBadge(Download, count, primary)`, hidden when count ≤ 0, tap → Downloads (`LibraryScreen.kt:409-412,575-589`) (kmp/library.md:107-112)
- **Priority:** P2
- **Acceptance criteria:** The download indicator conveys active-download state and navigates to Downloads. A count badge is acceptable on non-Android; on Android, ideally restore an animated indicator for parity, otherwise document the count badge as the cross-platform substitute.
- **Notes:** Lottie is Android-centric; the count badge is arguably an improvement (shows N). Recommend KEEP the badge but consider an animated tint/pulse for parity. Rationale: avoids a platform-specific Lottie dependency in commonMain.

### GAP-LIB-14 — Refresh & Random are TextButtons, not overflow menu items
- **Screen/surface:** LibraryScreen → top bar
- **Type:** DEVIATION(platform)
- **OLD (target):** overflow `MoreVert` `DropdownMenu` with "Refresh" (`dropdown_button_refresh`) and "Open Random Manga" (`dropdown_button_open_random_manga`) (`LibraryScreen.kt:157-171`) (old/library.md:14)
- **KMP (current):** Random + Refresh are plain `TextButton`s directly in the top bar; no overflow menu (`LibraryScreen.kt:413-418`; kmp/library.md:50)
- **Priority:** P2
- **Acceptance criteria:** Refresh and Open-Random are reachable from the Library top bar. Either restore the `MoreVert` overflow with both items (closer to native) or keep inline buttons if they fit; if kept inline, ensure no top-bar crowding on small widths.
- **Notes:** Native groups them under overflow to keep the bar uncluttered. Low-risk either way; flag as deviation. Random uses `library_random` (rework) vs `dropdown_button_open_random_manga` (native) — reconcile string key.

### GAP-LIB-15 — Per-card delete has no confirmation dialog in rework (single-step)
- **Screen/surface:** LibraryCard action row → delete
- **Type:** BEHAVIOR
- **OLD (target):** card delete sets `mangaToDelete`/`showDeleteDialog` → route-level delete-confirmation `AlertDialog` with long warning about permanent deletion of progress/read-status/bookmarks/downloads; confirm "Delete" → `removeManga` (`LibraryRoute.kt:94-154`; old/library.md:38,99)
- **KMP (current):** card delete dispatches `OnSingleDelete` directly → bulk-remove with 1-element list, **no confirm dialog** (`LibraryScreen.kt:1058-1064`; VM:599-603; kmp/library.md:61,169)
- **Priority:** P1
- **Acceptance criteria:** Tapping a card's delete button shows a confirmation dialog (matching native's warning text about permanent loss) before removing; only on confirm is the manga removed. Bulk delete already confirms; per-card should too.
- **Notes:** The rework's single-step delete is destructive without confirmation — a data-loss parity regression. Reuse `DeleteSelectedDialog` shape with a single-item variant + the legacy `delete_manga` / `are_you_sure...` strings.

### GAP-LIB-16 — Items-per-row slider (0=Auto..8) replaced by 3-step GridDensity
- **Screen/surface:** LibraryOptionsSheet → Display tab
- **Type:** DEVIATION(platform)
- **OLD (target):** `Slider` items-per-row range 0..8 (7 steps; 0="Auto"), "Items per row:" label + "N item(s)"/"Auto" caption (`DisplayOptionsSection.kt:23-70`; old/library.md:44,74); `itemsPerRow==0` → `GridCells.Adaptive`, >0 → fixed columns (old/library.md:175)
- **KMP (current):** 3-value `GridDensity` (COMPACT 96 / COMFORTABLE 120 / SPACIOUS 160 dp) feeding `GridCells.Adaptive` only; no fixed-column mode, no Auto/0 (`LibraryScreen.kt:507-511,697`; kmp/library.md:151) 
- **Priority:** P1
- **Acceptance criteria:** Users can choose a fixed number of columns (1..8) or an Auto/adaptive mode, matching native granularity. If GridDensity is kept as the substitute, it must at minimum cover the practical range and the choice persists.
- **Notes:** GridDensity loses the fixed-column and explicit-count affordance. Decide: (a) restore the slider (full parity, persists `itemsPerRow`, 0→Adaptive), or (b) justify GridDensity as a deliberate UX simplification (DEVIATION). Recommend restoring the slider for parity since column count is a power-user preference users will notice missing.

### GAP-LIB-17 — Source badge: rework shows raw api text, native shows branded "api - language" badge
- **Screen/surface:** LibraryCard → source badge
- **Type:** VISUAL
- **OLD (target):** top-start `Card` rounded 4.dp, container = source brand color (`api.COLORS`) at 80% alpha, text "api - language" (`source_badge_format`) via `AutoSubtitleText` 8sp Bold, black/white per `bgColor.isDark()` (`MangaCard.kt:94,110-113,172-193`; old/library.md:86)
- **KMP (current):** plain `manga.api` text caption, `labelSmall`, gated `showSource && api.isNotBlank()`, no brand color, no language, no badge container (`LibraryScreen.kt:863-872`; kmp/library.md:75)
- **Priority:** P1
- **Acceptance criteria:** Source badge renders as a small colored chip overlaid top-start on the cover, using the source brand color at ~80% alpha, showing "api - language", with contrast-aware text color. Matches native placement and `source_badge_format`.
- **Notes:** Needs an `api.COLORS` brand-color mapping in the rework (from `sources_repositry.data`). The rework places it as a below-cover caption, not an on-cover badge — both placement and styling diverge.

### GAP-LIB-18 — Card count badges: native overlays 4 icon+count badges on cover; rework shows separate below-cover badges with different gating/icons
- **Screen/surface:** LibraryCard → detail badges
- **Type:** VISUAL
- **OLD (target):** when `showDetails`, a bottom Row of 4 `IconWithCount` over the cover gradient: List→totalChapters, RemoveRedEye→readCount, Download→downloadedCount, BookmarkAdd→bookmarkedCount, all white, adaptive (`MangaCard.kt:286-330,92-96`; old/library.md:91-96)
- **KMP (current):** badges live in/under the title row, gated on `showCount` (not `showDetails`): downloaded-check (tertiary, no count), downloaded-count (tertiary), bookmark-count (secondary); plus a separate unread-count number (primary). No total-chapters badge, no read-count badge, different colors, different gating flag (`LibraryScreen.kt:803-852`; kmp/library.md:72-74)
- **Priority:** P1
- **Acceptance criteria:** Under the native `showDetails` flag, the card shows the 4 count badges (total chapters / read / downloaded / bookmarked) as white icon+count overlays at the bottom of the cover, matching native iconography and placement.
- **Notes:** The rework conflates `showCount`/`showDetails` semantics (see GAP-LIB-21) and drops the totalChapters + read-count badges, adds an unread number native lacks. Reconcile badge set, icons, colors, gating flag, and placement. Native icons: `Outlined.List`, `Outlined.RemoveRedEye`, `Outlined.Download`, `Outlined.BookmarkAdd`.

### GAP-LIB-19 — Card cover aspect/shape divergence (native fixed 1:1.5 AsyncImage with color painters)
- **Screen/surface:** LibraryCard → cover
- **Type:** VISUAL
- **OLD (target):** `Card` rounded 8.dp elevation 2.dp, `aspectRatio 1f/1.5f`, `widthIn 120.dp`; cover `AsyncImage` Crop with placeholder `onSurface 12%` / error `error 24%` ColorPainters (`MangaCard.kt:118-127,87-93,146-158`; old/library.md:84-85)
- **KMP (current):** `Card` rounded 8.dp elevation 3.dp (UP-4 lift), containerColor surfaceVariant/primaryContainer-when-selected; cover via `YamiCoverImage(scrim=true)`, broken-image glyph on error, no spinner (`LibraryScreen.kt:729-736,770,987-995`; kmp/library.md:58,65)
- **Priority:** P2
- **Acceptance criteria:** Card cover keeps the 1:1.5 portrait aspect ratio; elevation/placeholder/error styling are reconciled to native (or the rework's elevation 3.dp + YamiCoverImage scrim is accepted as a deliberate design-system choice and documented).
- **Notes:** Mostly minor; elevation 3 vs 2 and the selection-tinted container are intentional rework design-system choices (UP-4). Verify the aspect ratio is preserved in `LibraryCardCover`. The native source badge sits ON the cover (see GAP-LIB-17), which the rework's layout must accommodate.

### GAP-LIB-20 — Card adds last-read / added-at / chapter-progress text captions (KMP extra vs native)
- **Screen/surface:** LibraryCard → captions
- **Type:** KMP-EXTRA
- **OLD (target):** native card shows no last-read/added/progress text captions — detail data is conveyed only via the 4 icon badges (old/library.md:91-96, 103)
- **KMP (current):** under `showDetails`, three text captions: last-read (`library_card_last_read` + relative), added-at (`library_card_added` + relative), chapter-progress (`library_card_chapters_progress(read,total)`) (`LibraryScreen.kt:892-967`; kmp/library.md:76-79)
- **Priority:** P3
- **Acceptance criteria:** Decide keep-vs-remove. If kept, ensure they don't crowd the card or duplicate the (to-be-restored) badge data; if native parity is strict, these become redundant with GAP-LIB-18 badges and should be removed.
- **Notes:** Recommend REMOVE or make mutually exclusive with the native badge set to avoid double-presenting the same metrics. These are a rework addition with no native counterpart.

---

## C. LibraryOptionsSheet & semantics — minor divergences

### GAP-LIB-21 — `showCount` semantics mismatch (native = "N items" header label; rework = card badge gate)
- **Screen/surface:** LibraryScreen header row + LibraryOptionsSheet Display tab
- **Type:** BEHAVIOR
- **OLD (target):** "Show Items Count" toggle gates a header "N items" count caption (`items_count` plural) in the last-updated/count Row (`LibraryItems.kt:161-165`; old/library.md:20,44,67); doc'd in rework `LibraryDisplay.kt:17`
- **KMP (current):** `showCount` instead gates the per-card downloaded/bookmark badges; the documented "N items" header label is **not implemented** (kmp/library.md:36,166)
- **Priority:** P2
- **Acceptance criteria:** Restore the "N items" count caption in the header row, gated by `showCount`, matching native; decouple per-card badge gating to the correct flag (native badges are gated by `showDetails`, see GAP-LIB-18).
- **Notes:** Two bugs in one: missing header count label + wrong flag wiring for card badges. Fixing GAP-LIB-18 and this together aligns flag semantics with native (`showCount`→header count, `showDetails`→card detail badges).

### GAP-LIB-22 — `OnToggleInLibrary` intent + `state.error` field present but unwired (dead surfaces)
- **Screen/surface:** LibraryScreen (VM/state plumbing)
- **Type:** REFACTOR
- **OLD (target):** n/a — native has no in-grid library-membership toggle; error surfaced only as snackbar/centered snackbar (old/library.md:28)
- **KMP (current):** `LibraryIntent.OnToggleInLibrary` handled in VM (`VM:350-354`) but **no `:ui` affordance dispatches it**; `state.error` written by `startObserving().catch` (`VM:298`) but **never rendered** (kmp/library.md:36,164-166)
- **Priority:** P3
- **Acceptance criteria:** Remove the dead `OnToggleInLibrary` intent + handler and the unread `state.error` field (or wire `state.error` to a visible surface if intended). No behavior change for users; reduces confusion.
- **Notes:** Pure cleanup; no native parity impact. The error snackbar (`ShowError` effect) is the live error surface and matches native, so `state.error` is genuinely dead.

---

## Cross-cutting notes
- **Distinct enums:** the grid (LibraryViewModel: 6 filters / 6 sorts incl. RANDOM) and the chapter list (LibraryDetailsViewModel: 5 filters incl. READED / 4 sorts) use DIFFERENT enums — keep them separate when building GAP-LIB-08 (old/library.md:172).
- **Hardcoded-string parity hazards to fix when porting:** MangaCard action `contentDescription`s, FAB "Resume {n}"/"You finished this manga", `SortType.getDisplayName` literals, the inconsistent "Cancel chapter download" literal vs `cancel_chapter_download`, and the `onRefreshLibrary` Toast literal (old/library.md:164-170). New rework surfaces must use `stringResource`.
- **Ads (banner + rewarded):** Android-only; treat as DEVIATION(platform) — omit/no-op on other platforms, do not block the underlying download/chapter features.
- **`MangaTopAppBar` Share action is inert in native** (old/library.md:183) — when porting the library-details top bar, either implement Share properly or replicate the inert behavior; do not treat its absence as a gap.
- **`DownloadProgressDialog` double-nested-button bug** exists in old code but the component appears unused by this cluster (old/library.md:182) — do not port the bug.
