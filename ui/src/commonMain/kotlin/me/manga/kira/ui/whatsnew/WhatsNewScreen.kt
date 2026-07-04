package me.manga.kira.ui.whatsnew

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.manga.kira.domain.model.whatsnew.MediaType
import me.manga.kira.domain.model.whatsnew.WhatsNewFeature
import me.manga.kira.presentation.whatsnew.WhatsNewEffect
import me.manga.kira.presentation.whatsnew.WhatsNewIntent
import me.manga.kira.presentation.whatsnew.WhatsNewState
import me.manga.kira.presentation.whatsnew.WhatsNewViewModel
import me.manga.kira.ui.components.KiraEmptyState
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.new_badge
import me.manga.kira.ui.generated.resources.np_close
import me.manga.kira.ui.generated.resources.np_get_started
import me.manga.kira.ui.generated.resources.np_next
import me.manga.kira.ui.generated.resources.np_p3_whats_new_empty_body
import me.manga.kira.ui.generated.resources.np_p3_whats_new_empty_title
import me.manga.kira.ui.generated.resources.np_p3_whats_new_error_title
import me.manga.kira.ui.generated.resources.np_p3_whats_new_loading
import me.manga.kira.ui.generated.resources.np_p3_whats_new_title
import me.manga.kira.ui.generated.resources.np_previous
import me.manga.kira.ui.generated.resources.np_tap_outside_to_close
import me.manga.kira.ui.generated.resources.np_whats_new_close
import me.manga.kira.ui.generated.resources.play_video
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.generated.resources.video_preview
import me.manga.kira.ui.generated.resources.whats_new_feature_image
import me.manga.kira.ui.generated.resources.whats_new_image_failed
import me.manga.kira.ui.generated.resources.whats_new_thumbnail
import me.manga.kira.ui.generated.resources.whats_new_video_opens_externally
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * What's New screen — Compose entry point for the WhatsNew MVI slice.
 *
 * Renders [WhatsNewState] as one of four mutually-exclusive surfaces (see [WhatsNewScreenContent]):
 * - **Loading** (`state.isLoading == true`): a centered 48.dp [CircularProgressIndicator] above a
 *   "Loading What's New..." label (native `WhatsNewRoute.kt`'s `LoadingState`).
 * - **Error** (`!isLoading && errorMessage != null`): a centered elevated [Card] with a
 *   "Failed to Load" title, the failure message, and a Close + Retry button row. Retry dispatches
 *   [WhatsNewIntent.OnRetry]. Currently dormant — the `:data` impl swallows remote failures and
 *   returns the empty default list, so the empty path is reached instead.
 * - **Empty** (`!isLoading && features.isEmpty() && errorMessage == null`): a "No Updates
 *   Available" title + body line + Close button. De-facto path today because the legacy
 *   `getDefaultFeatures()` returns `emptyList()` after the KMP migration.
 * - **Loaded** (`!isLoading && features.isNotEmpty()`): a [FeaturePager] (HorizontalPager of
 *   feature cards) + a page-indicator dot row + Previous / Next / Get-Started navigation buttons.
 *   Each card renders media (image carousel / single image / video poster / title-initials
 *   placeholder) → centered auto-sized title (+ optional "NEW" chip) → centered auto-sized body.
 *
 * **Implemented parity surface** (mirrors native `features/whatsnew`):
 *  - **Pager**: `androidx.compose.foundation.pager.HorizontalPager` (one full-width page per
 *    feature) with bi-directional state sync — `rememberPagerState` seeds from
 *    [WhatsNewState.currentPage]; a `LaunchedEffect` keyed on `pagerState.currentPage` dispatches
 *    [WhatsNewIntent.OnPageChanged] back into the VM on user swipe.
 *  - **Navigation buttons**: a Previous (OutlinedButton) / Next (Button) row, with an 80.dp Spacer
 *    in the Previous slot on the first page and a Get-Started button replacing Next on the last
 *    page (native `WhatsNewComponents.kt`'s `NavigationButtons`).
 *  - **Images**: Coil `AsyncImage` renders [WhatsNewFeature.imageUrl] / [imageUrlList] (single
 *    image + thumbnail carousel); bundled drawable-name features (not resolvable in `:ui`
 *    composeResources) and media-less features fall back to a title-initials [ImagePlaceholder].
 *  - **Fullscreen image viewer**: tapping a feature image lifts it into a full-screen [Dialog]
 *    overlay ([FullscreenMediaViewer]) with a close button + tap-outside-to-close scrim.
 *  - **Video**: rendered as a tappable poster ([VideoPoster]) that emits [WhatsNewIntent.OnOpenVideo]
 *    → [WhatsNewEffect.OpenVideo] → the route adapter's platform `IntentLauncher` to open the URL
 *    externally. DEVIATION(platform): native plays video INLINE via an Android `VideoView`; a
 *    faithful cross-platform inline player needs the not-yet-existing `:platform` MediaPlayer SPI +
 *    a per-platform media dependency, so the poster substitutes (flagged cross-cutting). When that
 *    SPI lands, the poster's fullscreen routes into [FullscreenMediaViewer].
 *  - **Responsive sizing**: tablet (≥600.dp width) / landscape (width > height) breakpoints read
 *    via [BoxWithConstraints] (no `LocalConfiguration` in commonMain) scale horizontalPadding,
 *    cardPadding, media size, and the card max-height fraction to mirror native's values.
 *  - **Per-card animation**: a horizontal slide + fade keyed to the active page (native
 *    `FeatureCard.kt`'s `AnimatedVisibility`).
 *  - **Gradient**: a full-screen primaryContainer@0.3 → surface vertical gradient behind a
 *    transparent header.
 *
 * **Not modelled** (no native equivalent in the rework target, or deferred): version-group
 * splitting ("NEW since version X" section headers) — the rework renders all features in the pager
 * with an inline per-card "NEW" chip when [WhatsNewFeature.isNew] is true.
 *
 * **Effect collection**: a single [LaunchedEffect] keyed on the [effects] Flow reference forwards
 * each [WhatsNewEffect] to [onEffect] (currently the OpenVideo external-launch trigger). The Flow is
 * single-consumer (Channel-backed in the base [me.manga.kira.presentation.mvi.MviViewModel]);
 * collecting it here is safe because the route adapter is the only host. Same shape as
 * [AboutScreen]'s effect bridging.
 *
 * **Stateless inner [WhatsNewScreenContent]** mirrors the established rework `:ui` pattern —
 * "wire to VM" separated from "render state", so previews / tests can feed canned state without
 * spinning up a real VM.
 *
 * **SRP (contract §6)**: owns rendering + intent dispatch + effect forwarding to the host. No
 * remote fetch (lives in `:data` via `:shared`), no nav decisions (route adapter owns nav), no
 * platform calls (effect handler is host-owned).
 */
@Composable
fun WhatsNewScreen(
    viewModel: WhatsNewViewModel,
    onEffect: (WhatsNewEffect) -> Unit,
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    EffectBridge(effects = viewModel.effects, onEffect = onEffect)
    WhatsNewScreenContent(
        state = state,
        onIntent = viewModel::submit,
        onGetStarted = onGetStarted,
        modifier = modifier,
    )
}

@Composable
private fun EffectBridge(effects: Flow<WhatsNewEffect>, onEffect: (WhatsNewEffect) -> Unit) {
    LaunchedEffect(effects) {
        effects.collectLatest(onEffect)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhatsNewScreenContent(
    state: WhatsNewState,
    onIntent: (WhatsNewIntent) -> Unit,
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        // GAP-WN-08 — full-screen primaryContainer→surface vertical gradient (mirrors native
        // `WhatsNewScreen.kt:53-64`). The Scaffold + header containers are transparent so the
        // gradient shows through.
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            ),
        containerColor = Color.Transparent,
    ) { padding ->
        // GAP-WN-07 — tablet-/landscape-aware sizing mirroring native `WhatsNewScreen.kt:36-51`
        // (`isTablet = screenWidthDp >= 600`, `isLandscape = screenWidthDp > screenHeightDp`,
        // `horizontalPadding = if (isTablet) 32.dp else 16.dp`, and a `cardPadding` that branches
        // tablet/landscape/phone). `:ui` commonMain has no `LocalConfiguration`, so the breakpoints
        // are read from the available width/height via [BoxWithConstraints] (same posture as the
        // other responsive rework screens — Library / Reader).
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val isTablet = maxWidth >= 600.dp
            val isLandscape = maxWidth > maxHeight
            val horizontalPadding: Dp = if (isTablet) 32.dp else 16.dp
            // Native `WhatsNewScreen.kt:47-51`: cardPadding 32 (tablet) / 16 (landscape) / 24 (phone).
            val cardPadding: Dp = when {
                isTablet -> 32.dp
                isLandscape -> 16.dp
                else -> 24.dp
            }
            Column(modifier = Modifier.fillMaxSize()) {
                // GAP-WN-07 — header parity: a transparent custom Row (NOT a Material3 TopAppBar)
                // with a headlineSmall Bold title + a 40.dp circular surfaceVariant@0.5 close (X)
                // affordance, mirroring native `WhatsNewComponents.kt:21-63`. On Desktop / iOS
                // there is no hardware back, so the X is functionally important. Closing marks the
                // screen seen (persists version + timestamp) and asks the host to dismiss — same
                // dismiss path as the last-page Get-Started button (native's header X and
                // Get-Started both invoke the single `onDismiss`).
                WhatsNewHeader(
                    horizontalPadding = horizontalPadding,
                    onDismiss = {
                        onIntent(WhatsNewIntent.OnMarkSeen)
                        onGetStarted()
                    },
                )
                val errorMessage = state.errorMessage
                when {
                    state.isLoading -> LoadingState(Modifier.weight(1f))
                    errorMessage != null -> ErrorState(
                        modifier = Modifier.weight(1f),
                        message = errorMessage,
                        onRetry = { onIntent(WhatsNewIntent.OnRetry) },
                        // Close on the error/empty surfaces only dismisses (native pops back); it
                        // does NOT mark the screen seen — native reserves mark-seen for the loaded
                        // screen's dismiss/Get-Started.
                        onClose = onGetStarted,
                    )
                    state.features.isEmpty() -> EmptyState(
                        modifier = Modifier.weight(1f),
                        onClose = onGetStarted,
                    )
                    else -> FeaturePager(
                        features = state.features,
                        currentPage = state.currentPage,
                        isTablet = isTablet,
                        isLandscape = isLandscape,
                        horizontalPadding = horizontalPadding,
                        cardPadding = cardPadding,
                        onPageChanged = { onIntent(WhatsNewIntent.OnPageChanged(it)) },
                        onOpenVideo = { onIntent(WhatsNewIntent.OnOpenVideo(it)) },
                        // GAP-WN-03 / GAP-WN-05 — the Get-Started button on the last page marks the
                        // screen seen (persists version + timestamp via MarkWhatsNewSeenUseCase) and
                        // asks the host to dismiss / navigate back.
                        onGetStarted = {
                            onIntent(WhatsNewIntent.OnMarkSeen)
                            onGetStarted()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Transparent custom header (GAP-WN-07) — port of native `WhatsNewComponents.kt`'s
 * `WhatsNewHeader`. A transparent [Surface] (tonalElevation 2.dp) wrapping a [Row]
 * (horizontal = [horizontalPadding], vertical = 12.dp, SpaceBetween) with a headlineSmall Bold
 * title and a 40.dp circular surfaceVariant@0.5 close [IconButton]. Replaces the prior Material3
 * `TopAppBar` so the title style (headlineSmall vs titleLarge), the circular tinted close chip,
 * and the gradient-over-transparent header match native.
 */
@Composable
private fun WhatsNewHeader(horizontalPadding: Dp, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // Native `whats_new_title` = "What's New" (capital N). The shared base
                // `what_s_new` key reads "What's new" and is reused by Settings/About rows, so a
                // whatsnew-scoped key restores the native header casing without touching it.
                text = stringResource(Res.string.np_p3_whats_new_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        CircleShape,
                    ),
            ) {
                Icon(
                    imageVector = KiraIcons.Close,
                    contentDescription = stringResource(Res.string.np_whats_new_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Loading surface — a centered 48.dp spinner (strokeWidth 4.dp) above a "Loading What's New..."
 * label, mirroring native `WhatsNewRoute.kt`'s `LoadingState` (a bare shared `KiraLoadingState`
 * showed an unlabeled default-size spinner). Rendered inline rather than via the shared component
 * because that component takes no label and `:ui/components` is out of scope.
 */
@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                strokeWidth = 4.dp,
            )
            Text(
                text = stringResource(Res.string.np_p3_whats_new_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Error surface — a centered elevated [Card] with a "Failed to Load" (error-colored)
 * headlineSmall, the failure [message], and a Close + Retry button row, mirroring native
 * `WhatsNewRoute.kt`'s `ErrorState` (the prior single-action shared `KiraErrorState` offered Retry
 * only — no Close, which matters on Desktop/iOS where there is no hardware back). Rendered inline
 * because the shared error component cannot host a plain Close button.
 */
@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.np_p3_whats_new_error_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onClose) {
                        Text(stringResource(Res.string.np_close))
                    }
                    Button(onClick = onRetry) {
                        Text(stringResource(Res.string.retry))
                    }
                }
            }
        }
    }
}

/**
 * Empty surface — native `WhatsNewRoute.kt`'s `EmptyState` is a centered elevated Card with a
 * title, a body line, and a Close button. The rework keeps the design-system [KiraEmptyState] for
 * visual consistency with the other rework screens but now supplies native's body line ([message])
 * and a Close [action] button (was a title-only placeholder relying on system back, which is absent
 * on Desktop/iOS).
 */
@Composable
private fun EmptyState(onClose: () -> Unit, modifier: Modifier = Modifier) {
    KiraEmptyState(
        title = stringResource(Res.string.np_p3_whats_new_empty_title),
        message = stringResource(Res.string.np_p3_whats_new_empty_body),
        modifier = modifier,
        action = {
            Button(onClick = onClose) {
                Text(stringResource(Res.string.np_close))
            }
        },
    )
}

/**
 * Loaded-state surface: a [HorizontalPager] of feature cards, a page-indicator dot row, and a
 * Previous / Next / Get-Started navigation row, mirroring native `WhatsNewScreen.kt`'s Column
 * (pager → PageIndicators → NavigationButtons).
 *
 * The pager is the primary swipe surface; the dot row beneath gives a visual cue to total pages +
 * current position. Bi-directional sync between `pagerState.currentPage` and [currentPage]: the
 * pager seeds from [currentPage] on first composition; user swipes update `pagerState.currentPage`,
 * which a `LaunchedEffect` mirrors back into the VM via [onPageChanged]. The VM's `OnPageChanged`
 * arm writes the new index into `state.currentPage` — so on the next recomposition, the seed value
 * matches what the pager is already showing (no oscillation; the comparison is value-equal so
 * `snapTo` wouldn't fire even if it ran every frame).
 */
@Composable
private fun FeaturePager(
    features: List<WhatsNewFeature>,
    currentPage: Int,
    isTablet: Boolean,
    isLandscape: Boolean,
    horizontalPadding: Dp,
    cardPadding: Dp,
    onPageChanged: (Int) -> Unit,
    onOpenVideo: (String) -> Unit,
    onGetStarted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = currentPage.coerceIn(0, (features.size - 1).coerceAtLeast(0)),
        pageCount = { features.size },
    )
    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }
    // GAP-WN-02 — tapping a feature image lifts it into a fullscreen viewer. The selected URL is
    // held locally; null = viewer closed. Survives recomposition (rememberSaveable on the String).
    var fullscreenImageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        // GAP-WN-08 — no opaque background here so the Scaffold's primaryContainer→surface
        // gradient shows through the content area.
        modifier = Modifier.fillMaxSize(),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(0.dp),
            // No `key` — matches native (index-based default), which is crash-proof. A content key
            // (e.g. feature title) would risk a duplicate-key crash since feature titles aren't a
            // guaranteed-unique invariant, and there's no per-page saveable state that could bleed.
        ) { page ->
            // Per-card horizontal slide + fade mirroring native `FeatureCard.kt:50-60`'s
            // AnimatedVisibility (was a vertical slide + fade-in keyed to page composition). The
            // card is visible only when its page is the active page; it slides in from the trailing
            // edge (+width) and slides out toward the leading edge (-width), both 250ms, exactly as
            // native does with `slideInHorizontally { it }` + `slideOutHorizontally { -it }`.
            val isActive = page == pagerState.currentPage
            AnimatedVisibility(
                visible = isActive,
                enter = slideInHorizontally(animationSpec = tween(250)) { it } +
                    fadeIn(animationSpec = tween(250)),
                exit = slideOutHorizontally(animationSpec = tween(250)) { -it } +
                    fadeOut(animationSpec = tween(250)),
            ) {
                FeatureCard(
                    feature = features[page],
                    isTablet = isTablet,
                    isLandscape = isLandscape,
                    cardPadding = cardPadding,
                    onOpenVideo = onOpenVideo,
                    onImageClick = { url -> fullscreenImageUrl = url },
                )
            }
        }
        PageIndicatorRow(
            pageCount = features.size,
            selectedIndex = pagerState.currentPage,
            // GAP-WN-09 — native `PageIndicators` uses a 12.dp vertical padding row.
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        )
        // GAP-WN-03 — Previous / Next / Get-Started navigation buttons mirroring native
        // `WhatsNewComponents.kt:98-141`. Previous is a Spacer on the first page; the last page
        // swaps Next for Get-Started. Padding (horizontal = horizontalPadding, vertical = 12.dp)
        // mirrors native `NavigationButtons`.
        NavigationButtons(
            currentPage = pagerState.currentPage,
            pageCount = features.size,
            onPrevious = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
            onNext = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
            onGetStarted = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 12.dp),
        )
    }

    val viewerUrl = fullscreenImageUrl
    if (viewerUrl != null) {
        FullscreenMediaViewer(url = viewerUrl, onDismiss = { fullscreenImageUrl = null })
    }
}

