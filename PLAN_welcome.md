# Phase 7.x.welcome — Welcome screen rework (Task #306)

## Context

The Sources stack is now end-to-end reworked (foundation §84 +
gap-lifts §121/§122/§123/§139 + route-swaps §124/§140 — Task #305
just closed). Per the `/goal` Stop hook Rule 3 + "Auto-continue
between phases" standing directive, the next non-blocked candidate
is a fresh feature rework that is small enough to land cleanly
within remaining session budget without forcing context bloat.

Welcome is chosen because:

- The legacy `composeApp/.../presentation/features/onboarding/
  welcome/WelcomeScreen.kt` is **stateless** — pure Compose
  display, zero state, zero business logic, one `onGetStarted`
  callback. No `:domain`/`:data`/`:presentation` layers needed.
- The visual structure is trivially small (Surface → Box →
  Column with 2 Texts + 1 Spacer + 1 Button + an
  `AnimatedBackground` overlay).
- The rework `:ui` precedent at §122 / §138 has already settled
  the decision: **skip the legacy `AnimatedBackground` gradient
  sweep** because it's purely cosmetic with no semantic value.
  The rework Welcome will match that posture.
- Block-and-ask triggers (a)-(d) all NOT met: no contract
  library blocker, no observable behaviour change (legacy nav
  target preserved — `Screen.Theme`), no compile risk, no SOLID
  violation forced by the design.

The other non-blocked candidates were considered and rejected:

- **Home screen rework** — large multi-week port (4 VMs, 15+
  flows, search overlay, tab bar, infinite scroll, refresh, tab-
  reselect, 403 escalation). Not a one-session slice.
- **Library route-swap** — legacy adapter is 211 lines vs rework
  71 lines. Needs multiple gap-lifts (downloadViewModel
  bridging, onOpenRandomClick, library-manga-saved status logic)
  before the swap can land cleanly. Multi-commit slice.
- **MangaDetails route-swap** — `Screen.MangaDetails` carries a
  short arg tuple (mangaUrl + api), while `Screen.MangaDetailsRework`
  needs the full identity tuple (api + language + title + url +
  coverUrl + rating + genres). Requires nav-arg enrichment at
  every call site (Home, search, LibraryMangaDetails) — multi-
  commit.
