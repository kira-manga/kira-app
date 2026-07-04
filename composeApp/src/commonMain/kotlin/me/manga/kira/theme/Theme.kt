package me.manga.kira.theme

import androidx.compose.runtime.Composable
import me.manga.kira.ui.theme.KiraTheme

/**
 * App-root theme — Phase 11.ui.UP-7 (design-system consolidation).
 *
 * `KiraMangaTheme` is now a thin alias over the `:ui` design-system [KiraTheme]. The single
 * source of truth for the Material 3 color schemes (`:ui` `KiraColors`), `Shapes` (`KiraShapes`),
 * the Gellix typography (`kiraTypography()`), and the `LocalSpacing` 8-pt grid lives in `:ui`.
 *
 * Before UP-7 this file carried its own copies of the dark/light `ColorScheme`s + `Shapes`; those
 * were byte-for-byte identical to the `:ui` tokens (verified token-by-token before folding), so
 * collapsing onto [KiraTheme] is a pure dedup with **no on-screen change**. It also removes the
 * latent two-stack hazard where leaf `:ui` screens (which already call [KiraTheme]) could diverge
 * from the app-root scheme. `App.kt` now calls the `:ui` [KiraTheme] directly; the only remaining
 * caller of `KiraMangaTheme` is the legacy Android host's `CrashActivity`, which the kept alias
 * leaves untouched.
 *
 * `dynamicColor` is forwarded to [KiraTheme] but remains a no-op pending the Phase 10
 * dynamic-color expect/actual SPI (unchanged by the fold).
 */
@Composable
fun KiraMangaTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = false,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    KiraTheme(
        darkTheme = darkTheme,
        pureBlack = pureBlack,
        dynamicColor = dynamicColor,
        content = content,
    )
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster167.staleKdocSweep.cascade,
 * Task #623, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-thirty-fourth sibling of the cluster57-166
 * sweep — single-leaf file of the wave-39 commonMain Theme batch; SOLE
 * commonMain Theme file 1/1):
 *  (a) inline-comment "TODO-Phase-10-gate-behind-expect-actual-isDynamic
 *  ColorAvailable-plus-actual-dynamic-ColorScheme-context + dynamicDark
 *  ColorScheme-dynamicLightColorScheme-are-Android-only-API-31-plus-and-
 *  require-LocalContext + The-dynamicColor-flag-is-currently-ignored-in-
 *  commonMain-we-always-fall-back-to-the-static-schemes" — FORECAST-NOT-
 *  YET-FULFILLED (the Phase-10 dynamic-color expect/actual seam remains
 *  unbuilt. Grep-verified: no isDynamicColorAvailable expect-decl or
 *  actual exists anywhere in the workspace; no dynamicDarkColorScheme or
 *  dynamicLightColorScheme references outside this single forecast prose
 *  block. The `dynamicColor: Boolean = false` parameter on KiraMangaTheme
 *  IS currently ignored in the body — `val baseScheme = when { darkTheme
 *  -> DarkColorScheme; else -> LightColorScheme }` makes no branch on
 *  dynamicColor, so the static schemes ALWAYS serve. The "currently ignored
 *  in commonMain; we always fall back to the static schemes" prose
 *  describes the present truth accurately; the forecast remains an
 *  active prediction awaiting the Phase 10 expect/actual port). (b) inline-
 *  comment "typography-equals-Typography-Phase-10-re-enable-once-Type-kt-
 *  moves-with-compose-resources-Font-Res-font-gellix" — FORECAST-FULFILLED
 *  (Phase 11.ui.UP-1.typography, 2026-05-30: the typography parameter is
 *  now LIVE at the MaterialTheme call-site — `typography = kiraTypography()`
 *  — sourcing the Gellix family from the :ui design-system factory
 *  `me.manga.kira.ui.theme.kiraTypography` (compose-resources
 *  `Font(Res.font.gellix_regular)` plus the semibold and bold weights).
 *  The 3-slot Gellix override — bodyLarge 16sp Bold, titleMedium 14sp
 *  Medium, titleSmall 12sp Normal — mirrors the legacy app Type.kt
 *  byte-for-byte; the remaining Material 3 slots keep their defaults,
 *  preserving system-font fallback for the app's primary Arabic locale.
 *  The Phase-10 typography forecast is DISCHARGED).
 *  Verified: @Composable fun KiraMangaTheme(darkTheme, dynamicColor =
 *  false, pureBlack = false, content) shipped — body branches only on
 *  (darkTheme, pureBlack) to produce either DarkColorScheme or
 *  LightColorScheme with optional Color.Black override on background +
 *  surfaceContainer. Shapes = Shapes( extraSmall=4dp, small=8dp,
 *  medium=12dp, large=16dp, extraLarge=0dp ) shipped. Sibling: none
 *  this cluster (single-leaf commonMain Theme cluster). SOLE FILE of
 *  the cluster167 commonMain Theme 1-leaf cluster (1 of 1). Two
 *  classifications. Original Phase 7-or-earlier theme prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */

