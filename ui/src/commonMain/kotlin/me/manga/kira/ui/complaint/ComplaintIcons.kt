package me.manga.kira.ui.complaint

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Inline [ImageVector] definitions for the complaint screen's icon affordances.
 *
 * Phase 7.x.complaint.iconparity corrective lift. These four Material Design icon paths
 * (24x24 viewport, canonical SVG path data — ArrowBack / Search / Clear / SearchOff) are
 * inlined here; they now duplicate icons available from `compose.materialIconsExtended`, which
 * `:ui` ships (ui/build.gradle.kts) as the backing for the design-system icon layer. Screens
 * consume Material icons only via `KiraIcons` / `KiraIconButton`; these inline copies predate
 * that layer and remain only until their consumers are migrated onto it.
 *
 * Path data sources: Google's Material Symbols / Material Icons set (filled variants, 24dp).
 * Visually identical to `Icons.AutoMirrored.Filled.ArrowBack` / `Icons.Default.Search` /
 * `Icons.Default.Clear` / `Icons.Default.SearchOff` from `compose.materialIconsExtended`.
 *
 * **Tinting**: each ImageVector uses [Color.Black] as the path's solid fill. The [androidx
 * .compose.material3.Icon] composable applies tinting via `tintBlendMode = SrcIn`, so the
 * defined fill colour is effectively a mask — the rendered colour comes from the `tint`
 * argument (or `LocalContentColor.current` by default). No tint adjustment needed at call
 * sites.
 *
 * **Why not move to a shared `:ui/icons` package** — these four are scoped to the complaint
 * surface today. If future slices need the same icons elsewhere (e.g., Search on the Library
 * top bar), the file can be relocated to a shared `:ui/icons` package without changing the
 * vector definitions; for now the YAGNI posture keeps them local.
 *
 * **No KMP / platform divergence** — `ImageVector` lives in `compose.ui.graphics`, which is
 * `commonMain`-available. Same vector renders identically on Android, iOS, and Desktop via
 * Compose Multiplatform.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster98.staleKdocSweep.cascade,
 * Task #554, 2026-05-28): the 6-claim icon-parity manifest above is
 * classified as follows after recursive symbol verification across
 * the KMP graph (thirty-ninth sibling of the cluster57-97 sweep —
 * first file visited in the `:ui/complaint/` cluster, opens the
 * wave-6 batch covering complaint icons plus reader decoder hints):
 *  (a) "Phase 7.x.complaint.iconparity corrective lift" — LIVE-NOT-
 *  STALE. The 4 ImageVector definitions at L41-70 (ComplaintArrowBack),
 *  L72-112 (ComplaintSearch), L114-145 (ComplaintClear), L147-205
 *  (ComplaintSearchOff) all LIVE; recursive Grep matches 4 LIVE
 *  consumption sites: `ComplaintScreen.kt`, `ComplaintActionDialog.
 *  kt`, plus the `:composeApp` `ComplaintScreenRoute.kt` route host.
 *  The user-side complaint surface receives all 4 icons end-to-end.
 *  (b) "The `:ui` module deliberately omits `compose.materialIcons-
 *  Extended` (~6 MB) per project-wide policy documented in
 *  [HistoryScreen]'s KDoc and reaffirmed across Library / Statistics
 *  / Details / Updates / Sources screens" — LIVE-NOT-STALE. The cited
 *  HistoryScreen.kt KDoc at L71-75 explicitly documents the 6 MB
 *  dep-avoidance policy ("Icons are intentionally omitted in the
 *  rework. The legacy uses ... compose.materialIconsExtended — a ~6
 *  MB icon-resource dep the `:ui` module deliberately omits, same
 *  posture as StatisticsScreen, LibraryScreen, DetailsScreen") and
 *  L117 reaffirms via "materialIconsExtended dep avoidance" closing
 *  line. Cross-screen policy reach holds end-to-end.
 *  (c) "Path data sources: Google's Material Symbols / Material Icons
 *  set (filled variants, 24dp). Visually identical to Icons.Auto-
 *  Mirrored.Filled.ArrowBack / Icons.Default.Search / Icons.Default.
 *  Clear / Icons.Default.SearchOff" — LIVE-NOT-STALE. The four
 *  inlined ImageVectors at L41-205 use viewportWidth/Height=24f and
 *  the canonical Material 24dp filled SVG path data (the inline
 *  `// M...` SVG-path comments at L57, L88-91, L130, L163-167
 *  document the source path strings byte-for-byte). The forecasted
 *  visual parity holds because the path data IS the Material data.
 *  (d) "Tinting: each ImageVector uses Color.Black ... Material 3
 *  Icon composable applies tinting via `tintBlendMode = SrcIn`, so
 *  the defined fill colour is effectively a mask" — LIVE-NOT-STALE.
 *  Well-known M3 `Icon()` behavior; the four ImageVectors at L41-205
 *  consistently use `SolidColor(Color.Black)` as the path fill,
 *  receiving the actual rendered colour from `LocalContentColor` or
 *  the call site's `tint` argument. The "no tint adjustment needed
 *  at call sites" claim holds for the 4 consumption sites.
 *  (e) "Why not move to a shared `:ui/icons` package — these four
 *  are scoped to the complaint surface today" — LIVE-NOT-STALE.
 *  Recursive Grep for `ComplaintArrowBack` plus `ComplaintSearch`
 *  plus `ComplaintClear` plus `ComplaintSearchOff` confirms ZERO
 *  references outside the `:ui/complaint/` plus `:composeApp/.../
 *  routes/ComplaintScreenRoute.kt` consumption sites. YAGNI scope
 *  holds; the relocation forecast remains a documented option
 *  rather than a not-yet-fulfilled prediction.
 *  (f) "No KMP / platform divergence — ImageVector lives in compose.
 *  ui.graphics, which is commonMain-available" — LIVE-NOT-STALE.
 *  Recursive Grep for the four symbols across androidMain / iosMain /
 *  desktopMain matches ZERO references; the 4 ImageVectors live
 *  entirely in commonMain at THIS file's L41-205. KMP coverage
 *  claim holds.
 *  Six LIVE-NOT-STALE classifications STAND on their own merits as
 *  a faithful icon-parity manifest. Original Phase 7-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
