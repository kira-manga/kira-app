# UI & Feature Gaps — KMP rework vs OLD native (parity backlog)

_Phase 1 synthesis. Source: audit_workspace/gaps/*.md (per-cluster, each diffing the 1:1-aligned
old/kmp audit pair). The KMP app must match the OLD native app EXACTLY; every entry traces to a
real old-vs-new comparison with file:line citations in the per-cluster source files._

## How to read this
Each gap: **Type** (VISUAL | MISSING_FEATURE | BEHAVIOR | REFACTOR | KMP-EXTRA | DEVIATION(platform)),
**Priority** (P0 blocker → P3 polish), **Acceptance criteria** (definition of done). Implementation
(Phase 2) proceeds in priority order, in small gated batches, reusing the `:ui` design system.

## Priority rubric
- **P0** — core feature missing/broken, or a major visual break a user immediately notices.
- **P1** — noticeable missing feature or clear visual divergence users would notice.
- **P2** — minor visual/behavior polish.
- **P3** — nice-to-have / internal refactor / cleanup.

## Totals (by structured Priority field)
- **P0 = 10 · P1 = 71 · P2 = 92 · P3 = 69** (~242 gaps across 8 clusters)

## ⚠ P0 verification results (parent, against live App.kt — 2026-05-31)
Three P0s **CLOSED as false positives** (source agents didn't read `App.kt`):
- **GAP-SET-02 — CLOSED.** Settings reachable: bottom-nav Settings → `Screen.Setting` (`App.kt:510` → `SettingsRoute` → rework SettingsScreen); `BottomNavigationBar.kt:118`.
- **GAP-THM-01 — CLOSED.** Live onboarding `Screen.Theme` (`App.kt:408` → `ThemeSelectionScreenRoute` → rework `ThemeScreen`) wires onContinue→Sources + permission gating. Dead code seen was only the debug `ThemeReworkScreenRoute`.
- **GAP-THM-02 — CLOSED.** Same route wires auto-request + Grant + app-settings + permission toast (`ThemeSelectionScreenRoute.kt:40-69`).

**Real remaining P0 set (7):** GAP-LIB-01/02/03, GAP-SET-11, GAP-SRC-01, GAP-CMP-01, GAP-WN-01.

## P0 index (blockers — do first; ⚠ = verify against live App.kt/nav before acting)
1. **GAP-LIB-01** — Library-details (per-manga chapter list) screen does not exist *(pulls in LIB-02/03)*
2. **GAP-LIB-02** — Per-chapter read toggle / mark-read (single + bulk) missing
3. **GAP-LIB-03** — Per-chapter download / cancel / download-all / custom-selected download missing
4. **GAP-SET-02** ⚠ — Settings hub reportedly not wired to a user entry *(CONFLICTS with nav audit: Settings is a bottom-nav tab → VERIFY first; likely already reachable)*
5. **GAP-SET-11** — Feedback dialog 5-char UI gate vs 8-char use-case requirement (silent submit failures)
6. **GAP-SRC-01** — Sources request dialog min-length UI gate (5) vs use-case requirement (8)
7. **GAP-THM-01** ⚠ — Theme-picker onboarding affordances are dead code on the live route *(verify whether onboarding route is user-reachable)*
8. **GAP-THM-02** — Notification permission flow missing (auto-request/Grant/toast/app-settings/Continue gate)
9. **GAP-CMP-01** — Pinned FAQ complaints missing from user complaint list
10. **GAP-WN-01** — WhatsNew feature media (images / carousels / video) entirely missing

## Cross-cutting decisions to apply consistently (Phase 2)
- **Icon-vs-text buttons:** several clusters flag text buttons where native used icons (Downloads rows,
  History/Updates/Statistics back, reader). Apply one ruling: match native (icons) using `YamiIcons`.
- **Localized date/month labels under RTL:** History/Updates/Details use English month abbreviations —
  localize consistently.
- **KMP-EXTRAs to KEEP** (strict improvements, don't break native behavior): mutation snackbars,
  WhatsNew NewChip, complaint subject-edit fix, M3 About, read-dimming, per-source-header ImageLoader.
- **KMP-EXTRAs to REMOVE / fix stale docs:** admin bulk-select (never landed; scrub KDoc).
- **DECIDE items (recommendation in-entry):** Welcome Lottie-vs-gradient, admin per-row-vs-menu actions,
  cluster card style (r12/surfaceVariant vs native r16/elevated).
- **DEVIATION(platform) — intentionally dropped / substituted, NOT P0:** AdMob ads, Android VideoView
  help dialog, raw-OkHttp site probe, telephoto zoom, FCM/in-app-update/review onCreate side-effects,
  some Android-WebView internals (render-crash recovery, file-access flags). Each documented in-entry.

---

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


---

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


---

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


---

## Details & Reader — gaps

27 gaps: 6 P0, 9 P1, 8 P2, 4 P3. Grouped by screen (Details first, then Reader). Each entry cites OLD (target) and KMP (current) file:line.

---

# DETAILS

### GAP-DET-01 — Adult-gate meme monetization steps (MStep1/MStep2) not ported
- **Screen/surface:** Manga Details — adult-content gate
- **Type:** MISSING_FEATURE
- **OLD (target):** `MangaDetailsScreen.kt:63-128` (state machine `AdultWarning → MStep1 → MStep2 → None`), `MConfirmationDialog.kt:25-92` (two meme-image steps from `imgs1`/`imgs2`)
- **KMP (current):** `DetailsScreen.kt:353-364,958-1002` — single `AdultConfirmationDialog`; "Continue" is a plain dismiss+unblur, no MStep1/MStep2 (kmp notes line 245)
- **Priority:** P2
- **Acceptance criteria:** Decide policy. If parity required: adult confirm advances through 2 meme-image steps before unblur, each step Continue/Close, any dismiss pops back. If intentionally dropped: record as accepted DEVIATION.
- **Notes:** The MStep flow is described in OLD as a monetization meme gate. Likely intentional drop for KMP (no AdMob), but the single-dialog gate still protects content, so P2 not P0. Open question: product decision — keep simple single gate or restore 2-step.

### GAP-DET-02 — No `ic_pluss18` red 18+ imagery in adult dialog
- **Screen/surface:** Manga Details — AdultConfirmationDialog
- **Type:** VISUAL
- **OLD (target):** `AdultConfirmationDialog.kt:28-103` — header red 20.sp Bold (`Color.Red.copy(0.8f)`), `ic_pluss18` icon 120.dp red tint .65
- **KMP (current):** `DetailsScreen.kt:966-968` — no icon (`:ui` lacks `:composeApp` resources)
- **Priority:** P2
- **Acceptance criteria:** Adult dialog shows an 18+ red-tinted icon + red header to match legacy severity styling, or a `:ui`-owned vector substitute is added.
- **Notes:** `:ui` design-system needs its own 18+ vector asset (YamiIcons). Cosmetic; the dialog still gates.

### GAP-DET-03 — Per-chapter download action removed from chapter row
- **Screen/surface:** Manga Details — chapter row
- **Type:** MISSING_FEATURE
- **OLD (target):** `ChapterItem.kt:87-94` — trailing download `IconButton` per chapter; `Icons.Default.Download`/`DownloadDone`, tint primary when downloaded; if saved → `onDownloadClick()` else `onRequestAddBookmark()`
- **KMP (current):** `DetailsScreen.kt:888-897` — only a passive 8dp primary dot when downloaded; no tappable per-chapter download (kmp inventory line 111 "Absent vs legacy: per-chapter download")
- **Priority:** P1
- **Acceptance criteria:** Each chapter row has a tappable download affordance: queues that chapter when in-library, prompts add-to-library otherwise; shows downloaded state (done icon/filled dot). Behavior matches legacy gating.
- **Notes:** Reuse the `EnqueueAllChaptersDownloadUseCase` family for a single chapter; add per-chapter enqueue use case if absent. Shared :ui ChapterRow trailing slot.

### GAP-DET-04 — No per-chapter mark-read / mark-unread action
- **Screen/surface:** Manga Details — chapter row
- **Type:** MISSING_FEATURE
- **OLD (target):** OLD `ChapterItem.kt:63` explicitly has NO read/unread visual or action — so this is NOT a legacy feature.
- **KMP (current):** KMP also has read-dim only (`DetailsScreen.kt:871-875`), no toggle.
- **Priority:** P3
- **Acceptance criteria:** N/A for parity — legacy had no per-chapter read toggle. Only implement if product explicitly wants it as an enhancement.
- **Notes:** KMP-EXTRA candidate, not a parity gap. KMP already dims read chapters (an improvement over legacy which had none). Keep KMP read-dim; do not add toggle for parity. Listed for completeness because task called out "read/unread per chapter".

### GAP-DET-05 — Read-chapter dimming is a KMP enhancement (legacy had none)
- **Screen/surface:** Manga Details — chapter row
- **Type:** KMP-EXTRA
- **OLD (target):** `ChapterItem.kt:63-64` — "no read/unread dimming"
- **KMP (current):** `DetailsScreen.kt:871-875` — read chapters dimmed onSurfaceVariant, unread onSurface
- **Priority:** P3
- **Acceptance criteria:** Keep. Better UX than legacy and consistent with reader's mark-read tracking.
- **Notes:** KEEP. No action needed beyond confirming it doesn't conflict with parity expectations.

### GAP-DET-06 — Chapter row visual: elevated rounded Card vs flat row
- **Screen/surface:** Manga Details — chapter row
- **Type:** VISUAL
- **OLD (target):** `ChapterItem.kt:46-72` — each chapter is an elevated `Card` (shadow 4.dp, RoundedCornerShape 8.dp, ambient/spot onSurface .9, h16/v8 padding), bold number + relative date
- **KMP (current):** `DetailsScreen.kt:511-542,871-897` — `ChapterRow` items in plain LazyColumn (no per-row card/elevation noted)
- **Priority:** P2
- **Acceptance criteria:** Chapter rows render as elevated rounded cards matching legacy (8dp corners, subtle shadow, bold chapter number, dimmed relative-date subtitle), OR an explicit design-system decision documents the flatter rework styling.
- **Notes:** Verify current ChapterRow styling against legacy card. Shared :ui ChapterRow component. Could be intentional rework restyle — confirm with design.

### GAP-DET-07 — Cover size/shape divergence (200×250 8dp vs 96dp aspect-0.7 6dp)
- **Screen/surface:** Manga Details — header cover
- **Type:** VISUAL
- **OLD (target):** `HeaderSection.kt:70-78` — cover `AsyncImage` 200×250.dp, RoundedCornerShape 8.dp, ContentScale.Crop, centered column layout
- **KMP (current):** `DetailsScreen.kt:549-668,787` — `DetailsCover` 96dp aspect 0.7, RoundedCornerShape(6.dp), in a Row beside metadata column
- **Priority:** P1
- **Acceptance criteria:** Confirm intended header layout. If parity: large centered cover (≈200×250) with 8dp corners. If rework redesign (side-by-side cover+metadata): document as accepted DEVIATION with rationale.
- **Notes:** This is a whole-header layout change (legacy = centered stacked column; rework = Row cover+metadata). Likely an intentional rework redesign — flag for explicit sign-off rather than blind revert. The blurred parallax backdrop exists in both (`DetailsContent.kt:118-123` vs `DetailsScreen.kt:682-709`).

### GAP-DET-08 — Subtitle format `"api language - status"` vs split metadata lines
- **Screen/surface:** Manga Details — header metadata
- **Type:** VISUAL
- **OLD (target):** `HeaderSection.kt:97-102` — single subtitle `"${api} ${language} - ${status}"` bodyMedium onSurface .7
- **KMP (current):** `DetailsScreen.kt:549-668` — separate lines: title / author / status (labelLarge primary) / `api · language` source line / `★ rating`
- **Priority:** P2
- **Acceptance criteria:** Decide on metadata presentation. KMP adds author + rating (richer). Confirm acceptable; ensure api/language/status all still present.
- **Notes:** KMP is a superset (adds author + rating). Likely keep KMP. P2 polish — just verify no legacy field dropped.

### GAP-DET-09 — Header action button Row removed (bookmark/schedule/download-all/open-browser as 4 SpaceEvenly buttons)
- **Screen/surface:** Manga Details — header actions
- **Type:** REFACTOR
- **OLD (target):** `HeaderSection.kt:109-145` — action Row SpaceEvenly with 4 weighted buttons: Bookmark, Schedule (last-update chip, inert), Download-all, Open-in-browser
- **KMP (current):** actions split between top bar (bookmark heart, Downloads, Open-in-WebView — `DetailsScreen.kt:285-322`) and header (Download-all OutlinedButton in-library only `DetailsScreen.kt:655-666`; Schedule AssistChip `DetailsScreen.kt:638-639`)
- **Priority:** P2
- **Acceptance criteria:** All 4 legacy affordances reachable: bookmark, last-update/schedule indicator, download-all, open-in-browser. Verify none lost; layout location (top bar vs header) is a rework choice.
- **Notes:** All four functions appear present, relocated. REFACTOR not MISSING. Confirm "Downloads" top-bar button (→ DownloadsRework) vs legacy "Download all" (enqueue all) are both available — KMP has BOTH download-all (header) and Downloads-screen (top bar), which is a superset. OK.

### GAP-DET-10 — Genre overflow: "+N more" expand chip vs hard cap at 8
- **Screen/surface:** Manga Details — genres
- **Type:** BEHAVIOR
- **OLD (target):** `GenresAndDescriptionSection.kt:50-65,80-97` — collapsed shows 4 genres + `MoreGenresChip("+N more")`; tap expands to full FlowRow
- **KMP (current):** `DetailsScreen.kt:847,901` — `MAX_GENRE_CHIPS = 8` AssistChips, non-interactive, no "+N more", no expand
- **Priority:** P1
- **Acceptance criteria:** When genres exceed the collapsed cap, show a "+N more" affordance that expands to reveal all genres on tap. Collapsed default; expandable.
- **Notes:** Legacy collapsed cap = 4 with overflow chip; KMP shows up to 8 then silently truncates beyond. Add expand state + overflow chip to GenreChipRow. Shared :ui component.

### GAP-DET-11 — Expandable description with gradient fade + expand/collapse icon missing
- **Screen/surface:** Manga Details — description
- **Type:** MISSING_FEATURE
- **OLD (target):** `GenresAndDescriptionSection.kt:124-149` (CollapsedDescription: maxLines=4, ellipsis, vertical gradient fade Transparent→background, ExpandMore IconButton) + `:68-77` (ExpandedDescription: full text, ExpandLess, animateContentSize)
- **KMP (current):** `DetailsScreen.kt:511-544` — `"description"` item is a plain `bodyMedium` Text, no collapse/expand, no gradient fade
- **Priority:** P1
- **Acceptance criteria:** Description collapses to ~4 lines with a gradient fade-out and an expand chevron; tapping expands to full text with animateContentSize and a collapse chevron.
- **Notes:** Reuse engawapg-free Compose `animateContentSize`. Shared :ui ExpandableDescription component (pair with GAP-DET-10 genre expand, legacy had them in one section).

### GAP-DET-12 — Title long-press copy: missing "title copied" toast/feedback
- **Screen/surface:** Manga Details — title
- **Type:** BEHAVIOR
- **OLD (target):** `HeaderSection.kt:86-95` — long-press copies title to clipboard AND shows toast `R.string.title_copied`
- **KMP (current):** `DetailsScreen.kt:583-586` — long-press copies title via `LocalClipboardManager`, no confirmation feedback noted
- **Priority:** P2
- **Acceptance criteria:** Long-press title copies to clipboard and shows a confirmation (snackbar/toast equivalent) using the legacy `title_copied` string.
- **Notes:** KMP has a snackbar host already (`DetailsScreen.kt:322`) — emit a "title copied" snackbar. Localize via stringResource.

### GAP-DET-13 — "Download all" label is a hardcoded English literal
- **Screen/surface:** Manga Details — header download-all button
- **Type:** BEHAVIOR
- **OLD (target):** `HeaderSection.kt:132-138` — uses `R.string.action_download_all` (localized)
- **KMP (current):** `DetailsScreen.kt:664` — hardcoded English `"Download all"` (kmp notes line 240)
- **Priority:** P1
- **Acceptance criteria:** Button label uses `stringResource` (reuse legacy `action_download_all` key + verbatim Arabic).
- **Notes:** Follows UP-3 localization playbook. Same fix-pass as GAP-DET-14.

### GAP-DET-14 — Last-chapter-date / schedule labels are English-only inline literals
- **Screen/surface:** Manga Details — schedule/last-update chip
- **Type:** BEHAVIOR
- **OLD (target):** `HeaderSection.kt:118-131` — localized `action_no_chapter_yet`/`action_today`/`action_yesterday`/`day_since_format`
- **KMP (current):** `DetailsScreen.kt:718-727` — inline English "Today"/"Yesterday"/"N days ago"/"No chapter yet" (kmp notes line 240)
- **Priority:** P1
- **Acceptance criteria:** All four date states use `stringResource` with legacy keys + Arabic translations; pluralization/format matches `day_since_format`.
- **Notes:** UP-3 playbook. Reuse legacy keys verbatim.

### GAP-DET-15 — No sort dropdown / filter bottom sheet on Details
- **Screen/surface:** Manga Details — chapter list controls
- **Type:** MISSING_FEATURE
- **OLD (target):** OLD details has NO sort/filter UI — chapters render in `manga.chapters` order (`details_reader.md:209` explicitly: "no chapter sort/filter UI exists in the OLD details screen ... the filter/sort sheets belong to Home/Search, not Details")
- **KMP (current):** `DetailsScreen.kt:84` "No sort/filter bottom sheet"; kmp notes line 234 lists this as a gap vs a *retired :composeApp legacy tree*, NOT the OLD native app
- **Priority:** P3
- **Acceptance criteria:** N/A for OLD-native parity — the OLD native Details screen has no sort/filter. Do not implement for parity.
- **Notes:** IMPORTANT: the KMP audit's "sort/filter gap" references the retired intermediate `:composeApp` legacy tree, which is NOT the parity target. The task brief's mention of "sort/filter sheets on Details" does not match the OLD native source. Confirm with product before building; default = drop.

### GAP-DET-16 — Banner ad in header (legacy) — drop on KMP
- **Screen/surface:** Manga Details — header
- **Type:** DEVIATION(platform)
- **OLD (target):** `HeaderSection.kt:104` — `BannerAdView()` (AdMob)
- **KMP (current):** absent
- **Priority:** P3
- **Acceptance criteria:** Accepted drop — AdMob is Android-only and KMP rework drops ads (memory: "KMP rework typically drops ads").
- **Notes:** DEVIATION, no substitute. Documented, no action.

### GAP-DET-17 — Firebase `manga_open` analytics event not ported
- **Screen/surface:** Manga Details — analytics
- **Type:** DEVIATION(platform)
- **OLD (target):** `MangaDetailsScreen.kt:66-73` — Firebase logs `manga_open` in LaunchedEffect(title)
- **KMP (current):** no analytics noted in `DetailsViewModel`
- **Priority:** P3
- **Acceptance criteria:** Either wire a multiplatform analytics SPI for `manga_open`, or accept drop if analytics is out of scope for rework.
- **Notes:** Firebase is Android-only; needs an expect/actual analytics facade if kept. Likely deferred. Open question: is analytics in rework scope?

### GAP-DET-18 — Help video dialog (HelpVideoDialog) not ported
- **Screen/surface:** Manga Details — error/help affordance
- **Type:** MISSING_FEATURE
- **OLD (target):** `MangaDetailsRoute.kt:113-117` — `HelpVideoDialog` toggled by `onHelp`; error pane offers help (`MangaDetailsScreen.kt:15` lists error pane help button)
- **KMP (current):** `DetailsScreen.kt:449-478` `DetailsErrorPane` has message + Open-in-WebView + Retry only; no Help affordance
- **Priority:** P2
- **Acceptance criteria:** Error pane offers a Help action that opens the help video/guide, OR documented as intentional drop.
- **Notes:** Verify whether help video is still desired. Low-traffic affordance. Open question.

---

# READER

### GAP-RDR-01 — 403/Cloudflare recovery asymmetry: Reader only treats 403, Details treats {403,429,503,520-524}
- **Screen/surface:** Reader — challenge recovery
- **Type:** BEHAVIOR
- **OLD (target):** OLD Reader uses `errorCode==403` for in-reader WebView recovery (`ReaderScreen.kt:188-205`, `ReaderViewModel.kt:232-263`); OLD Details retries after 1000ms, Reader reloads after 500ms (`details_reader.md:207`). Legacy reader is 403-only too.
- **KMP (current):** Reader treats only `statusCode == 403` (`ReaderViewModel.kt:534`); Details broadens to {403,429,503,520-524} (`DetailsViewModel.kt:120`). A 503 on a reader page falls to a generic snackbar instead of auto-WebView (kmp notes line 250)
- **Priority:** P1
- **Acceptance criteria:** Reader page-fetch challenge detection covers the same status set as Details ({403,429,503,520-524}) so Cloudflare 503/429 on a page auto-routes to WebView + auto-retry, matching Details.
- **Notes:** Task flagged "403 recovery asymmetry" specifically. Legacy was 403-only on BOTH surfaces, so broadening the reader is technically beyond legacy — but it aligns Reader with the already-broadened KMP Details and fixes a real Cloudflare gap. Recommend broadening Reader. Single-line change to the challenge-status predicate in `ReaderViewModel`.

### GAP-RDR-02 — Reading-mode picker: DropdownMenu vs full ReadingModeDialog (chips + Apply/Revert)
- **Screen/surface:** Reader — reading-mode selection
- **Type:** VISUAL
- **OLD (target):** `ReadingModeDialog.kt:42-155` — `Dialog`+`Surface` (surfaceContainerHigh, 16dp, tonalElevation 8dp), header, scrollable `FilterChip` grid (`ReadingModeChips.kt:29-101`, icon+title, primary selected), Divider, footer Revert (OutlinedButton) + Apply (Button w/ check icon)
- **KMP (current):** `ReaderScreen.kt:701-742` — plain `DropdownMenu` anchored on overflow icon, 6 entries with checkmark, immediate apply (no Revert/Apply staging)
- **Priority:** P1
- **Acceptance criteria:** Reader mode picker presents chips (icon + localized title) with a selected highlight and an Apply/Revert footer (staged selection), matching legacy dialog. OR documented design decision to keep lightweight dropdown.
- **Notes:** Legacy uses staged selection (select → Apply); KMP applies immediately. Shared :ui ReadingModePicker. The 6 modes + icons are covered; this is presentation parity. Confirm whether instant-apply dropdown is an acceptable rework simplification (P1 because it's a noticeable UX divergence).

### GAP-RDR-03 — All 6 reading modes present and correctly mapped (parity confirmed)
- **Screen/surface:** Reader — reading modes
- **Type:** BEHAVIOR
- **OLD (target):** `ReaderScreen.kt:372-525` + `isPaged.kt:6-12`: DEFAULT/VERTICAL=VerticalPager, LTR/RTL=HorizontalPager(reverse for RTL), WEBTOON=gapless LazyColumn, CONTINUOUS_VERTICAL=LazyColumn
- **KMP (current):** `ReaderScreen.kt:899-963` — identical mapping: RTL/LTR HorizontalPager(reverse=RTL), DEFAULT/VERTICAL VerticalPager, WEBTOON/CONTINUOUS_VERTICAL free-scroll LazyColumn
- **Priority:** P3
- **Acceptance criteria:** No gap — all 6 modes render with the same pager/list split and reverseLayout for RTL. Confirmed.
- **Notes:** PARITY OK. One nuance: legacy WEBTOON is *gapless* (keyed LazyColumn, FillWidth) while CONTINUOUS_VERTICAL allows item spacing; KMP collapses both to one free-scroll LazyColumn painting bg for gapless. See GAP-RDR-04 for the gap nuance.

### GAP-RDR-04 — WEBTOON vs CONTINUOUS_VERTICAL collapsed to identical layout (legacy differentiated them)
- **Screen/surface:** Reader — webtoon/continuous modes
- **Type:** BEHAVIOR
- **OLD (target):** WEBTOON = explicitly gapless `WebToonReader` (`WebToonReadingMode.kt:65-435`, FillWidth, no gaps); CONTINUOUS_VERTICAL = separate `ContinuousVerticalReader` (`ContinuousVerticalReadingMode.kt:54-386`) — two distinct composables
- **KMP (current):** `ReaderScreen.kt:911-961` — BOTH WEBTOON and CONTINUOUS_VERTICAL dispatch to the same `ReaderVerticalList` (free-scroll LazyColumn)
- **Priority:** P2
- **Acceptance criteria:** Confirm webtoon renders truly gapless (no inter-page spacing) and continuous-vertical matches its legacy spacing, OR document that one unified gapless list is acceptable for both.
- **Notes:** KMP paints theme bg behind items for gapless webtoon (`ReaderScreen.kt:1042`). If continuous-vertical legacy had visible gaps, the two now look identical. Verify visually. Likely acceptable simplification — P2.

### GAP-RDR-05 — Next-chapter overlay card (full-screen "going to next chapter") missing
- **Screen/surface:** Reader — chapter boundary
- **Type:** MISSING_FEATURE
- **OLD (target):** `NextChapterCard.kt:24-78` — full-screen Box (surface .95), shows `you_are_in` + current chapter + `going_to` + next chapter (titleLarge primary/secondary), tap → onGoToNext; spinner while loading. Rendered as a pager page / list item at chapter end (`VerticalReadingMode.kt:45-85`, etc.)
- **KMP (current):** Multi-chapter is VM-internal in-place navigation (`ReaderViewModel.kt:422-446`); Next/Prev via auto-advance + buttons. No next-chapter transition card noted in `ReaderScreen.kt:899-1203`
- **Priority:** P1
- **Acceptance criteria:** On reaching the last page, a transition affordance shows current→next chapter and advances on tap (or auto-advances), with a loading state, matching legacy NextChapterCard.
- **Notes:** KMP auto-advances on last page (`ReaderViewModel.kt:433-436`) which covers the *function*, but the visible "you are in X, going to Y" transition card is absent. Confirm whether the silent auto-advance is acceptable UX or the card is required. Shared :ui component if restored. P1 (noticeable affordance).

### GAP-RDR-06 — Terminal "No Next Chapter" / fetch-error full-screen errorCard missing
- **Screen/surface:** Reader — last chapter boundary
- **Type:** MISSING_FEATURE
- **OLD (target):** `errorCard.kt:21-60` — full-screen surface .95, "You are in" + chapter number (primary) + error message (error color); used for terminal "No Next Chapter" + fetch errors
- **KMP (current):** Chapter-level error → `ReaderErrorPane` (message + Retry) (`ReaderScreen.kt:495-499,1367-1387`); Next disabled at list end (`ReaderScreen.kt` inventory line 198). No dedicated "no next chapter" terminal card
- **Priority:** P2
- **Acceptance criteria:** At the last chapter, reaching the end shows a clear terminal indicator (e.g., "You're on the last chapter"); fetch errors at boundary are surfaced. Match legacy intent.
- **Notes:** KMP disables Next button at the end (functionally prevents over-scroll) but lacks the explicit terminal card. P2 polish.

### GAP-RDR-07 — Reader page scrubber: simple Slider vs legacy 3-card seekbar with per-page prev/next
- **Screen/surface:** Reader — bottom chrome scrubber
- **Type:** VISUAL
- **OLD (target):** `SeekBarContainer.kt:26-132` — Row of 3 rounded Cards (bg .8): left next-page IconButton (`ic_previous`, fires onNext), middle current-page text + Slider(0..total-1, steps) + total text, right prev-page IconButton (`ic_next`, fires onPrevious)
- **KMP (current):** `ReaderScreen.kt:542-575,831-853` — HUD pill ("X / Y") + jump-to-page `Slider` only (`ReaderPageScrubber`, >1 page). No discrete per-page prev/next buttons flanking the slider
- **Priority:** P2
- **Acceptance criteria:** Scrubber shows current/total page numbers and offers discrete previous-page / next-page step buttons flanking the slider, matching legacy 3-card layout (preserve callback wiring, not literal swapped labels).
- **Notes:** Legacy has the famous label/icon inversion (`details_reader.md:202`) — carry intent not labels. KMP has slider drag (covers seek) but no single-step buttons. P2. Shared :ui scrubber component.

### GAP-RDR-08 — Banner ad below reader (legacy) — drop on KMP
- **Screen/surface:** Reader — bottom
- **Type:** DEVIATION(platform)
- **OLD (target):** `ReaderScreen.kt:617` — `BannerAdView()` below reader Box
- **KMP (current):** absent
- **Priority:** P3
- **Acceptance criteria:** Accepted drop — AdMob Android-only; rework drops ads.
- **Notes:** DEVIATION, no substitute. No action.

### GAP-RDR-09 — Reader share content-description hardcoded English "Share"
- **Screen/surface:** Reader — top bar share action
- **Type:** BEHAVIOR
- **OLD (target):** legacy uses string resources / contentDescriptions (`details_reader.md:200` lists CDs as a localization concern)
- **KMP (current):** `ReaderScreen.kt:697` — hardcoded literal `"Share"` content-description (kmp notes line 241)
- **Priority:** P2
- **Acceptance criteria:** Share (and other reader icon) content-descriptions use `stringResource`.
- **Notes:** UP-3 localization pass. Sweep reader contentDescriptions while here.

### GAP-RDR-10 — Auto-hide chrome after 3s timeout not confirmed in KMP
- **Screen/surface:** Reader — chrome HUD
- **Type:** BEHAVIOR
- **OLD (target):** `ReaderScreen.kt:345-350` — controls auto-hide after 3s (`LaunchedEffect(showControls){ delay(3000); showControls=false }`)
- **KMP (current):** `ReaderScreen.kt:433-437` — chrome visibility driven by `state.isUiVisible` toggled on tap; no 3s auto-hide timer noted in audit
- **Priority:** P2
- **Acceptance criteria:** After showing chrome, it auto-hides after ~3s of inactivity (matching legacy), in addition to tap-toggle.
- **Notes:** Verify whether `isUiVisible` has an auto-hide timer; audit didn't mention one. If missing, add a LaunchedEffect(isUiVisible) delay. P2.

### GAP-RDR-11 — Reader top-bar overflow menu (dots) — legacy inert, KMP repurposed as mode picker
- **Screen/surface:** Reader — top bar overflow
- **Type:** BEHAVIOR
- **OLD (target):** `ControlOverlay.kt:64` — top-bar menu (dots) is a no-op/inert
- **KMP (current):** `ReaderScreen.kt:701-742` — overflow icon opens the reading-mode dropdown
- **Priority:** P3
- **Acceptance criteria:** No gap — KMP gives the previously-inert dots button a useful function (mode picker). Acceptable improvement.
- **Notes:** KMP-EXTRA / improvement. KEEP. Note: legacy opened mode picker from the bottom BottomActionBar settings button (`ic_reader_setting`, `ControlOverlay.kt:158-196`); KMP moved it to the top overflow. Verify the bottom settings entry isn't expected — see GAP-RDR-12.

### GAP-RDR-12 — Legacy BottomActionBar (settings/bookmark/share triad) restructured
- **Screen/surface:** Reader — bottom action bar
- **Type:** REFACTOR
- **OLD (target):** `ControlOverlay.kt:158-196` — 3 weighted IconButtons at bottom: Settings (`ic_reader_setting` → mode dialog), Bookmark, Share
- **KMP (current):** bookmark + share live in top bar (`ReaderScreen.kt:683-697`); mode picker in top overflow (`:701-742`); bottom chrome = HUD pill + scrubber only (`:542-575`)
- **Priority:** P2
- **Acceptance criteria:** All three actions (mode/settings, bookmark, share) remain reachable from reader chrome. Location (top vs bottom) is a rework choice; verify none lost.
- **Notes:** All three present, relocated to top bar/overflow. REFACTOR not MISSING. Confirm bookmark + share + mode all accessible — audit confirms they are. OK.

### GAP-RDR-13 — Large-image (>99MB) compression handling — verify KMP parity
- **Screen/surface:** Reader — per-page image
- **Type:** BEHAVIOR
- **OLD (target):** `WebToonReadingMode.kt:296-355`, `PagerImageItem.kt:49-141`, `ReaderViewModel.kt:580-698` — images >99MB → `CompressedImageHandler`, reserve aspect-ratio height, `compressImageToSizeOptimized` to screenWidthPx, `notification_compressing_images` placeholder
- **KMP (current):** Not mentioned in KMP audit; KMP uses per-platform `ReaderDecoderHints.kt` decode hints (`ReaderScreen.kt` citation line 211) + placeholder reserves `screenHeightDb` (`ReaderScreen.kt:1296`)
- **Priority:** P1
- **Acceptance criteria:** Oversized images don't OOM/fail; either decoder-hint downsampling (KMP approach) demonstrably handles >99MB pages, or explicit compression path exists. Verify on a known oversized page.
- **Notes:** KMP's per-platform decode hints (memory: HighQualitySkiaImageDecoder + RGB_565/buildImageRequest on Android) may already subsample large images, making explicit compression unnecessary. Needs verification — P1 because OOM on a huge page is a hard failure. Open question: does KMP have an equivalent to compressImageToSizeOptimized?

### GAP-RDR-14 — OOM fallback (drawFallbackOnOOM tap-to-open-webview) — verify KMP equivalent
- **Screen/surface:** Reader — per-page OOM
- **Type:** BEHAVIOR
- **OLD (target):** `drawFallbackOnOOM.kt:34-144` — Modifier catches draw OOM, draws broken-image icon + tappable `image_too_large_tap_here_to_open_the_chapter_in_webview` text → onOpenInWebView
- **KMP (current):** per-page error → `failed_to_load_image` + Retry + Open-in-WebView (`ReaderScreen.kt:1306-1363`); no draw-time OOM catch noted
- **Priority:** P2
- **Acceptance criteria:** A page that fails to render (OOM/too-large) shows a recoverable fallback with an open-in-WebView affordance, matching legacy intent.
- **Notes:** KMP's per-page error pane covers fetch errors but `drawFallbackOnOOM` catches *render-time* OOM specifically (Android Canvas). On iOS/Desktop (Skia) this is less of an issue. P2 — Android-specific render OOM may need an actual. Open question.

### GAP-RDR-15 — ProManga (PROCHAN) streaming incremental loader — verify KMP parity
- **Screen/surface:** Reader — streaming source
- **Type:** BEHAVIOR
- **OLD (target):** `ReaderViewModel.kt:130-271,277-468` — `loadChapterStreaming` for `MangaSource.PROCHAN.API` (incremental URL adds as pages stream in)
- **KMP (current):** `FetchChapterPagesUseCase` is "streaming-aware; replaces page list on each Success" (`ReaderViewModel.kt:353-405`)
- **Priority:** P2
- **Acceptance criteria:** ProManga/streaming sources progressively reveal pages as they load (not all-or-nothing). Verify behavior on a PROCHAN chapter.
- **Notes:** KMP appears to have streaming support generically. Confirm PROCHAN-specific incremental behavior matches. P2.

### GAP-RDR-16 — Legacy-args reader path shows chapter NUMBER as title instead of name
- **Screen/surface:** Reader — top-bar title (Home/History/Updates row entry)
- **Type:** BEHAVIOR
- **OLD (target):** legacy reader top bar shows manga title + chapter number (`ControlOverlay.kt` inventory `details_reader.md:147`) — number is expected in legacy top bar
- **KMP (current):** `ChapterImagesByLegacyArgsReworkScreenRoute.kt:118-125` sets `chapter.name = args.chapterNumber` so list-row entry shows the number, while Details entry shows the real name (`MangaDetailsReworkScreenRoute.kt:122`) — asymmetric between the two reader entry paths (kmp notes line 29-36, 230-233)
- **Priority:** P2
- **Acceptance criteria:** Reader top-bar title is consistent regardless of entry path — both show the same chapter label (name or number, pick one and apply to both adapters).
- **Notes:** Pass the real chapter name into the legacy-args adapter, or accept number in both. Minor cosmetic but it's an internal inconsistency. P2.

### GAP-RDR-17 — Hardcoded English reader strings (errorCard "You are in", "Compressing image...", mode dialog header)
- **Screen/surface:** Reader — various
- **Type:** BEHAVIOR
- **OLD (target):** legacy itself has these hardcoded (`details_reader.md:200`): errorCard "You are in", "Compressing image..." (`PagerImageItem.kt:214`, `ContinuousVerticalReadingMode.kt:380`), ReadingModeDialog header "Reading mode", Toast "You should add the manga to Library first"
- **KMP (current):** Reader Share CD hardcoded (GAP-RDR-09); other reader strings not individually audited in KMP
- **Priority:** P3
- **Acceptance criteria:** Reader user-facing strings use `stringResource`; where legacy was also hardcoded, KMP should localize (improvement). Sweep mode-picker header, any "compressing"/"you are in" text, library-required message.
- **Notes:** Legacy was also non-localized here, so this is parity+improvement, not a regression. Bundle with GAP-RDR-09 in one UP-3 localization pass. P3.

---

## Cross-cutting / accepted deviations
- **Ads (GAP-DET-16, GAP-RDR-08):** AdMob dropped on KMP — accepted DEVIATION(platform), no substitute.
- **Analytics (GAP-DET-17):** Firebase manga_open — needs multiplatform SPI or accepted drop.
- **Zoom library:** OLD live modes use engawapg `zoomableWithScroll`; KMP uses engawapg `.zoomable` on all 3 layouts (`ReaderScreen.kt:1032,1120,1185`) — same library family, parity OK (the orphaned telephoto `ZoomableImage.kt` was correctly not ported).
- **Sort/filter on Details (GAP-DET-15):** the KMP audit's "missing sort/filter" references the retired `:composeApp` intermediate tree, NOT the OLD native app, which had none. Not an OLD-native parity gap — confirm before building.
- **Localization sweep:** GAP-DET-13, GAP-DET-14, GAP-RDR-09, GAP-RDR-17 are one UP-3 localization pass (reuse legacy keys + verbatim Arabic).


---

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


---

## History, Updates & Statistics — gaps

24 gaps identified (History 7, Updates 10, Statistics 5, cross-cluster 2): 3 P0, 9 P1, 8 P2, 4 P3.

---

## History

### GAP-HIST-01 — Cover thumbnail loads without `buildImageRequest` parity
- **Screen/surface:** HistoryScreen — per-row cover thumbnail
- **Type:** BEHAVIOR
- **OLD (target):** Cover `AsyncImage` model comes from `buildImageRequest(context, item.mangaImageUrl, item.api)` and an explicit `imageLoader = getImageLoader()` (old `HistoryItem.kt:67-85`; VM exposes `buildImageRequest` passed down via `HistoryRoute.kt:16-37`).
- **KMP (current):** Plain `AsyncImage(model = url)` on the singleton ImageLoader, no per-request builder (`HistoryScreen.kt:271-284`; cluster note `kmp:77`).
- **Priority:** P1
- **Acceptance criteria:** History cover requests carry the same per-source headers/referer/size/decoder configuration as the native `buildImageRequest`. For a source requiring referer headers (e.g. Azora-type), the History thumbnail loads where it currently would 403/fail. If the singleton ImageLoader's interceptor already injects per-`api` headers, document that equivalence; otherwise add a request builder.
- **Notes:** Directly tied to MEMORY notes on `buildImageRequest` / OkHttp fetcher / header interceptor parity. Verify whether the singleton's header interceptor keys off the request URL host or needs the `api` hint that native passed explicitly. Same concern as GAP-UPD-01.

### GAP-HIST-02 — Cover dimensions changed 80×120 → 72×108
- **Screen/surface:** HistoryScreen — cover card
- **Type:** VISUAL
- **OLD (target):** Cover card 80×120 dp with `shapes.small` (old `HistoryItem.kt:67-85`, visual `old:17`).
- **KMP (current):** Cover 72×108 dp with `RoundedCornerShape(6.dp)` clip + `background.copy(alpha=0.15f)` tint (`HistoryScreen.kt:270-284`).
- **Priority:** P2
- **Acceptance criteria:** History cover renders at 80×120 dp to match native (or this delta is explicitly accepted as the rework design-system sizing). Corner radius matches the design-system `shapes.small` equivalent.
- **Notes:** Same 72×108 idiom is shared with Updates (GAP-UPD-02); fix both together if reverting to 80×120.

### GAP-HIST-03 — Card container color/shape divergence
- **Screen/surface:** HistoryScreen — row card
- **Type:** VISUAL
- **OLD (target):** Card container color `surface.copy(alpha=0.12f)`, default M3 card corner, no faint cover bg tint (old `HistoryItem.kt:52-60`, `old:17`).
- **KMP (current):** Card `containerColor = surfaceVariant`, `RoundedCornerShape(12.dp)`, cover gets a `background.copy(alpha=0.15f)` tint behind the image (`HistoryScreen.kt:254-282`).
- **Priority:** P2
- **Acceptance criteria:** Row card background and corner match native (`surface @ 0.12` alpha, default M3 corner) or the surfaceVariant choice is accepted as the unified design-system token. Cover background tint matches native (native has none).
- **Notes:** Cosmetic; resolve alongside GAP-HIST-02 as one visual-parity pass on `HistoryRow`.

### GAP-HIST-04 — Delete/Clear-all are text buttons instead of icons
- **Screen/surface:** HistoryScreen — top-bar action + per-row delete
- **Type:** DEVIATION(intentional design-system)
- **OLD (target):** Top-bar `DeleteForever` `IconButton` tinted `error`; per-row outlined `Delete` `IconButton` tinted `onBackground` (old `HistoryScreen.kt:55-61`, `HistoryItem.kt:114-120`).
- **KMP (current):** Top-bar "Clear all" `TextButton` (error-colored) and per-row "Delete" `TextButton` (error-colored); `:ui` omits `materialIconsExtended` (`HistoryScreen.kt:181-186,313-315`; cluster note `kmp:69`).
- **Priority:** P2
- **Acceptance criteria:** Decide cluster-wide whether icon affordances are restored. The shared design system already exposes `YamiIcons`, so an icon-based affordance is feasible without `materialIconsExtended`. If text buttons are the accepted rework posture, mark this resolved-by-design; otherwise restore icon buttons (DeleteForever / Delete) for native parity.
- **Notes:** Spans all three screens (also GAP-UPD-09, GAP-STAT-04). Single product decision should cover the whole cluster; `YamiIcons` is the shared path.

### GAP-HIST-05 — Per-row delete tint changed (onBackground → error)
- **Screen/surface:** HistoryScreen — per-row delete affordance
- **Type:** VISUAL
- **OLD (target):** Per-row delete icon tinted `colorScheme.onBackground` (only the top-bar clear-all is `error`) (old `HistoryItem.kt:114-120`, `old:17`).
- **KMP (current):** Per-row "Delete" colored `colorScheme.error` (same as Clear-all) (`HistoryScreen.kt:314`).
- **Priority:** P3
- **Acceptance criteria:** Per-row delete uses `onBackground` tint matching native, with only the clear-all action carrying the destructive `error` color — OR the unified-error coloring is accepted as intentional.
- **Notes:** Minor; bundle with GAP-HIST-04 affordance decision.

### GAP-HIST-06 — History now has loading + empty UI (native rendered neither)
- **Screen/surface:** HistoryScreen — loading & empty states
- **Type:** KMP-EXTRA
- **OLD (target):** `isLoading`/`error` exist in state but the screen never reads them; empty list renders blank background; no spinner, no empty illustration (old `HistoryScreen.kt`, `old:19-21`).
- **KMP (current):** loading → shared `YamiLoadingState` spinner; empty → shared `YamiEmptyState(no_reading_history)` illustration (`HistoryScreen.kt:198-199`; `YamiStateViews.kt:48-112`).
- **Priority:** P3
- **Acceptance criteria:** Confirm the added loading/empty states are an accepted intentional improvement (rework tasks #239/#288/#357 indicate History was reworked). No action unless strict pixel-parity with the blank native screen is required.
- **Notes:** Improvement, not a regression. Document as accepted KMP-EXTRA. Aligns shared-component reuse (see GAP-UPD-08).

### GAP-HIST-07 — Absolute-date group header is English-only
- **Screen/surface:** HistoryScreen — sticky date-group header (>6 days old)
- **Type:** BEHAVIOR
- **OLD (target):** `formatGroupLabel` returns `"MMM d, yyyy"` for entries older than 6 days using the platform date formatter (old `HistoryScreen.kt:107-128`); on Android this respects locale.
- **KMP (current):** `monthAbbrev` returns hard-coded English "Jan".."Dec"; only relative keys (today/yesterday/N-ago) are localized (`HistoryScreen.kt:332-376`; cluster note `kmp:73`).
- **Priority:** P2
- **Acceptance criteria:** Group headers for entries >6 days old render localized month names (Arabic under RTL), matching native locale behavior, or use `kotlinx-datetime` + localized resources rather than a hard-coded English array.
- **Notes:** Same defect in Updates (GAP-UPD-10). Both lists copy-paste `monthAbbrev`; fixing the shared helper resolves both (see GAP-X-01 dedup).

---

## Updates

### GAP-UPD-01 — Thumbnail request parity (and native used RAW url; KMP uses singleton)
- **Screen/surface:** UpdatesScreen — per-row thumbnail
- **Type:** BEHAVIOR
- **OLD (target):** Native fed the RAW `notification.mangaImageUrl` straight into `AsyncImage` with NO `buildImageRequest` (old `UpdateItem.kt:61-71`; explicitly flagged as a divergence/quality risk `old:98`).
- **KMP (current):** Plain `AsyncImage(model = url)` on the singleton ImageLoader (AVIF/OkHttp/header-interceptor/Skia) (`UpdatesScreen.kt:367-380`; `kmp:77`).
- **Priority:** P1
- **Acceptance criteria:** Updates thumbnails load reliably for header/referer-gated sources. The KMP singleton path is arguably BETTER than native's raw-url path; confirm the singleton's header interceptor covers the `api`/source that native omitted. If parity-with-native means "raw url" the KMP behavior should still be kept (it fixes a latent native bug) and documented as an intentional improvement.
- **Notes:** Native's raw-url approach was itself a likely bug. Treat KMP singleton as the desired target but verify header coverage. Linked to GAP-HIST-01.

### GAP-UPD-02 — Thumbnail size changed 50×50 → 72×108
- **Screen/surface:** UpdatesScreen — per-row thumbnail
- **Type:** VISUAL
- **OLD (target):** 50×50 dp `AsyncImage`, `clip(RoundedCornerShape(8.dp))`, bg `surfaceVariant` (old `UpdateItem.kt:61-71`, `old:37`).
- **KMP (current):** 72×108 dp cover with 6dp clip + alpha-0.15 tint — same idiom as History (`UpdatesScreen.kt:367-380`).
- **Priority:** P1
- **Acceptance criteria:** Updates thumbnail renders at native's 50×50 square with 8dp corners — OR the 72×108 portrait cover is an explicitly accepted rework redesign of the Updates row. This is a noticeable layout change (square badge vs full portrait cover), so a deliberate decision is required.
- **Notes:** The native Updates row is visually compact (square thumb + dot + chapter#); the KMP row mirrors the History card. Confirm intended design.

### GAP-UPD-03 — Per-row download spinner (queued/running states) missing
- **Screen/surface:** UpdatesScreen — per-row download affordance
- **Type:** MISSING_FEATURE
- **OLD (target):** Trailing affordance has THREE states: 24dp `CircularProgressIndicator` (strokeWidth 2dp; `primary` tint when this is the running chapter, `onPrimary` when merely queued) while downloading, else a 48dp download `IconButton` (`Download` idle / `DownloadDone` when downloaded). Derived from `downloadViewModel.queuedChapterIds` + `runningChapter` injected via the route (old `UpdateItem.kt:113-137`, `NotificationsRoute.kt:31-32`, `old:36,97`).
- **KMP (current):** Single "Download"/"Downloaded" `TextButton`, disabled when downloaded; enqueue-only via `EnqueueDownloadUseCase`. No queued/running progress indicator (`UpdatesScreen.kt:417-428`; feature inventory `kmp:41` notes it was DEFERRED then replaced by enqueue-only; `UpdatesReworkScreenRoute.kt:53-87`).
- **Priority:** P1
- **Acceptance criteria:** Each row reflects live download progress: idle → download affordance; queued → spinner; running → distinct (primary-tinted) spinner; done → "Downloaded"/done affordance. Wire the download-queue/running state into `UpdatesState`/row so progress is observable, matching native.
- **Notes:** Requires surfacing download-manager queued/running state into the presentation layer (rework slices #299/#300). Largest functional Updates gap.

### GAP-UPD-04 — Interstitial-ad gate on download not invoked
- **Screen/surface:** UpdatesScreen — download action
- **Type:** MISSING_FEATURE
- **OLD (target):** Download tap calls `downloadViewModel.downloadChapterNotification(...)` then `adViewModel.onDownloadStarted(context, ...)` — an interstitial-ad gate (old `NotificationsRoute.kt:42-50`, `old:46,97`).
- **KMP (current):** Download dispatches `OnDownloadClick` → `EnqueueDownloadUseCase` only; no ad gate (`UpdatesViewModel.kt:166-176`).
- **Priority:** P2
- **Acceptance criteria:** Confirm whether the interstitial-ad gate is part of target parity. If ads are retained in the rework, the Updates download path must invoke the ad gate consistently with other download surfaces (Library/Details). If ads were intentionally dropped cluster-wide, document as accepted.
- **Notes:** Cross-feature with the Ad subsystem; verify against the global ad-strategy decision rather than fixing in isolation. Could be DEVIATION if ads are Android-only.

### GAP-UPD-05 — Swipe-to-mark-read / swipe-to-delete gestures replaced by buttons
- **Screen/surface:** UpdatesScreen — row gestures
- **Type:** BEHAVIOR
- **OLD (target):** `SwipeToDismissBox` both directions: swipe-right (StartToEnd) → mark read (Done icon, primary@0.2 bg); swipe-left (EndToStart) → delete-with-undo (Delete icon, error@0.2 bg); animated 300ms color crossfade; `confirmValueChange` always false so the row performs the side-effect and snaps back (old `UpdatesScreen.kt:163-214`, `old:35,47-49,99`).
- **KMP (current):** No swipe gestures. Mark-read and Delete are stacked `TextButton`s in the row's trailing column; "Delete" still produces an undo snackbar (`UpdatesScreen.kt:410-432`; inventory `kmp:36,41` — "legacy swipe-to-dismiss reframed as per-row Delete button").
- **Priority:** P1
- **Acceptance criteria:** Decide whether native swipe affordances are restored. If parity requires swipe, implement a multiplatform swipe-to-dismiss (mark-read on right, delete-with-undo on left, perform-side-effect-without-settling semantics). If the button reframe is accepted, document as intentional and ensure discoverability matches.
- **Notes:** `SwipeToDismissBox` is multiplatform in Compose; the "perform side-effect, don't dismiss" pattern (`confirmValueChange=false`) must be replicated. Significant UX delta from native.

### GAP-UPD-06 — Unread dot indicator missing; styling differs
- **Screen/surface:** UpdatesScreen — unread indication
- **Type:** VISUAL
- **OLD (target):** Unread rows show an 8dp `primary` `CircleShape` dot before the chapter number; read rows dim to **0.4 alpha**; title 16.sp `titleMedium`, chapter# 12.sp `bodyMedium` (old `UpdateItem.kt:73-112`, `old:36-37`).
- **KMP (current):** No unread dot. Unread → `FontWeight.SemiBold` title + full alpha; read → `Normal` weight + **0.60 alpha** content (`UpdatesScreen.kt:348-349,388-407`).
- **Priority:** P2
- **Acceptance criteria:** Add the 8dp primary unread dot adjacent to the chapter number, and align read-state dimming to native's 0.4 alpha (currently 0.6) — or accept the weight-based scheme as the rework standard and reconcile the alpha value.
- **Notes:** Two sub-deltas: missing dot + alpha 0.4 vs 0.6. Dot is the clearer parity miss.

### GAP-UPD-07 — Recency-bucket grouping (Last Week / Older) vs calendar-date headers + chapter-number sort
- **Screen/surface:** UpdatesScreen — grouping & sort
- **Type:** BEHAVIOR
- **OLD (target):** Fixed recency buckets — Today / Yesterday / Last Week (>today-7 & <yesterday) / Older — each sorted **descending by chapter number** (`chapterNumber.toDoubleOrNull() ?: 0.0`); header strings `notifications_group_today/yesterday/last_week/older` (old `NotificationRepository.kt:45-72`, `old:53,101`).
- **KMP (current):** Updates uses the SAME calendar-date `groupByDate`/`formatGroupLabel` as History (Today / Yesterday / "N days ago" / "MMM d, yyyy"), grouped by `notificationDate`, not the 4 fixed recency buckets; sort is by date not chapter number (`UpdatesScreen.kt:311-322,443-494`; cluster note `kmp:72`).
- **Priority:** P1
- **Acceptance criteria:** Updates groups into Today / Yesterday / Last Week / Older buckets with within-bucket descending chapter-number sort, matching native — OR the calendar-date scheme is explicitly accepted. Header labels must match the native bucket strings.
- **Notes:** Native deliberately uses a DIFFERENT grouping scheme from History; the KMP port collapsed both onto History's scheme. Port the native bucket logic verbatim. Watch the chapter-number numeric sort.

### GAP-UPD-08 — Updates loading/empty state doesn't reuse shared components
- **Screen/surface:** UpdatesScreen — loading & empty states
- **Type:** REFACTOR
- **OLD (target):** Native used a shared `LoadingScreen()` (centered `CircularProgressIndicator`, color `inversePrimary`) for loading; no empty composable (old `UpdatesScreen.kt:108-109`, `LoadingScreen.kt:18-33`, `old:39-40`).
- **KMP (current):** Hand-rolled bare `CircularProgressIndicator` for loading and a plain `Text(no_updates)` for empty — does NOT use the shared `YamiLoadingState`/`YamiEmptyState` that History uses (`UpdatesScreen.kt:283-289`; cluster note `kmp:70`).
- **Priority:** P2
- **Acceptance criteria:** Updates uses the shared `YamiLoadingState` and `YamiEmptyState` components (matching History) for visual consistency across the cluster.
- **Notes:** Shared components exist (`YamiStateViews.kt`); purely a reuse/consistency fix. Pairs with GAP-STAT-03.

### GAP-UPD-09 — Action affordances are text buttons instead of icons (DoneAll/DeleteSweep/Download)
- **Screen/surface:** UpdatesScreen — top-bar + per-row affordances
- **Type:** DEVIATION(intentional design-system)
- **OLD (target):** Top-bar `DeleteSweep` (delete-all) + `DoneAll` (mark-all-read) icon buttons, disabled when empty; per-row `Done`/`Delete`/`Download`/`DownloadDone` icons (old `UpdatesScreen.kt:82-93`, `UpdateItem.kt`, `old:34-36`).
- **KMP (current):** "Mark all read"/"Clear all" `TextButton`s in top bar; per-row "Mark read"/"Download"/"Delete" `TextButton`s; `:ui` omits `materialIconsExtended` (`UpdatesScreen.kt:259-270,410-432`; `kmp:69`).
- **Priority:** P2
- **Acceptance criteria:** Same cluster-wide icon-vs-text decision as GAP-HIST-04. Restore icon affordances via `YamiIcons` or accept text-button posture as the rework standard.
- **Notes:** One product decision should cover GAP-HIST-04 / GAP-UPD-09 / GAP-STAT-04.

### GAP-UPD-10 — Absolute-date fallback English-only (shared with History)
- **Screen/surface:** UpdatesScreen — date header fallback
- **Type:** BEHAVIOR
- **OLD (target):** Native's bucket headers are fully localized string resources (`notifications_group_*`); no raw English month fallback because grouping is bucket-based (old `old:53`).
- **KMP (current):** Because Updates adopted History's calendar-date scheme, it inherits the hard-coded English `monthAbbrev` ("Jan".."Dec") for entries >6 days old (`UpdatesScreen.kt:480-494`; `kmp:73`).
- **Priority:** P2
- **Acceptance criteria:** No English month names appear under Arabic/RTL. Resolved automatically if GAP-UPD-07 restores bucket grouping; otherwise localize `monthAbbrev`.
- **Notes:** Tightly coupled to GAP-UPD-07 and GAP-HIST-07. Best fixed by the shared-helper dedup (GAP-X-01).

---

## Statistics

### GAP-STAT-01 — Overview metric formatting lost thousands-separator (`"%,d"`)
- **Screen/surface:** StatisticsScreen — overview cells + stat rows
- **Type:** VISUAL
- **OLD (target):** All integer metrics formatted with grouped thousands via `R.string.value_count` = `"%,d"` in both `OverviewItem` and `StatsItem`; read-duration via `R.string.h_m` = `"%1$dh %2$dm"` (old `StatsOverview.kt:85-90`, `StatsItem.kt:31-83`, `old:65-67,78`).
- **KMP (current):** Overview/stat values rendered as plain integer text via state fields; no evidence of `"%,d"` thousands grouping (`StatisticsScreen.kt:248-321`; metrics inventory `kmp:56-61`). `readDuration` is a VM-supplied pre-formatted "Xh Ym" string.
- **Priority:** P2
- **Acceptance criteria:** Counts ≥1000 render with locale-grouped thousands separators (e.g. "1,234") matching native; read-duration matches the "Xh Ym" format. Verify against a library with >1000 chapters.
- **Notes:** Confirm whether the KMP value text already routes through a `value_count`-equivalent formatter; if not, add one. Low effort, clear visual delta on large libraries.

### GAP-STAT-02 — Overview/stat value typography & cell layout differ
- **Screen/surface:** StatisticsScreen — overview card + stat rows
- **Type:** VISUAL
- **OLD (target):** Overview row vertical pad 16dp, item horizontal pad 18dp, value 20.sp Bold / label 12.sp; `StatsItem` value 12.sp Bold; section titles 14.sp Bold; cards `RoundedCornerShape(12.dp)` `surfaceContainerHigh`; `Row(spacedBy 8.dp)` of equal-weight cells (old `StatsOverview.kt:27-90`, `StatsItem.kt`, `old:65-67`).
- **KMP (current):** Overview cell value `headlineSmall`/SemiBold, label `labelMedium`; section title `titleMedium`/SemiBold; `StatsItem` title `bodyMedium`, value `titleMedium`/Medium; cards `surfaceVariant`; `Row(SpaceEvenly)` (`StatisticsScreen.kt:228-321`).
- **Priority:** P2
- **Acceptance criteria:** Typography scale, font weights, card color (`surfaceContainerHigh` vs `surfaceVariant`), and cell spacing/arrangement match native — or the rework type-scale tokens are accepted as the design-system standard.
- **Notes:** Multiple small deltas; treat as one visual-parity pass on `StatsOverview`/`StatsItem`. `surfaceVariant` vs `surfaceContainerHigh` is the most visible.

### GAP-STAT-03 — Statistics loading state doesn't reuse shared component
- **Screen/surface:** StatisticsScreen — loading state
- **Type:** REFACTOR
- **OLD (target):** No loading state at all — metrics are `Eagerly` started with 0/"0h 0m" defaults; screen always renders (old `StatisticsViewModel.kt:17-43`, `old:69`).
- **KMP (current):** Hand-rolled centered `CircularProgressIndicator` with early `return@Scaffold`; does not use shared `YamiLoadingState` (`StatisticsScreen.kt:177-186`; cluster note `kmp:70`).
- **Priority:** P3
- **Acceptance criteria:** Statistics either renders immediately with defaults (native behavior) or, if a loading gate is kept, uses the shared `YamiLoadingState` for consistency.
- **Notes:** Minor. KMP-EXTRA loading gate is harmless; the only parity item is shared-component reuse. Pairs with GAP-UPD-08.

### GAP-STAT-04 — Stat icons & back arrow replaced by text (no leading metric icons)
- **Screen/surface:** StatisticsScreen — stat rows + nav icon
- **Type:** DEVIATION(intentional design-system)
- **OLD (target):** Each `StatsItem` has a 24dp leading outlined Material icon (`LibraryBooks`/`NotStarted`/`DoneAll`/`SelectAll`/`RemoveRedEye`/`FileDownloadDone`/`BookmarkAdd`) tinted `onBackground`; back nav is an `AutoMirrored.ArrowBack` `IconButton` (old `StatisticsScreen.kt:52-122`, `old:63-64,66`).
- **KMP (current):** No leading icons on stat rows; back is a labelled "Back" `TextButton`; `:ui` omits `materialIconsExtended` (`StatisticsScreen.kt:165-173,299-321`; inventory `kmp:62`).
- **Priority:** P2
- **Acceptance criteria:** Same cluster-wide icon decision (GAP-HIST-04/GAP-UPD-09). If parity is required, restore the 7 leading stat icons + back arrow via `YamiIcons`; otherwise accept text posture.
- **Notes:** Statistics loses the most visual richness from icon removal (7 distinct glyphs). Weigh this when making the cluster-wide icon decision.

### GAP-STAT-05 — No charts (native also has none — confirm scope)
- **Screen/surface:** StatisticsScreen — charts
- **Type:** (no gap — scope confirmation)
- **OLD (target):** Native has NO graphical charts despite the audit prompt's "every chart" wording — purely a numeric overview card + two grouped metric lists, 8 metrics (old `old:77,100`).
- **KMP (current):** Also no charts — text-only numeric rows, identical metric set of 8 (`StatisticsScreen.kt`; metrics inventory `kmp:56-62`).
- **Priority:** P3
- **Acceptance criteria:** No action — metric set (8 fields), sections, and chart-absence all match native. Logged only to close out the "charts" line of the audit prompt: there is nothing to port.
- **Notes:** Metric parity is COMPLETE (in-library, read duration, completed, started, total, read, downloaded, bookmarked). Read-duration remains DataStore-backed via the session timer in both apps (rework session-timer port #232).

---

## Cross-cluster

### GAP-X-01 — Duplicated date-helper code across History & Updates
- **Screen/surface:** History + Updates — date grouping/formatting helpers
- **Type:** REFACTOR
- **OLD (target):** Native History and Updates use deliberately DIFFERENT schemes (History calendar-date with relative-vs-absolute fallback; Updates fixed recency buckets) — no shared helper expected (old `old:101`).
- **KMP (current):** History and Updates each carry an identical copy-pasted `groupByDate`/`formatGroupLabel`/`formatRelativeDate`/`monthAbbrev`, differing only in the date field used (`HistoryScreen.kt:325-376`, `UpdatesScreen.kt:443-494`; cluster note `kmp:72`).
- **Priority:** P3
- **Acceptance criteria:** Date helpers live in one shared `:ui` location, with localized month names (resolving GAP-HIST-07/GAP-UPD-10). NOTE: if GAP-UPD-07 restores native's distinct Updates bucket scheme, the two will legitimately diverge again — sequence this AFTER the GAP-UPD-07 decision.
- **Notes:** The KMP KDoc claims a "convergence point" that is actually copy-paste (`kmp:72`). Resolve only after deciding GAP-UPD-07.

### GAP-X-02 — Dead `*ReworkScreenRoute` duplicate adapters compiled but unwired
- **Screen/surface:** All three — route layer
- **Type:** REFACTOR
- **OLD (target):** Native has one route per screen (`HistoryRoute`/`NotificationsRoute`/`StatisticsRoute`) (old `old:14,33,60`).
- **KMP (current):** Each screen has BOTH a swapped legacy-key adapter and a parallel `*ReworkScreenRoute` rendering the same `:ui` screen; the Rework keys are unreachable in production nav — dead-but-compiled debug entries carrying large `@Suppress("UNUSED_PARAMETER")` + §253 KDoc blocks (`App.kt:651-696`; cluster note `kmp:74`).
- **Priority:** P3
- **Acceptance criteria:** Remove the unwired `*ReworkScreenRoute` duplicates (or document why they must remain), eliminating dead routes and unused-parameter suppressions once the cutover is confirmed final.
- **Notes:** Pure cleanup; no user-facing effect. Confirm the platform cutover (#422, marked COMPLETE in MEMORY) truly retired the need for the Rework keys before deleting.


---

## Complaint, WhatsNew, Welcome & About — gaps

42 gaps: 8 P0, 16 P1, 12 P2, 6 P3 — covering user Complaint, Admin Complaint, WhatsNew, Welcome, and About surfaces against the OLD native target.

---

## Complaint (user-side) — gaps

### GAP-CMP-01 — Pinned FAQ complaints missing from user list
- **Screen/surface:** ComplaintScreen (user Feedback Manager)
- **Type:** MISSING_FEATURE
- **OLD (target):** `getCustomTopComplaints.kt:41-74` — two localized PINNED FAQ entries (id=`R.string.admin`, userId="0", type CUSTOM, status PINNED) are ALWAYS prepended to `allComplaints = getCustomTopComplaints(context) + Success.data` (`ComplaintScreen.kt:111-113`): (1) "Content removed (18+/hentai)" w/ reason; (2) "New manga site requirements" (≥200 titles, no bot checks) w/ reason.
- **KMP (current):** No pinned/FAQ prepend. VM `loadList()` populates `state.all` purely from `ObserveUserComplaintsUseCase` (`ComplaintViewModel.kt:124-126,251-279`); audit feature-inventory lists no pinned entries (`ComplaintScreen.kt:298-344`).
- **Priority:** P0
- **Acceptance criteria:** User Complaint list always shows the 2 localized PINNED FAQ cards at the top (above remote complaints), each rendered with the PINNED StatusChip and a ClosureReasonCard; both are Reply-only (Edit/Delete hidden). FAQ content/strings match native verbatim (Arabic + en).
- **Notes:** Port `getCustomTopComplaints` into the domain/presentation layer (not :ui) so it composes with the observed list; PINNED-gating already exists in the KMP dialog (`ComplaintActionDialog.kt:302-321`), so only the data prepend + ClosureReasonCard rendering are missing. Reuse existing `ComplaintStatusChip`.

### GAP-CMP-02 — ClosureReasonCard not rendered on CLOSED/PINNED cards
- **Screen/surface:** ComplaintRow (user card) + ComplaintActionDialog preview
- **Type:** MISSING_FEATURE
- **OLD (target):** `ComplaintCard.kt:111-117` — when status is CLOSED or PINNED and metadata "reason" present, renders `ClosureReasonCard` (Card r8, color by `ClosureReasonType.getColorScheme()`, icon DONE→CheckCircle / DONE_WAIT_UPDATE→Update / PINNED→PushPin / OTHER→Info, label `closure_reason_label`, optional type chip, reason maxLines 10) (`ComplaintComponents.kt:53-124`).
- **KMP (current):** ComplaintRow renders subject/status chip/3-line body/type/timestamp only (`ComplaintScreen.kt:449-492`); no ClosureReasonCard in the row or preview-card feature inventory.
- **Priority:** P1
- **Acceptance criteria:** CLOSED and PINNED complaints with a `reason` in metadata show a closure-reason card matching native (icon by ClosureReasonType, label, optional non-OTHER type chip, reason text). Applies to user rows and the dialog preview.
- **Notes:** Needs a shared `:ui` ClosureReasonCard + `ClosureReasonType` enum/`fromString`/`getColorScheme` ported. Required by GAP-CMP-01 (pinned FAQs carry reasons).

### GAP-CMP-03 — Device/source InfoItem row (Android version + manufacturer) missing on cards
- **Screen/surface:** ComplaintRow (user card)
- **Type:** MISSING_FEATURE
- **OLD (target):** `ComplaintCard.kt:70-96` — Row SpaceBetween of `InfoItem(Android icon, apiLevelToAndroidVersion(osVersion))` + `InfoItem(PhoneAndroid icon, manufacturer)`; osVersion from `metadata["osVersion"]`, manufacturer from `metadata["manufacturer"]`.
- **KMP (current):** No device/manufacturer InfoItem row in the KMP card (`ComplaintScreen.kt:449-492` lists subject/status/body/type/timestamp only).
- **Priority:** P1
- **Acceptance criteria:** User complaint cards display Android-version (mapped via apiLevelToAndroidVersion) and manufacturer InfoItems when present in metadata, matching native icons/layout.
- **Notes:** Port `apiLevelToAndroidVersion` + shared `InfoItem`. Metadata is captured at submit time on Android; iOS/Desktop device metadata is a DEVIATION(platform) — render only what metadata exists.

### GAP-CMP-04 — Footer short-id (Monospace) missing on user cards
- **Screen/surface:** ComplaintRow (user card)
- **Type:** VISUAL
- **OLD (target):** `ComplaintCard.kt:122-139` — footer Row SpaceBetween: `formatTimestamp(createdAt)` + `feedback_id_format`(id.take(8)) in `FontFamily.Monospace`.
- **KMP (current):** Footer shows timestamp only; no short-id chip (`ComplaintScreen.kt:449-492`).
- **Priority:** P2
- **Acceptance criteria:** User card footer shows the 8-char Monospace id alongside the timestamp, matching native `feedback_id_format`.
- **Notes:** Trivial Text addition; reuse `formatComplaintTimestamp`.

### GAP-CMP-05 — Subject vs type-label ordering / 1-line vs 2-line divergence
- **Screen/surface:** ComplaintRow header
- **Type:** VISUAL
- **OLD (target):** `ComplaintCard.kt:44-65` — left column: type display-name (`titleMedium`/SemiBold, maxLines 2 ellipsis) as the PRIMARY line, then subject (`bodySmall`/onSurfaceVariant) as secondary.
- **KMP (current):** Card shows "subject-or-type" as primary `titleMedium`/SemiBold 1-line + type label as `bodySmall` secondary (`ComplaintScreen.kt:449-492`) — inverted emphasis and 1-line vs native's 2-line type.
- **Priority:** P2
- **Acceptance criteria:** Header primary line = type display-name (maxLines 2), secondary = subject, matching native typography and line caps.
- **Notes:** Confirm against native intent; the inversion changes which text leads each card.

### GAP-CMP-06 — Body maxLines 3 vs native 10
- **Screen/surface:** ComplaintRow body
- **Type:** VISUAL
- **OLD (target):** `ComplaintCard.kt:102-108` — body `bodyMedium` maxLines 10 ellipsis.
- **KMP (current):** body `bodyMedium` 3 lines ellipsis (`ComplaintScreen.kt:449-492`).
- **Priority:** P2
- **Acceptance criteria:** User card body shows up to 10 lines before ellipsis (admin row stays 3 per native `AdminComplaintCard.kt:799-816`).
- **Notes:** One-line maxLines change; do NOT also bump admin (native admin is 3).

### GAP-CMP-07 — Results-count text present but verify string/format
- **Screen/surface:** ComplaintScreen SearchAndFilterSection
- **Type:** VISUAL
- **OLD (target):** `feedbacks_found_count` results-count text (`ComplaintScreen.kt` feature inventory line 18).
- **KMP (current):** `complaint_results_count` text present (`ComplaintScreen.kt:22` inventory).
- **Priority:** P3
- **Acceptance criteria:** Count string matches native pluralization/wording (`feedbacks_found_count`), Arabic + en.
- **Notes:** Likely a key-name divergence only; confirm strings align.

### GAP-CMP-08 — Card container color (surfaceVariant) vs native elevated Card
- **Screen/surface:** ComplaintRow card style
- **Type:** VISUAL
- **OLD (target):** `ComplaintCard.kt:30-37` — `Card` elevation 2.dp, `RoundedCornerShape(16.dp)`, default surface container (elevated).
- **KMP (current):** `surfaceVariant` container, `RoundedCornerShape(12.dp)`, no stated elevation (`ComplaintScreen.kt:430-442`).
- **Priority:** P2
- **Acceptance criteria:** Decide one card visual language; if matching native, use elevated Card r16 + 2.dp elevation. Apply consistently to user + admin.
- **Notes:** KMP standardized r12/surfaceVariant across the cluster; this is a deliberate KMP design-system choice. Recommend keeping KMP's system but document as accepted DEVIATION if not aligning to r16/elev.

### GAP-CMP-09 — User mutation snackbars: KMP-EXTRA vs native silent
- **Screen/surface:** ComplaintScreen (reply/edit/delete feedback)
- **Type:** KMP-EXTRA
- **OLD (target):** Native shows NO snackbar/toast on user mutations — route wires `onShowMessage = {}`, messages dropped (`ComplaintScreenRoute.kt`; `ComplaintScreen.kt:231,237,243`; OLD cluster note line 141).
- **KMP (current):** Snackbars on reply/edit/delete success+error via `ComplaintEffect.ShowSuccessMessage/ShowErrorMessage` (`ComplaintScreen.kt:173-180`).
- **Priority:** P3
- **Acceptance criteria:** Decide keep-or-remove. Recommended KEEP — silent mutation in native is an INFERRED bug/oversight; visible confirmation is a genuine UX improvement. Document as intentional divergence.
- **Notes:** Low risk. If strict parity is mandated later, gate behind a flag rather than delete.

### GAP-CMP-10 — Edit dialog: subject now propagated (KMP fixes native bug)
- **Screen/surface:** ComplaintActionDialog EDIT
- **Type:** KMP-EXTRA
- **OLD (target):** `ComplaintActionDialog.kt:333-473` — EditContent lets user edit subject locally but `onEdit(complaint, body)` only propagates body; subject change is silently discarded (INFERRED bug, OLD note line 143).
- **KMP (current):** `OnSubmitEdit(subject, body)` propagates BOTH subject and body (`ComplaintActionDialog.kt:458-587`; `ComplaintViewModel.kt:198-229`).
- **Priority:** P3
- **Acceptance criteria:** Keep KMP behavior (subject persists). Confirm `EditComplaintUseCase` writes subject to backend.
- **Notes:** Desirable bug-fix; keep. No action beyond verification.

### GAP-CMP-11 — Reply original-complaint reference card / metadata "replyto"
- **Screen/surface:** ComplaintActionDialog REPLY
- **Type:** BEHAVIOR
- **OLD (target):** `ComplaintActionDialog.kt:230-328` — Reply shows an original-complaint reference card (label `reply_to_complaint_id`, subject 1-line, body 2-line) and builds a NEW `Complaint(status=OPEN, metadata += "replyto"->id)`.
- **KMP (current):** Reply panel highlights `complaint_replying_to` in primary on the preview card (`ComplaintActionDialog.kt:361-394`); submit is `OnSubmitReply(trimmed)` → `ReplyToComplaintUseCase` (`ComplaintViewModel.kt:198-229`). Whether it creates a new OPEN complaint with `replyto` metadata is not confirmed in audit.
- **Priority:** P1
- **Acceptance criteria:** Replying creates a new complaint with `status=OPEN` and `metadata["replyto"]=originalId` (verify the use case does this); reference card shows label + subject (1-line) + body (2-line).
- **Notes:** Open question — verify `ReplyToComplaintUseCase` payload matches native metadata shape so admin `replyToId` display (GAP-CMP-12) works.

---

## Admin Complaint — gaps

### GAP-CMP-12 — Admin row: replyToId reference + app-version chip placement
- **Screen/surface:** AdminComplaintRow
- **Type:** MISSING_FEATURE
- **OLD (target):** `AdminComplaintScreen.kt:713-768` — header column shows type, subject, optional `replyToId` text (from metadata "replyto"), then Row with `user_id_format`(userId.take(8)) Monospace + optional app-version Surface chip (tertiaryContainer, "v$ver" Monospace, r4).
- **KMP (current):** Row footer shows type label + userId.take(8) Monospace + optional `vX` appVersion + optional timestamp (`AdminComplaintScreen.kt:700-736`); no `replyToId` reference line.
- **Priority:** P1
- **Acceptance criteria:** Admin card displays the `replyto` reference (when present) and renders the app-version chip with native styling (tertiaryContainer Surface, Monospace, r4).
- **Notes:** Depends on GAP-CMP-11 metadata. App-version chip styling may differ (KMP renders inline Monospace text vs native Surface chip).

### GAP-CMP-13 — Admin row action IconButtons (closure-note / edit / delete) missing from row
- **Screen/surface:** AdminComplaintRow footer
- **Type:** MISSING_FEATURE
- **OLD (target):** `AdminComplaintScreen.kt:829-895` — footer right Row of 3 IconButtons: closure-note (secondaryContainer, Note icon), edit (tertiaryContainer, Edit icon), delete (errorContainer, Delete icon); plus status `Surface` (primaryContainer, clickable→StatusChange) containing StatusChip + Edit icon (`:770-794`).
- **KMP (current):** Row click opens admin MENU dialog (`AdminComplaintScreen.kt` interactions); all four mutations are reached via the dialog, NOT via per-row IconButtons. No in-row action buttons or clickable status surface.
- **Priority:** P1
- **Acceptance criteria:** Either (a) restore native per-row action IconButtons + clickable status surface, OR (b) accept the dialog-MENU consolidation as an intentional, documented DEVIATION. If (b), confirm all four native actions remain reachable (they are, via MENU).
- **Notes:** KMP consolidated 4 row affordances into a single row-tap→MENU. Recommend documenting as accepted simplification unless native fidelity is strict; flag for parity review.

### GAP-CMP-14 — Admin back affordance: actions-slot TextButton vs native navigationIcon
- **Screen/surface:** AdminComplaintScreen TopAppBar
- **Type:** VISUAL
- **OLD (target):** `AdminComplaintScreen.kt:162-188` — `CenterAlignedTopAppBar` with back arrow as `navigationIcon` (AutoMirrored ArrowBack) + stats-toggle IconButton in actions.
- **KMP (current):** Back is an `actions`-slot `TextButton` labeled "back", NOT a navigationIcon (`AdminComplaintScreen.kt:208-224`).
- **Priority:** P2
- **Acceptance criteria:** Admin back uses a leading `navigationIcon` arrow consistent with user-side ComplaintScreen and native; harmonize the cluster's 3 inconsistent back treatments (user=navIcon arrow, admin=actions TextButton, About=navIcon TextButton).
- **Notes:** Cross-cluster nit (KMP note line 113). Pick one back treatment; arrow `navigationIcon` matches native best.

### GAP-CMP-15 — Stats-toggle (show/hide statistics) action missing
- **Screen/surface:** AdminComplaintScreen TopAppBar + StatisticsCard
- **Type:** MISSING_FEATURE
- **OLD (target):** `AdminComplaintScreen.kt:162-188` — TopAppBar actions has a toggle-stats IconButton (`VisibilityOff`/`Visibility`); StatisticsCard renders only when `showStats`.
- **KMP (current):** StatisticsCard is always item 1 of the list (`AdminComplaintScreen.kt:323-371`); no visibility toggle.
- **Priority:** P2
- **Acceptance criteria:** Add a TopAppBar toggle that shows/hides the StatisticsCard, default-visible matching native default.
- **Notes:** Small state flag in AdminComplaintState + intent. Low risk.

### GAP-CMP-16 — Statistics card "By app version" top-5 gating
- **Screen/surface:** AdminStatisticsCard
- **Type:** BEHAVIOR
- **OLD (target):** `AdminComplaintScreen.kt:581-688` — "By app version" section only when >1 app version, showing TOP-5 by count (sorted desc); "By status" shows only statuses with count>0.
- **KMP (current):** StatisticsCard shows per-status rows + per-app-version rows (`AdminComplaintScreen.kt:556-644`); audit does not confirm top-5 cap, >1-version gate, or count>0 filtering.
- **Priority:** P2
- **Acceptance criteria:** Per-status rows shown only when count>0; app-version section appears only with >1 version and is capped to top-5 by count, descending.
- **Notes:** Verify `computeStatistics`/card rendering (`AdminComplaintViewModel.kt:258-296`). KMP-EXTRA risk: KMP stats card itself exceeds native (native VM `getComplaintsStatistics()` was unwired; native card computed inline). KMP card is fine — just match gating.

### GAP-CMP-17 — Admin no-matches missing SearchOff icon
- **Screen/surface:** AdminComplaintScreen no-results state
- **Type:** VISUAL
- **OLD (target):** `AdminComplaintScreen.kt:260-270` — no-results `EmptyState(no_results_found / try_different_filters, icon=FilterAlt)`.
- **KMP (current):** Admin no-matches is plain text only, no icon (`AdminComplaintScreen.kt:345-361`); user-side DOES show an icon (`ComplaintScreen.kt:312-334`).
- **Priority:** P2
- **Acceptance criteria:** Admin no-matches shows an icon (native uses FilterAlt; user-side uses SearchOff) + title + message, consistent with user-side.
- **Notes:** KMP note line 115. Reuse a shared `:ui` empty/no-match component with an icon slot.

### GAP-CMP-18 — Admin sort affix "▾" vs native DropdownMenu styling
- **Screen/surface:** AdminComplaintScreen sort control
- **Type:** VISUAL
- **OLD (target):** `AdminComplaintScreen.kt` sort `DropdownMenu` (7 options) via SortOption enum (OLD line 45).
- **KMP (current):** Sort `OutlinedButton`+`DropdownMenu` over 7 `AdminSortMode.entries` with a "▾" text affix (`AdminComplaintScreen.kt:518-543`).
- **Priority:** P3
- **Acceptance criteria:** Sort control uses a proper dropdown-arrow icon rather than a literal "▾" glyph; 7 modes preserved.
- **Notes:** Minor polish; both reach the same 7 sort modes (DATE_DESC default etc.).

### GAP-CMP-19 — Active-filters summary line
- **Screen/surface:** AdminComplaintScreen SearchAndFilterSection
- **Type:** MISSING_FEATURE
- **OLD (target):** Feature inventory lists an "active-filters summary line" (OLD line 46).
- **KMP (current):** Not present in KMP admin feature inventory (`AdminComplaintScreen.kt:374-516`) — only results-count text.
- **Priority:** P3
- **Acceptance criteria:** Show an active-filters summary line when any filter/search is active, matching native content.
- **Notes:** Nice-to-have; verify native renders it (inventory-only mention).

### GAP-CMP-20 — Admin bulk multi-select: KMP-EXTRA never landed (native lacked it too)
- **Screen/surface:** AdminComplaintScreen
- **Type:** KMP-EXTRA
- **OLD (target):** Native `bulkUpdateStatus`/`bulkDeleteComplaints` exist in `AdminComplaintViewModel` but are NOT wired to any UI (OLD note line 144). Task #265 "bulk" exceeds native parity.
- **KMP (current):** NO bulk-select UI; `AdminComplaintIntent` has no bulk variants (`AdminComplaintIntent.kt:104-237`; KMP note line 109). KDoc claims bulk "landed" but it is absent.
- **Priority:** P3
- **Acceptance criteria:** Resolve the stale KDoc. RECOMMEND: DROP bulk-select (native never exposed it; no parity need) and remove "landed" KDoc claims, OR implement only if product explicitly wants it.
- **Notes:** Keep/remove rec = REMOVE for parity. Either way fix the misleading KDoc.

### GAP-CMP-21 — Admin mutation snackbars: KMP-EXTRA vs native println/silent
- **Screen/surface:** AdminComplaintScreen
- **Type:** KMP-EXTRA
- **OLD (target):** Admin route `showMessage` is just `println("Admin Message: ...")` — no visible snackbar/toast (`AdminComplaintScreenRoute.kt:37-41`; OLD note line 141). Only Toast in native admin is body-copy `title_copied`.
- **KMP (current):** Real snackbars on admin mutations via `LaunchedEffect` collecting effects (`AdminComplaintScreen.kt:180-187`).
- **Priority:** P3
- **Acceptance criteria:** KEEP (visible feedback is a clear improvement over println). Document as intentional divergence.
- **Notes:** Same disposition as GAP-CMP-09.

### GAP-CMP-22 — Admin dialog PINNED override (no gate) parity
- **Screen/surface:** AdminComplaintActionDialog
- **Type:** BEHAVIOR
- **OLD (target):** Native admin can act on any complaint incl. PINNED (admin dialogs offer Status/Edit/Closure/Delete with no PINNED gate; `StatusChangeDialog.kt`).
- **KMP (current):** Admin dialog explicitly has NO PINNED gate (`AdminComplaintActionDialog.kt:118-120`) — matches native.
- **Priority:** P3
- **Acceptance criteria:** Confirmed parity; no change. (Recorded for completeness.)
- **Notes:** No action. Contrast with user dialog which correctly gates PINNED to Reply-only.

### GAP-CMP-23 — Closure-reason builds "${type.key}: ${reason}" + auto-CLOSED side effect
- **Screen/surface:** AdminComplaintActionDialog CLOSURE_REASON
- **Type:** BEHAVIOR
- **OLD (target):** `StatusChengeDialog.kt:288-456` / `AdminComplaintViewModel.kt` — closure builds `"${type.key}: ${reason}"` unless type==OTHER; `addClosureReason` writes metadata reason/reasonAddedBy/reasonAddedAt and AUTO-sets status CLOSED if currently OPEN/IN_PROGRESS. Reason-type dropdown (`ClosureReasonType.entries`) + pre-fill of existing reason/type.
- **KMP (current):** CLOSURE_REASON is a plain textarea + counter (≤500) + Add (`AdminComplaintActionDialog.kt:352-420`) → `AddClosureReasonUseCase`. NO reason-type dropdown, NO `type.key:` prefixing, pre-fill/auto-CLOSED not confirmed.
- **Priority:** P1
- **Acceptance criteria:** Closure dialog offers a ClosureReasonType dropdown, pre-fills existing reason+type, stores `"${type.key}: ${reason}"` (except OTHER), writes reasonAddedBy/reasonAddedAt metadata, and auto-sets CLOSED from OPEN/IN_PROGRESS. Verify `AddClosureReasonUseCase` does the metadata + status side effects.
- **Notes:** Largest admin-dialog functional gap. Requires porting `ClosureReasonType` + display text. Ties to GAP-CMP-02 (card rendering reads the same reason format).

### GAP-CMP-24 — Admin closure char cap 500 vs native (verify)
- **Screen/surface:** AdminComplaintActionDialog CLOSURE_REASON / EDIT
- **Type:** VISUAL
- **OLD (target):** Native closure reason field is `height 120 maxLines 5` with no explicit char counter cited; admin edit body `height 120 maxLines 5` (`StatusChangeDialog.kt:112-456`).
- **KMP (current):** Closure ≤500 char counter; edit body ≤1000 counter (`AdminComplaintActionDialog.kt:352-551`).
- **Priority:** P3
- **Acceptance criteria:** Confirm char caps match native intent (native admin edit had no explicit cap cited; KMP adds 1000). Keep counters if harmless; align caps if native enforced different limits.
- **Notes:** KMP adds validation native lacked — likely a desirable improvement; verify no backend conflict.

### GAP-CMP-25 — StatusChange: button-per-status vs native radio list
- **Screen/surface:** AdminComplaintActionDialog STATUS_CHANGE
- **Type:** VISUAL
- **OLD (target):** `StatusChangeDialog.kt:45-92` — radio list (RadioButton + StatusChip) of ALL statuses; Confirm `update_status` enabled when selection ≠ current.
- **KMP (current):** A button PER status excluding current (`AdminComplaintActionDialog.kt:286-350`) — immediate apply, no radio+confirm step.
- **Priority:** P2
- **Acceptance criteria:** Decide pattern. If matching native, use radio-select + Confirm. KMP's one-tap-applies is fewer steps; document as DEVIATION if kept.
- **Notes:** Behavioral nuance: native requires explicit Confirm; KMP applies on tap. Confirm UX intent with product.

---

## WhatsNew — gaps

### GAP-WN-01 — Feature media (images / carousels / video) entirely missing
- **Screen/surface:** WhatsNewScreen FeatureCard
- **Type:** MISSING_FEATURE
- **OLD (target):** `FeatureCard.kt:112-183` + `FeatureMedia` — per-card media: SingleImage (res), ImageCarousel (res-list), ImageUrlsCarousel (url-list via Coil `SubcomposeAsyncImage`), SingleUrlImage, VIDEO via `SafeVideoPlayer`, ImagePlaceholder/VideoPlaceholder fallbacks.
- **KMP (current):** FeatureCard renders title + optional NewChip + description ONLY; "NOT present (all deferred): images, video, fullscreen viewer" (`WhatsNewScreen.kt:338-392`, feature inventory line 70).
- **Priority:** P0
- **Acceptance criteria:** FeatureCard renders the feature's media per mediaType: single image (res/url), image carousels (res-list/url-list), and video; placeholders when absent. Uses Coil for URL images (cross-platform).
- **Notes:** Largest WhatsNew gap. Video is the hardest (cross-platform player) — consider phasing: P0 for images/carousels, video may be DEVIATION(platform) per platform if no shared player. Mitigated in practice because native default feature list is empty (see GAP-WN-04), but remote data may carry media.

### GAP-WN-02 — Fullscreen media viewer missing
- **Screen/surface:** WhatsNewScreen
- **Type:** MISSING_FEATURE
- **OLD (target):** `FullscreenMediaViewer.kt:26-141` — tap media → black .95α overlay with full image (Coil) or `SafeVideoPlayer`, close IconButton top-end, `tap_outside_to_close` caption.
- **KMP (current):** No media, so no fullscreen viewer; "No clicks, no long-press, no media" (`WhatsNewScreen.kt:261-299`).
- **Priority:** P1
- **Acceptance criteria:** Tapping a card's image/video opens a fullscreen viewer with close + tap-outside-to-dismiss, matching native.
- **Notes:** Depends on GAP-WN-01. Downgrade to P2 if media itself is deferred.

### GAP-WN-03 — Prev/Next/Get-Started navigation buttons missing
- **Screen/surface:** WhatsNewScreen NavigationButtons
- **Type:** MISSING_FEATURE
- **OLD (target):** `WhatsNewComponents.kt:98-141` — Row: `OutlinedButton` Previous (or Spacer), `Button` Next, and on last page `Button` `get_started` → onDismiss.
- **KMP (current):** Only horizontal swipe + page-indicator dots; "nav arrows" listed under NOT present (`WhatsNewScreen.kt:261-299`, inventory line 70). No Get-Started/dismiss button.
- **Priority:** P1
- **Acceptance criteria:** Show Previous/Next buttons mirroring page state and a Get-Started button on the last page that dismisses/marks-seen.
- **Notes:** Ties to GAP-WN-05 (onDismiss/markSeen). Swipe-only is reachable but native parity wants explicit buttons.

### GAP-WN-04 — Remote-data fetch + default-fallback behavior
- **Screen/surface:** WhatsNew data/behavior
- **Type:** BEHAVIOR
- **OLD (target):** `WhatsNewViewModel.kt:24-263` — fetches remote features by user-language code, maps `RemoteData`→`WhatsNewFeature` via `getLocalizedFeature`, falls back to `getDefaultFeatures` on empty/error (which currently returns EMPTY → EmptyState). Version-gated auto-show; on new version sets `ds.setNewSources(true)`.
- **KMP (current):** VM `init{}`→`loadFeatures()` via `GetWhatsNewFeaturesUseCase`; `:data` SWALLOWS failures and returns empty list (KDoc:59-67), so empty is the de-facto path; error state wired-but-dormant (`WhatsNewViewModel.kt:140-171`, KMP note line 65,119).
- **Priority:** P1
- **Acceptance criteria:** WhatsNew fetches remote, localized features (by language) and renders them when present; on error, surface the error state (don't silently swallow) OR fall back to defaults — matching native's remote-first-with-fallback contract.
- **Notes:** KMP `:data` swallowing failures diverges from native's error/empty branching. Native default list is empty by design, so empty fallback is acceptable, but error suppression should be reconsidered (the error UI exists but never fires).

### GAP-WN-05 — Version-gated auto-show + markSeen persistence not wired
- **Screen/surface:** WhatsNew auto-show gate
- **Type:** MISSING_FEATURE
- **OLD (target):** `WhatsNewViewModel.kt` — `checkIfShouldShowWhatsNew()` auto-shows when currentVersionCode>lastShown; `markWhatsNewAsSeen()` persists version+timestamp; `shouldShowBasedOnTime` (30d). onDismiss marks seen when isFirstOpen.
- **KMP (current):** `OnMarkSeen` is VM-wired to `MarkWhatsNewSeenUseCase` but NEVER dispatched by `:ui` — "auto-show gate deferred" (`WhatsNewViewModel.kt:140-171`, KMP note line 119).
- **Priority:** P1
- **Acceptance criteria:** WhatsNew auto-shows on version bump (and/or 30-day rule), and dismissing/Get-Started marks it seen (persists version+timestamp) so it doesn't reappear. `setNewSources(true)` side effect preserved if used by NewChip/sources badge.
- **Notes:** Dispatch `OnMarkSeen` from a Get-Started/dismiss action (GAP-WN-03). Wire the version-gate at the route or app-start level. Open question: where the auto-show trigger should live in KMP nav.

### GAP-WN-06 — NewChip: KMP-EXTRA vs native (isNew/version unused)
- **Screen/surface:** WhatsNewScreen FeatureCard
- **Type:** KMP-EXTRA
- **OLD (target):** `FeatureCard.kt:28-110` — OLD renders NO NewChip/isNew badge nor version label; `feature.isNew`/`feature.version` are unused in UI (OLD note line 97,145). Task #248 NewChip is net-new.
- **KMP (current):** Renders a primary-color `NewChip` (`new_badge` text) next to title when feature.isNew (`WhatsNewScreen.kt:338-392`).
- **Priority:** P3
- **Acceptance criteria:** Decide keep-or-remove. RECOMMEND KEEP (harmless enhancement; uses existing model field) but note it is non-parity; ensure remote/default data populates `isNew` meaningfully or the chip never shows.
- **Notes:** Native target lacked it. Keep is low-risk; if strict parity, remove the chip + `new_badge` string.

### GAP-WN-07 — Header close (X) button + title bar parity
- **Screen/surface:** WhatsNewScreen header
- **Type:** VISUAL
- **OLD (target):** `WhatsNewComponents.kt:21-63` — header Surface (tonalElev 2), Row SpaceBetween: title `whats_new_title` headlineSmall Bold + close IconButton (40.dp circle surfaceVariant .5α, `Close` icon).
- **KMP (current):** `TopAppBar` title `what_s_new`, NO back/close icon — relies on system back (`WhatsNewScreen.kt:195-199`).
- **Priority:** P2
- **Acceptance criteria:** Provide an explicit close affordance (X) consistent with native; title styling (`whats_new_title`, Bold) matches.
- **Notes:** On Desktop/iOS there is no hardware back, so an explicit close is functionally important — bumps real-world priority. String key divergence (`what_s_new` vs `whats_new_title`) to reconcile.

### GAP-WN-08 — Gradient background + responsive sizing missing
- **Screen/surface:** WhatsNewScreen container
- **Type:** VISUAL
- **OLD (target):** `WhatsNewScreen.kt:53-64` — full-screen vertical gradient (primaryContainer .3α → surface); responsive isTablet/isLandscape padding + media sizing; card `fillMaxHeight(0.85/0.75)`, r20, elev4; FeatureCard slide+fade `AnimatedVisibility` 250ms.
- **KMP (current):** `Scaffold` plain background; pager card = `surfaceVariant` r12, padding lg (`WhatsNewScreen.kt:261-370`); no gradient, no responsive sizing, no card slide/fade animation.
- **Priority:** P2
- **Acceptance criteria:** Apply the primaryContainer→surface vertical gradient and the per-card slide+fade animation; consider responsive card height. Card corner/elevation to match (r20/elev4) or accept design-system r12.
- **Notes:** Visual polish; pair with GAP-CMP-08 card-style decision for cluster consistency.

### GAP-WN-09 — PageIndicators pill geometry (24×8 selected) vs KMP dots
- **Screen/surface:** WhatsNewScreen PageIndicatorRow
- **Type:** VISUAL
- **OLD (target):** `WhatsNewComponents.kt:65-96` — selected pill 24×8 primary, unselected 8×8 outline .3α, r4 (elongated pill).
- **KMP (current):** dot row — selected 10.dp primary, unselected 8.dp onSurfaceVariant .3α (round dots) (`WhatsNewScreen.kt:307-330`).
- **Priority:** P3
- **Acceptance criteria:** Selected indicator is an elongated 24×8 pill (r4) matching native, not a 10.dp dot.
- **Notes:** Minor; same color intent, different shape.

---

## Welcome — gaps

### GAP-WEL-01 — Lottie animated background replaced by gradient (DEVIATION)
- **Screen/surface:** WelcomeScreen background
- **Type:** DEVIATION(platform)
- **OLD (target):** `WelcomeScreen.kt:118-132` — `AnimatedBackground` = Lottie raw resource `R.raw.background`, looping forever, `ContentScale.FillBounds`.
- **KMP (current):** NO Lottie — lottie-compose not on `:ui` classpath; `background.lottie` asset unused; substituted by a Compose-native `rememberInfiniteTransition` panning gradient (primary→secondary→base, 9000ms) (`WelcomeScreen.kt:124-154`, KMP note line 107). Task #743 only landed the About-logo half.
- **Priority:** P2
- **Acceptance criteria:** Either (a) add a multiplatform Lottie renderer (e.g. Compottie) to `:ui` and render `background.lottie` to match native, OR (b) formally accept the gradient as an intentional cross-platform DEVIATION and update task #743/KDoc to reflect it.
- **Notes:** Gradient "works" and is not broken; this is a fidelity gap, not a functional one. Compottie supports KMP if exact Lottie parity is required. Decision needed.

### GAP-WEL-02 — Welcome title/subtitle/CTA visual parity (verify)
- **Screen/surface:** WelcomeScreen content
- **Type:** VISUAL
- **OLD (target):** `WelcomeScreen.kt:42-116` — title `welcome_title` headlineLarge 36sp ExtraBold primary; subtitle `welcome_suptitle` bodyMedium 18sp onBackground .9α; Get-Started `Button` fillMaxWidth h52 r26 primary, label 18sp onPrimary; bottom-aligned Column padding 24 animateContentSize 600ms.
- **KMP (current):** Matches: title 36sp ExtraBold primary, subtitle 18sp .9α, Button fillMaxWidth h52 r26, label 18sp, Column padding 24 animateContentSize 600ms (`WelcomeScreen.kt:178-218`).
- **Priority:** P3
- **Acceptance criteria:** Confirmed visual parity for text/CTA; no change needed beyond background (GAP-WEL-01).
- **Notes:** Recorded as verified parity. Only the legibility overlay + background differ.

---

## About — gaps

### GAP-ABT-01 — Discord social button is a no-op (no URL) — parity but broken UX
- **Screen/surface:** AboutScreen SocialMediaRow
- **Type:** BEHAVIOR
- **OLD (target):** `SocialMediaRow.kt:33-164` — Discord uses `CustomIcons.Discord` with a no-op default callback (native also defaults to no-op unless overridden) — i.e. native Discord is ALSO inert by default.
- **KMP (current):** Discord button is present-but-no-op tap target; "Discord has no URL (no-op)" (`AboutScreen.kt:488-493`, KMP note line 119).
- **Priority:** P1
- **Acceptance criteria:** Provide the real Discord invite URL and wire the button to open it (the watch item explicitly flags "Discord URL"). If no canonical invite exists, hide the Discord button rather than ship a dead control.
- **Notes:** Both OLD and KMP ship Discord inert, so this is parity — but the task brief calls out Discord URL as a watch item, implying the intended behavior is a working invite (native had `openDiscordInvite` helper in `openLink.kt`). Open question: confirm canonical invite URL.

### GAP-ABT-02 — "Source code" row omitted (native had inert "soon" row)
- **Screen/surface:** AboutScreen items group
- **Type:** MISSING_FEATURE
- **OLD (target):** `AboutScreen.kt:108-149` — includes a "Source code" row (`soon` subtitle, `Icons.Outlined.Code`, inert/disabled).
- **KMP (current):** Source-code row intentionally omitted (`AboutScreen.kt` feature inventory line 94: "NOT present: Source-code row — intentionally omitted, disabled in legacy").
- **Priority:** P3
- **Acceptance criteria:** Decide: restore the inert "Source code / soon" row for visual parity, OR confirm intentional omission. RECOMMEND restore for 1:1 row parity (it's a cheap disabled row) unless product wants it gone.
- **Notes:** Native row was inert anyway; omission is low-impact. Documented KMP choice.

### GAP-ABT-03 — About logo size 120.dp vs native 250.dp
- **Screen/surface:** AboutScreen header logo
- **Type:** VISUAL
- **OLD (target):** `AboutScreen.kt:99-107` — `Image(R.drawable.ic_launcher_foreground)` 250.dp, vertical padding 24.
- **KMP (current):** logo `ic_launcher_foreground` 120.dp centered (`AboutScreen.kt:253-259`).
- **Priority:** P2
- **Acceptance criteria:** About logo renders at native 250.dp (or a deliberate responsive size), padding 24 — currently less than half native size.
- **Notes:** KMP KDoc wrongly says logo "dropped" though it renders (KMP note line 105). Fix size + correct the stale KDoc.

### GAP-ABT-04 — Header divider + ItemsGroup divider styling
- **Screen/surface:** AboutScreen body
- **Type:** VISUAL
- **OLD (target):** `AboutScreen.kt:108-149` — `Divider` (Gray .3α) under logo + Spacer 24; rows separated by Dividers (background-color .8α).
- **KMP (current):** Rows card with dividers `background` alpha 0.8 (`AboutScreen.kt:364-369`); a separate under-logo Gray .3α divider is not confirmed.
- **Priority:** P3
- **Acceptance criteria:** Under-logo divider (Gray .3α) present; inter-row dividers at .8α — match native.
- **Notes:** Minor; KMP uses a card-wrapped ItemsGroup (M3) vs native Material2 ItemsGroup — see GAP-ABT-06.

### GAP-ABT-05 — SocialMediaRow press-scale spring animation
- **Screen/surface:** AboutScreen social buttons
- **Type:** VISUAL
- **OLD (target):** `SocialMediaRow.kt:33-164` — each `SocialMediaButton` has scale-press spring animation (0.85f) on tap; circle primaryContainer .2–.3α; adaptive 36–56.dp button / 18–28.dp icon; SpaceEvenly.
- **KMP (current):** Social buttons = `IconButton` in primaryContainer-tinted circle, adaptive 36–56.dp / 18–28.dp (`AboutScreen.kt:444-542`); no press-scale spring noted.
- **Priority:** P3
- **Acceptance criteria:** Social buttons animate a 0.85f press-scale spring on tap matching native; sizing/alpha already aligned.
- **Notes:** Use `animateFloatAsState` on interaction-source pressed. Reuse android-compose-ui press-scale pattern.

### GAP-ABT-06 — About uses M3 vs native Material2 Scaffold/TopAppBar (REFACTOR — desirable)
- **Screen/surface:** AboutScreen scaffold
- **Type:** REFACTOR
- **OLD (target):** `AboutScreen.kt:70-84` — native About uses Material2 `Scaffold`/`TopAppBarCom`/`Divider`/`IconButton` (mixed-version, OLD note line 147).
- **KMP (current):** M3 `Scaffold` + `TopAppBar` (`AboutScreen.kt:223-232`).
- **Priority:** P3
- **Acceptance criteria:** Keep M3 (KMP correctly standardizes the cluster on M3; native's M2 in About was a legacy inconsistency). No revert.
- **Notes:** Desirable divergence; recorded so it isn't re-flagged as a regression.

### GAP-ABT-07 — Package-id footer label: KMP-EXTRA
- **Screen/surface:** AboutScreen footer
- **Type:** KMP-EXTRA
- **OLD (target):** Native About has no package-id footer label (feature inventory line 134 lists only logo/version/rows/social).
- **KMP (current):** `PackageLabel` footer (labelSmall centered onSurfaceVariant, package id) (`AboutScreen.kt:376-388`).
- **Priority:** P3
- **Acceptance criteria:** Decide keep-or-remove. RECOMMEND KEEP (harmless, informative) but note non-parity.
- **Notes:** Native showed versionName in the Version row only; KMP adds package id at bottom.

### GAP-ABT-08 — Social row button count / ordering parity
- **Screen/surface:** AboutScreen SocialMediaRow
- **Type:** VISUAL
- **OLD (target):** `SocialMediaRow.kt:33-164` — 6 buttons in order: X, Facebook, Instagram, WhatsApp, Discord, Website; WhatsApp prefilled message to `01558657735`.
- **KMP (current):** 6-button row X/Facebook/Instagram/WhatsApp/Discord-noop/Website (`AboutScreen.kt:444-542`) — order matches.
- **Priority:** P3
- **Acceptance criteria:** Confirm 6 buttons in native order; WhatsApp opens with the prefilled message + Egypt 0→20 normalization. (Discord covered by GAP-ABT-01.)
- **Notes:** Mostly parity; verify WhatsApp prefilled-message + phone normalization survived the port.

---

## Cross-cluster gaps

### GAP-CMP-26 — Shared LoadingState / ErrorState visual parity
- **Screen/surface:** Complaint + Admin + WhatsNew loading/error
- **Type:** VISUAL
- **OLD (target):** `LoadingState.kt:27-91` (rotating Refresh 64.dp 1000ms + message + LinearProgressIndicator); `ErrorState.kt:27-122` (errorContainer card r16, code-specific icon WifiOff/Lock/CloudOff/Error, `error_code_format` Monospace, Retry button).
- **KMP (current):** Complaint loading = plain centered `CircularProgressIndicator` (`ComplaintScreen.kt:230-232`); error = error-color text + Retry TextButton (`:269-289`). WhatsNew uses `YamiLoadingState`/`YamiErrorState`.
- **Priority:** P2
- **Acceptance criteria:** Loading/error states match native richness (animated loading indicator + message; error card with code-specific icon + error code + Retry). Use a shared `:ui` component (YamiStateViews) across the cluster.
- **Notes:** KMP already has `YamiLoadingState`/`YamiErrorState`/`YamiEmptyState` (memory: design-system). Apply them to Complaint screens too for consistency.

### GAP-CMP-27 — EmptyState icon + title + message vs KMP plain text
- **Screen/surface:** Complaint + Admin empty states
- **Type:** VISUAL
- **OLD (target):** `EmptyState.kt:26-65` — centered icon (Inbox 80.dp .6α) + title `titleLarge`/Medium + message `bodyMedium` (defaults `no_feedback_found`/`no_feedback_message`).
- **KMP (current):** User empty = centered `complaint_no_feedback` TEXT only (`ComplaintScreen.kt:240-247`); admin empty = `admincomplaint_no_complaints` text (`AdminComplaintScreen.kt:244-251`) — no icon/title.
- **Priority:** P2
- **Acceptance criteria:** Empty states render icon + title + message matching native; reuse shared `YamiEmptyState`.
- **Notes:** Pairs with GAP-CMP-17 (no-match icon). One shared component fixes both.

---

## Summary of dispositions
- **KEEP (KMP-EXTRA, document as intentional):** user snackbars (CMP-09), admin snackbars (CMP-21), subject-edit fix (CMP-10), NewChip (WN-06, ensure data-driven), package footer (ABT-07), M3 About (ABT-06).
- **REMOVE / resolve stale KDoc:** admin bulk-select (CMP-20).
- **DECIDE (platform/product):** Welcome Lottie vs gradient (WEL-01), admin per-row actions vs MENU (CMP-13), StatusChange radio vs one-tap (CMP-25), card visual language r12/surfaceVariant vs native r16/elevated (CMP-08).
- **VERIFY use-case payloads:** reply `replyto` metadata (CMP-11), closure `type.key:` + auto-CLOSED + metadata (CMP-23), WhatsApp prefill/normalization (ABT-08).


---

## WebView, Navigation, Shell & Theming — gaps

Gap count: 22 (WV: 11, NAV: 4, SHELL: 4, THEME: 3). Priority spread: P0 ×3, P1 ×8, P2 ×6, P3 ×5.

---

# ① WebView Screen

### GAP-WV-01 — Same-host URL sandbox (`shouldOverrideUrlLoading` / `isAllowed`) not ported
- **Screen/surface:** WebView
- **Type:** MISSING_FEATURE
- **OLD (target):** `WebViewComposeScreen.kt:188-211` (`shouldOverrideUrlLoading` blocks main-frame nav unless `isAllowed()` — same-host as `initialUrl`, http/https only, `urlValidationCache` at `:165-186`; sub-frame `javascript`/`file` schemes blocked; `about:`/`data:` always allowed).
- **KMP (current):** `WebViewComposeScreen.kt:142-280` + actuals — no override gate; Android/iOS/Desktop actuals just `loadUrl(url)` (kmp audit `:33`, `:35`).
- **Priority:** P1
- **Acceptance criteria:** Main-frame navigations to a host other than the initial host are blocked (no load occurs); same-host http/https loads proceed; `about:`/`data:` allowed; sub-frame `javascript:`/`file:` blocked. Verifiable by attempting an off-host link and asserting the URL bar / `onPageFinished` host is unchanged.
- **Notes:** Load-bearing for the Cloudflare flow — keeping the WebView pinned to the source host prevents redirect drift that loses the `cf_clearance` context. Implement in `WebViewController`/expect layer (a `shouldOverrideUrlLoading`-equivalent on Android `WebViewClient`, `decidePolicyForNavigationAction` on iOS WKWebView, `CefRequestHandler.onBeforeBrowse` on Desktop). `urlValidationCache` is an optimization, not required for parity.

### GAP-WV-02 — `shouldInterceptRequest` background cookie-merge channel dropped
- **Screen/surface:** WebView
- **Type:** BEHAVIOR
- **OLD (target):** `WebViewComposeScreen.kt:214-245` — `shouldInterceptRequest` fires per-request; when request host contains the initial host, a `Dispatchers.IO` coroutine reads `CookieManager.getCookie(initialUrl)`, merges into request headers as `Cookie`, posts to `savedHeaders` on Main.
- **KMP (current):** kmp audit `:30-33` — capture is now `onCookiesAvailable(cookieHeader)` fired from `onPageFinished` (Android `WebViewHost.android.kt:53`; iOS `:170-181`; Desktop `:275-307`). No per-request interception.
- **Priority:** P2
- **Acceptance criteria:** Cookies (specifically `cf_clearance` / `__cf_bm`) captured after a Cloudflare challenge are still surfaced into `savedHeaders` and persisted on Save. Confirm the captured Cookie header on a known Cloudflare-gated source matches what the OLD app saved.
- **Notes:** The KMP `onPageFinished`-driven capture is a deliberate substitute and likely sufficient (memory: Bug-4-layer-2 keeps Cookie+UA). Risk: if the challenge resolves via a sub-resource request without a full page-finish, the page-finished hook may miss it where `shouldInterceptRequest` would have caught it. Assess against a live CF source before downgrading to P3. Keep as a behavior-parity watch item, not a re-port.

### GAP-WV-03 — Render-process-gone crash recovery / auto-recreation removed
- **Screen/surface:** WebView (Android)
- **Type:** DEVIATION(platform)
- **OLD (target):** `WebViewComposeScreen.kt:277-293` — `onRenderProcessGone` cleans up, sets `webViewError=true`, bumps `recreationKey` to force recreation, clears history/headers; body shows `Text("WebView crashed. Recreating...")` (`:518`).
- **KMP (current):** kmp audit `:19` — no commonMain crash UI, no render-process-gone recovery/recreation; Android actual only `stopLoading()+destroy()` on dispose (`WebViewHost.android.kt:84-87`).
- **Priority:** P2
- **Acceptance criteria:** Document the deviation. On Android, a render-process crash should not hard-crash the app; minimum bar is graceful teardown. Full auto-recreation + "Recreating..." UI is optional. iOS/Desktop have no equivalent crash signal.
- **Notes:** Android WebView internal (`onRenderProcessGone` is `android.webkit`-only). Substitute: catch the platform crash callback in `WebViewHost.android.kt` and rebuild the controller, or accept teardown-only. Rationale: OOM render kills are an Android-specific failure mode; iOS/Desktop don't expose it. Recommend at least re-adding the Android crash hook so a CF page that OOMs doesn't take the activity down.

### GAP-WV-04 — Custom in-memory back/forward history (50-entry) + BackHandler not ported
- **Screen/surface:** WebView
- **Type:** BEHAVIOR
- **OLD (target):** `WebViewComposeScreen.kt:258-266` (capped 50-entry `mutableStateListOf<String>`, forward branch truncated on new nav, `historyIndex`) + `BackHandler` `:364-383` (steps back through in-memory history, else cleanup+close).
- **KMP (current):** kmp audit `:22,:24` — no `BackHandler`; uses the WebView's native back/forward list via the controller (`canGoBack()/canGoForward()`); system back pops the nav back stack normally.
- **Priority:** P2
- **Acceptance criteria:** Hardware/gesture back inside the WebView navigates back through page history before leaving the screen; only when no in-page history remains does back close the WebView. Forward button reachable after going back.
- **Notes:** Native back/forward list is the more correct model and is cross-platform — the divergence is acceptable. The user-visible gap is the back-button BEHAVIOR: in OLD, system back walked WebView history first; in KMP it pops the nav stack and leaves the screen. Add a `BackHandler` (Android) / equivalent that calls `controller.goBack()` while `canGoBack`, else closes. iOS/Desktop: wire the platform back affordance similarly. The 50-entry cap and forward-truncation are implementation detail, not parity targets.

### GAP-WV-05 — Pinch/zoom controls not configured
- **Screen/surface:** WebView (Android, iOS)
- **Type:** MISSING_FEATURE
- **OLD (target):** `WebViewComposeScreen.kt:126-128` — `setSupportZoom(true)`, `builtInZoomControls=true`, `displayZoomControls=false` (pinch zoom on, no on-screen buttons).
- **KMP (current):** kmp audit `:25` — not configured in commonMain; Android actual does not set `setSupportZoom`/`builtInZoomControls`.
- **Priority:** P2
- **Acceptance criteria:** Pinch-to-zoom works inside the WebView on Android (and iOS WKWebView, which is on by default); no visible zoom buttons. Verify by pinching a source page.
- **Notes:** Trivial Android fix in `WebViewHost.android.kt` settings block. iOS WKWebView allows zoom by default (likely already parity). Desktop KCEF has native zoom. Low effort, restores expected browser feel for image-heavy source pages.

### GAP-WV-06 — ANR-hardening WebSettings (cacheMode, hardware layer, focus guards) dropped
- **Screen/surface:** WebView (Android)
- **Type:** DEVIATION(platform)
- **OLD (target):** `WebViewComposeScreen.kt:116-151` — `cacheMode=LOAD_CACHE_ELSE_NETWORK` (ANR mitigation), `RenderPriority.HIGH`, `LAYER_TYPE_HARDWARE`, `allowContentAccess=false`, `allowFileAccess=false`, `mediaPlaybackRequiresUserGesture=true`, `mixedContentMode=COMPATIBILITY_MODE`, geolocation off, `isFocusable=false`/`isFocusableInTouchMode=false` (focus-crash dodge).
- **KMP (current):** kmp audit `:35` — Android actual sets JS+DOM+DB enabled, `loadWithOverviewMode`, `useWideViewPort`, optional UA override only; `cacheMode`, hardware-layer, focus-crash guards all ABSENT.
- **Priority:** P1
- **Acceptance criteria:** Restore the security-relevant settings on Android: `allowFileAccess=false`, `allowContentAccess=false`, file-URL access off, `mixedContentMode=COMPATIBILITY`, geolocation off, `mediaPlaybackRequiresUserGesture=true`. ANR cacheMode + hardware layer + focus guards are best-effort.
- **Notes:** Split priority — the `allowFileAccess`/`allowContentAccess`/mixed-content/geolocation flags are a SECURITY posture (P1, easy, must restore in `WebViewHost.android.kt`). The `cacheMode`/`LAYER_TYPE_HARDWARE`/focus-crash guards are Android-specific ANR/stability tuning (DEVIATION, optional). iOS/Desktop have no equivalents.

### GAP-WV-07 — Lifecycle pause/resume (timers) not wired on Android
- **Screen/surface:** WebView (Android)
- **Type:** DEVIATION(platform)
- **OLD (target):** `WebViewComposeScreen.kt:326-361` — `DisposableEffect` wires `onResume→resumeTimers`, `onPause→pauseTimers`, `onDestroy→cleanup`.
- **KMP (current):** kmp audit `:35` — lifecycle pause/resume from OLD absent; Android actual only `stopLoading()+destroy()` on dispose.
- **Priority:** P3
- **Acceptance criteria:** Document deviation. Optional: pause WebView JS timers when the app backgrounds to save battery/CPU.
- **Notes:** `pauseTimers`/`resumeTimers` are `android.webkit`-only and tied to Android lifecycle. iOS/Desktop manage this differently. Low user impact for a short-lived modal browser; safe to defer.

### GAP-WV-08 — `cleanupWebView` (clearCache/clearHistory/removeView/destroy) reduced
- **Screen/surface:** WebView (Android)
- **Type:** DEVIATION(platform)
- **OLD (target):** `WebViewComposeScreen.kt:86-99` — `clearFocus, stopLoading, clearCache(true), clearHistory, removeView from parent, destroy`, all try/catch-guarded; invoked on close/back/crash.
- **KMP (current):** kmp audit `:35` — Android actual does `stopLoading()+destroy()` on dispose only (`WebViewHost.android.kt:84-87`).
- **Priority:** P3
- **Acceptance criteria:** Document deviation. Ensure no WebView instance leak on repeated open/close (destroy is called). Full cache/history clear optional.
- **Notes:** `destroy()` covers the leak risk. `clearCache(true)`/`clearHistory` were partly tied to the crash-recovery path (GAP-WV-03). Bundle any revisit with WV-03/WV-04.

### GAP-WV-09 — WebView chrome strings hardcoded (un-localized)
- **Screen/surface:** WebView
- **Type:** VISUAL
- **OLD (target):** OLD uses `pageTitle.ifEmpty{"Loading..."}` literal too, but action icons carry no visible text; OLD title literal at `WebViewComposeScreen.kt:398`.
- **KMP (current):** `WebViewComposeScreen.kt:183,189,208,217,224,230` — hardcoded English literals "Loading...", "Close", "Back", "Forward", "Reload", "Save Headers" (kmp audit `:15,:248`). Every other surface in the app goes through compose-resources.
- **Priority:** P2
- **Acceptance criteria:** WebView chrome strings resolved via `stringResource(Res.string.…)` with Arabic parity entries; "Loading...", "Close", "Back", "Forward", "Reload", "Save Headers" all localized.
- **Notes:** Only un-localized surface in the cluster. Most are `contentDescription`s (a11y) rather than visible labels, so user-visible impact is small, but it breaks the app-wide localization invariant. Reuse existing keys where they exist (Close/Back are common).

### GAP-WV-10 — Desktop ignores per-browser User-Agent override
- **Screen/surface:** WebView (Desktop)
- **Type:** DEVIATION(platform)
- **OLD (target):** N/A (OLD is Android-only; UA override is implicit via Android WebView settings).
- **KMP (current):** `WebViewHost.desktop.kt:190-195` — `userAgent` input param IGNORED; JCEF has no per-browser UA override (kmp audit `:37`).
- **Priority:** P2
- **Acceptance criteria:** Document deviation. If a global JCEF UA can be set at client init, set it to match the captured/source UA so `cf_clearance` (UA-bound) stays valid on Desktop.
- **Notes:** JCEF limitation. Bug-4-layer-2 (memory) says `cf_clearance` is bound to the UA that earned it — on Desktop, if the browsing UA ≠ the UA later sent with image requests, the clearance cookie is rejected. Investigate `CefSettings`/command-line `--user-agent` at `KCEF` init as a workaround. Desktop-only; lower user base.

### GAP-WV-11 — `WebViewController` / nav-controls layer is a KMP addition
- **Screen/surface:** WebView
- **Type:** KMP-EXTRA
- **OLD (target):** OLD drove the WebView imperatively inside the composable; no controller abstraction (`WebViewComposeScreen.kt:102` `LaunchedEffect` building the instance).
- **KMP (current):** `core/webview/WebViewController.kt:20-57` + `WebViewHost.kt:22-31` expect/actual — clean controller exposing goBack/goForward/reload/progress (kmp audit `:13,:24`; restored by task #742).
- **Priority:** P3
- **Acceptance criteria:** N/A (improvement). No action needed; nav controls already at parity (Back/Forward/Reload/Save/Close all present, kmp audit `:23`).
- **Notes:** Healthy refactor enabling 3-platform support. Recorded for completeness; the OLD KDoc that forecast this as a TODO is now FULFILLED (kmp audit `:256`).

---

# ② Navigation graph

### GAP-NAV-01 — `LibraryMangaDetails(mangaId: Long)` route fully retired
- **Screen/surface:** Navigation graph
- **Type:** MISSING_FEATURE
- **OLD (target):** `NavGraphV2.kt:469-497` — `LibraryMangaDetails` (data, `mangaId:Long`) → `LibraryMangaRoute`, the by-ID local/offline details path. Reached from Library item click and `onOpenRandomClick` (`:284-300`).
- **KMP (current):** kmp audit `:92` — `LibraryMangaDetails` key fully retired (App.kt:104-109 comment); all details now route through `Screen.MangaDetails(mangaUrl, api)` → `MangaDetailsByUrlReworkScreenRoute`.
- **Priority:** P1
- **Acceptance criteria:** Tapping a library item opens its details from the LOCAL DB (offline-capable), not a fresh network-by-URL fetch; "Open random" from Library opens a random library manga's details offline. Behavior matches OLD's by-ID path.
- **Notes:** Cross-check with memory "Details offline-DB fix (REAL)" (`dae40c0`) — the offline local-DB Details path was added to `MangaDetailsByUrlRework`, which may already subsume the by-ID route's offline behavior. CONFIRM the rework url-based route resolves library items from DB (no network round-trip) before deciding effort. If it does, downgrade to P3/KMP-EXTRA (route consolidation). If library details still hit the network, this is a real P0 regression. Open question flagged.

### GAP-NAV-02 — Parallel `*Rework` debug routes are KMP-only additions
- **Screen/surface:** Navigation graph
- **Type:** KMP-EXTRA
- **OLD (target):** OLD has a single route per screen (route table `NavGraphV2.kt:56-132`).
- **KMP (current):** App.kt — 14 extra `*Rework` route keys (MangaDetailsRework, ChapterImagesRework, StatisticsRework, HistoryRework, UpdatesRework, SourcesRework, ThemeRework, AboutRework, WhatsNewRework, LanguageRework, ComplaintRework, ComplaintAdminRework, SettingsRework) marked "debug-only, not user-reachable" except `ComplaintAdminRework` (admin-reachable) and `HistoryRework`/`UpdatesRework` (show bottom bar) (kmp audit table rows, `:80-89`).
- **Priority:** P2
- **Acceptance criteria:** Confirm the debug-only `*Rework` routes are not user-reachable in release builds (no nav edge from user-facing UI). `HistoryRework`/`UpdatesRework` showing the bottom bar must not be reachable in release, or they'd present a duplicate tab experience.
- **Notes:** Intentional dev scaffolding. Cleanup item: gate the `composable<>` blocks behind a debug flag (or remove pre-release) so the route table is 1:1 with OLD in production. `ComplaintAdminRework` is the one with a real reachable edge — verify it's the intended admin path.

### GAP-NAV-03 — `safeNavigate` signature drift + debug `println` logging
- **Screen/surface:** Navigation utilities
- **Type:** REFACTOR
- **OLD (target):** `safePopBackStack.kt:138-158` — `safeNavigate(route, clearFocus=true, builder)` (focus-clearing wrapper).
- **KMP (current):** `navigation/safePopBackStack.kt:65-79` — `safeNavigate(route: Any, builder)`: NO `clearFocus` param; adds `[ReaderNav]` `println` invoke/success/failure debug prints (kmp audit `:107`).
- **Priority:** P3
- **Acceptance criteria:** `[ReaderNav]` `println` debug logging removed (or behind a debug flag) before release. The dropped `clearFocus` param is acceptable (commonMain has no decor-view focus model) — document, don't restore.
- **Notes:** `clearFocus`/`clearAllFocus(context)` was an Android FocusFinder-crash guard (`safePopBackStack.kt:103-108` OLD) tied to `decorView` — correctly dropped in commonMain. Only the stray `println`s are the cleanup target. If the focus-crash recurs on Android, re-add as an Android-actual hook, not commonMain.

### GAP-NAV-04 — `safePopBackStack` drops Android focus-clear guard (commonMain)
- **Screen/surface:** Navigation utilities (Android)
- **Type:** DEVIATION(platform)
- **OLD (target):** `safePopBackStack.kt:24-72` — clears decor-view focus before pop (FocusFinder crash guard), default `clearFocus=true`.
- **KMP (current):** `navigation/safePopBackStack.kt:8-34` — `runCatching` start-dest + pop/fallback-to-library; OLD's `clearFocus`/`clearAllFocus(context)` NOT present (kmp audit `:104`).
- **Priority:** P3
- **Acceptance criteria:** Document deviation. Watch for FocusFinder-related crashes on Android when popping from text-input screens; if observed, re-introduce focus clearing as an Android-platform hook.
- **Notes:** Android-specific (`decorView`/`rootView` focus). The 3-catch fallback-to-library safety is preserved, which is the load-bearing part. Pairs with GAP-NAV-03.

---

# ③ App Shell / Scaffold

### GAP-SHELL-01 — `MainActivity.onCreate` side-effect stack not present in common shell
- **Screen/surface:** App shell / startup
- **Type:** DEVIATION(platform)
- **OLD (target):** `MainActivity.kt:274-313,428-544` — Firebase `APP_OPEN` analytics; `updateSources.initializeSources()`; in-app update check (flexible); UMP ads consent (`requestConsent`→`loadAndShowForm`→`initializeAds`); in-app review; update progress listener; `onResume` resume-update; `onActivityResult` update result.
- **KMP (current):** kmp audit `:126` — NONE of these live in the common shell (`App()`/`MainScreen()`); deferred to platform hosts / `:platform` SPIs (INFERRED — outside scoped files).
- **Priority:** P1
- **Acceptance criteria:** Each OLD onCreate effect has a defined home and runs on Android with parity: (a) `updateSources.initializeSources()` runs at startup; (b) FCM, analytics APP_OPEN, in-app update, UMP consent, in-app review either run via `:platform` SPI on Android or are explicitly logged as descoped. Produce a checklist of which are wired vs descoped.
- **Notes:** Most are Android/Google-only (FCM, UMP, Play in-app-update/review, Firebase) → legitimately DEVIATION via `:platform` SPI on Android, no-op elsewhere. BUT `updateSources.initializeSources()` is core app behavior (sources won't refresh) — verify it runs somewhere in the Android host or a startup SPI. This audit cluster can't see platform hosts; flag as an open verification item with a real functional risk if `initializeSources` was lost. Split: source-init = functional P1; ads/update/review/FCM/analytics = platform deviations to enumerate.

### GAP-SHELL-02 — Shared graph-scoped ViewModels eliminated (esp. SharedChaptersViewModel)
- **Screen/surface:** App shell / Navigation
- **Type:** REFACTOR
- **OLD (target):** `NavGraphV2.kt:140-144` — 4–5 NavGraph-scoped VMs hoisted: `WhatsNewViewModel`, `RepoSettingsViewModel`, `SharedChaptersViewModel` (chapter list shared into reader), `DownloadViewModelv2`, `AdViewModel`. Used to pass chapter lists Home/Details/Library/History/Updates → reader (`:240-266`, `:438-465`, etc.).
- **KMP (current):** kmp audit `:49` — NONE; NavHost is a pure dispatcher; VMs resolved per-route-host via Koin.
- **Priority:** P1
- **Acceptance criteria:** The reader receives the correct chapter LIST (for prev/next navigation within the manga) when opened from Home, Details, Library, History, and Updates — not just the single tapped chapter. Verify in-reader chapter swiping/next-chapter works from each of the 5 entry points.
- **Notes:** `SharedChaptersViewModel` was the mechanism OLD used to hand the full chapter list to the reader. KMP's reader takes a 12-field legacy args tuple (route `ChapterImagesFragment`, kmp audit table) which may carry `paths`/chapter context, but the FULL list for in-reader prev/next needs a source. Memory ("Reader-convergence R4", parity campaign complete) suggests the reader was reworked — CONFIRM list-passing parity. If the reworked reader fetches its own chapter list, this is a clean refactor (downgrade to KMP-EXTRA). If next/prev-chapter is broken from some entry points, it's a P0. Open question.

### GAP-SHELL-03 — Navigation cross-link guards/toasts (deleted-manga, random-empty) parity
- **Screen/surface:** Navigation cross-links (History/Updates/Library)
- **Type:** BEHAVIOR
- **OLD (target):** `NavGraphV2.kt:325-336,356-384` — History/Updates guard `sharedChaptersVm.isMangaExists(mangaId)` else Toast "THis Manga Is Deleted from the Libarary" (sic); `:284-300` Library random → Toast "No manga in your library yet!" when empty.
- **KMP (current):** kmp audit — cross-links described (`:94`) route Details→reader/downloads/webview and onboarding chain, but the `isMangaExists` guard + the two specific Toasts are not enumerated (and `SharedChaptersViewModel.isMangaExists` is gone per GAP-SHELL-02).
- **Priority:** P2
- **Acceptance criteria:** Tapping a History/Updates entry whose manga was removed from the library shows a "deleted from library" message instead of opening a broken screen; "Open random" on an empty library shows the empty message. Both messages localized.
- **Notes:** Tie to GAP-SHELL-02 (the guard used `SharedChaptersViewModel`). Re-home the existence check on whatever repo the rework History/Updates routes use. The OLD Toast strings have typos ("THis", "Libarary") — fix in the localized rework copy, don't reproduce verbatim.

### GAP-SHELL-04 — Singleton ImageLoader / `CoilSourceHeaderInterceptor` is a KMP addition
- **Screen/surface:** App shell
- **Type:** KMP-EXTRA
- **OLD (target):** OLD registers AVIF decoder on Coil per memory but had no common ImageLoader-factory wiring at shell scope in this audit.
- **KMP (current):** `App.kt:266-291` (`CoilSourceHeaderInterceptor`, Bug-4-layer-3 per-source header injection) + `:306-324` (singleton ImageLoader: OkHttp forced Android, AVIF decoder Android, `maxBitmapSize(Size.ORIGINAL)`).
- **Priority:** P3
- **Acceptance criteria:** N/A (improvement). Ensure parity invariants from memory hold: AVIF decoder registered on Android, OkHttp fetcher forced on Android, `maxBitmapSize` lifts the 4096 cap. No regression.
- **Notes:** Directly encodes several memory items (AVIF decoder, OkHttp fetcher, image-quality buildRequest, Skia size cap). Recorded so a future sweep doesn't "simplify" these away — they are load-bearing for image quality. Remove the `[CoilDbg]` `println`s before release.

---

# ④ Theming

### GAP-THEME-01 — App-root still binds legacy `YamiMangaTheme`, not rework `YamiTheme`
- **Screen/surface:** App shell / Theming
- **Type:** REFACTOR
- **OLD (target):** N/A for the rewire itself; OLD wraps content in `YamiMangaTheme(darkTheme, pureBlack)` (`MainActivity.kt:339-342`) — and the rework's own design system defines `YamiTheme` (`ui/theme/YamiTheme.kt:88-114`) as the intended app-root.
- **KMP (current):** `App.kt:17,332` — app root imports/calls LEGACY `me.manga.kira.theme.YamiMangaTheme` (in `composeApp`), NOT the rework `:ui` `YamiTheme`. `YamiTheme` is used only at leaf-screen scope; the root rewire is FORECAST-NOT-YET-FULFILLED (kmp audit `:132`, `YamiTheme.kt:55-66` postscript).
- **Priority:** P1
- **Acceptance criteria:** App root (`App()`/`MainScreen`) wraps content in the rework `:ui` `YamiTheme(darkTheme, pureBlack, dynamicColor, content)`; the legacy `composeApp` `YamiMangaTheme` is removed (or reduced to a thin alias). Verify identical visuals (colors/typography/shapes are byte-for-byte parity per kmp audit `:206,:214,:228`) so the swap is non-visual. Leaf screens that already call `YamiTheme` get the same theme from root (no double-wrap).
- **Notes:** This is the explicitly-called-out app-root theme rewire. Because tokens are byte-for-byte identical (YamiColors/YamiTypography/YamiShapes mirror legacy Theme.kt), the swap should be visually inert — that's the test: screenshot diff before/after must be empty. Watch for double-theming: leaf screens currently re-wrap in `YamiTheme`; after the root rewire, remove redundant leaf wraps or confirm they're idempotent. Also fold `LocalSpacing`/`Spacing()` provision (currently in `YamiTheme`) into the now-root path so spacing is available app-wide.

### GAP-THEME-02 — Dynamic color is a documented no-op (DynamicColorProvider SPI missing)
- **Screen/surface:** Theming
- **Type:** DEVIATION(platform)
- **OLD (target):** `theme/Theme.kt:101,108-111` — dynamic color supported (Android 31+ Material You) but default OFF (`dynamicColor=false`).
- **KMP (current):** `ui/theme/YamiTheme.kt:67-81,92` — `dynamicColor` param present but `@Suppress("UNUSED_PARAMETER")` NO-OP, awaiting a `:platform DynamicColorProvider` SPI (FORECAST-NOT-YET-FULFILLED). Same OFF-by-default posture as OLD (kmp audit `:225`).
- **Priority:** P3
- **Acceptance criteria:** Document deviation. Since OLD defaults dynamic color OFF, no user-visible parity gap today. If/when a user toggle for dynamic color is exposed, implement the `:platform DynamicColorProvider` SPI (Android `dynamicDarkColorScheme`/`dynamicLightColorScheme`; no-op iOS/Desktop).
- **Notes:** Android-only Material You feature → platform SPI is the right home. No parity gap while both default OFF. Pure forecast item.

### GAP-THEME-03 — Unreferenced fonts (Gilroy/Poppins/alba/extra-Gellix) shipped in `:composeApp`
- **Screen/surface:** Theming / assets
- **Type:** REFACTOR
- **OLD (target):** `theme/Type.kt:12-16` wires only 3 Gellix weights; Gilroy/Poppins/alba ship in `res/font/` but are unreferenced by the Compose theme (OLD audit `:228,:293`).
- **KMP (current):** kmp audit `:199` — `:ui` ships only the lean 3-Gellix set (good); but `:composeApp/.../composeResources/font/` still carries `alba.TTF`, extra Gellix weights, Gilroy ×3, Poppins ×7 — unreferenced (INFERRED dead assets, same situation as OLD).
- **Priority:** P3
- **Acceptance criteria:** Confirm no rework code references Gilroy/Poppins/alba/extra-Gellix; if confirmed dead, remove from `:composeApp/composeResources/font/` to shrink the bundle. No visual change.
- **Notes:** Parity-neutral (OLD also shipped them dead). Pure bundle-size cleanup. Verify no XML layout / legacy `composeApp` screen still references them before deleting. Low priority.

