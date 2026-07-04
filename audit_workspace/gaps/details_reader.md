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