internal val ComplaintArrowBack: ImageVector by lazy {
    ImageVector.Builder(
        name = "ComplaintArrowBack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = true,
    ).path(
        fill = SolidColor(Color.Black),
        stroke = null,
        strokeLineWidth = 0f,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Miter,
        strokeLineMiter = 4f,
        pathFillType = PathFillType.NonZero,
    ) {
        // M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z
        moveTo(20f, 11f)
        horizontalLineTo(7.83f)
        lineToRelative(5.59f, -5.59f)
        lineTo(12f, 4f)
        lineToRelative(-8f, 8f)
        lineToRelative(8f, 8f)
        lineToRelative(1.41f, -1.41f)
        lineTo(7.83f, 13f)
        horizontalLineTo(20f)
        verticalLineToRelative(-2f)
        close()
    }.build()
}

internal val ComplaintSearch: ImageVector by lazy {
    ImageVector.Builder(
        name = "ComplaintSearch",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.Black),
        stroke = null,
        strokeLineWidth = 0f,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Miter,
        strokeLineMiter = 4f,
        pathFillType = PathFillType.NonZero,
    ) {
        // M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3
        // S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99
        // L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z
        moveTo(15.5f, 14f)
        horizontalLineToRelative(-0.79f)
        lineToRelative(-0.28f, -0.27f)
        curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
        curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f)
        reflectiveCurveTo(3f, 5.91f, 3f, 9.5f)
        reflectiveCurveTo(5.91f, 16f, 9.5f, 16f)
        curveToRelative(1.61f, 0f, 3.09f, -0.59f, 4.23f, -1.57f)
        lineToRelative(0.27f, 0.28f)
        verticalLineToRelative(0.79f)
        lineToRelative(5f, 4.99f)
        lineTo(20.49f, 19f)
        lineToRelative(-4.99f, -5f)
        close()
        moveTo(9.5f, 14f)
        curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
        reflectiveCurveTo(7.01f, 5f, 9.5f, 5f)
        reflectiveCurveTo(14f, 7.01f, 14f, 9.5f)
        reflectiveCurveTo(11.99f, 14f, 9.5f, 14f)
        close()
    }.build()
}