/**
 * Previous / Next / Get-Started row (GAP-WN-03), mirroring native `WhatsNewComponents.kt`'s
 * `NavigationButtons`. On the first page the Previous slot collapses to an 80.dp [Spacer] (so Next
 * stays right-aligned and reserves the Previous button's footprint, matching native — was a 1.dp
 * spacer); on the last page the Next button is replaced by a primary Get-Started button that marks
 * the screen seen + dismisses. Each button uses native's explicit `contentPadding(horizontal =
 * 20.dp, vertical = 10.dp)` (was Material default content padding).
 */
@Composable
private fun NavigationButtons(
    currentPage: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isFirst = currentPage <= 0
    val isLast = currentPage >= pageCount - 1
    val buttonPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isFirst) {
            Spacer(Modifier.width(80.dp))
        } else {
            OutlinedButton(onClick = onPrevious, contentPadding = buttonPadding) {
                Text(stringResource(Res.string.np_previous))
            }
        }
        if (isLast) {
            Button(onClick = onGetStarted, contentPadding = buttonPadding) {
                Text(stringResource(Res.string.np_get_started))
            }
        } else {
            Button(onClick = onNext, contentPadding = buttonPadding) {
                Text(stringResource(Res.string.np_next))
            }
        }
    }
}

/**
 * Fullscreen media viewer (GAP-WN-02) — port of native `FullscreenMediaViewer.kt`. A black
 * .95-alpha overlay [Dialog] showing the full image (Coil), a close [IconButton] top-end, and a
 * `tap_outside_to_close` caption. Tapping the scrim or the close button dismisses.
 *
 * The close affordance and the caption are styled as surface@0.9 chips to match native
 * `FullscreenMediaViewer.kt:108-139`: the close button is a 48.dp circular surface@0.9-backed
 * IconButton with an onSurface 24.dp glyph; the caption is wrapped in a rounded (12.dp) surface@0.9
 * chip with onSurface text (was a bare white@0.7 label / unstyled icon).
 *
 * DEVIATION(platform): native's viewer also opens VIDEO fullscreen (`SafeVideoPlayer`). The KMP
 * viewer is image-only because inline/fullscreen video requires the not-yet-existing `:platform`
 * MediaPlayer SPI (deferred — the video poster opens externally instead). When that SPI lands, the
 * video poster's fullscreen routes here.
 */
