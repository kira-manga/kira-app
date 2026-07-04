# Phase 7.x.about.whatsnewrow — wire the deferred Whats-new row on the rework About screen

## Context

Phase 7.x.about (commits `eda7571` → `666d538`) ported the legacy About
screen to the rework stack but deliberately deferred four rows: Whats-new,
Source-code, SocialMediaRow, and the app-icon image. The reason for
Whats-new was specifically that `Screen.WhatsNewRework` didn't exist —
porting a row that navigates to a nonexistent destination would either
require a placeholder TODO or block on a separate slice.

Phase 7.x.whatsnew (commits `170a93f` → `e5d91b0`) just shipped the
foundation `Screen.WhatsNewRework` + `WhatsNewReworkScreenRoute`, so the
Whats-new row's blocker is gone. This slice closes the cross-cutting loop.

This is the smallest-possible follow-on slice: 5 source files touched, plus
plan + close-out docs. All block-and-ask triggers (a)-(d) NOT met.

## Approach

Add the `OnOpenWhatsNew` intent and `NavigateToWhatsNew` effect to the
existing `AboutIntent` / `AboutEffect` sealed hierarchies (additive — no
breaking changes), grow the `AboutViewModel.handle()` `when` block by one
arm, add a single `AboutRow` to `AboutScreen` between "Privacy policy" and
the package label footer, and dispatch the new effect from the
`AboutReworkScreenRoute` adapter via `navController.navigate(Screen.
WhatsNewRework)`.

The route adapter's `navController` parameter changes from
`@Suppress("UNUSED_PARAMETER")` to actually-used — the suppression
annotation is removed (no other unused params at the call site).

The `Screen.WhatsNewRework` route is reachable already via direct nav-bar
testing, but no in-app surface lights up the route today. This slice
makes the rework About screen the first in-app entry point for the rework
WhatsNew route. End-user discoverability still depends on Phase 9.x
route-swap (which retires the legacy About + WhatsNew routes); until then,
both pairs of routes (legacy + rework) coexist and the rework About →
rework WhatsNew flow is exercised via developer trigger / debug nav entry.

### MVI shape decision: `data object` for both new variants

`OnOpenWhatsNew` carries no params (the target route is a parameterless
`Screen.WhatsNewRework` object). `NavigateToWhatsNew` also carries no
params (the route adapter knows the target destination). Both fit as
`data object` cleanly, matching the existing `OnOpenPlayStore` and the
empty-state effect patterns in the rework slices.

### Why not `OnOpenScreen(target: Screen)` / `NavigateTo(target: Screen)`

A generic "navigate-to" intent / effect with a `Screen` payload would
"generalise" the new variants for future rows. Rejected because:

1. It pulls `Screen` (a `:composeApp`-layer type) into `:presentation`,
   reversing the layer-dependency arrow.
2. The other rework slices that need in-app nav (Reader → next chapter,
   Details → reader entry) all use per-target effects, not a generic
   carrier. Consistency with the established pattern wins.
3. Adding per-row intents is cheap (one `data object` + one `when` arm),
   and OCP-friendly (each new row gets explicit dispatch logic at every
   layer — no implicit "navigate-anywhere" hatch).

### Why no nav callback parameter on `AboutScreen`

The `AboutScreen` already takes a single `onEffect: (AboutEffect) -> Unit`
callback. Adding `onNavigateToWhatsNew: () -> Unit` would force the
screen signature to grow as new effects are added. The single-callback
pattern keeps `AboutScreen(viewModel, onEffect, modifier)` stable. Same
posture as the Reader's effect-callback channel decision (Phase 7.x.reader.
modelayout.openwebview §7.x.reader.modelayout.openwebview).

## Commit roadmap

3 commits, all ≤5 files per the standing cap:

1. **Plan** — `PLAN_about_whatsnewrow.md` only (1 file).

2. **Impl** — 5 files at the cap:
   - `presentation/.../about/AboutIntent.kt` — add
     `data object OnOpenWhatsNew : AboutIntent` + KDoc update covering the
     Phase 7.x.about deferral lifted, OCP rationale, parameterless-design.
   - `presentation/.../about/AboutEffect.kt` — add
     `data object NavigateToWhatsNew : AboutEffect` + KDoc update covering
     the route-adapter contract (`navController.navigate(Screen.
     WhatsNewRework)`), why no in-app launcher reach.
   - `presentation/.../about/AboutViewModel.kt` — add `OnOpenWhatsNew →
     emit(NavigateToWhatsNew)` arm; update class-level KDoc to mention the
     new arm + cross-reference Phase 7.x.about.whatsnewrow.
   - `ui/.../about/AboutScreen.kt` — add new `AboutRow(title = "What's
     new", onClick = { onIntent(AboutIntent.OnOpenWhatsNew) })` between
     "Privacy policy" and the package label footer; KDoc update lifting
     the visual-delta line covering the Whats-new row deferral; row count
     goes from 4 to 5.
   - `composeApp/.../navigation/routes/AboutReworkScreenRoute.kt` — add
     `AboutEffect.NavigateToWhatsNew → navController.navigate(Screen.
     WhatsNewRework)` arm in the `when (effect)` block; remove the
     `@Suppress("UNUSED_PARAMETER")` from `navController` (now used);
     update KDoc to cover the new dispatch path + cross-reference Phase
     7.x.about.whatsnewrow + Phase 7.x.whatsnew foundation.