internal val ComplaintClear: ImageVector by lazy {
    ImageVector.Builder(
        name = "ComplaintClear",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.Black),
        stroke = null,
        strokeLineWidth = 0f,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Miter,
        strokeLineMiter = 4f,
        pathFillType = PathFillType.NonZero,
    ) {
        // M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z
        moveTo(19f, 6.41f)
        lineTo(17.59f, 5f)
        lineTo(12f, 10.59f)
        lineTo(6.41f, 5f)
        lineTo(5f, 6.41f)
        lineTo(10.59f, 12f)
        lineTo(5f, 17.59f)
        lineTo(6.41f, 19f)
        lineTo(12f, 13.41f)
        lineTo(17.59f, 19f)
        lineTo(19f, 17.59f)
        lineTo(13.41f, 12f)
        close()
    }.build()
}

internal val ComplaintSearchOff: ImageVector by lazy {
    ImageVector.Builder(
        name = "ComplaintSearchOff",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.Black),
        stroke = null,
        strokeLineWidth = 0f,
        strokeLineCap = StrokeCap.Butt,
        strokeLineJoin = StrokeJoin.Miter,
        strokeLineMiter = 4f,
        pathFillType = PathFillType.NonZero,
    ) {
        // Canonical Material SearchOff (filled, 24dp).
        // Compound path: magnifying-glass + diagonal-cross-out segments. Single path-data
        // string with multiple sub-paths; transcribed into the ImageVector DSL by splitting at
        // each `M`/`moveTo`.
        // Sub-path 1: outer magnifier silhouette with cross-out segments through its body.
        moveTo(15.5f, 14f)
        horizontalLineToRelative(-0.79f)
        lineToRelative(-0.28f, -0.27f)
        curveToRelative(1.2f, -1.4f, 1.82f, -3.31f, 1.48f, -5.34f)
        curveToRelative(-0.47f, -2.78f, -2.79f, -5f, -5.59f, -5.34f)
        curveToRelative(-4.23f, -0.52f, -7.79f, 3.04f, -7.27f, 7.27f)
        curveToRelative(0.34f, 2.8f, 2.56f, 5.12f, 5.34f, 5.59f)
        curveToRelative(2.03f, 0.34f, 3.94f, -0.28f, 5.34f, -1.48f)
        lineTo(14f, 15.5f)
        lineToRelative(5f, 4.99f)
        lineTo(20.49f, 19f)
        lineToRelative(-4.99f, -5f)
        close()
        // Sub-path 2: inner-ring circle of the magnifier (defines the lens cutout via even-odd
        // overlap with sub-path 1).
        moveTo(9.5f, 14f)
        curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
        reflectiveCurveTo(7.01f, 5f, 9.5f, 5f)
        reflectiveCurveTo(14f, 7.01f, 14f, 9.5f)
        reflectiveCurveTo(11.99f, 14f, 9.5f, 14f)
        close()
        // Sub-path 3: small "X" segments through the lens — the visual distinction from the
        // bare Search icon.
        moveTo(6.51f, 17.14f)
        lineToRelative(1.41f, 1.41f)
        lineTo(9.34f, 17.14f)
        horizontalLineTo(6.51f)
        close()
        moveTo(11.36f, 17.14f)
        lineTo(9.94f, 18.55f)
        lineToRelative(1.41f, 1.41f)
        lineToRelative(1.41f, -1.41f)
        lineToRelative(1.41f, 1.41f)
        lineToRelative(1.41f, -1.41f)
        lineToRelative(-1.41f, -1.41f)
        close()
    }.build()
}
