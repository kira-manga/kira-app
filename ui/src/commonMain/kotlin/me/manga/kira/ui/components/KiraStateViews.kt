package me.manga.kira.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WebAsset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.kira.ui.theme.LocalSpacing

/**
 * Shared full-area placeholder states for the Yami design system (Phase 11.ui.UP-5).
 *
 * The rework screens had each grown their own ad-hoc "centered spinner" / "centered grey text"
 * empty + loading branches (see the pre-UP-5 `DownloadsScreen`/`HistoryScreen`/`SourcesScreen`
 * bodies). These three composables consolidate that into one consistent illustrated-state
 * vocabulary — a centred icon + title (+ optional message + optional action) — so every screen
 * reads the same and a future restyle happens in one file.
 *
 * **String-free by design.** Callers pass already-resolved strings (via `stringResource(...)`).
 * Keeping the catalog lookup at the call site means `:ui` carries no per-screen key coupling and
 * each screen owns its own copy — matching how the existing `DownloadBucketList(emptyLabel = …)`
 * call sites already hoist their text.
 *
 * All three fill their parent and centre their content, so callers drop them straight into the
 * `Box(Modifier.fillMaxSize())` content slot of a `Scaffold` body.
 */
/**
 * Shared circular icon medallion for the placeholder states — a 96.dp tinted circle holding a 44.dp
 * glyph. Redesign 2026-06: gives empty/error states a designed, branded feel (vs a bare grey glyph),
 * consistent across every screen that uses these state views.
 */
@Composable
private fun StateIcon(
    icon: ImageVector,
    tint: Color,
    containerColor: Color,
) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = tint,
        )
    }
}

/**
 * Centred indeterminate progress spinner covering the whole content area.
 *
 * Replaces the repeated `CircularProgressIndicator(Modifier.align(Alignment.Center))` inside a
 * `fillMaxSize` Box that every list screen hand-rolled for its `state.isLoading` branch.
 *
 * Mirrors native `presentation/common/screens/LoadingScreen.kt` value-for-value: a `fillMaxSize`
 * container with `16.dp` padding centring a [CircularProgressIndicator] whose [color] defaults to
 * `inversePrimary` (the native default) rather than the Material primary the rework spinner used.
 *
 * @param color spinner colour; defaults to `inversePrimary` to match native `LoadingScreen`.
 */
@Composable
fun KiraLoadingState(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.inversePrimary,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = color)
    }
}

/**
 * Centred empty-state illustration: a large muted [icon], a [title], an optional [message], and an
 * optional [action] slot (typically a `Button` / `TextButton`).
 *
 * @param title primary line — already resolved (e.g. `stringResource(Res.string.no_results_found)`).
 * @param icon defaults to [KiraIcons.Empty] (outline inbox); pass a domain-specific glyph when one
 *   reads better (e.g. [KiraIcons.FavoriteOutline] for an empty library).
 * @param message optional secondary explanatory line under the title.
 * @param action optional trailing composable (call-to-action button); omitted when null.
 */
@Composable
fun KiraEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = KiraIcons.Empty,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StateIcon(
            icon = icon,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.md),
        )
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
        if (action != null) {
            Column(modifier = Modifier.padding(top = spacing.lg)) {
                action()
            }
        }
    }
}

/**
 * Centred error-state illustration: an error glyph, the failure [message], and an optional Retry
 * button wired to [onRetry].
 *
 * @param message already-resolved failure text (e.g. `stringResource(Res.string.error_occurred)`).
 * @param retryLabel resolved label for the retry button; the button only renders when BOTH
 *   [retryLabel] and [onRetry] are non-null.
 * @param onRetry invoked on retry-button tap.
 */
@Composable
fun KiraErrorState(
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StateIcon(
            icon = KiraIcons.Error,
            tint = MaterialTheme.colorScheme.error,
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = spacing.md),
        )
        if (retryLabel != null && onRetry != null) {
            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = spacing.lg),
            ) {
                Text(retryLabel)
            }
        }
    }
}