@Composable
private fun FullscreenMediaViewer(url: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(Res.string.whats_new_feature_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        CircleShape,
                    ),
            ) {
                Icon(
                    imageVector = KiraIcons.Close,
                    contentDescription = stringResource(Res.string.np_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = stringResource(Res.string.np_tap_outside_to_close),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(20.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Row of page indicators showing total page count + selected index. GAP-WN-09 — the selected
 * indicator is an elongated 24×8 pill (`RoundedCornerShape(4.dp)`) in
 * [MaterialTheme.colorScheme.primary]; unselected indicators are 8×8 dots in `outline@0.3`. The
 * unselected color (`outline`, not `onSurfaceVariant`) and the 3.dp horizontal padding match native
 * `WhatsNewComponents.kt`'s `PageIndicators` exactly (was `onSurfaceVariant@0.3` / a 2.dp
 * spacing.xxs pad).
 */
@Composable
private fun PageIndicatorRow(pageCount: Int, selectedIndex: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isSelected = index == selectedIndex
            val color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(8.dp)
                    .width(if (isSelected) 24.dp else 8.dp)
                    .background(color = color, shape = RoundedCornerShape(4.dp)),
            )
        }
    }
}

/**
 * Card rendering one [WhatsNewFeature] (GAP-WN-01, Phase 7.x.whatsnew.media). Renders the
 * feature's media at the top ([FeatureMedia]), then a centered auto-sized title (+ optional "NEW"
 * chip), then a centered auto-sized description. Mirrors the native `FeatureCard.kt` layout
 * (media → title → body) within the rework design system.
 *
 * GAP-WN-04 — title/description typography matches native `FeatureCard.kt:86-105`'s
 * `AutoSubtitleText`: the title is centered, Bold, step-based auto-sized (26.sp phone / 32.sp
 * tablet initial, 20.sp min, 32.sp phone / 36.sp tablet max, 2-line cap); the description is
 * centered, step-based auto-sized (15.sp phone / 18.sp tablet initial, 12.sp min, 18.sp phone /
 * 20.sp tablet max, 15-line cap). The card padding tracks the native tablet/landscape-aware
 * `cardPadding`, and the card height is capped at native's `maxCardHeight` fraction
 * (`FeatureCard.kt:45,65`: 0.85 landscape / 0.75 portrait).
 */