- **ChapterImages route-swap** — Reader. Phase 6.4.x.bookmark
  (Task #217) is still blocked on chapter-identity strategy; the
  rework Reader's chapter-identity surface should not be
  finalised by a route-swap before that blocker resolves.
- **Phase 9.x.onboarding.cleanup** — cosmetic, no behaviour
  delta; deferred.

## Approach

The legacy `WelcomeScreen.kt` (at `composeApp/.../presentation/
features/onboarding/welcome/WelcomeScreen.kt:48-123`) is a 75-
line stateless composable. The rework relocates the visible
content into `:ui/.../welcome/WelcomeScreen.kt` and route-swaps
the `WelcomeScreenRoute.kt` adapter to render the new file.

### Layer surfaces

- **`:domain`** — unchanged. Welcome has no domain entities.
- **`:data`** — unchanged. Welcome reads nothing persisted.
- **`:presentation`** — unchanged. No MVI surface needed
  (stateless).
- **`:ui`** — 1 new file:
  `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/welcome/
  WelcomeScreen.kt`. Stateless composable taking one
  `onGetStarted: () -> Unit` callback. Renders Surface → Box →
  Column → Title Text + Subtitle Text + Spacer + Get-Started
  Button. **Inline literal strings** (Phase 10 i18n lift handles
  unification). **No `AnimatedBackground`** (matches §122 /
  §138 precedent — purely cosmetic).
- **`:composeApp`** — 1 modified file:
  `composeApp/.../navigation/routes/WelcomeScreenRoute.kt`.
  Rewrite body: replace the legacy `me.manga.kira.presentation.
  features.onboarding.welcome.WelcomeScreen` import with the
  new `me.manga.kira.ui.welcome.WelcomeScreen` import. Body
  is unchanged (same one-line nav target).

No Koin module changes (no VM bound). No nav-graph changes (the
`composable<Screen.Welcome>` entry stays bound to the adapter;
only the adapter's import changes).

### Strangler-fig boundary

None. Welcome reads nothing from `:shared`. The slice does not
extend any strangler-fig boundary.

## Commit roadmap

Three commits, all ≤5 files per the standing cap.

1. **Plan commit** — `PLAN_welcome.md` only (1 file).
2. **Source commit** — 2 files:
   - `ui/.../welcome/WelcomeScreen.kt` (NEW) — stateless `:ui`
     port.
   - `composeApp/.../navigation/routes/WelcomeScreenRoute.kt`
     (MODIFIED) — swap the import to the new `:ui` composable.
3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append `## §141 — Phase 7.x.welcome —
     Welcome screen rework + route-swap` section.
   - `SOLID_AUDIT.md` — append `# Phase 7.x.welcome — Welcome
     screen rework (Task #306)` entry with per-file SOLID 10-
     point checklist.

## Critical files

### New

- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/welcome/WelcomeScreen.kt`
- `PLAN_welcome.md`

### Modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/WelcomeScreenRoute.kt`
- `ARCHITECTURE.md`
- `SOLID_AUDIT.md`

### Untouched (preserved on disk, no longer reachable post-swap)

- `composeApp/.../presentation/features/onboarding/welcome/WelcomeScreen.kt` —
  legacy stateless composable. Stays on disk until Phase 9.x
  cleanup retires it alongside other retired legacy screens.
- `composeApp/.../presentation/features/onboarding/components/
  AnimatedBackground.kt` — still referenced by the legacy
  Welcome on disk. Stays bound; cosmetic only.

## Verification

After the source commit:

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` —
  iOS simulator arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop
  (because `:ui/commonMain` changes link into the Desktop entry
  point).

On-device smoke (deferred to user's Mac):

- Launch a fresh install (or reset `first_launch` to true).
- Verify the Welcome screen renders with the title, subtitle,
  and Get Started button.
- Verify tapping Get Started advances to Screen.Theme (rework
  via §138).
- Verify no visual regression vs the legacy aside from the
  AnimatedBackground omission (intentional, matches §122 /
  §138).

## Deferrals

- **No i18n lift** — inline literal strings on the rework Welcome,
  same as every other rework screen. Phase 10 unifies legacy +
  rework consumers in one pass.
- **No AnimatedBackground port** — purely cosmetic, no semantic
  value. Matches §122 / §138 precedent.
- **No `:domain`/`:data`/`:presentation` layers** — Welcome is
  genuinely stateless. Adding empty layers would be premature
  abstraction.
- **No Koin module** — no VM, no use case, no repository to bind.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: New `:ui` file has one rule (render the Welcome
  visual). Route adapter has one rule (adapt `Screen.Welcome`
  to the rework composable).
- **OCP**: The `onGetStarted: () -> Unit` callback is the
  extension surface; the screen is closed under modification.
- **DIP**: `:ui` depends on nothing besides Compose primitives;
  no dependency inversion concern. Route adapter depends on
  `:ui` (UI module via Compose) — same direction as every other
  rework route adapter.
- **Layer boundary**: changes touch `:ui` (1 new file) +
  `:composeApp` (1 modified route adapter + 2 modified close-
  out docs). No `:domain`/`:data`/`:presentation`/`:shared`
  reach.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`.
  Stateless composable with no concurrency primitives.
- **MVI contract**: N/A — no MVI surface added or modified.
- **Strangler-fig**: N/A — no `:data` → `:shared` reach.
- **Load-bearing fixes preserved**: this slice does NOT touch
  the Coil ImageLoader, the Reader's per-request listener, the
  Reader's decoder hints, OkHttp interceptor, or any of the
  prior load-bearing image-quality posture (Welcome has no
  images). No load-bearing risk.

## Visual delta

Three intentional visual deltas vs the legacy `WelcomeScreen.kt`:

1. **AnimatedBackground omission** — matches §122 / §138
   precedent. Purely cosmetic.
2. **Same title / subtitle / button copy** — inline literal
   strings on the rework path (matches every other rework
   screen). Identical visible text.
3. **Same Material 3 Button styling** — preserved verbatim
   (52dp height, 26.dp RoundedCornerShape clip, primary
   container, onPrimary label).

Net: the user sees the same screen minus the animated gradient
background. The bottom-aligned content column (title + subtitle
+ button) is preserved verbatim.
