# Phase 7.x.whatsnew.pager — HorizontalPager + page-indicator dots on the rework WhatsNew screen

## Context

Phase 7.x.whatsnew (commits `170a93f` → `e5d91b0`) shipped the rework
WhatsNew foundation: a `LazyColumn` rendering each feature as a stacked
`Card`. The legacy WhatsNew screen at
`composeApp/.../presentation/features/whatsnew/ui/WhatsNewScreen.kt`
treats each `WhatsNewFeature` as a full-screen swipeable page with a
`HorizontalPager` + page-indicator dot row + (legacy-only) nav button
row.

ARCHITECTURE.md §88.7 + SOLID_AUDIT.md Phase 7.x.whatsnew End-of-slice
verdict both named `Phase 7.x.whatsnew.pager` as the next-smallest
WhatsNew sub-slice. Phase 7.x.about.whatsnewrow (commits `46bb0d3` →
`d0f719e`) just closed the cross-cutting About-row loop; this slice
picks the next-smallest follow-on, keeping the WhatsNew thread hot.

Block-and-ask triggers (a)-(d) all NOT met:

- (a) no contract library blocker — `HorizontalPager` and
  `rememberPagerState` already used in `:ui` by the Reader screen
  (`ui/.../reader/ReaderScreen.kt:23-25`); `compose-foundation` already
  on the `:ui` dependency graph.
- (b) no observable behaviour change at the end-user level — legacy
  `Screen.WhatsNewScreen` stays bound to its legacy route; rework
  `Screen.WhatsNewRework` only reached via dev nav (debug-guarded) and
  via the new About → WhatsNew in-app edge from Phase 7.x.about.whatsnewrow.
  Both pre-slice and post-slice the rework route renders WhatsNew content;
  the layout differs but no end-user surface depends on the foundation's
  flat-list shape.
- (c) no compile risk — additive MVI surface extension only. State gains
  one field with a default value (`currentPage: Int = 0`); intent surface
  gains one `data class` variant; VM's `when` block gains one arm.
- (d) no SOLID violation — the slice is OCP §6 pure (additive sealed-type
  growth + additive state field). DIP unchanged (no new layer reach).

## Approach

Additive MVI surface extension:

1. **`:presentation/.../WhatsNewState.kt`** — add `currentPage: Int = 0`
   field. KDoc lifts the "Tab/pager index NOT modelled" deferral from
   Phase 7.x.whatsnew §88.7. The field defaults to `0` (first page) so
   existing state construction is unbroken.

2. **`:presentation/.../WhatsNewIntent.kt`** — add
   `data class OnPageChanged(val index: Int) : WhatsNewIntent`. KDoc
   lifts the "no `OnPageChanged` in foundation" deferral. Per-target
   `data class` (not `data object`) because the intent carries an `Int`
   payload from the pager's `currentPage`.

3. **`:presentation/.../WhatsNewViewModel.kt`** — add
   `is OnPageChanged → updateState { it.copy(currentPage = intent.index) }`
   arm to the exhaustive `when (intent)` block. KDoc updated to describe
   the third intent → state mutation. The `currentPage` state mutation
   is a pure passthrough (no side-effects, no use case invocation) — same
   shape as the About slice's `OnOpenPlayStore → emit(...)` arm but with
   a state mutation rather than an effect emission. The new arm respects
   the existing fresh-fetch `loadFeatures()` reset behaviour (on
   `OnRetry`, the existing `loadFeatures()` rebuilds state from scratch,
   which now also resets `currentPage` to its `0` default — desirable:
   retry should land the user back at page 0).

