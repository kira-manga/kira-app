@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package me.manga.kira.ui.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.AppRegistration
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.StarRate
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import me.manga.kira.presentation.about.AboutEffect
import me.manga.kira.presentation.about.AboutIntent
import me.manga.kira.presentation.about.AboutState
import me.manga.kira.presentation.about.AboutViewModel
import me.manga.kira.ui.components.KiraSocialMediaRow
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.about
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.check_for_update
import me.manga.kira.ui.generated.resources.kira_logo
import me.manga.kira.ui.generated.resources.np_soon
import me.manga.kira.ui.generated.resources.np_source_code
import me.manga.kira.ui.generated.resources.privacy_policy
import me.manga.kira.ui.generated.resources.rate_our_app
import me.manga.kira.ui.generated.resources.version
import me.manga.kira.ui.generated.resources.what_s_new
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * About screen — Compose entry point for the About MVI slice.
 *
 * Phase 7.x.about rework + Phase 7.x.about.whatsnewrow. Renders [AboutState] (the running
 * app's version + package id) as a five-row list: a Version row + four actionable rows
 * (Check for update, Rate our app, Privacy policy, What's new). Dispatches
 * [AboutIntent.OnOpenPlayStore] / [AboutIntent.OnOpenUrl] / [AboutIntent.OnOpenWhatsNew]
 * and surfaces [AboutEffect.OpenPlayStorePage] / [AboutEffect.OpenUrl] /
 * [AboutEffect.NavigateToWhatsNew] to the host via the [onEffect] callback.
 *
 * **Visual delta vs the legacy `composeApp/.../features/about/screen/AboutScreen.kt`**:
 *
 *  - **Five rows + SocialMediaRow vs six rows + SocialMediaRow in the legacy**. The rework
 *    About screen deliberately omits the Source-code row (it was already disabled in the
 *    legacy — no-op click + "soon" subtitle; no behaviour to port). The SocialMediaRow
 *    itself was RESTORED by **Phase 7.x.about.socialmediarow** once brand-icon vendoring
 *    landed via [SocialMediaIcons] (X, Facebook, Instagram, WhatsApp, Discord, Public):
 *    six circular [SocialMediaButton]s in a [BoxWithConstraints]-driven adaptive Row,
 *    each dispatching [AboutIntent.OnOpenUrl] verbatim against the same web URLs the
 *    legacy `composeApp/.../features/about/common/SocialMediaRow.kt` uses. The
 *    Discord button has no landing page in the legacy ("/* default: no-op */") so the
 *    rework also leaves it as a no-op tap target — visual presence preserved for parity.
 *      * **Whats-new row** — RESTORED by Phase 7.x.about.whatsnewrow once Phase 7.x.whatsnew
 *        shipped the `Screen.WhatsNewRework` foundation (commit `e5d91b0`). Row is now wired:
 *        tap dispatches [AboutIntent.OnOpenWhatsNew]; the route adapter routes the in-app
 *        nav via `navController.navigate(Screen.WhatsNewRework)`.
 *  - **Per-row leading icons + trailing chevron** (parity-fix): each [AboutRow] now renders the
 *    same leading Material icon as native (`Icons.Outlined.AppRegistration` / `.Update` /
 *    `.StarRate` / `Icons.AutoMirrored.Outlined.ManageSearch` / `Icons.Outlined.Code`; the
 *    privacy row has none) at 24.dp tinted `onSurface` with a 16.dp gap, plus a trailing
 *    `Icons.AutoMirrored.Filled.KeyboardArrowRight` chevron on every row — matching native
 *    `SettingsNavigationItem.kt:40-88`. `:ui` already depends on `compose.materialIconsExtended`
 *    (Phase 11.ui.UP-2), so the real Material vectors are used directly — no vendoring needed.
 *    (Earlier this slice rendered text-only rows; the prior "icons omitted" note is historical.)
 *  - **Brand image** (GAP-ABT-03): the canonical `design/brand/kira-logo.svg` is mirrored into
 *    `:ui/commonMain/composeResources` and renders at the top of the column at native parity —
 *    250.dp with vertical padding 24, centered — matching legacy `AboutScreen.kt:99-107`.
 *  - **Design tokens**: legacy uses `.dp` literals + `Color.Gray.copy(...)` for dividers +
 *    custom `ItemsGroup` / `SettingsNavigationItem` helpers. Rework uses [LocalSpacing] +
 *    `HorizontalDivider` / `Text` directly. **(P2 parity-fix)** [AboutItemsGroup] now mirrors
 *    native `ItemsGroup.kt:17-25` exactly — a flat `Column` with a `surfaceContainerHigh`
 *    background + `RoundedCornerShape(16.dp)` + `padding(horizontal = 16.dp, vertical = 8.dp)`
 *    (replacing the earlier Material 3 `Card` + `surfaceVariant` + 12.dp-corner shape, which
 *    added an unwanted tonal elevation / shadow and a different shade vs native).
 *  - **Localized strings (Phase 11.ui.UP-3d)** — row labels resolve through `stringResource`
 *    (reused legacy keys, en + ar). Social-button contentDescriptions stay literal brand names
 *    (proper nouns, conventionally not localized).
 *  - **Privacy URL is a literal** here (`"https://kiramanga.me/privacy"`). Centralising URL
 *    constants is a Phase 10 concern (a future `:domain.config.RemoteUrls` value object) that
 *    lifts both consumers
 *    in one pass. The six SocialMediaRow URLs ([TWITTER_URL] / [FACEBOOK_URL] /
 *    [INSTAGRAM_URL] / [WHATSAPP_URL] / [WEBSITE_URL]) are also inline literals —
 *    same Phase 10 lift target.
 *
 * **Effect collection**: a single [LaunchedEffect] keyed on the [effects] Flow reference
 * forwards each [AboutEffect] to [onEffect]. The Flow is single-consumer (Channel-backed in
 * the base [me.manga.kira.presentation.mvi.MviViewModel]); collecting it here is safe
 * because the route adapter ([me.manga.kira.navigation.routes.AboutReworkScreenRoute])
 * is the only host. Same shape as the Reader's effect collection in
 * [me.manga.kira.ui.reader.ReaderScreen].
 *
 * **Loading state**: a centered [CircularProgressIndicator] while `state.isLoading == true`.
 * In practice this is sub-frame on a real device (the legacy `AppVersionProvider` resolves
 * synchronously via two property reads), but the spinner avoids a one-frame
 * "version: " gap on cold start.
 *
 * **Stateless inner [AboutScreenContent]** mirrors the established rework `:ui` pattern —
 * "wire to VM" separated from "render state", so previews / tests can feed canned state
 * without spinning up a real VM.
 *
 * Constructor takes the [AboutViewModel] + an [onEffect] callback + an [onBack] callback.
 * The route adapter supplies the [onEffect] callback that bridges [AboutEffect] to the
 * platform [IntentLauncher] (Koin-resolved at the route) and the [onBack] callback that
 * forwards back-press to `navController.safePopBackStack()`. Same boundary as the
 * Reader's effect → callback wiring + every other rework `:ui` screen's back posture.
 *
 * **Back-press posture** (Phase 7.x.about.backbutton; parity-fix P2): the rework About
 * screen renders an [IconButton] with an `Icons.AutoMirrored.Filled.ArrowBack` icon
 * (tinted onBackground) in its [TopAppBar.navigationIcon] slot — matching native
 * `AboutScreen.kt:74-82` exactly. (This slice previously rendered a text "Back"
 * TextButton; the P2 parity-fix swapped it for the standard back-arrow IconButton to
 * match native chrome.) Tap dispatches the supplied [onBack] callback. No back-press
 * behaviour delta — system back (Android hardware back / iOS swipe / Desktop ESC) is
 * unaffected.
 *
 * **SRP (contract §6)**: owns rendering + intent dispatch + effect forwarding to the host.
 * No nav decisions, no platform calls — `IntentLauncher` resolution lives at the route
 * adapter.
 *
 * **Why an `onEffect` callback parameter, not a host-supplied set of per-effect callbacks
 * (`onOpenUrl: (String) -> Unit`, `onOpenPlayStore: (String) -> Unit`)** — the per-effect
 * shape would force the screen signature to grow as new effects are added (e.g., a future
 * `NavigateToWhatsNew` would add `onNavigateToWhatsNew: () -> Unit`). A single
 * `onEffect: (AboutEffect) -> Unit` keeps the signature stable; the route adapter switches
 * on the sealed type. Same posture as the Reader's `onOpenInWebView` → effect-callback
 * channel decision documented in Phase 7.x.reader.modelayout.openwebview.
 *
 * **Audit-trail postscript** (Phase 9.x.settingsabout.staleKdocSweep.cascade,
 * Task #453, 2026-05-28): the file-level KDoc above and several URL-constant
 * KDocs below carry stale path citations into the §354-retired legacy
 * Settings+About chain:
 *  - Line 61 cites legacy `composeApp/.../features/about/screen/AboutScreen.kt`.
 *  - Line 70 cites legacy `composeApp/.../features/about/common/
 *    SocialMediaRow.kt`.
 *  - Line 98 cites "legacy `AboutScreen.kt:172`" line-anchored.
 *  - Line 128 cites legacy `composeApp/.../features/about/screen/AboutScreen.kt`
 *    again (back-press posture paragraph).
 *  - The URL-constant KDocs at lines 338 (`AboutScreen.kt:172`), 344
 *    (`SocialMediaRow.kt:99`), 347 (`SocialMediaRow.kt:107`), 350
 *    (`SocialMediaRow.kt:115`), 354 (`SocialMediaRow.kt:125-128`), 360
 *    (`SocialMediaRow.kt:145`) all carry line-anchored citations into the
 *    same retired pair of legacy files.
 *  - The [SocialMediaRow] composable KDoc at lines 364-365 cites legacy
 *    `composeApp/.../features/about/common/SocialMediaRow.kt`.
 * All cited legacy paths were retired in Phase 9.x.settings_about.legacyui.retire
 * (§354 — multi-commit chain `5cc42d2` + `b0387cb` + `171050c` + `d8404a1`);
 * verified by filesystem checks returning zero hits for both paths. The
 * visual-delta, URL-literal, and SocialMediaRow rationales stand on their
 * own merits — the rework's 6-button row, the inline URL constants, and the
 * row-omission decisions are all documented exhaustively inline above and
 * independent of which legacy file originally rendered them. Phase 10's
 * URL-centralization (`:domain.config.RemoteUrls`) and i18n lifts remain
 * the canonical next opportunities. Original §253-era prose preserved
 * verbatim per the audit-trail-preservation convention — the citations are
 * historical record of the design lineage; the screen continues to render
 * correctly through the legacy retire.
 */
@Composable
fun AboutScreen(
    viewModel: AboutViewModel,
    onEffect: (AboutEffect) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    EffectBridge(effects = viewModel.effects, onEffect = onEffect)
    AboutScreenContent(
        state = state,
        onIntent = viewModel::submit,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun EffectBridge(
    effects: Flow<AboutEffect>,
    onEffect: (AboutEffect) -> Unit,
) {
    LaunchedEffect(effects) {
        effects.collectLatest(onEffect)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutScreenContent(
    state: AboutState,
    onIntent: (AboutIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                // Title typography matches native `TopAppBarCom.kt:33-40`: titleLarge with
                // an explicit 24.sp size + FontWeight.Bold, tinted onBackground (default
                // Material3 TopAppBar title is ~22.sp Medium — visibly lighter/smaller).
                title = {
                    Text(
                        text = stringResource(Res.string.about),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // Back affordance matches native `AboutScreen.kt:74-82`: an IconButton with
                // an AutoMirrored ArrowBack icon tinted onBackground (was a text "Back"
                // TextButton). RTL-mirrored automatically via the AutoMirrored variant.
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                // Native `TopAppBarCom.kt:29-31` sets the container color to background.
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
            ) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            return@Scaffold
        }
        val spacing = LocalSpacing.current
        // Match native `AboutScreen.kt:89-93` LazyColumn (inherently vertically scrollable): the
        // 250.dp logo + dividers + six rows + SocialMediaRow can exceed the viewport on short /
        // landscape screens, so the content must scroll rather than clip.
        //
        // Padding parity-fix: native applies ONLY the top inset + horizontal 16.dp
        // (`padding(start = 16.dp, end = 16.dp, top = paddingValues.calculateTopPadding())`) — no
        // bottom inset and no extra uniform vertical content padding (the logo's own vertical=24.dp
        // supplies the top gap). The earlier KMP modifier applied the full Scaffold insets
        // (incl. bottom) plus a uniform 16.dp on all sides, breaking the native vertical rhythm.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            // GAP-ABT-03 — logo renders at native 250.dp with vertical padding 24 (was 120.dp).
            Image(
                painter = painterResource(Res.drawable.kira_logo),
                contentDescription = null,
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 24.dp)
                        .size(250.dp),
            )

            // GAP-ABT-04 — under-logo divider (Gray .3α) matching native `AboutScreen.kt:108`,
            // separating the logo header from the rows group.
            HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))

            Spacer(Modifier.height(spacing.md))

            // Row order matches native `AboutScreen.kt:112-148` exactly: Version,
            // Check for update, Rate our app, What's new, Source code, Privacy policy
            // (parity-fix — Privacy policy moves to last; What's new and Source code
            // precede it, mirroring the native `ItemsGroup` ordering).
            AboutItemsGroup {
                AboutRow(
                    title = stringResource(Res.string.version),
                    description = state.versionName,
                    leadingIcon = Icons.Outlined.AppRegistration,
                    onClick = null,
                )
                AboutRowDivider()
                AboutRow(
                    title = stringResource(Res.string.check_for_update),
                    description = null,
                    leadingIcon = Icons.Outlined.Update,
                    onClick = { onIntent(AboutIntent.OnOpenPlayStore) },
                )
                AboutRowDivider()
                AboutRow(
                    title = stringResource(Res.string.rate_our_app),
                    description = null,
                    leadingIcon = Icons.Outlined.StarRate,
                    onClick = { onIntent(AboutIntent.OnRequestReview) },
                )
                AboutRowDivider()
                AboutRow(
                    title = stringResource(Res.string.what_s_new),
                    description = null,
                    leadingIcon = Icons.AutoMirrored.Outlined.ManageSearch,
                    onClick = { onIntent(AboutIntent.OnOpenWhatsNew) },
                )
                AboutRowDivider()
                // GAP-ABT-02 — restore the inert "Source code" row (disabled "soon" subtitle) for
                // 1:1 row parity with native `AboutScreen.kt:108-149`. onClick = null keeps it
                // display-only, matching the legacy's disabled/no-op posture.
                AboutRow(
                    title = stringResource(Res.string.np_source_code),
                    description = stringResource(Res.string.np_soon),
                    leadingIcon = Icons.Outlined.Code,
                    onClick = null,
                )
                AboutRowDivider()
                AboutRow(
                    title = stringResource(Res.string.privacy_policy),
                    description = null,
                    // Native `AboutScreen.kt:148` passes no leading icon on the privacy row.
                    leadingIcon = null,
                    onClick = { onIntent(AboutIntent.OnOpenUrl(PRIVACY_POLICY_URL)) },
                )
            }

            Spacer(Modifier.height(spacing.lg))

            KiraSocialMediaRow(onOpenUrl = { url -> onIntent(AboutIntent.OnOpenUrl(url)) })

            // Parity-fix: native `AboutScreen.kt:151-155` ends the content with a 24.dp Spacer
            // after SocialMediaRow and renders NO package-id footer — the package id is read only
            // inline for the Version row value (native AboutScreen.kt:114). The earlier
            // KMP-only `PackageLabel(state.packageName)` footer was a divergence and is removed.
            Spacer(Modifier.height(spacing.lg))
        }
    }
}

/**
 * Container that visually groups the About rows. Mirrors native
 * `ItemsGroup.kt:17-25` exactly (parity-fix): a flat `Column` with a
 * `surfaceContainerHigh` background clipped to a `RoundedCornerShape(16.dp)` and
 * `padding(horizontal = 16.dp, vertical = 8.dp)`. No Material 3 `Card` — native draws a
 * plain background (no tonal elevation / shadow), so the rework drops the elevated `Card`
 * (which previously added `surfaceVariant` + a 12.dp corner + default Card elevation).
 * The 16.dp horizontal padding here provides each row's leading inset, so [AboutRow]
 * itself carries only vertical padding (matching native `SettingsNavigationItem.kt:47`).
 * Children render top-to-bottom; the caller is responsible for [HorizontalDivider]s
 * between rows.
 */
@Composable
private fun AboutItemsGroup(content: @Composable () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(16.dp),
                ).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        content()
    }
}

/**
 * Single About-screen row. Layout (matching native `SettingsNavigationItem.kt:40-88`):
 * an optional 24.dp leading icon + 16.dp gap, then a title above an optional description
 * (e.g., the Version row's `versionName` displays as description), then a trailing
 * chevron. The leading icon is tinted `onSurface` per the native `iconColor =
 * MaterialTheme.colorScheme.onBackground` default (rework token equivalent).
 *
 * **Leading icon**: native passes `Icons.Outlined.AppRegistration` / `.Update` / `.StarRate`
 * / `Icons.AutoMirrored.Outlined.ManageSearch` / `Icons.Outlined.Code` per actionable row;
 * the privacy row passes none. [leadingIcon] = null renders no icon and no leading gap,
 * exactly as native does for the privacy row.
 *
 * **Trailing chevron**: native `SettingsNavigationItem` always renders its
 * `endIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight` (never set to null in
 * `AboutScreen.kt`), so every row — including display-only Version / Source-code rows —
 * shows the chevron. Tinted `onSurface` per native `MaterialTheme.colorScheme.onBackground`.
 *
 * **Click handling**: if [onClick] is non-null, the row is `Modifier.clickable`. If null,
 * the row is display-only (used for the Version + Source-code rows, which have no action).
 * Same convention as the native `SettingsNavigationItem` (a null lambda → display-only row).
 */
@Composable
private fun AboutRow(
    title: String,
    description: String?,
    leadingIcon: ImageVector?,
    onClick: (() -> Unit)?,
) {
    val spacing = LocalSpacing.current
    // Native `SettingsNavigationItem.kt:47` pads only vertically (16.dp); the row's
    // horizontal inset comes from the enclosing `AboutItemsGroup` (native `ItemsGroup`'s
    // horizontal=16.dp). Match that — vertical 16.dp here, no horizontal row padding.
    val rowModifier =
        Modifier
            .fillMaxWidth()
            .let { base ->
                if (onClick != null) base.clickable(onClick = onClick) else base
            }.padding(vertical = spacing.lg)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            // Typography matches native `SettingsNavigationItem.kt:63`: title 14.sp onBackground
            // (was Material3 bodyLarge ~16.sp onSurface).
            Text(
                text = title,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (description != null) {
                Spacer(Modifier.height(spacing.xxs))
                // Subtitle matches native `SettingsNavigationItem.kt:64-77` (AutoSubtitleText):
                // 12.sp onBackground@0.8, single line, ellipsized on overflow. The native
                // StepBased auto-shrink (6sp..12sp) is approximated here by the maxLines=1 +
                // Ellipsis cap — `:ui` has no shared step-based auto-size text primitive, and the
                // values shown (version name, "soon") never overflow at 12.sp.
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun AboutRowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
    )
}

/**
 * Privacy policy URL — matches the legacy `AboutScreen.kt:172` literal. Centralising URLs
 * into a `:domain.config.RemoteUrls` value object is a Phase 10 concern that lifts both
 * legacy and rework consumers in one pass.
 */
private const val PRIVACY_POLICY_URL = "https://kiramanga.me/privacy"
