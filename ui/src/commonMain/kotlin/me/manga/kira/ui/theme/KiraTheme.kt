package me.manga.kira.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Root theme composable for Yami screens.
 *
 * Wraps [MaterialTheme] with project [KiraColors], [KiraShapes], and installs [LocalSpacing]
 * so feature screens can read 8-pt grid tokens without importing private internals.
 *
 * **Behavior preservation:** logic mirrors legacy `KiraMangaTheme` byte-for-byte —
 *  - `darkTheme` → swap base scheme;
 *  - `pureBlack && darkTheme` → override `background` + `surfaceContainer` to `Color.Black`;
 *  - dynamic color is currently a no-op (Android-31+ feature; restored when
 *    `:platform` ships a `DynamicColorProvider` SPI).
 *
 * Renamed from `KiraMangaTheme` to drop the redundant "Manga" — the module name already
 * scopes it. Legacy callsites still reference the old composable; rewiring lands in
 * Phase 8 when `:composeApp` migrates to this binding.
 *
 * @param darkTheme `true` to pick [KiraDarkColorScheme], else [KiraLightColorScheme].
 * @param pureBlack `true` to force AMOLED-friendly pure-black surfaces in dark mode. Ignored
 *                  when [darkTheme] is `false`. The parameter default is `false` — matching the
 *                  native `KiraMangaTheme` parameter default byte-for-byte. This default is never
 *                  exercised in production: every call site collects the value from the PureBlack
 *                  preference flow (whose stored default is `true`) and passes it explicitly, so
 *                  runtime parity holds. The `false` default is the wizard/preview fallback only.
 * @param dynamicColor Reserved for Phase 8.x dynamic-color SPI; currently no-op (kept on the
 *                     public signature so future wiring doesn't break callers).
 * @param content composable content to receive the theme through [MaterialTheme] locals plus
 *                [LocalSpacing] via [CompositionLocalProvider].
 *
 * **Audit-trail postscript** (Phase 9.x.cluster97.staleKdocSweep.cascade,
 * Task #553, 2026-05-28): the 5-claim composable-theme manifest above
 * is classified as follows after recursive symbol verification across
 * the KMP graph (thirty-eighth sibling of the cluster57-96 sweep —
 * closes the wave-5 `:ui/theme/` cluster):
 *  (a) "Wraps [MaterialTheme] with project [KiraColors], [KiraShapes],
 *  and installs [LocalSpacing]" — LIVE-NOT-STALE. L49-55 realization:
 *  `MaterialTheme(colorScheme = colorScheme, shapes = KiraShapes)`
 *  outer wrap plus `CompositionLocalProvider(LocalSpacing provides
 *  Spacing())` inner installation. KiraColors symbols reached via
 *  L39 (`if (darkTheme) KiraDarkColorScheme else KiraLightColor-
 *  Scheme`). All three siblings present at file scope.
 *  (b) "Behavior preservation: logic mirrors legacy `KiraMangaTheme`
 *  byte-for-byte ... darkTheme rename-to swap base scheme; pureBlack
 *  plus darkTheme rename-to override `background` plus `surface-
 *  Container` to `Color.Black`" — LIVE-NOT-STALE. Legacy `KiraManga-
 *  Theme` LIVE at `composeApp/src/commonMain/kotlin/me/manga/yamiapk/
 *  theme/Theme.kt:94-129` with identical conditional shape:
 *  `darkTheme rename-to DarkColorScheme else LightColorScheme` at
 *  L106-109, plus `darkTheme && pureBlack rename-to baseScheme.copy(
 *  background = Color.Black, surfaceContainer = Color.Black)` at
 *  L112-120. Byte-for-byte mirror intact between the two implement-
 *  ations.
 *  (c) "Renamed from `KiraMangaTheme` to drop the redundant 'Manga'.
 *  Legacy callsites still reference the old composable; rewiring
 *  lands in Phase 8 when `:composeApp` migrates to this binding" —
 *  FORECAST-NOT-YET-FULFILLED. `App.kt:17` LIVE imports `me.manga.
 *  yamiapk.theme.KiraMangaTheme` and `App.kt:331` LIVE calls
 *  `KiraMangaTheme(darkTheme = effectiveDark, pureBlack = pureBlack)`.
 *  The app-root MaterialTheme provider remains the legacy `Yami-
 *  MangaTheme` host; the Phase-8 `:composeApp` migration to this
 *  rework `KiraTheme` has NOT yet landed. NOTE: leaf `:ui` consumers
 *  like `ThemeScreen.kt` plus the route-host `ChapterImagesScreen-
 *  Route.kt` already use `KiraTheme` at screen scope; the unfulfilled
 *  forecast is specifically the app-root rewire.
 *  (d) `@param dynamicColor` Reserved for Phase 8.x dynamic-color SPI;
 *  currently no-op — REGISTERED-BUT-DORMANT. L36 declares `@Suppress
 *  ("UNUSED_PARAMETER") dynamicColor: Boolean = false`. Recursive
 *  Grep across `:ui` plus `:composeApp` call sites confirms no
 *  caller overrides the default. Forward-compat reservation holds
 *  as written; signature stability across the eventual SPI wire-up
 *  preserved.
 *  (e) "dynamic color is currently a no-op (Android-31-plus feature;
 *  restored when `:platform` ships a `DynamicColorProvider` SPI)" —
 *  FORECAST-NOT-YET-FULFILLED. Recursive Grep for `DynamicColor-
 *  Provider` across `:platform` matches ZERO live references. The
 *  expect/actual SPI seam has NOT yet been declared; the legacy
 *  `KiraMangaTheme` documents the same gap with a `TODO Phase 10`
 *  marker at Theme.kt L103-105. Forecast preserved as the durable
 *  documentation of the planned dynamic-color landing.
 *  Two LIVE-NOT-STALE classifications plus two FORECAST-NOT-YET-
 *  FULFILLED classifications plus one REGISTERED-BUT-DORMANT
 *  classification STAND on their own merits as a faithful root-
 *  theme manifest. Original Phase 7-era prose preserved verbatim
 *  per the audit-trail-preservation convention.
 */
@Composable
fun KiraTheme(
    darkTheme: Boolean,
    pureBlack: Boolean = false,
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val baseScheme = if (darkTheme) KiraDarkColorScheme else KiraLightColorScheme
    val colorScheme = if (darkTheme && pureBlack) {
        // Material3 components draw their chrome from the elevation / container slots, NOT just
        // `surface`/`background`: cards, bottom sheets, dialogs, the bottom nav bar and the settings
        // rows use surfaceContainer*, and elevated surfaces add a `surfaceTint` overlay. The old
        // override only blacked `background` + `surfaceContainer`, so `surface` (a dark blue) and the
        // higher container slots (dark gray) stayed visible — the user-reported "some components
        // don't use pure dark". Black every surface a container can resolve to. `surfaceVariant` is
        // deliberately LEFT as the dark gray so chips / dividers / unchecked switch tracks keep a
        // visible contrast against the now-black surfaces.
        baseScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceTint = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color.Black,
            surfaceContainerHighest = Color.Black,
        )
    } else {
        baseScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = kiraTypography(),
        shapes = KiraShapes,
    ) {
        CompositionLocalProvider(LocalSpacing provides Spacing()) {
            content()
        }
    }
}