4. **`:ui/.../whatsnew/WhatsNewScreen.kt`** — replace the `FeatureList`
   private composable's body. New behaviour:
   - Wrap features in a `HorizontalPager` (`beyondViewportPageCount = 1`,
     `pageSpacing = 0.dp` to mirror the legacy posture).
   - Each pager page renders one `FeatureCard` filling the full pager
     viewport (the existing `FeatureCard` shape is unchanged; it stays
     the same Card + Column composable, just inside the pager's `page`
     lambda instead of `LazyColumn`'s `items()`).
   - Below the pager: `PageIndicatorRow` — a centered `Row` of small dot
     `Box`es, one per feature, with the current dot widened
     (`width = 24.dp` selected, `8.dp` otherwise) and primary-coloured.
     Mirrors the legacy `PageIndicators` composable's posture inline —
     no new file, no `material-icons-extended` reach.
   - Bi-directional sync: `LaunchedEffect(pagerState.currentPage)`
     observes pager scroll and dispatches `OnPageChanged(index)` to the
     VM. The reverse direction is NOT modelled today (no programmatic
     jump-to-page needed yet; deferred to a follow-on slice if
     deep-linking to a feature index becomes a requirement).
   - `rememberPagerState(initialPage = state.currentPage, pageCount = ...)`
     seeds the pager at the VM-known position (relevant for screen
     recompositions / config-change).

The four-state branch (Loading / Error / Empty / Loaded) stays
unchanged — only the Loaded branch's rendering shape changes.

### Pager state ownership — VM-owned (with Compose mirror) over Compose-only

The legacy keeps `pagerState` purely Compose-local (`rememberPagerState`
+ `derivedStateOf`). This slice puts `currentPage: Int` in the VM state
because:

1. **Strict-MVI discipline (§17)**: any state that survives screen
   recomposition should live in the VM. Compose-local pager state survives
   config-change via `rememberSaveable` semantics, but a future
   deep-link or "open feature N" cross-cutting slice would force a
   refactor. Putting `currentPage` in the VM now keeps that door open
   without churn.
2. **Consistency with the existing slice**: `WhatsNewState` already
   carries `isLoading` / `features` / `errorMessage` — adding
   `currentPage` matches the field shape and the same posture as
   the Reader's `state.currentPageIndex` (which DOES live in the VM
   per Phase 7.x.reader.resumeposition).
3. **OCP-friendly extensibility**: the SOLID_AUDIT Phase 7.x.whatsnew
   entry's "next candidate" block named `OnPageChanged(index: Int)` as
   the future intent — landing it now closes the gap exactly as
   anticipated.

The Compose-side `pagerState` (still a `rememberPagerState` instance)
remains the Compose-side source of truth FOR THE PAGER WIDGET; the
LaunchedEffect bridges its `currentPage` Flow into the VM via
`OnPageChanged`. This is the same bi-directional sync pattern the
Reader's `pagerState` ↔ `state.currentPageIndex` uses (Phase
7.x.reader.modelayout.scrollpos §74.x).

### NavigationButtons NOT modelled in this slice

Legacy renders a "Previous" / "Next" / "Get-started" row below the page
indicators. The rework explicitly skips these because:

1. **Nav-stack dismiss already exists** — the rework `WhatsNewScreen` is
   reached via the standard Compose nav stack (NOT a modal overlay like
   the legacy). System back / nav-back arrow handles dismiss; the
   "Get-started" button would be a redundant pop-back.
2. **Swipe + tap-dot is sufficient** — pager swipes navigate forward /
   backward; tapping the indicator dot row's current dot is the standard
   Material 3 pager-dot interaction (deferred — not in this slice).
3. **Smaller blast radius** — 4 files vs 5 if we ported NavigationButtons
   too. NavigationButtons can lift in a follow-on
   `Phase 7.x.whatsnew.navbuttons` sub-slice if user feedback requires
   them.

### NewChip Spacer behaviour preserved

The existing `FeatureCard` uses `Spacer(Modifier.height(0.dp))` inside
the `if (feature.isNew)` block (foundation code line 274). That's a
no-op spacer; the slice leaves it as-is to keep the diff minimal.
Whether the spacer is removed or replaced with a proper horizontal
spacer is a `Phase 7.x.whatsnew.polish` follow-on concern.

## Commit roadmap

3 commits, all ≤5 files per the standing cap:

1. **Plan** — `PLAN_whatsnew_pager.md` only (1 file).

2. **Impl** — 4 files (below cap, +1 buffer):
   - `presentation/.../whatsnew/WhatsNewState.kt` — add `currentPage: Int = 0`
     field + KDoc update.
   - `presentation/.../whatsnew/WhatsNewIntent.kt` — add
     `data class OnPageChanged(val index: Int) : WhatsNewIntent` + KDoc
     update.
   - `presentation/.../whatsnew/WhatsNewViewModel.kt` — add `when` arm
     + KDoc update.
   - `ui/.../whatsnew/WhatsNewScreen.kt` — replace `FeatureList`'s body
     with `HorizontalPager` + page indicator dot row + bi-directional
     sync `LaunchedEffect`; KDoc visual-delta lift.

