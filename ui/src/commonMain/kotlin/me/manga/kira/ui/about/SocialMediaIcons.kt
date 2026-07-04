package me.manga.kira.ui.about

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType.Companion.NonZero
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap.Companion.Butt
import androidx.compose.ui.graphics.StrokeJoin.Companion.Miter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * `:ui`-local brand icons for the rework About screen's SocialMediaRow.
 *
 * Phase 7.x.about.socialmediarow — the true brand glyphs (X, Discord, Instagram, WhatsApp) are
 * not in `compose.materialIconsExtended`, so they are vendored locally as [ImageVector]s —
 * Material Design / official-brand 24×24 path data — keeping the row pixel-equivalent to the
 * legacy. (Facebook/Public re-implement Material glyphs that are now on `:ui`'s classpath via
 * `compose.materialIconsExtended`.) Same posture as
 * [me.manga.kira.ui.complaint.ComplaintIcons] (Task #268).
 *
 *  - **[X]**: lifted verbatim from `composeApp/.../features/about/common/icons/X.kt`
 *    (Twitter/X angular cross). 24×24 viewport.
 *  - **[Facebook]**: Material Design "Facebook (filled)" — the rounded-square "f"
 *    glyph that Material's `Icons.Default.Facebook` renders. 24×24 viewport.
 *  - **[Instagram]**: ported from `composeApp/.../composeResources/drawable/ic_instagram.xml`
 *    (rounded-square camera + dot, 32×32 viewport preserved).
 *  - **[WhatsApp]**: ported from `composeApp/.../composeResources/drawable/ic_whatsapp.xml`
 *    (chat bubble + phone receiver, 32×32 viewport preserved).
 *  - **[Discord]**: lifted verbatim from `composeApp/.../features/about/common/icons/Discord.kt`
 *    (chat bubble with two pill-eyes). 24×24 viewport.
 *  - **[Public]**: Material Design "Public" (globe with longitude/latitude lines, the
 *    canonical website-link glyph). 24×24 viewport. Substitutes for the legacy's complex
 *    `earth_svgrepo_com.xml` (48.6 viewport, ~30 paths) — at the 18-28dp button size used
 *    in [SocialMediaRow], the simpler Material glyph reads cleaner than the detailed
 *    continent-outline drawing. No affordance change; same "website" semantic.
 *
 * **Caching**: each icon is exposed as a `get()` property over a private `_xxx: ImageVector?`
 * cache field — same single-instance-per-process pattern as `androidx.compose.material.icons.
 * Icons.Default.*` and as the legacy `CustomIcons.X` / `CustomIcons.Discord` vendored files.
 * Each first read constructs the `Builder`; subsequent reads return the cached instance.
 *
 * **Fill color** — all paths use `SolidColor(Color(0xFF000000))`; the [SocialMediaButton]
 * call site tints via `Icon(..., tint = MaterialTheme.colorScheme.primary)` per
 * `:ui` theme tokens. Same posture as the legacy `SocialMediaRow`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster35.staleKdocSweep.cascade,
 * Task #491, 2026-05-28): four stale citations appear in this file's
 * class-level KDoc above, all referencing the deleted legacy About
 * vendored-icon + SocialMediaRow chain:
 *  - Lines 23-24 ("lifted verbatim from `composeApp/.../features/
 *    about/common/icons/X.kt`").
 *  - Lines 31-32 ("lifted verbatim from `composeApp/.../features/
 *    about/common/icons/Discord.kt`").
 *  - Line 41 ("the legacy `CustomIcons.X` / `CustomIcons.Discord`
 *    vendored files").
 *  - Line 46 ("Same posture as the legacy `SocialMediaRow`").
 *  All four classified as STALE-SYMBOL-REFERENCE — Phase 9.x.
 *  settings_about.legacyui.retire (§354) DELETED the legacy
 *  `:composeApp` About feature subtree (11-file legacy
 *  Settings+About orphan chain), including the vendored
 *  `common/icons/X.kt` + `common/icons/Discord.kt` + the
 *  `CustomIcons` aggregator object + the legacy `SocialMediaRow`
 *  composable. A recursive search of the legacy `composeApp` About
 *  feature subtree for any `.kt` files returns NO MATCHES, and a
 *  recursive grep for `CustomIcons` against the live source tree
 *  returns matches only in this file's own historical KDoc +
 *  documentation Markdown — the symbol itself is retired. HOWEVER — three
 *  separate cite-targets in this same KDoc REMAIN LIVE and are
 *  DELIBERATELY preserved as LIVE-NOT-STALE references (the
 *  audit-trail-preservation convention requires disambiguating
 *  these so future sweeps do not misclassify them):
 *  (i) Line 27 "ic_instagram.xml" — LIVE in `composeApp/src/
 *  commonMain/composeResources/drawable/ic_instagram.xml`;
 *  (ii) Line 29 "ic_whatsapp.xml" — LIVE in `composeApp/src/
 *  commonMain/composeResources/drawable/ic_whatsapp.xml`;
 *  (iii) Line 35 "earth_svgrepo_com.xml" — LIVE in `composeApp/
 *  src/commonMain/composeResources/drawable/earth_svgrepo_com.xml`.
 *  These three drawables SURVIVE the §354 retirement (drawables
 *  are resource-tier, not Kotlin source — the legacy About
 *  retirement scope was the composeApp `.kt` source tree only)
 *  and the rework `:ui` `SocialMediaIcons.Instagram` / `.WhatsApp`
 *  / `.Public` ImageVectors continue to cite their original XML
 *  drawable provenance as LIVE pixel-equivalence anchors. This
 *  rework `:ui` `SocialMediaIcons` object (different filename,
 *  different package: `me.manga.kira.ui.about.SocialMediaIcons`)
 *  is LIVE as the canonical 6-brand-glyph vendor backing the
 *  rework About screen's SocialMediaRow (Phase 7.x.about.
 *  socialmediarow, §296); all six glyph rationales STAND on their
 *  own merits past the §354 fulfilled landing as LIVE rework
 *  realizations: (a) the X.kt path-data origin is preserved
 *  verbatim in `SocialMediaIcons.X` (24×24 viewport, identical
 *  path commands); (b) the Discord.kt path-data origin is
 *  preserved verbatim in `SocialMediaIcons.Discord` (24×24
 *  viewport, identical bubble + pill-eye path commands); (c) the
 *  Instagram drawable origin is preserved verbatim in
 *  `SocialMediaIcons.Instagram` (32×32 viewport, identical
 *  rounded-square camera + dot path commands); (d) the WhatsApp
 *  drawable origin is preserved verbatim in `SocialMediaIcons.
 *  WhatsApp` (32×32 viewport, identical chat-bubble + phone-
 *  receiver path commands); (e) the `CustomIcons` single-instance-
 *  per-process caching pattern (`get()` over private `_xxx:
 *  ImageVector?` cache field) is preserved verbatim across all
 *  six glyphs; (f) the legacy-`SocialMediaRow`-posture (fill +
 *  `Icon(..., tint = MaterialTheme.colorScheme.primary)` site
 *  tinting) is preserved verbatim in the rework's `SocialMediaRow`.
 *  The Material Design "Public" glyph deliberate-simplification
 *  (substituting the legacy's 48.6-viewport ~30-path
 *  earth_svgrepo_com.xml with the canonical 24×24 globe-with-
 *  longitude-lines glyph) STANDS as an intentional rework
 *  improvement, not a regression — the affordance/semantic is
 *  preserved (both are "website" glyphs). Original Phase 7.x-era
 *  prose preserved verbatim per the audit-trail-preservation
 *  convention — the citations are historical record of the
 *  design lineage including all four parity rationales that were
 *  subsequently fulfilled (legacy About vendored-icon + SocialMediaRow
 *  chain retired) across §354.
 */
internal object SocialMediaIcons

@Suppress("UnusedReceiverParameter")
internal val SocialMediaIcons.X: ImageVector
    get() {
        val cached = _x
        if (cached != null) return cached
        val built = Builder(
            name = "X",
            defaultWidth = 24.0.dp,
            defaultHeight = 24.0.dp,
            viewportWidth = 24.0f,
            viewportHeight = 24.0f,
        ).apply {
            path(
                fill = SolidColor(Color(0xFF000000)),
                stroke = null,
                strokeLineWidth = 0.0f,
                strokeLineCap = Butt,
                strokeLineJoin = Miter,
                strokeLineMiter = 4.0f,
                pathFillType = NonZero,
            ) {
                moveTo(18.901f, 1.153f)
                horizontalLineToRelative(3.68f)
                lineToRelative(-8.04f, 9.19f)
                lineTo(24.0f, 22.846f)
                horizontalLineToRelative(-7.406f)
                lineToRelative(-5.8f, -7.584f)
                lineToRelative(-6.638f, 7.584f)
                horizontalLineTo(0.474f)
                lineToRelative(8.6f, -9.83f)
                lineTo(0.0f, 1.154f)
                horizontalLineToRelative(7.594f)
                lineToRelative(5.243f, 6.932f)
                close()
                moveTo(17.61f, 20.644f)
                horizontalLineToRelative(2.039f)
                lineTo(6.486f, 3.24f)
                horizontalLineTo(4.298f)
                close()
            }
        }.build()
        _x = built
        return built
    }

@Suppress("ObjectPropertyName")
private var _x: ImageVector? = null

@Suppress("UnusedReceiverParameter")
internal val SocialMediaIcons.Facebook: ImageVector
    get() {
        val cached = _facebook
        if (cached != null) return cached
        val built = Builder(
            name = "Facebook",
            defaultWidth = 24.0.dp,
            defaultHeight = 24.0.dp,
            viewportWidth = 24.0f,
            viewportHeight = 24.0f,
        ).apply {
            path(
                fill = SolidColor(Color(0xFF000000)),
                stroke = null,
                strokeLineWidth = 0.0f,
                strokeLineCap = Butt,
                strokeLineJoin = Miter,
                strokeLineMiter = 4.0f,
                pathFillType = NonZero,
            ) {
                moveTo(24.0f, 12.0f)
                curveToRelative(0.0f, -6.627f, -5.373f, -12.0f, -12.0f, -12.0f)
                reflectiveCurveTo(0.0f, 5.373f, 0.0f, 12.0f)
                curveToRelative(0.0f, 5.99f, 4.388f, 10.954f, 10.125f, 11.854f)
                verticalLineToRelative(-8.385f)
                horizontalLineTo(7.078f)
                verticalLineToRelative(-3.47f)
                horizontalLineToRelative(3.047f)
                verticalLineTo(9.43f)
                curveToRelative(0.0f, -3.007f, 1.792f, -4.669f, 4.533f, -4.669f)
                curveToRelative(1.312f, 0.0f, 2.686f, 0.235f, 2.686f, 0.235f)
                verticalLineToRelative(2.953f)
                horizontalLineTo(15.83f)
                curveToRelative(-1.491f, 0.0f, -1.956f, 0.925f, -1.956f, 1.874f)
                verticalLineToRelative(2.25f)
                horizontalLineToRelative(3.328f)
                lineToRelative(-0.532f, 3.47f)
                horizontalLineToRelative(-2.796f)
                verticalLineToRelative(8.385f)
                curveTo(19.612f, 22.954f, 24.0f, 17.99f, 24.0f, 12.0f)
                close()
            }
        }.build()
        _facebook = built
        return built
    }

@Suppress("ObjectPropertyName")
private var _facebook: ImageVector? = null

@Suppress("UnusedReceiverParameter")
internal val SocialMediaIcons.Instagram: ImageVector
    get() {
        val cached = _instagram
        if (cached != null) return cached
        val built = Builder(
            name = "Instagram",
            defaultWidth = 24.0.dp,
            defaultHeight = 24.0.dp,
            viewportWidth = 32.0f,
            viewportHeight = 32.0f,
        ).apply {
            path(
                fill = SolidColor(Color(0xFF000000)),
                stroke = null,
                strokeLineWidth = 0.0f,
                strokeLineCap = Butt,
                strokeLineJoin = Miter,
                strokeLineMiter = 4.0f,
                pathFillType = NonZero,
            ) {
                moveTo(20.445f, 5.0f)
                horizontalLineToRelative(-8.891f)
                arcToRelative(6.559f, 6.559f, 0.0f, false, false, -6.554f, 6.554f)
                verticalLineToRelative(8.891f)
                arcToRelative(6.559f, 6.559f, 0.0f, false, false, 6.554f, 6.555f)
                horizontalLineToRelative(8.891f)
                arcToRelative(6.56f, 6.56f, 0.0f, false, false, 6.554f, -6.555f)
                verticalLineToRelative(-8.891f)
                arcTo(6.557f, 6.557f, 0.0f, false, false, 20.445f, 5.0f)
                close()
                moveTo(24.787f, 20.445f)
                arcToRelative(4.343f, 4.343f, 0.0f, false, true, -4.342f, 4.342f)
                horizontalLineToRelative(-8.891f)
                arcToRelative(4.341f, 4.341f, 0.0f, false, true, -4.341f, -4.342f)
                verticalLineToRelative(-8.891f)
                arcToRelative(4.34f, 4.34f, 0.0f, false, true, 4.341f, -4.341f)
                horizontalLineToRelative(8.891f)
                arcToRelative(4.342f, 4.342f, 0.0f, false, true, 4.341f, 4.341f)
                lineToRelative(0.001f, 8.891f)
                close()
            }
            path(
                fill = SolidColor(Color(0xFF000000)),
                stroke = null,
                strokeLineWidth = 0.0f,
                strokeLineCap = Butt,
                strokeLineJoin = Miter,
                strokeLineMiter = 4.0f,
                pathFillType = NonZero,
            ) {
                moveTo(16.0f, 10.312f)
                curveToRelative(-3.138f, 0.0f, -5.688f, 2.551f, -5.688f, 5.688f)
                reflectiveCurveToRelative(2.551f, 5.688f, 5.688f, 5.688f)
                reflectiveCurveToRelative(5.688f, -2.551f, 5.688f, -5.688f)
                reflectiveCurveToRelative(-2.55f, -5.688f, -5.688f, -5.688f)
                close()
                moveTo(16.0f, 19.475f)
                arcToRelative(3.475f, 3.475f, 0.0f, true, true, -0.001f, -6.95f)
                arcToRelative(3.475f, 3.475f, 0.0f, false, true, 0.001f, 6.95f)
                close()
                moveTo(21.7f, 8.991f)
                arcToRelative(1.363f, 1.363f, 0.0f, true, true, -1.364f, 1.364f)
                curveToRelative(0.0f, -0.752f, 0.51f, -1.364f, 1.364f, -1.364f)
                close()
            }
        }.build()
        _instagram = built
        return built
    }

@Suppress("ObjectPropertyName")
private var _instagram: ImageVector? = null

@Suppress("UnusedReceiverParameter")
internal val SocialMediaIcons.WhatsApp: ImageVector
    get() {
        val cached = _whatsapp
        if (cached != null) return cached
        val built = Builder(
            name = "WhatsApp",
            defaultWidth = 24.0.dp,
            defaultHeight = 24.0.dp,
            viewportWidth = 32.0f,
            viewportHeight = 32.0f,
        ).apply {
            path(
                fill = SolidColor(Color(0xFF000000)),
                stroke = null,
                strokeLineWidth = 0.0f,
                strokeLineCap = Butt,
                strokeLineJoin = Miter,
                strokeLineMiter = 4.0f,
                pathFillType = NonZero,
            ) {
                moveTo(26.576f, 5.363f)
                curveToRelative(-2.69f, -2.69f, -6.406f, -4.354f, -10.511f, -4.354f)
                curveToRelative(-8.209f, 0.0f, -14.865f, 6.655f, -14.865f, 14.865f)
                curveToRelative(0.0f, 2.732f, 0.737f, 5.291f, 2.022f, 7.491f)
                lineToRelative(-0.038f, -0.07f)
                lineToRelative(-2.109f, 7.702f)
                lineToRelative(7.879f, -2.067f)
                curveToRelative(2.051f, 1.139f, 4.498f, 1.809f, 7.102f, 1.809f)
                horizontalLineToRelative(0.006f)
                curveToRelative(8.209f, -0.003f, 14.862f, -6.659f, 14.862f, -14.868f)
                curveToRelative(0.0f, -4.103f, -1.662f, -7.817f, -4.349f, -10.507f)
                lineToRelative(0.0f, 0.0f)
                close()
                moveTo(16.062f, 28.228f)
                horizontalLineToRelative(-0.005f)
                curveToRelative(-0.0f, 0.0f, -0.001f, 0.0f, -0.001f, 0.0f)
                curveToRelative(-2.319f, 0.0f, -4.489f, -0.64f, -6.342f, -1.753f)
                lineToRelative(0.056f, 0.031f)
                lineToRelative(-0.451f, -0.267f)
                lineToRelative(-4.675f, 1.227f)
                lineToRelative(1.247f, -4.559f)
                lineToRelative(-0.294f, -0.467f)
                curveToRelative(-1.185f, -1.862f, -1.889f, -4.131f, -1.889f, -6.565f)
                curveToRelative(0.0f, -6.822f, 5.531f, -12.353f, 12.353f, -12.353f)
                reflectiveCurveToRelative(12.353f, 5.531f, 12.353f, 12.353f)
                curveToRelative(0.0f, 6.822f, -5.53f, 12.353f, -12.353f, 12.353f)
                horizontalLineToRelative(-0.0f)
                close()
                moveTo(22.838f, 18.977f)
                curveToRelative(-0.371f, -0.186f, -2.197f, -1.083f, -2.537f, -1.208f)
                curveToRelative(-0.341f, -0.124f, -0.589f, -0.185f, -0.837f, 0.187f)
                curveToRelative(-0.246f, 0.371f, -0.958f, 1.207f, -1.175f, 1.455f)
                curveToRelative(-0.216f, 0.249f, -0.434f, 0.279f, -0.805f, 0.094f)
                curveToRelative(-1.15f, -0.466f, -2.138f, -1.087f, -2.997f, -1.852f)
                lineToRelative(0.01f, 0.009f)
                curveToRelative(-0.799f, -0.74f, -1.484f, -1.587f, -2.037f, -2.521f)
                lineToRelative(-0.028f, -0.052f)
                curveToRelative(-0.216f, -0.371f, -0.023f, -0.572f, 0.162f, -0.757f)
                curveToRelative(0.167f, -0.166f, 0.372f, -0.434f, 0.557f, -0.65f)
                curveToRelative(0.146f, -0.179f, 0.271f, -0.384f, 0.366f, -0.604f)
                lineToRelative(0.006f, -0.017f)
                curveToRelative(0.043f, -0.087f, 0.068f, -0.188f, 0.068f, -0.296f)
                curveToRelative(0.0f, -0.131f, -0.037f, -0.253f, -0.101f, -0.357f)
                lineToRelative(0.002f, 0.003f)
                curveToRelative(-0.094f, -0.186f, -0.836f, -2.014f, -1.145f, -2.758f)
                curveToRelative(-0.302f, -0.724f, -0.609f, -0.625f, -0.836f, -0.637f)
                curveToRelative(-0.216f, -0.01f, -0.464f, -0.012f, -0.712f, -0.012f)
                curveToRelative(-0.395f, 0.01f, -0.746f, 0.188f, -0.988f, 0.463f)
                lineToRelative(-0.001f, 0.002f)
                curveToRelative(-0.802f, 0.761f, -1.3f, 1.834f, -1.3f, 3.023f)
                curveToRelative(0.0f, 0.026f, 0.0f, 0.053f, 0.001f, 0.079f)
                lineToRelative(-0.0f, -0.004f)
                curveToRelative(0.131f, 1.467f, 0.681f, 2.784f, 1.527f, 3.857f)
                lineToRelative(-0.012f, -0.015f)
                curveToRelative(1.604f, 2.379f, 3.742f, 4.282f, 6.251f, 5.564f)
                lineToRelative(0.094f, 0.043f)
                curveToRelative(0.548f, 0.248f, 1.25f, 0.513f, 1.968f, 0.74f)
                lineToRelative(0.149f, 0.041f)
                curveToRelative(0.442f, 0.14f, 0.951f, 0.221f, 1.479f, 0.221f)
                curveToRelative(0.303f, 0.0f, 0.601f, -0.027f, 0.889f, -0.078f)
                lineToRelative(-0.031f, 0.004f)
                curveToRelative(1.069f, -0.223f, 1.956f, -0.868f, 2.497f, -1.749f)
                lineToRelative(0.009f, -0.017f)
                curveToRelative(0.165f, -0.366f, 0.261f, -0.793f, 0.261f, -1.242f)
                curveToRelative(0.0f, -0.185f, -0.016f, -0.366f, -0.047f, -0.542f)
                lineToRelative(0.003f, 0.019f)
                curveToRelative(-0.092f, -0.155f, -0.34f, -0.247f, -0.712f, -0.434f)
                close()
            }
        }.build()
        _whatsapp = built
        return built
    }

@Suppress("ObjectPropertyName")
private var _whatsapp: ImageVector? = null

@Suppress("UnusedReceiverParameter", "BooleanLiteralArgument")
internal val SocialMediaIcons.Discord: ImageVector
    get() {
        val cached = _discord
        if (cached != null) return cached
        val built = Builder(
            name = "Discord",
            defaultWidth = 24.0.dp,
            defaultHeight = 24.0.dp,
            viewportWidth = 24.0f,
            viewportHeight = 24.0f,
        ).apply {
            path(
                fill = SolidColor(Color(0xFF000000)),
                stroke = null,
                strokeLineWidth = 0.0f,
                strokeLineCap = Butt,
                strokeLineJoin = Miter,
                strokeLineMiter = 4.0f,
                pathFillType = NonZero,
            ) {
                moveTo(20.317f, 4.3698f)
                arcToRelative(19.7913f, 19.7913f, 0.0f, false, false, -4.8851f, -1.5152f)
                arcToRelative(0.0741f, 0.0741f, 0.0f, false, false, -0.0785f, 0.0371f)
                curveToRelative(-0.211f, 0.3753f, -0.4447f, 0.8648f, -0.6083f, 1.2495f)
                curveToRelative(-1.8447f, -0.2762f, -3.68f, -0.2762f, -5.4868f, 0.0f)
                curveToRelative(-0.1636f, -0.3933f, -0.4058f, -0.8742f, -0.6177f, -1.2495f)
                arcToRelative(0.077f, 0.077f, 0.0f, false, false, -0.0785f, -0.037f)
                arcToRelative(19.7363f, 19.7363f, 0.0f, false, false, -4.8852f, 1.515f)
                arcToRelative(0.0699f, 0.0699f, 0.0f, false, false, -0.0321f, 0.0277f)
                curveTo(0.5334f, 9.0458f, -0.319f, 13.5799f, 0.0992f, 18.0578f)
                arcToRelative(0.0824f, 0.0824f, 0.0f, false, false, 0.0312f, 0.0561f)
                curveToRelative(2.0528f, 1.5076f, 4.0413f, 2.4228f, 5.9929f, 3.0294f)
                arcToRelative(0.0777f, 0.0777f, 0.0f, false, false, 0.0842f, -0.0276f)
                curveToRelative(0.4616f, -0.6304f, 0.8731f, -1.2952f, 1.226f, -1.9942f)
                arcToRelative(0.076f, 0.076f, 0.0f, false, false, -0.0416f, -0.1057f)
                curveToRelative(-0.6528f, -0.2476f, -1.2743f, -0.5495f, -1.8722f, -0.8923f)
                arcToRelative(0.077f, 0.077f, 0.0f, false, true, -0.0076f, -0.1277f)
                curveToRelative(0.1258f, -0.0943f, 0.2517f, -0.1923f, 0.3718f, -0.2914f)
                arcToRelative(0.0743f, 0.0743f, 0.0f, false, true, 0.0776f, -0.0105f)
                curveToRelative(3.9278f, 1.7933f, 8.18f, 1.7933f, 12.0614f, 0.0f)
                arcToRelative(0.0739f, 0.0739f, 0.0f, false, true, 0.0785f, 0.0095f)
                curveToRelative(0.1202f, 0.099f, 0.246f, 0.1981f, 0.3728f, 0.2924f)
                arcToRelative(0.077f, 0.077f, 0.0f, false, true, -0.0066f, 0.1276f)
                arcToRelative(12.2986f, 12.2986f, 0.0f, false, true, -1.873f, 0.8914f)
                arcToRelative(0.0766f, 0.0766f, 0.0f, false, false, -0.0407f, 0.1067f)
                curveToRelative(0.3604f, 0.698f, 0.7719f, 1.3628f, 1.225f, 1.9932f)
                arcToRelative(0.076f, 0.076f, 0.0f, false, false, 0.0842f, 0.0286f)
                curveToRelative(1.961f, -0.6067f, 3.9495f, -1.5219f, 6.0023f, -3.0294f)
                arcToRelative(0.077f, 0.077f, 0.0f, false, false, 0.0313f, -0.0552f)
                curveToRelative(0.5004f, -5.177f, -0.8382f, -9.6739f, -3.5485f, -13.6604f)
                arcToRelative(0.061f, 0.061f, 0.0f, false, false, -0.0312f, -0.0286f)
                close()
                moveTo(8.02f, 15.3312f)
                curveToRelative(-1.1825f, 0.0f, -2.1569f, -1.0857f, -2.1569f, -2.419f)
                curveToRelative(0.0f, -1.3332f, 0.9555f, -2.4189f, 2.157f, -2.4189f)
                curveToRelative(1.2108f, 0.0f, 2.1757f, 1.0952f, 2.1568f, 2.419f)
                curveToRelative(0.0f, 1.3332f, -0.9555f, 2.4189f, -2.1569f, 2.4189f)
                close()
                moveTo(15.9948f, 15.3312f)
                curveToRelative(-1.1825f, 0.0f, -2.1569f, -1.0857f, -2.1569f, -2.419f)
                curveToRelative(0.0f, -1.3332f, 0.9554f, -2.4189f, 2.1569f, -2.4189f)
                curveToRelative(1.2108f, 0.0f, 2.1757f, 1.0952f, 2.1568f, 2.419f)
                curveToRelative(0.0f, 1.3332f, -0.946f, 2.4189f, -2.1568f, 2.4189f)
                close()
            }
        }.build()
        _discord = built
        return built
    }

@Suppress("ObjectPropertyName")
private var _discord: ImageVector? = null

@Suppress("UnusedReceiverParameter")
internal val SocialMediaIcons.Public: ImageVector
    get() {
        val cached = _public
        if (cached != null) return cached
        val built = Builder(
            name = "Public",
            defaultWidth = 24.0.dp,
            defaultHeight = 24.0.dp,
            viewportWidth = 24.0f,
            viewportHeight = 24.0f,
        ).apply {
            path(
                fill = SolidColor(Color(0xFF000000)),
                stroke = null,
                strokeLineWidth = 0.0f,
                strokeLineCap = Butt,
                strokeLineJoin = Miter,
                strokeLineMiter = 4.0f,
                pathFillType = NonZero,
            ) {
                moveTo(11.99f, 2.0f)
                curveTo(6.47f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.47f, 10.0f, 9.99f, 10.0f)
                curveTo(17.52f, 22.0f, 22.0f, 17.52f, 22.0f, 12.0f)
                reflectiveCurveTo(17.52f, 2.0f, 11.99f, 2.0f)
                close()
                moveTo(18.92f, 8.0f)
                horizontalLineToRelative(-2.95f)
                curveToRelative(-0.32f, -1.25f, -0.78f, -2.45f, -1.38f, -3.56f)
                curveToRelative(1.84f, 0.63f, 3.37f, 1.91f, 4.33f, 3.56f)
                close()
                moveTo(12.0f, 4.04f)
                curveToRelative(0.83f, 1.2f, 1.48f, 2.53f, 1.91f, 3.96f)
                horizontalLineToRelative(-3.82f)
                curveToRelative(0.43f, -1.43f, 1.08f, -2.76f, 1.91f, -3.96f)
                close()
                moveTo(4.26f, 14.0f)
                curveTo(4.1f, 13.36f, 4.0f, 12.69f, 4.0f, 12.0f)
                reflectiveCurveToRelative(0.1f, -1.36f, 0.26f, -2.0f)
                horizontalLineToRelative(3.38f)
                curveToRelative(-0.08f, 0.66f, -0.14f, 1.32f, -0.14f, 2.0f)
                reflectiveCurveToRelative(0.06f, 1.34f, 0.14f, 2.0f)
                horizontalLineTo(4.26f)
                close()
                moveTo(5.08f, 16.0f)
                horizontalLineToRelative(2.95f)
                curveToRelative(0.32f, 1.25f, 0.78f, 2.45f, 1.38f, 3.56f)
                curveToRelative(-1.84f, -0.63f, -3.37f, -1.9f, -4.33f, -3.56f)
                close()
                moveTo(8.03f, 8.0f)
                horizontalLineTo(5.08f)
                curveToRelative(0.96f, -1.66f, 2.49f, -2.93f, 4.33f, -3.56f)
                curveTo(8.81f, 5.55f, 8.35f, 6.75f, 8.03f, 8.0f)
                close()
                moveTo(12.0f, 19.96f)
                curveToRelative(-0.83f, -1.2f, -1.48f, -2.53f, -1.91f, -3.96f)
                horizontalLineToRelative(3.82f)
                curveToRelative(-0.43f, 1.43f, -1.08f, 2.76f, -1.91f, 3.96f)
                close()
                moveTo(14.34f, 14.0f)
                horizontalLineTo(9.66f)
                curveToRelative(-0.09f, -0.66f, -0.16f, -1.32f, -0.16f, -2.0f)
                reflectiveCurveToRelative(0.07f, -1.35f, 0.16f, -2.0f)
                horizontalLineToRelative(4.68f)
                curveToRelative(0.09f, 0.65f, 0.16f, 1.32f, 0.16f, 2.0f)
                reflectiveCurveToRelative(-0.07f, 1.34f, -0.16f, 2.0f)
                close()
                moveTo(14.59f, 19.56f)
                curveToRelative(0.6f, -1.11f, 1.06f, -2.31f, 1.38f, -3.56f)
                horizontalLineToRelative(2.95f)
                curveToRelative(-0.96f, 1.65f, -2.49f, 2.93f, -4.33f, 3.56f)
                close()
                moveTo(16.36f, 14.0f)
                curveToRelative(0.08f, -0.66f, 0.14f, -1.32f, 0.14f, -2.0f)
                reflectiveCurveToRelative(-0.06f, -1.34f, -0.14f, -2.0f)
                horizontalLineToRelative(3.38f)
                curveToRelative(0.16f, 0.64f, 0.26f, 1.31f, 0.26f, 2.0f)
                reflectiveCurveToRelative(-0.1f, 1.36f, -0.26f, 2.0f)
                horizontalLineToRelative(-3.38f)
                close()
            }
        }.build()
        _public = built
        return built
    }

@Suppress("ObjectPropertyName")
private var _public: ImageVector? = null