@Composable
private fun FeatureCard(
    feature: WhatsNewFeature,
    isTablet: Boolean,
    isLandscape: Boolean,
    cardPadding: Dp,
    onOpenVideo: (String) -> Unit,
    onImageClick: (String) -> Unit,
) {
    // Native `FeatureCard.kt:45` caps the card height at 0.85 (landscape) / 0.75 (portrait) of the
    // available height so it never spans the whole pager page.
    val maxCardHeight = if (isLandscape) 0.85f else 0.75f
    // GAP-WN-08 — card corner r20 + elevation 4, matching native `WhatsNewScreen.kt`'s
    // FeatureCard surface (was r12 / no elevation).
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(maxCardHeight)
            // Native `FeatureCard.kt:66` insets the card with horizontal = cardPadding,
            // vertical = 8.dp.
            .padding(horizontal = cardPadding, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                // Native `FeatureCard.kt:71-72` fills the card and scrolls inside it.
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(cardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Native uses a 16.dp inter-element gap inside the card Column.
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FeatureMedia(
                feature = feature,
                isTablet = isTablet,
                isLandscape = isLandscape,
                onOpenVideo = onOpenVideo,
                onImageClick = onImageClick,
            )
            AutoSubtitleText(
                text = feature.title,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                fontSize = if (isTablet) 32.sp else 26.sp,
                maxSize = if (isTablet) 36.sp else 32.sp,
                minSize = 20.sp,
                maxLines = 2,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
            // The NEW chip has no native equivalent in the FeatureCard (preserved rework accent);
            // centered below the title to keep the native centered-title layout intact.
            if (feature.isNew) {
                NewChip()
            }
            AutoSubtitleText(
                text = feature.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = if (isTablet) 18.sp else 15.sp,
                maxSize = if (isTablet) 20.sp else 18.sp,
                minSize = 12.sp,
                maxLines = 15,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Auto-sizing text helper (GAP-WN-04) — port of native
 * `presentation/common/.../AutoSizedText.kt`'s `AutoSubtitleText`. Wraps [BasicText] with a
 * [TextAutoSize.StepBased] (0.1.sp step) so the text shrinks/grows between [minSize] and [maxSize]
 * to fit within [maxLines]. The [fontSize] seeds the style; the autosize range governs the final
 * rendered size. Kept private to the WhatsNew screen (no `:ui/components` edit) since it mirrors a
 * native component that lives in the WhatsNew feature's common-componants package.
 */
@Composable
private fun AutoSubtitleText(
    text: String,
    color: Color,
    textAlign: TextAlign,
    fontSize: TextUnit,
    maxSize: TextUnit,
    minSize: TextUnit,
    maxLines: Int,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    Box(modifier = modifier) {
        BasicText(
            text = text,
            style = TextStyle(
                color = color,
                fontSize = fontSize,
                textAlign = textAlign,
                fontWeight = fontWeight,
            ),
            maxLines = maxLines,
            autoSize = TextAutoSize.StepBased(
                minFontSize = minSize,
                maxFontSize = maxSize,
                stepSize = 0.1.sp,
            ),
        )
    }
}

/**
 * Square media side length for a feature card's image / video poster, mirroring native
 * `FeatureCard.kt:38-43`: 300.dp (tablet + landscape) / 280.dp (tablet) / 180.dp (landscape) /
 * 220.dp (phone portrait).
 */
private fun mediaSizeFor(isTablet: Boolean, isLandscape: Boolean): Dp = when {
    isTablet && isLandscape -> 300.dp
    isTablet -> 280.dp
    isLandscape -> 180.dp
    else -> 220.dp
}

/**
 * Branches on [WhatsNewFeature.mediaType] to render the feature's media, mirroring the native
 * `FeatureCard.kt`'s `FeatureMedia` (GAP-WN-01):
 *  - **IMAGE**: a URL carousel ([imageUrlList]), a single URL image ([imageUrl]), or — when only
 *    bundled drawable names ([imageResName] / [imageResNameList]) are carried (or nothing at all) —
 *    an [ImagePlaceholder] of the title initials. (Bundled `imageResName(List)` drawables are not
 *    packaged in `:ui` composeResources; remote data uses URLs, so the resource-name branches fall
 *    back to the placeholder rather than rendering nothing — GAP-WN-03. Native used Android
 *    `@DrawableRes` ids for those and likewise fell back to `ImagePlaceholder` when no media
 *    matched, per `FeatureCard.kt:161-163`.)
 *  - **LIST**: the URL carousel ([imageUrlList]).
 *  - **VIDEO**: a tappable poster ([videoUrl] thumbnail isn't carried, so a play-affordance card)
 *    that emits [onOpenVideo] — DEVIATION(platform) substitute for the native inline `VideoView`.
 *  - **URL**: a single URL image (the native "image with external open" affordance).
 */
@Composable
private fun FeatureMedia(
    feature: WhatsNewFeature,
    isTablet: Boolean,
    isLandscape: Boolean,
    onOpenVideo: (String) -> Unit,
    onImageClick: (String) -> Unit,
) {
    val mediaSize = mediaSizeFor(isTablet = isTablet, isLandscape = isLandscape)
    when (feature.mediaType) {
        MediaType.VIDEO -> {
            // The data layer only emits VIDEO when a videoUrl is present; a null url is an
            // unreachable edge that renders nothing (the card collapses to title + description),
            // matching the pre-existing VideoPoster behavior. GAP-WN-03's placeholder fall-back is
            // scoped to the IMAGE / LIST drawable-name cases native explicitly placeholder-fills.
            val url = feature.videoUrl
            if (url != null) {
                VideoPoster(mediaSize = mediaSize, onClick = { onOpenVideo(url) })
            }
        }
        MediaType.LIST -> {
            if (feature.imageUrlList.isNotEmpty()) {
                ImageUrlCarousel(
                    imageUrlList = feature.imageUrlList,
                    mediaSize = mediaSize,
                    onImageClick = onImageClick,
                )
            } else {
                // Only bundled drawable names (imageResNameList) — not resolvable in :ui. Emit the
                // placeholder so the card is not blank (GAP-WN-03).
                ImagePlaceholder(title = feature.title, mediaSize = mediaSize)
            }
        }
        MediaType.IMAGE, MediaType.URL -> {
            // Capture into a local so the null-check smart-casts (imageUrl is a cross-module
            // public API property, which cannot be smart-cast directly).
            val singleUrl = feature.imageUrl
            when {
                feature.imageUrlList.isNotEmpty() ->
                    ImageUrlCarousel(
                        imageUrlList = feature.imageUrlList,
                        mediaSize = mediaSize,
                        onImageClick = onImageClick,
                    )
                singleUrl != null ->
                    FeatureImage(
                        url = singleUrl,
                        mediaSize = mediaSize,
                        contentDescription = feature.title,
                        onClick = { onImageClick(singleUrl) },
                    )
                // Only bundled drawable names (imageResName / imageResNameList) or no media at all
                // — emit the title-initials placeholder (GAP-WN-03), matching native's fall-back.
                else -> ImagePlaceholder(title = feature.title, mediaSize = mediaSize)
            }
        }
    }
}

/**
 * Single rounded Coil image at [mediaSize], with a calm error glyph on load failure. Tapping
 * opens the fullscreen viewer (GAP-WN-02) via [onClick].
 */
@Composable
private fun FeatureImage(
    url: String,
    mediaSize: Dp,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    var isError by remember(url) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(mediaSize)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onState = { state -> isError = state is AsyncImagePainter.State.Error },
        )
        if (isError) {
            Text(
                text = stringResource(Res.string.whats_new_image_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Horizontal image carousel mirroring the native `ImageUrlsCarousel`: a large selected image on
 * top + a thumbnail [LazyRow] beneath (shown only when >1). Tapping a thumbnail selects it.
 */
@Composable
private fun ImageUrlCarousel(
    imageUrlList: List<String>,
    mediaSize: Dp,
    onImageClick: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    var selectedIndex by rememberSaveable(imageUrlList) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val safeIndex = selectedIndex.coerceIn(0, imageUrlList.lastIndex)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        FeatureImage(
            url = imageUrlList[safeIndex],
            mediaSize = mediaSize,
            contentDescription = stringResource(Res.string.whats_new_feature_image),
            onClick = { onImageClick(imageUrlList[safeIndex]) },
        )
        if (imageUrlList.size > 1) {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                contentPadding = PaddingValues(horizontal = spacing.sm),
            ) {
                itemsIndexed(
                    items = imageUrlList,
                    key = { index, url -> "$index:$url" },
                ) { index, url ->
                    val isSelected = index == safeIndex
                    val thumbModifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (isSelected) {
                                Modifier.border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(8.dp),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clickable {
                            selectedIndex = index
                            scope.launch { listState.animateScrollToItem(index) }
                        }
                    AsyncImage(
                        model = url,
                        contentDescription = stringResource(Res.string.whats_new_thumbnail, index + 1),
                        contentScale = ContentScale.Crop,
                        modifier = thumbModifier,
                    )
                }
            }
        }
    }
}

/**
 * Video poster — a tappable card with a centered play affordance, an explicit "opens externally"
 * caption, and an open-in-new affordance in the bottom-end corner.
 *
 * DEVIATION(platform), audit `p1/whatsnew` finding 1 (inline video playback). The native
 * `FeatureCard.kt` renders an inline Android `VideoView` (`VideoComponents.kt`'s `SafeVideoPlayer`):
 * looping muted preview, play/pause on tap, lifecycle pause/resume, a 10s load timeout, an
 * error+retry placeholder, and an in-card Fullscreen button. Compose Multiplatform ships no
 * built-in video player and the deferred `:platform` MediaPlayer SPI does not yet exist, so a
 * faithful cross-platform inline player would require BOTH a new `expect`/`actual` media SPI AND a
 * new per-platform media dependency (Android ExoPlayer / iOS AVPlayer / Desktop VLCJ) — a
 * cross-cutting change outside this screen's scope and flagged for approval rather than added here.
 *
 * The closest faithful in-`:ui` behavior (per the finding's interim guidance) keeps the poster but
 * labels it clearly: tapping the play affordance emits [WhatsNewIntent.OnOpenVideo] →
 * [WhatsNewEffect.OpenVideo] → the route adapter's `IntentLauncher.openUrl`, which hands the URL to
 * the system video player / browser. The poster mirrors native's `VideoPlaceholder` styling
 * (surfaceVariant card, r16, the same 64.dp circular play button with the native
 * `surface@0.85`-alpha fill + `primary` tint + 14.dp icon padding) and adds a `Video Preview`
 * heading + an `Opens in your video player` caption so the external hand-off is unmistakable; the
 * corner glyph echoes native's bottom-end button position. Substitutes — does not replicate — the
 * native inline `VideoView`.
 */
@Composable
private fun VideoPoster(mediaSize: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(mediaSize)
            .height(mediaSize * 0.75f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 64.dp circular play button — geometry + colors copied from native
            // VideoComponents.kt's SafeVideoPlayer play affordance (Surface 64.dp / CircleShape /
            // surface@0.85 alpha / primary tint / 14.dp icon padding).
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    imageVector = KiraIcons.Play,
                    contentDescription = stringResource(Res.string.play_video),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(14.dp),
                )
            }
            // "Video Preview" heading mirrors native VideoPlaceholder's label.
            Text(
                text = stringResource(Res.string.video_preview),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // DEVIATION caption — makes the external hand-off explicit (no native equivalent;
            // native plays inline).
            Text(
                text = stringResource(Res.string.whats_new_video_opens_externally),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        // Open-in-new glyph in the bottom-end corner — echoes the position of native's in-card
        // Fullscreen button (VideoComponents.kt:248-267, BottomEnd, 8.dp padding). Reinforces that
        // the action leaves the app to an external player.
        Icon(
            imageVector = KiraIcons.OpenInWebView,
            contentDescription = stringResource(Res.string.whats_new_video_opens_externally),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .size(22.dp),
        )
    }
}

/**
 * Title-initials media placeholder (GAP-WN-03) — port of native `ImageComponents.kt`'s
 * `ImagePlaceholder`. A rounded [mediaSize] square in [MaterialTheme.colorScheme.primaryContainer]
 * with the first two title characters (uppercased) centered in a Bold `displayMedium`. Emitted by
 * [FeatureMedia] when a feature carries only bundled drawable names (not resolvable in `:ui`
 * composeResources) or no media at all, so the card is never blank — matching native's fall-back
 * at `FeatureCard.kt:161-163`.
 */
@Composable
private fun ImagePlaceholder(title: String, mediaSize: Dp) {
    Box(
        modifier = Modifier
            .size(mediaSize)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.take(2).uppercase(),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Small accent label rendered next to a feature's title when [WhatsNewFeature.isNew] is true.
 * Uses [MaterialTheme.colorScheme.primary] as the background — same accent posture as the
 * legacy `WhatsNewBadge` composable.
 */
@Composable
private fun NewChip() {
    val spacing = LocalSpacing.current
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = stringResource(Res.string.new_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
        )
    }
}