3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append §90 — Phase 7.x.whatsnew.pager with
     subsections: §90.1 Strategy, §90.2 Layer-by-layer surfaces (4 file
     changes), §90.3 MVI shape rationale (VM-owned `currentPage` over
     Compose-local), §90.4 Strangler-fig boundary (zero new reaches —
     pure in-stack MVI surface extension), §90.5 Files modified, §90.6
     Build gates, §90.7 Remaining WhatsNew deferrals (images, video,
     fullscreen, gate, i18n, navbuttons — Phase 7.x.whatsnew §88.7
     items still deferred).
   - `SOLID_AUDIT.md` — append Phase 7.x.whatsnew.pager entry with
     per-file 10-point SOLID checklists for all 4 modified files +
     End-of-slice verdict.

## Critical files

### New

- `PLAN_whatsnew_pager.md`

### Modified

- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/whatsnew/WhatsNewState.kt` — add `currentPage` + KDoc.
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/whatsnew/WhatsNewIntent.kt` — add `OnPageChanged` + KDoc.
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/whatsnew/WhatsNewViewModel.kt` — add `when` arm + KDoc.
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/whatsnew/WhatsNewScreen.kt` — HorizontalPager + page indicators + bi-directional sync + KDoc.
- `ARCHITECTURE.md` — append §90.
- `SOLID_AUDIT.md` — append Phase 7.x.whatsnew.pager entry.

### Untouched (verify by read, not modified)

- `presentation/.../whatsnew/WhatsNewEffect.kt` — empty sealed interface;
  no new effect needed (pager changes are intent-driven state mutations).
- `data/.../repository/WhatsNewRepositoryImpl.kt` — strangler-fig impl
  unchanged; no new data source reach.
- `composeApp/.../navigation/routes/WhatsNewReworkScreenRoute.kt` — route
  adapter unchanged (effect handler stays no-op-ish; the bi-directional
  pager sync is intra-screen state, not route-adapter business).
- `composeApp/.../di/WhatsNewReworkModule.kt` — Koin bindings unchanged
  (no new use cases / repos needed).

## Reuse

- **HorizontalPager + rememberPagerState**: pattern lifted from the
  Reader screen's mode-layout branch (`ui/.../reader/ReaderScreen.kt`
  for LTR / RIGHT_TO_LEFT modes). Same `compose-foundation` dependency
  edge, same Compose-side state-ownership pattern.
- **LaunchedEffect bi-directional sync**: same shape as the Reader's
  `pagerState.currentPage` → `state.currentPageIndex` sync (Phase
  7.x.reader.modelayout.scrollpos §74.x).
- **Page indicator dot row**: inline composable (no separate file),
  mirroring the legacy `PageIndicators` posture. The
  `material-icons-extended` icon set is NOT pulled in (preserving the
  `:ui` module's icon-free posture).
- **State + Intent + ViewModel `when` arm**: same additive MVI surface
  extension shape as Phase 7.x.about.whatsnewrow's
  `OnOpenWhatsNew` / `NavigateToWhatsNew` addition.

## Verification

After the impl commit:

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — required (touches
  `:presentation/commonMain` + `:ui/commonMain`).
- `gradlew.bat :composeApp:compileKotlinIosArm64` — required (commonMain
  changes link into the iOS framework).
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — required.
- `gradlew.bat :composeApp:compileKotlinDesktop` — required (`:ui`
  composable touched; Desktop entry point links the change).

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Build with the rework debug flag.
- Navigate to `Screen.WhatsNewRework` via the rework About → Whats-new
  row (validated by Phase 7.x.about.whatsnewrow).
- Verify the loaded state renders ONE feature per pager page (not a
  stacked list).
- Swipe left / right; verify page-indicator dots track the active page
  (selected dot is wider + primary-coloured).
- Verify state survives screen recomposition (rotate device / orientation
  change on Android; resize window on Desktop) — the pager seeds at
  `state.currentPage`.
- Verify back navigation returns to the rework About screen.

Edge cases to mentally model:

- Empty feature list: the `EmptyState` branch handles this BEFORE the
  pager renders. The pager is only reached when `features.isNotEmpty()`;
  `pagerState.pageCount = features.size > 0` is guaranteed.
- Single feature: pager renders one page; the page-indicator dot row
  renders one (wide, primary-coloured) dot. No degenerate behaviour.
- Pager scroll during state update (e.g., re-fetch triggers
  `OnRetry` mid-swipe): `OnRetry` resets `features` via `loadFeatures()`
  which sets `isLoading = true` first — the Loaded branch un-renders
  and the Loading spinner takes over. When the re-fetch completes, the
  Loaded branch re-renders with `currentPage` defaulted to `0` (since
  state was reconstructed from scratch by `loadFeatures()`). Desirable
  behaviour: retry resets the user to page 0.

## Deferrals

- **NavigationButtons** — legacy "Previous" / "Next" / "Get-started"
  row deferred. Back-nav handles dismiss; swipe handles page nav.
  Lifts as `Phase 7.x.whatsnew.navbuttons` if user feedback requires.
- **Indicator-dot tap-to-jump** — legacy doesn't have this (dots are
  display-only); rework matches.
