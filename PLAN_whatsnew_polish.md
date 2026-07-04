# Phase 7.x.whatsnew.polish — NewChip horizontal spacer fix

## Context

Phase 7.x.whatsnew (foundation, commits `4b4dffe`-`e5d91b0`) ported the
WhatsNew screen onto the rework foundation. The legacy feature-card layout
had a horizontal `Spacer` between the feature title and the `NEW` chip to
visually separate them; during the port, the spacer's modifier got
corrupted to `Modifier.height(0.dp)` (a no-op vertical spacer inside a
horizontal `Row`). The foundation slice's KDoc explicitly deferred this
to a follow-on `Phase 7.x.whatsnew.polish` sub-slice to keep the
foundation's 5-file commit budget tight.

Phase 7.x.whatsnew.pager (commits `b8445cc`-`b6a67a7`) noted the polish
deferral in the End-of-slice verdict as the smallest-blast-radius next
slice. This slice closes that deferral.

## Approach

Single-line fix in `ui/.../whatsnew/WhatsNewScreen.kt` inside the private
`FeatureCard` composable's `if (feature.isNew)` branch:

```kotlin
// Before
if (feature.isNew) {
    Spacer(Modifier.height(0.dp))   // no-op vertical spacer in a Row
    NewChip()
}

// After
if (feature.isNew) {
    Spacer(Modifier.width(spacing.sm))   // horizontal breathing room
    NewChip()
}
```

The `.weight(1f)` modifier on the title `Text` already absorbs all
available horizontal space, pushing `NewChip()` to the row's trailing
edge. The new `Spacer(Modifier.width(spacing.sm))` adds a small gap
between the trailing edge of the title text (after weight resolution)
and the chip — same posture as the legacy `WhatsNewBadge` row.

## Imports

Drop `androidx.compose.foundation.layout.height` (no longer used —
verify the existing `Spacer(Modifier.height(spacing.sm))` between the
title row and the description Text still uses `.height`). Add
`androidx.compose.foundation.layout.width`.

**Check:** the existing `Spacer(Modifier.height(spacing.sm))` on
`WhatsNewScreen.kt:362` IS using `.height`, so `.height` import stays.
ONLY a new `.width` import is added.

## Commit roadmap

3 commits, all ≤5 files per the standing cap:

1. **Plan** — `PLAN_whatsnew_polish.md` only (1 file).
2. **Impl** — 1 file:
   - `ui/.../whatsnew/WhatsNewScreen.kt` — swap `Modifier.height(0.dp)`
     for `Modifier.width(spacing.sm)` inside the `isNew` branch; add
     the `.width` import.
3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append §91 — Phase 7.x.whatsnew.polish with
     the rationale, file delta, build-gate notes, deferrals.
   - `SOLID_AUDIT.md` — append Phase 7.x.whatsnew.polish entry with
     per-file SOLID 10-point checklist + End-of-slice verdict.

## Critical files

### Modified

- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/whatsnew/WhatsNewScreen.kt`
  — 1-line modifier swap + 1-line import addition.

### Untouched

- `presentation/.../whatsnew/*.kt` — VM / Intent / Effect / State all
  unchanged.
- `composeApp/.../navigation/routes/WhatsNewReworkScreenRoute.kt` —
  unchanged.
- `composeApp/.../di/WhatsNewReworkModule.kt` — unchanged.
- Legacy `composeApp/.../features/whatsnew/ui/WhatsNewScreen.kt` —
  unchanged (legacy stays).

## Verification

After impl commit:
- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — must pass.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — must pass.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — must pass.
- `gradlew.bat :composeApp:compileKotlinDesktop` — must pass.

On-device smoke: open rework WhatsNew, scroll to any feature with
`isNew = true`, verify the `NEW` chip has a small gap from the title
text (no longer flush). (Windows-impossible; deferred to user's Mac.)

## Deferrals

Same as the pager slice's remaining deferrals (§90.7):
- `.navbuttons` — left/right arrow buttons.
- `.images` — Coil rendering for `imageResName` / `imageUrl` /
  `imageUrlList`.
- `.video` — `:platform` MediaPlayer SPI.
- `.fullscreen` — FullscreenMediaViewer.
- `.gate` — auto-trigger should-show comparator.
- `.i18n` — `Res.string.*` lift.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: ✅ Single file, single composable, single-line modifier
  swap. No scope creep.
- **OCP**: ✅ Cosmetic-only — no signature changes, no MVI surface
  changes. Strict-MVI contract §6 unaffected.
- **DIP**: ✅ No new dependencies; uses existing `LocalSpacing.current`
  (already imported).
- **Layer boundary**: ✅ `:ui`-only change. No `:presentation` /
  `:domain` / `:data` / `:shared` reach.
- **Banned features**: ✅ No `Any` / `!!` / `lateinit` / `Thread`.
- **Strangler-fig**: ✅ ZERO `:shared` reach. Pure rework-side polish.
- **Load-bearing fixes preserved**: ✅ No Coil, no Reader decoder
  hints, no `maxBitmapSize`, no OkHttp interceptor, no AVIF decoder.
  No load-bearing risk.