/**
 * Multi-action error state — the design-system equivalent of the legacy 3-action `ErrorScreen`
 * (Retry + Open-in-WebView + Help) used on the Home feed error surface (GAP-HOME-01 / GAP-HOME-02).
 *
 * Layout is a faithful port of native `presentation/common/screens/ErrorScreen.kt:67-103`: the
 * failure [message] is rendered error-coloured, Bold, 14.sp, centred, capped at 2 lines with an
 * ellipsis, followed by a single horizontal [Row] (`fillMaxWidth`, `padding(horizontal = 8.dp)`,
 * `spacedBy(12.dp)`) of up to three equally-weighted icon-above-text buttons:
 *  - a Retry action (Refresh glyph) when [retryLabel] + [onRetry] are non-null,
 *  - an Open-in-WebView action (WebAsset glyph) when [openInWebViewLabel] + [onOpenInWebView] are
 *    non-null — the cross-platform recovery affordance equivalent to legacy `Handle403Error`'s
 *    WebView token-refresh path (GAP-HOME-19),
 *  - a Help action (HelpOutline glyph, auto-mirrored for RTL) when [helpLabel] + [onHelp] are
 *    non-null.
 *
 * Each present button takes `Modifier.weight(1f)` so they share the row width equally, matching the
 * native trio. (Native always shows all three; this overload renders only the wired actions, so a
 * caller passing fewer than three still gets a centred, evenly-weighted row.)
 *
 * All labels are already-resolved strings (string-free by design, matching the sibling state views).
 */
@Composable
fun KiraErrorState(
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    openInWebViewLabel: String? = null,
    onOpenInWebView: (() -> Unit)? = null,
    helpLabel: String? = null,
    onHelp: (() -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StateIcon(
            icon = KiraIcons.Error,
            tint = MaterialTheme.colorScheme.error,
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        )
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = spacing.md),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (retryLabel != null && onRetry != null) {
                IconAboveTextButton(
                    title = retryLabel,
                    icon = Icons.Default.Refresh,
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                )
            }
            if (openInWebViewLabel != null && onOpenInWebView != null) {
                IconAboveTextButton(
                    title = openInWebViewLabel,
                    icon = Icons.Default.WebAsset,
                    onClick = onOpenInWebView,
                    modifier = Modifier.weight(1f),
                )
            }
            if (helpLabel != null && onHelp != null) {
                IconAboveTextButton(
                    title = helpLabel,
                    icon = Icons.AutoMirrored.Filled.HelpOutline,
                    onClick = onHelp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Icon-above-text recovery button — port of native
 * `presentation/common/componants/buttons/IconAboveTextButton.kt`. A transparent, flat (no
 * elevation) button whose content is a centred [icon] over a [title], both tinted with the theme
 * primary colour. Used by the multi-action [KiraErrorState] action row.
 */
@Composable
private fun IconAboveTextButton(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    spacing: Dp = 4.dp,
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(iconSize),
            )
            Spacer(modifier = Modifier.height(spacing))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Rich per-source site-status view — a faithful port of the legacy `SimpleStatusScreen`
 * (Maintenance / Stopped / Adult-blocked variants, GAP-HOME-17).
 *
 * Layout matches the legacy spec value-for-value: a 120.dp circular [Card] (rounded 60, elevation
 * 8) tinted by [containerColor] holding a 48.dp [icon] tinted by [iconColor], then a
 * `headlineMedium` Bold [title], a `titleLarge` primary [subtitle] (the site name), and a
 * `bodyLarge` [message] at 0.7 alpha.
 *
 * The three call-site variants supply distinct icon + colour pairs (Build/primary-container,
 * Error/error-container, Error/tertiary-container), so each state reads visually distinct rather
 * than collapsing to a single generic empty glyph.
 *
 * String-free by design — callers pass already-resolved [title] / [subtitle] / [message].
 */
@Composable
fun KiraSiteStatusView(
    icon: ImageVector,
    iconColor: Color,
    containerColor: Color,
    title: String,
    subtitle: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Card(
                modifier = Modifier.size(120.dp),
                shape = RoundedCornerShape(60.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 32.dp),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}