- **Image rendering** — `Phase 7.x.whatsnew.images` still deferred
  (Coil dependency edit + compose-resources binding).
- **Video rendering** — `Phase 7.x.whatsnew.video` still deferred
  (new `:platform` MediaPlayer SPI + 3 actuals).
- **Fullscreen viewer** — `Phase 7.x.whatsnew.fullscreen` still
  deferred (depends on .images + .video).
- **Should-show auto-trigger gate** — `Phase 7.x.whatsnew.gate` still
  deferred (cross-cutting About/Home MVI extension).
- **Language localization** — `Phase 7.x.whatsnew.i18n` (or Phase 10)
  still deferred.
- **NewChip horizontal-spacer polish** — the no-op
  `Spacer(Modifier.height(0.dp))` in `FeatureCard`'s `isNew` branch is
  preserved as-is. `Phase 7.x.whatsnew.polish` can lift to a proper
  horizontal `Spacer(Modifier.width(spacing.sm))`.
- **No nav graph route-swap** — legacy `Screen.WhatsNewScreen` stays
  bound to the legacy route. Phase 9.x route-swap is its own slice.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: each modified file retains its single responsibility —
  `WhatsNewState` enumerates view-state fields; `WhatsNewIntent`
  enumerates intents; `WhatsNewViewModel` orchestrates; `WhatsNewScreen`
  renders + dispatches. The new `currentPage` field + `OnPageChanged`
  intent + new `when` arm + new composable surface change all stay
  within each file's existing responsibility.
- **OCP**: state data class grows by one field with a default value
  (no existing call sites break — `WhatsNewState()` continues to
  compile because `currentPage = 0` is defaulted). Sealed intent
  hierarchy grows by one `data class` variant. VM's `when (intent)`
  block grows one exhaustive arm. Each addition is OCP §6-clean.
- **DIP**: zero new layer crossings. The bi-directional pager sync is
  intra-`:ui` (composable owns `pagerState`; LaunchedEffect dispatches
  to the VM via the existing `onIntent` lambda) and intra-`:presentation`
  (VM handles `OnPageChanged` via existing `updateState`).
- **Layer boundary**: changes touch `:presentation` (3 mods) and `:ui`
  (1 mod). No `:composeApp` touch (the route adapter handles only
  effects, and no new effects are emitted).
- **Banned features**: no `!!`, no `Any`, no `lateinit`, no `Thread`.
  `currentPage: Int` is a primitive. `OnPageChanged` is a `data class`
  with one `val index: Int` constructor param.
- **MVI contract**: existing slice extension — adds `currentPage: Int`
  to `WhatsNewState` + `OnPageChanged(index: Int) : WhatsNewIntent`
  `data class`. State remains a single immutable data class; intents
  remain a sealed interface. No reducer pattern introduced (state is
  intent-driven via `updateState { it.copy(...) }`).
- **Strangler-fig**: ZERO new `:shared` reaches. This is pure in-stack
  MVI surface extension — `:data` impl unchanged, route adapter
  unchanged, Koin module unchanged. No legacy facade extension.
- **Load-bearing fixes preserved**: this slice touches NO load-bearing
  paths (no Coil, no Reader decoder hints, no `maxBitmapSize`, no
  OkHttp interceptor, no AVIF decoder, no per-host repo registry, no
  HighQualitySkiaImageDecoder, no Android RGB_565 / `allowHardware
  (false)` / OkHttp fetcher override). No load-bearing risk.