3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append §89 — Phase 7.x.about.whatsnewrow with
     subsections: §89.1 Strategy, §89.2 Layer-by-layer surfaces (5 file
     changes), §89.3 MVI shape rationale (per-target variants over
     generic carrier), §89.4 Strangler-fig boundary (zero new reaches —
     pure in-stack rework cross-cutting), §89.5 Files modified, §89.6
     Build gates, §89.7 Remaining About-screen deferrals (Source-code +
     SocialMediaRow + app-icon image — Phase 7.x.about §87.7 items
     still deferred).
   - `SOLID_AUDIT.md` — append Phase 7.x.about.whatsnewrow entry with
     per-file 10-point SOLID checklists for all 5 modified files +
     End-of-slice verdict.

## Critical files

### New

- `PLAN_about_whatsnewrow.md`

### Modified

- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/about/AboutIntent.kt` — add `OnOpenWhatsNew` variant + KDoc.
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/about/AboutEffect.kt` — add `NavigateToWhatsNew` variant + KDoc.
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/about/AboutViewModel.kt` — add `when` arm + KDoc.
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/about/AboutScreen.kt` — add row + KDoc.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/AboutReworkScreenRoute.kt` — add `when` arm + KDoc + un-suppress `navController`.
- `ARCHITECTURE.md` — append §89.
- `SOLID_AUDIT.md` — append Phase 7.x.about.whatsnewrow entry.

### Untouched (verify by read, not modified)

- `composeApp/.../di/AboutReworkModule.kt` — Koin bindings unchanged
  (no new use cases / repos needed; nav is `navController.navigate(...)`,
  not Koin-resolved).
- `composeApp/.../navigation/Screen.kt` — `Screen.WhatsNewRework` already
  added by Phase 7.x.whatsnew.
- `composeApp/.../App.kt` — `composable<Screen.WhatsNewRework>` already
  registered by Phase 7.x.whatsnew.
- `presentation/.../about/AboutState.kt` — no new state fields needed
  (the nav action is fire-and-forget; no result, no loading flag).

## Reuse

- **Effect-bridging shape**: single `onEffect: (AboutEffect) -> Unit`
  callback on `AboutScreen`; route adapter `when (effect)` block. No new
  pattern.
- **Per-target intent / effect variants**: matches the existing
  `OnOpenPlayStore` / `OnOpenUrl` shape — per-target dispatch, no generic
  carrier. Same posture as Reader's per-effect variants.
- **Route adapter nav dispatch**: `navController.navigate(Screen.X)` is
  the same pattern as the legacy `AboutScreenRoute`'s nav dispatch +
  every other rework route adapter that needs in-app nav.

## Verification

After the impl commit:

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — required (touches
  `:presentation/commonMain` + `:ui/commonMain` + `:composeApp/commonMain`).
- `gradlew.bat :composeApp:compileKotlinIosArm64` — required (commonMain
  changes link into the iOS framework).
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — required.
- `gradlew.bat :composeApp:compileKotlinDesktop` — required (`:ui`
  composable touched; Desktop entry point links the change).

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Build with the rework debug flag.
- Navigate to `Screen.AboutRework` via the existing debug entry.
- Verify the Whats-new row renders between "Privacy policy" and the
  package label footer.
- Tap the row; verify `Screen.WhatsNewRework` route renders the
  rework WhatsNew screen.
- Verify back navigation returns to the rework About screen (NOT to the
  legacy About — verifies the rework graph is in use end-to-end).

## Deferrals

- **Source-code row** — Phase 7.x.about §87.7 deferral still stands. Row
  was already disabled in the legacy (no-op click + "soon" subtitle); no
  behaviour to port. If the source goes public, a single
  `OnOpenUrl(SOURCE_CODE_URL)` row addition lifts it.
- **SocialMediaRow** — Phase 7.x.about §87.7 deferral still stands. Needs
  brand icons (`material-icons-extended` or `:ui`-local `ImageVector`s).
- **App-icon image** — Phase 7.x.about §87.7 deferral still stands. Needs
  `:ui/commonMain/composeResources` binding or `painterResource`
  threading.
- **No nav graph route-swap** — legacy `Screen.AboutScreen` + legacy
  `Screen.WhatsNewScreen` both stay bound to legacy routes. Phase 9.x
  route-swap retires the legacy entries.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: each modified file retains its single responsibility —
  `AboutIntent` enumerates intents; `AboutEffect` enumerates effects;
  `AboutViewModel` dispatches; `AboutScreen` renders; `AboutReworkScreen
  Route` bridges. No new responsibilities introduced.
- **OCP**: sealed interfaces grow by one variant each. No existing
  consumers break; the VM's `when` becomes exhaustive on the new arm.
- **DIP**: no new layer crossings. The nav dispatch is `:composeApp` →
  `:composeApp` (route adapter calls `NavController.navigate` on
  `Screen` — both `:composeApp`-layer types). No `:presentation` →
  `:composeApp` reach.
- **Layer boundary**: changes touch `:presentation` (3 mods), `:ui` (1
  mod), `:composeApp` (1 mod). No cross-layer reach beyond the existing
  uniform shape.
- **Banned features**: no `!!`, no `Any`, no `lateinit`, no `Thread`. All
  intents / effects are `data object`s. The `when` block uses sealed-type
  exhaustiveness.
- **MVI contract**: existing slice extension — adds `OnOpenWhatsNew :
  AboutIntent` `data object` + `NavigateToWhatsNew : AboutEffect`
  `data object`. State unchanged.
- **Strangler-fig**: ZERO new `:shared` reaches. This is pure in-stack
  rework cross-cutting — the route adapter already knew about
  `NavController`; we add one more `navigate(Screen.X)` call. No legacy
  facade extension.
- **Load-bearing fixes preserved**: this slice touches NO load-bearing
  paths (no Coil, no Reader decoder hints, no `maxBitmapSize`, no
  OkHttp interceptor, no AVIF decoder, no per-host repo registry, no
  HighQualitySkiaImageDecoder, no Android RGB_565 / `allowHardware
  (false)` / OkHttp fetcher override). No load-bearing risk.
