package me.manga.kira.ui.sources

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.sources.Source
import me.manga.kira.presentation.sources.SourcesEffect
import me.manga.kira.presentation.sources.SourcesIntent
import me.manga.kira.presentation.sources.SourcesState
import me.manga.kira.presentation.sources.SourcesViewModel
import me.manga.kira.ui.components.KiraLoadingState
import me.manga.kira.ui.components.KiraSocialMediaRow
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.source_disabled
import me.manga.kira.ui.generated.resources.source_enabled
import me.manga.kira.ui.generated.resources.add_manga_site
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.cancel
import me.manga.kira.ui.generated.resources.connect_with_us_in_social_media
import me.manga.kira.ui.generated.resources.enable_disable_all_sources
import me.manga.kira.ui.generated.resources.enter_the_site_url
import me.manga.kira.ui.generated.resources.enter_the_url_for_site_you_want_us_to_add
import me.manga.kira.ui.generated.resources.finish
import me.manga.kira.ui.generated.resources.languages_coming_soon_description
import me.manga.kira.ui.generated.resources.languages_coming_soon_title
import me.manga.kira.ui.generated.resources.minimum_5_characters_required
import me.manga.kira.ui.generated.resources.request_adding_source_full
import me.manga.kira.ui.generated.resources.request_failed
import me.manga.kira.ui.generated.resources.request_submitted_successfully
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.generated.resources.select_your_manga_sources
import me.manga.kira.ui.generated.resources.sources_title
import me.manga.kira.ui.generated.resources.submit
import me.manga.kira.ui.generated.resources.submitting
import me.manga.kira.ui.generated.resources.title_sources_settings
import me.manga.kira.ui.generated.resources.we_d_love_to_hear_from_you
import me.manga.kira.ui.generated.resources.we_will_add_it_as_soon_it_possible
import me.manga.kira.ui.generated.resources.you_ll_receive_a_prompt_response
import me.manga.kira.ui.theme.LocalSpacing
import me.manga.kira.ui.util.displayLanguageName
import me.manga.kira.ui.common.LocalSourceIconResolver
import me.manga.kira.ui.common.RemoteSourceIcon
import me.manga.kira.ui.common.SourceIconResolution
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Sources screen — Compose entry point for the Sources MVI slice.
 *
 * Phase 7.x.sources rework. Renders [SourcesState] (a flat list bucketed by language) and
 * dispatches [SourcesIntent].
 *
 * **Phase 7.x.sources.complaint extension**: a "Request adding source" header row sits above
 * the language list; tapping it dispatches [SourcesIntent.OnOpenComplaintDialog] which surfaces
 * the [RequestSourceDialog] (single body field — the complaint type is pinned at
 * `ComplaintType.SITES_ADD` server-side). Submit/failure outcomes arrive as
 * [SourcesEffect] (RequestSubmitted / RequestFailed) effects routed through a screen-owned
 * [SnackbarHost]. Same
 * pattern as the rework Settings hub's feedback dialog (Phase 7.x.settings.feedback /
 * §95.5 in [ARCHITECTURE.md]).
 *
 * **Phase 7.x.sources.onboardingseed extension**: a `LaunchedEffect(onboardingLanguageTag)`
 * fires [SourcesIntent.OnSeedDefaultLanguage] whenever the caller-provided locale tag changes,
 * reproducing the legacy `SourcesScreen.kt:124-127`'s
 * `LaunchedEffect(userLanguageCode) { repoSettingsViewModel.setLanguageEnabledDefault
 * ("(${tag.uppercase()})", true) }` posture verbatim. The param defaults to `null`; non-
 * onboarding callers (the settings-entry [SourcesReworkScreenRoute]) pass null → effect is a
 * no-op. The onboarding-entry [SourcesScreenRoute] (legacy `Screen.Sources`, will route to the
 * rework screen in a future `Phase 7.x.sources.swap`) passes the user-selected locale code
 * collected from `DataStoreHelper.languageFlow`. The `LaunchedEffect` keys on the tag itself
 * (not the VM) so the seed re-fires only when the user actually changes their locale during
 * onboarding — the standard Compose key-based dedup. Idempotency note: the use case's
 * underlying Room `UPDATE sources SET isEnabled = 1` is a no-op on already-enabled rows; the
 * Room flow does NOT re-emit when the row value doesn't change, so re-firing the seed on the
 * same locale is harmless.
 *
 * **Visual parity vs the legacy `composeApp/.../onboarding/sources/SourcesScreen.kt` +
 * `repo_settings/.../RepoSettingsScreen.kt`**:
 *  - Layout shape — top bar with title ("Sources") and the LazyColumn rendering a single
 *    "Request adding source" row above one section per language. Each language section
 *    starts with a header row showing the language name + a Material 3 `Switch` driven by
 *    `any { it.isEnabled }` over the language's sources; below the header come per-source
 *    rows with the API label + a per-source `Switch`.
 *  - **Onboarding-flavour UI restored**: no `AnimatedBackground` (cosmetic decoration
 *    deferred — purely cosmetic, no semantic value); "Upcoming Languages" info card
 *    restored in Phase 7.x.sources.infocard (via [UpcomingLanguagesCard]); "Finish"
 *    button restored in Phase 7.x.sources.onboardingfinish (via [FinishButton] — rendered
 *    only when [onFinish] is non-null, mirroring the legacy `if (isFirstOpen)` gate).
 *    With these two affordances landed, the rework Sources screen now reaches feature-parity
 *    with the legacy `RepoSettingsScreen.kt`, unblocking `Phase 7.x.reposettings.swap`.
 *  - **Bottom-bar visible**: same posture as the History / Updates / Statistics reworks.
 *  - Labels resolve through compose-resources `stringResource(Res.string.*)` against the
 *    `:ui` catalog (Phase 11.ui.UP-3f). Keys mirror the legacy catalog so the shipped
 *    Arabic translations are reused verbatim; the count caption uses the parameterized
 *    `sources_enabled_count` ("%1$d of %2$d enabled").
 *  - Design tokens use [LocalSpacing] + Material 3 directly; legacy used ad-hoc `.dp`
 *    literals + the onboarding's gradient overlay.
 *
 * **Language grouping**: read directly off [SourcesState.groupedByLanguage] — the regroup
 * lives in `:presentation`, not in the composable, to keep the screen pure-render. Same
 * flat-domain + state-side-regroup posture established for History / Updates date
 * grouping (§82.3, §83.3).
 *
 * **Per-language `Switch` semantics**:
 *  - `checked = sources.any { it.isEnabled }` — the legacy `LanguageToggle` uses the same
 *    "ON if any enabled" predicate. Mixed-state language groups show ON; flipping the
 *    Switch OFF in that case bulk-disables every source in the group.
 *  - `onCheckedChange = { dispatch(OnToggleLanguage(language, it)) }` — emits the bulk
 *    toggle intent; the per-source fan-out lives in the `:data` impl.
 *
 * **Per-source row**: API label (rendered as a heading) + the per-source `Switch`. No
 * cover, no chapter info, no priority badge (the legacy doesn't show priority either —
 * it's purely a sort key, not a user-visible field).
 *
 * **Loading state**: the shared `KiraLoadingState` (centred spinner) while `isLoading == true`.
 * **No empty state** (parity-fix): native `RepoSettingsScreen.kt` has no empty branch — it always
 * renders the request-source row + "Upcoming Languages" info card above the (possibly empty)
 * language sections, so the request affordance is never lost. The screen therefore always renders
 * the list once loaded; an empty source snapshot simply contributes no language sections. (The
 * earlier full-screen `KiraEmptyState` short-circuit dropped the request affordance and was
 * removed.)
 *
 * **`viewModel` reference in `LaunchedEffect`**: keying on `effects` (the flow itself, not
 * the VM) is the safe pattern — same posture as the rework Settings hub.
 *
 * Constructor takes the [SourcesViewModel] directly (not the route's `NavController`) — the
 * route adapter in `:composeApp` is responsible for VM resolution, keeping the screen
 * nav-host-agnostic. Same posture as
 * [me.manga.kira.ui.statistics.StatisticsScreen] (also a terminal screen with no nav
 * callbacks).
 *
 * **SRP (contract §6)**: owns rendering + intent dispatch + snackbar host + nothing else.
 * State derivation (`groupedByLanguage`, `isEmpty`, `enabledCount`) lives on [SourcesState];
 * toggle propagation and complaint submission live on [SourcesViewModel].
 *
 * **Stateless inner [SourcesScreenContent]** for preview / test substitution — same
 * convention the other rework screens follow.
 *
 * **Audit-trail postscript** (Phase 9.x.sources.staleKdocSweep.cascade,
 * Task #455, 2026-05-28): five stale line-anchored citations into the
 * §353-retired legacy Sources + RepoSettings paths appear in per-section
 * KDocs across this file:
 *  - Line 69 (file-level KDoc) cites the legacy
 *    `SourcesScreen.kt:124-127` `LaunchedEffect(userLanguageCode)` posture
 *    (the legacy onboarding entry).
 *  - Lines 82-83 (file-level KDoc) cite both legacy paths together —
 *    `composeApp/.../onboarding/sources/SourcesScreen.kt` and
 *    `repo_settings/.../RepoSettingsScreen.kt` — as the visual-parity
 *    targets for the rework screen.
 *  - Line 95 (file-level KDoc) cites the legacy `RepoSettingsScreen.kt`
 *    as the feature-parity unblock target for Phase 7.x.reposettings.swap.
 *  - The [FinishButton] KDoc cites legacy `RepoSettingsScreen.kt:125-153`
 *    for the full-width pill-button parity (26.dp / 50.dp / 24.dp+12.dp
 *    padding shape).
 *  - The [RequestSourceDialog] KDoc cites legacy
 *    `RepoSettingsScreen.kt:215-248` for the submit-gating parity.
 * Both legacy paths — `composeApp/.../features/repo_settings/ui/screens/
 * RepoSettingsScreen.kt` and `composeApp/.../features/onboarding/sources/
 * SourcesScreen.kt` — were retired together in Phase 9.x.reposettings.
 * legacyui.retire (§353, commit `37f21da`); verified by a filesystem
 * check returning zero hits for both paths. The behavioural rationales
 * stand on their own merits — the onboarding-seed `LaunchedEffect`, the
 * any-enabled bulk Switch semantics, the FinishButton pill shape, and
 * the RequestSourceDialog submit-gating posture are all documented
 * inline above and are independent of which legacy file originally
 * carried the parity precedent. Phase 7.x.reposettings.swap (§285) and
 * Phase 7.x.sources.swap (§305) both landed pre-retire, so the
 * route-swap predictions in the file-level KDoc have all closed the
 * loop. Original §253-era prose preserved verbatim per the audit-
 * trail-preservation convention — the citations are historical record
 * of the design lineage; the rework Sources screen continues to render
 * correctly through the legacy retire.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster40.staleKdocSweep.cascade,
 * Task #496, 2026-05-28): one stale citation beyond those enumerated in
 * the cluster `sources` (§455) postscript above appears in this file —
 * the §455 postscript covered only §353-retired RepoSettings/Sources
 * path cites, missing the §307-retired AnimatedBackground class cite:
 *  - Line 89 (Onboarding-flavour UI bullet): "no `AnimatedBackground`
 *    (cosmetic decoration deferred — purely cosmetic, no semantic
 *    value)".
 *  Classified as STALE-SYMBOL-REFERENCE — Phase 9.x.onboarding.legacy_retire
 *  (§307, commit `6c83364` "delete 5 unreachable legacy onboarding files")
 *  DELETED the legacy `:composeApp/.../presentation/features/onboarding/
 *  components/AnimatedBackground.kt` along with 4 sibling legacy
 *  onboarding files as a cascade-orphan-retire chain (re-verified by
 *  §457 + §458 + §465 + cluster36 + cluster37 + cluster38 + cluster39
 *  prior sweeps — a recursive Glob for `AnimatedBackground.kt` returns
 *  NO MATCHES). The backtick-prose cite-target is gone; the bare
 *  `AnimatedBackground` symbol survives only as documentation prose
 *  in sibling theme / welcome / sources / library KDocs + project
 *  documentation Markdown — the Kotlin source class itself is retired.
 *  HOWEVER — the architectural rationale of the citation STANDS on
 *  its own merits past the §307 fulfilled landing as a LIVE design-
 *  lineage record: the L89 "Onboarding-flavour UI restored" bullet
 *  documents the intentional omission of the AnimatedBackground
 *  decoration in the rework Sources screen as a "cosmetic, no
 *  semantic value" design choice — this matches the §122 sources.
 *  onboardingfinish + §138 theme.swap + §306 welcome precedents
 *  (all of which intentionally skipped the equivalent legacy
 *  decorative background). The §307 retire of AnimatedBackground.kt
 *  retroactively validates the "deferred" framing — there is no
 *  longer a legacy file to defer porting from, so the cosmetic-
 *  decoration omission is no longer a deferred-port but a fulfilled
 *  design choice (the rework Sources screen never needs an
 *  AnimatedBackground decoration). Note: the §455 postscript above
 *  did not enumerate L89 because that postscript was scoped to the
 *  §353 RepoSettings/Sources retire campaign — the L89 cite belongs
 *  to a different cascade-orphan chain (§307 onboarding retire) and
 *  was missed by the §455 sweep. Original Phase 7.x.sources-era prose
 *  preserved verbatim per the audit-trail-preservation convention —
 *  the citation is historical record of the design lineage including
 *  the §307-retired AnimatedBackground precedent that originally
 *  established the Lottie-to-Compose-primitive omit-decorative-chrome
 *  pattern.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    modifier: Modifier = Modifier,
    onFinish: (() -> Unit)? = null,
    onboardingLanguageTag: String? = null,
    onBack: (() -> Unit)? = null,
    // GAP-SRC-SOCIAL — social-media row URL-open callback for the Request-Source dialog footer
    // (native FeedbackDialog.kt:200 renders SocialMediaRow there). No-op default mirrors the
    // Language request dialog's onOpenUrl; the host route adapter may forward it to the platform
    // IntentLauncher.openUrl.
    onOpenUrl: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    SourcesScreenContent(
        state = state,
        effects = viewModel.effects,
        onIntent = viewModel::submit,
        modifier = modifier,
        onFinish = onFinish,
        onboardingLanguageTag = onboardingLanguageTag,
        onBack = onBack,
        onOpenUrl = onOpenUrl,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourcesScreenContent(
    state: SourcesState,
    effects: Flow<SourcesEffect>,
    onIntent: (SourcesIntent) -> Unit,
    modifier: Modifier = Modifier,
    onFinish: (() -> Unit)? = null,
    onboardingLanguageTag: String? = null,
    onBack: (() -> Unit)? = null,
    // GAP-SRC-SOCIAL — forwarded to the Request-Source dialog's social-media footer.
    onOpenUrl: (String) -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val snackbarHostState = remember { SnackbarHostState() }
    // Launch snackbars off the effect collector so showing one never blocks a later navigation
    // effect for the snackbar's duration (the user couldn't leave a screen while a snackbar showed).
    val scope = rememberCoroutineScope()
    val isOnboarding = onboardingLanguageTag != null
    // NP Phase 2 (GAP-SRC-06): the top-bar title is parameterized by entry. The onboarding entry
    // (onboardingLanguageTag != null) surfaces its title as the centered "Select Your Manga
    // Sources" headline inside the list (see SourcesList), so its top bar carries the generic
    // "Sources". The in-settings (RepoSettings) entry — the SourcesReworkScreenRoute, which passes
    // a null tag and null onFinish — reads "Sources Settings" (legacy title_sources_settings),
    // matching legacy RepoSettingsScreen.kt:70-84. Standalone entries keep "Sources".
    val topBarTitle = if (!isOnboarding && onFinish == null) {
        stringResource(Res.string.title_sources_settings)
    } else {
        stringResource(Res.string.sources_title)
    }
    // Snackbar copy resolved in composable scope — stringResource can't be called inside the
    // effect-collector coroutine below. NP Phase 2 (GAP-SRC-02): replaces the former English
    // literals built VM-side with localized en+ar resources.
    val submittedMessage = stringResource(Res.string.request_submitted_successfully)
    val failedMessage = stringResource(Res.string.request_failed)
    val retryLabel = stringResource(Res.string.retry)
    // NP Phase 2 P2 (sources complaint subject): the complaint subject is the localized
    // ComplaintType.SITES_ADD display name ("Add Manga Site" / add_manga_site), resolved here in
    // composable scope and threaded into OnSubmitComplaint. Matches native RepoSettingsScreen,
    // which submits getDisplayName(context) as the subject (vs the former VM-side "SITES_ADD"
    // enum name). The Request-Source row pins the type to SITES_ADD, so the subject is fixed.
    val complaintSubject = stringResource(Res.string.add_manga_site)

    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is SourcesEffect.RequestSubmitted ->
                    scope.launch { snackbarHostState.showSnackbar(submittedMessage) }
                is SourcesEffect.RequestFailed -> scope.launch {
                    // NP Phase 2 (GAP-SRC-03): failure snackbar offers a "Retry" action (Long
                    // duration) that re-submits the preserved body, matching the legacy
                    // RepoSettingsScreen.kt:178-209 onError posture.
                    val result = snackbarHostState.showSnackbar(
                        message = failedMessage,
                        actionLabel = retryLabel,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        onIntent(
                            SourcesIntent.OnSubmitComplaint(
                                body = effect.body,
                                subject = complaintSubject,
                            ),
                        )
                    }
                }
            }
        }
    }

    // Phase 7.x.sources.onboardingseed — fire the default-language seed when the caller
    // provides a locale tag. Keyed on the tag itself so the seed re-fires only when the user
    // changes their locale during onboarding. Non-onboarding callers pass null → no-op.
    // Mirrors legacy SourcesScreen.kt:124-127's LaunchedEffect(userLanguageCode) posture.
    LaunchedEffect(onboardingLanguageTag) {
        if (onboardingLanguageTag != null) {
            onIntent(SourcesIntent.OnSeedDefaultLanguage(onboardingLanguageTag))
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(topBarTitle) },
                navigationIcon = {
                    // Native parity: RepoSettingsScreen.kt:74-82 renders an AutoMirrored
                    // ArrowBack IconButton (tinted onBackground, contentDescription = desc_back)
                    // wired to onBackPress. Rendered only when a back callback is supplied — the
                    // in-settings (RepoSettings) entry passes one; the onboarding entry does not
                    // (its back is the wizard chain). AutoMirrored so it flips in RTL layouts.
                    onBack?.let { back ->
                        IconButton(onClick = back) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.back),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            // Phase 7.x.sources.onboardingfinish — onboarding-only Finish button.
            // Renders iff [onFinish] is non-null (the route adapter passes a real callback only
            // on the onboarding entry; the settings entry passes null → no bottom bar).
            // Mirrors legacy RepoSettingsScreen.kt:125-153's `if (isFirstOpen)` gate.
            onFinish?.let { FinishButton(onClick = it) }
        },
    ) { innerPadding ->
        // NP Phase 2 (GAP-SRC-04): the onboarding entry (onboardingLanguageTag != null) stacks an
        // AnimatedBackground + a vertical gradient overlay behind the content, mirroring the legacy
        // onboarding SourcesScreen.kt:88-182. The non-onboarding entries (RepoSettings / standalone)
        // render flat. The retired legacy `AnimatedBackground.kt` (a Lottie composition) is replaced
        // by a KMP-friendly infinite-transition gradient sweep — no Lottie / asset dependency.
        Box(modifier = Modifier.fillMaxSize()) {
            if (isOnboarding) {
                AnimatedSourcesBackground(modifier = Modifier.fillMaxSize())
            }
            when {
                state.isLoading -> LoadingBox(innerPadding)
                // Parity-fix: native RepoSettingsScreen.kt has NO empty branch — it always
                // renders the LazyColumn with the request-source row + "Upcoming Languages" info
                // card above the (possibly empty) language sections, so the request affordance is
                // never lost. The KMP screen previously short-circuited to a full-screen
                // KiraEmptyState when the source list was empty, dropping that affordance. We now
                // always render SourcesList — its request-source row and info card are rendered
                // unconditionally (the language `groups` loop simply contributes no sections when
                // empty), matching native.
                else -> SourcesList(
                    groups = state.groupedByLanguage,
                    onIntent = onIntent,
                    contentPadding = innerPadding,
                    spacingMd = spacing.md,
                    spacingLg = spacing.lg,
                    showOnboardingHeadline = isOnboarding,
                )
            }
        }
    }

    if (state.complaintDialogOpen) {
        RequestSourceDialog(
            isSubmitting = state.isSubmittingComplaint,
            onSubmit = { body ->
                onIntent(
                    SourcesIntent.OnSubmitComplaint(body = body, subject = complaintSubject),
                )
            },
            onDismiss = { onIntent(SourcesIntent.OnDismissComplaintDialog) },
            onOpenUrl = onOpenUrl,
        )
    }
}

@Composable
private fun LoadingBox(contentPadding: PaddingValues) {
    KiraLoadingState(modifier = Modifier.padding(contentPadding))
}

/**
 * NP Phase 2 (GAP-SRC-04): animated decorative background for the onboarding Sources entry.
 *
 * The legacy onboarding `SourcesScreen.kt:88-182` stacked a Lottie `AnimatedBackground()` plus a
 * vertical gradient overlay (`background@0.1f → @0.3f → solid`) behind the source cards. The legacy
 * `AnimatedBackground.kt` Lottie composition was retired in §307, so this is a KMP-friendly
 * replacement: an infinite-transition vertical gradient sweep (primary/tertiary container tints
 * fading toward the surface) plus the same darkening overlay toward the bottom. Cosmetic only — no
 * interactivity, no asset dependency. Non-onboarding entries do not render it.
 */
@Composable
private fun AnimatedSourcesBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "sources-bg")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sources-bg-shift",
    )
    val sweepTop = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
    val sweepMid = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.12f)
    val surface = MaterialTheme.colorScheme.surface
    val overlayLight = MaterialTheme.colorScheme.background.copy(alpha = 0.1f)
    val overlayMid = MaterialTheme.colorScheme.background.copy(alpha = 0.3f)
    Box(
        modifier = modifier.drawBehind {
            // Animated diagonal sweep (the "AnimatedBackground" stand-in).
            val travel = size.height * shift
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(sweepTop, sweepMid, surface),
                    start = Offset(0f, travel - size.height),
                    end = Offset(size.width, travel + size.height),
                ),
            )
            // Static darkening overlay toward the bottom (background@0.1f → @0.3f → solid).
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(overlayLight, overlayMid, surface),
                ),
            )
        },
    )
}

@Composable
private fun SourcesList(
    groups: Map<String, List<Source>>,
    onIntent: (SourcesIntent) -> Unit,
    contentPadding: PaddingValues,
    spacingMd: androidx.compose.ui.unit.Dp,
    spacingLg: androidx.compose.ui.unit.Dp,
    showOnboardingHeadline: Boolean = false,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        // NP Phase 2 (GAP-SRC-04/05): centered "Select Your Manga Sources" headline at the top
        // of the onboarding entry (headlineMedium 24.sp, primary color). The non-onboarding
        // entries omit it. Mirrors the legacy onboarding SourcesScreen.kt:112-118.
        if (showOnboardingHeadline) {
            item(key = "onboarding-headline") {
                Text(
                    text = stringResource(Res.string.select_your_manga_sources),
                    style = MaterialTheme.typography.headlineMedium,
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacingLg, vertical = spacingLg),
                )
            }
        }
        // Phase 7.x.sources.complaint — "Request adding source" row above the language list.
        // Pinned to ComplaintType.SITES_ADD; tap surfaces the RequestSourceDialog.
        // NP Phase 2 (GAP-SRC-11): wrapped in an ItemsGroup-style rounded surface card with a
        // leading AddCircleOutline + trailing chevron, replacing the former plain row + divider.
        item(key = "request-source") {
            ItemsGroup(paddingHorizontal = spacingLg, paddingVertical = spacingMd) {
                RequestSourceRow(
                    onClick = { onIntent(SourcesIntent.OnOpenComplaintDialog) },
                    paddingHorizontal = spacingMd,
                    paddingVertical = spacingMd,
                )
            }
        }
        // Phase 7.x.sources.infocard — "Upcoming Languages" informational row.
        // Non-interactive parity with legacy RepoSettingsScreen.kt:174-186.
        // NP Phase 2 (GAP-SRC-11): wrapped in an ItemsGroup-style rounded surface card with a
        // leading red Info icon, replacing the former plain column + divider.
        item(key = "upcoming-languages") {
            ItemsGroup(paddingHorizontal = spacingLg, paddingVertical = spacingMd) {
                UpcomingLanguagesCard(
                    paddingHorizontal = spacingMd,
                    paddingVertical = spacingMd,
                )
            }
        }
        groups.forEach { (language, sources) ->
            // Redesign 2026-06 (mockup `.glabel`): an uppercase section label sits ABOVE each
            // language group card (mockup `<div class="glabel">Arabic</div>` preceding the
            // `.group`). Uses the same parens-strip + onboarding-localize transform as
            // LanguageHeader so the label text matches the card's master-row title.
            item(key = "glabel-$language") {
                GroupLabel(
                    text = languageDisplayName(language, showOnboardingHeadline),
                    paddingHorizontal = spacingLg + spacingMd,
                )
            }
            // NP Phase 2 P2 (sources grouped-card layout): each language section (master-row
            // header + its per-source rows) is wrapped in one ItemsGroup rounded-surface card
            // with inter-group spacing, matching native RepoSettingsScreen, which wraps each
            // (LanguageToggle + LanguageToggleWithAnimation) group inside an ItemsGroup. The
            // former flat rows + per-row HorizontalDivider rendering is removed — native has no
            // dividers between source rows; they are plain rows stacked in the card's column.
            //
            // Native parity (GAP-SRC-07): the per-source list only appears when the language
            // master toggle is on (`any { it.isEnabled }`); the reveal animates via
            // AnimatedVisibility(fadeIn + expandVertically / fadeOut + shrinkVertically),
            // mirroring the legacy LanguageToggleWithAnimation.
            val anyEnabled = sources.any { it.isEnabled }
            item(key = "group-$language") {
                ItemsGroup(paddingHorizontal = spacingLg, paddingVertical = spacingMd) {
                    Column {
                        LanguageHeader(
                            language = language,
                            sources = sources,
                            isOnboarding = showOnboardingHeadline,
                            onToggleLanguage = { enabled ->
                                onIntent(SourcesIntent.OnToggleLanguage(language, enabled))
                            },
                            paddingHorizontal = spacingMd,
                            paddingVertical = spacingMd,
                        )
                        AnimatedVisibility(
                            visible = anyEnabled,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Column {
                                sources.forEach { source ->
                                    SourceRow(
                                        source = source,
                                        language = language,
                                        onToggle = { enabled ->
                                            onIntent(SourcesIntent.OnToggleSource(source, enabled))
                                        },
                                        paddingHorizontal = spacingMd,
                                        paddingVertical = spacingMd,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * NP Phase 2 (GAP-SRC-11): ItemsGroup-style rounded surface card wrapper.
 *
 * Mirrors the legacy `ItemsGroup` design-system component (surfaceContainerHigh background,
 * RoundedCornerShape(16.dp), padded) that wrapped the legacy `SettingsNavigationItem` request +
 * info rows on `RepoSettingsScreen.kt:121-146`. The rework :ui has no shared `ItemsGroup`
 * component yet, so this private helper recreates the grouping look local to the Sources screen.
 */
@Composable
private fun ItemsGroup(
    paddingHorizontal: androidx.compose.ui.unit.Dp,
    paddingVertical: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = paddingHorizontal, vertical = paddingVertical),
        // Redesign 2026-06 (mockup `.group`): 18dp radius, `--card` (surfaceContainer) fill, and a
        // hairline `--ghost-line` (outlineVariant) border.
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        content()
    }
}

@Composable
private fun RequestSourceRow(
    onClick: () -> Unit,
    paddingHorizontal: androidx.compose.ui.unit.Dp,
    paddingVertical: androidx.compose.ui.unit.Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = paddingHorizontal, vertical = paddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(paddingHorizontal),
    ) {
        // Redesign 2026-06 (mockup `.si` with `+` glyph): the leading add-circle icon is wrapped
        // in a ~38dp accent-soft rounded medallion to match the source rows' brand medallions.
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.AddCircleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            // Typography parity-fix: native SettingsNavigationItem.kt:63-77 renders title 14.sp
            // onBackground + description 12.sp onBackground@0.8 — was titleMedium SemiBold
            // (~16.sp) / bodySmall onSurfaceVariant.
            // Copy parity-fix: native strings.xml:271 request_adding_source =
            // "Request Adding Source/Site"; the :ui base value reads "Request adding source" and is
            // out of edit scope, so request_adding_source_full (pfix) carries the native wording.
            // Redesign 2026-06 (mockup `.st .a` 15sp/700 + `.st .b` 12.5sp muted) — matches the
            // source-row text styles for cohesion within the grouped-card system.
            Text(
                text = stringResource(Res.string.request_adding_source_full),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(Res.string.enter_the_url_for_site_you_want_us_to_add),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // GAP-SRC-11: trailing chevron, matching the legacy SettingsNavigationItem's
        // KeyboardArrowRight endIcon. AutoMirrored so it flips in RTL (ar) layouts.
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Upcoming Languages" info row — non-interactive informational card that mirrors the legacy
 * `RepoSettingsScreen.kt:174-186`'s `Icons.Outlined.Info` ItemsGroup with title + multi-line
 * description.
 *
 * Phase 7.x.sources.infocard — restores one of the two deferred §84.8 affordances (the other
 * being the onboarding "Finish" button, deferred to Phase 7.x.sources.onboardingfinish). The
 * legacy renders this between the "Request adding source" row and the language sections, so
 * the rework places it in the same slot.
 *
 * **Icon-color choice**: legacy uses `MaterialTheme.colorScheme.error` for the Info icon — an
 * informational notice in an attention-grabbing tone. The rework preserves this verbatim
 * because the user-facing semantic ("we know languages are missing; here are the ones we're
 * working on") benefits from the visual emphasis.
 *
 * **Localized copy (Phase 11.ui.UP-3f)**: resolves through
 * `stringResource(Res.string.languages_coming_soon_title / languages_coming_soon_description)`
 * — the same legacy keys, reused verbatim (incl. the emoji glyphs) so the shipped Arabic
 * translation lands automatically.
 *
 * **No `clickable` modifier**: legacy `SettingsNavigationItem` has `endIcon = null` and no tap
 * handler — this is a notice card, not a navigation row.
 */
@Composable
private fun UpcomingLanguagesCard(
    paddingHorizontal: androidx.compose.ui.unit.Dp,
    paddingVertical: androidx.compose.ui.unit.Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = paddingHorizontal, vertical = paddingVertical),
        horizontalArrangement = Arrangement.spacedBy(paddingHorizontal),
    ) {
        // GAP-SRC-11: leading red Info icon, matching the legacy SettingsNavigationItem's
        // Icons.Outlined.Info tinted `error` (an informational notice in an attention-grabbing
        // tone — verbatim with the legacy RepoSettingsScreen.kt:174-186 color choice).
        // Redesign 2026-06 (mockup `.si`): wrapped in a ~38dp error-tinted rounded medallion so the
        // notice row carries the same leading-medallion shape as the source / request rows.
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            // Redesign 2026-06 (mockup `.st .a` 15sp/700 + `.st .b` 12.5sp muted) — matches the
            // source-row / request-row text styles for cohesion within the grouped-card system.
            Text(
                text = stringResource(Res.string.languages_coming_soon_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(Res.string.languages_coming_soon_description),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Onboarding-only Finish CTA — rendered iff [SourcesScreenContent.onFinish] is non-null.
 *
 * Visual parity with legacy `RepoSettingsScreen.kt:125-153`: full-width pill button (26.dp
 * rounded corners on a 50.dp-tall surface) inside a Box with 24.dp horizontal / 12.dp vertical
 * padding. Material 3 default colors apply (primary container + on-primary content), matching
 * the legacy's explicit `containerColor = MaterialTheme.colorScheme.primary`.
 *
 * **Why a single nullable [onClick] instead of legacy's `isFirstOpen: Boolean + onFinish: () ->
 * Unit` pair**: collapses the two parameters into one, removing the footgun where
 * `isFirstOpen=true` could pair with a no-op `onFinish`. Non-null signal IS the gate.
 *
 * **Localized label (Phase 11.ui.UP-3f)**: resolves through `stringResource(Res.string.finish)`
 * — the legacy `finish` key, reused verbatim (en + ar shipped).
 */
@Composable
private fun FinishButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Text(
                text = stringResource(Res.string.finish),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun LanguageHeader(
    language: String,
    sources: List<Source>,
    isOnboarding: Boolean,
    onToggleLanguage: (Boolean) -> Unit,
    paddingHorizontal: androidx.compose.ui.unit.Dp,
    paddingVertical: androidx.compose.ui.unit.Dp,
) {
    val anyEnabled = sources.any { it.isEnabled }
    // Display name resolves through the shared [languageDisplayName] transform (parens-strip +
    // onboarding-localize) so the master-row title matches the `.glabel` section label above the
    // card. See [languageDisplayName] for the parity rationale.
    val displayName = languageDisplayName(language, isOnboarding)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = paddingHorizontal, vertical = paddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Typography parity-fix: native SwitchItem.kt:55-57 (the language master header)
            // renders title 14.sp onBackground + description 12.sp onBackground@0.5 — was
            // titleMedium SemiBold (~16.sp) / bodySmall onSurfaceVariant.
            Text(
                text = displayName,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            // NP Phase 2 P2 (sources master-row subtitle): the per-language master row's
            // secondary line describes the bulk action ("Enable/disable all <Lang> sources"),
            // matching native RepoSettingsScreen passing
            // stringResource(enable_disable_all_sources, language) into LanguageToggle ->
            // SwitchItem. The format arg is the raw (parenthesised) language key, matching
            // native's un-stripped description arg (the title strips the parens, the subtitle
            // does not). Supersedes the former live "N of M enabled" count caption
            // (sources_enabled_count), which was a text divergence from the native source of
            // truth.
            Text(
                text = stringResource(
                    Res.string.enable_disable_all_sources,
                    language,
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
        Switch(
            checked = anyEnabled,
            onCheckedChange = onToggleLanguage,
        )
    }
}

@Composable
private fun SourceRow(
    source: Source,
    language: String,
    onToggle: (Boolean) -> Unit,
    paddingHorizontal: androidx.compose.ui.unit.Dp,
    paddingVertical: androidx.compose.ui.unit.Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = paddingHorizontal, vertical = paddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(paddingHorizontal),
    ) {
        // Redesign 2026-06 (mockup `.si`): a ~38dp rounded medallion holding the resolved source
        // brand icon. When [LocalSourceIconResolver] returns null the medallion falls back to a
        // tinted (accent-soft) container showing the source's initials. Preserves the native
        // RepoToggleItem brand-icon semantics (enabled = full brand color; disabled = flat
        // onBackground-tinted silhouette) via the icon path's colorFilter.
        SourceMedallion(api = source.api, label = source.displayName, isEnabled = source.isEnabled)
        // Native parity (GAP-SRC-07): source name (mockup `.st .a`, 15.sp weight 700) + a
        // "manga · <lang>" sublabel (mockup `.st .b`, 12.5px muted) underneath. The lowercase
        // language code reuses the same parens-strip transform as the header.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.displayName,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                // Localized enabled/disabled status. The language is already shown in the group
                // header above, so the per-row sublabel conveys the toggle state instead — and stays
                // translatable (a hardcoded "manga · <lang>" literal did not translate).
                text = stringResource(
                    if (source.isEnabled) Res.string.source_enabled else Res.string.source_disabled,
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Redesign 2026-06 (mockup `.tog`): per-source coral Switch (colorScheme.primary). The
        // per-source enable toggle behaviour is unchanged — only the control glyph changed from
        // the prior Checkbox to a Switch to match the mockup's `.tog` toggles.
        Switch(
            checked = source.isEnabled,
            onCheckedChange = onToggle,
        )
    }
}

/**
 * Redesign 2026-06 — source brand-icon medallion (mockup `.si`): a 38dp rounded-corner container.
 *
 * When [LocalSourceIconResolver] resolves a packaged drawable for [api], the medallion renders the
 * brand icon (native RepoToggleItem parity: enabled rows show full brand color; disabled rows render
 * a flat onBackground-tinted silhouette via `colorFilter`). A config-declared remote icon URL renders
 * through [RemoteSourceIcon] with the initials avatar as its loading/error fallback. When nothing
 * resolves, the tinted (primary @ accent-soft alpha) container shows the source's initials, so every
 * row carries a leading medallion even for sources with no shipped icon — deterministic across
 * launches (MangaSource decoupling, 2026-07).
 */
@Composable
private fun SourceMedallion(api: String, label: String, isEnabled: Boolean) {
    val resolution = LocalSourceIconResolver.current(api)
    val colorFilter = if (isEnabled) null else ColorFilter.tint(MaterialTheme.colorScheme.onBackground)
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) {
        when (resolution) {
            is SourceIconResolution.Packaged -> Image(
                painter = painterResource(resolution.drawable),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                colorFilter = colorFilter,
            )
            is SourceIconResolution.Remote -> RemoteSourceIcon(
                url = resolution.url,
                modifier = Modifier.size(24.dp),
                colorFilter = colorFilter,
                fallback = { MedallionInitials(label) },
            )
            SourceIconResolution.None -> MedallionInitials(label)
        }
    }
}

@Composable
private fun MedallionInitials(label: String) {
    Text(
        text = sourceInitials(label),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Derives a 1–2 character initials label for the medallion fallback from a source `api` string
 * (e.g. "Lekmanga" -> "L", "Team X" -> "TX", "3asq" -> "3A"). Splits on whitespace and takes the
 * first letter of each of the first two words; for a single word it takes the first character
 * (the mockup uses styles like "L" / "TX" / "3a").
 */
private fun sourceInitials(api: String): String {
    val words = api.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/**
 * Redesign 2026-06 — uppercase section label above each language group card (mockup `.glabel`:
 * 12sp, weight 800, letter-spacing, muted, uppercase). Sits between group cards in the LazyColumn.
 */
@Composable
private fun GroupLabel(
    text: String,
    paddingHorizontal: androidx.compose.ui.unit.Dp,
) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = paddingHorizontal, vertical = 6.dp),
    )
}

/**
 * NP Phase 2 (GAP-SRC-10): language-code → localized display name for the onboarding section headers.
 *
 * Native's onboarding `getLanguageName` used `java.util.Locale(code).getDisplayLanguage(deviceLocale)`,
 * which renders the language name in the DEVICE locale (e.g. on an Arabic device the EN group header
 * reads "الإنجليزية"). The previous rework used a hardcoded English-only table, which lost that
 * device-locale rendering. This now delegates to the platform formatter ([displayLanguageName]) so the
 * names follow the active locale on every platform, matching native — with a raw-code fallback for any
 * unmapped code (native's `catch { code }`).
 */
private fun localizedLanguageName(code: String): String = displayLanguageName(code)

/**
 * Shared language-code → display-name transform used by both the `.glabel` section label
 * ([GroupLabel]) and the in-card master row ([LanguageHeader]).
 *
 * Strips the parentheses the legacy data carries on the language code (e.g. "(AR)" -> "AR"),
 * matching the legacy `RepoSettingsScreen`'s `language.removeAllParens()` display transform — the
 * rework `Source.language` passes the raw "(AR)"/"(EN)" code through from the legacy
 * `SourcesEntity`. For the onboarding entry the stripped code is then resolved to a localized
 * display name (e.g. "EN" -> "English") via [localizedLanguageName] (GAP-SRC-10, native onboarding
 * `getLanguageName`); non-onboarding (RepoSettings) entries keep the raw stripped code to match the
 * legacy `RepoSettingsScreen` behavior.
 */
private fun languageDisplayName(language: String, isOnboarding: Boolean): String {
    val strippedCode = language.replace("(", "").replace(")", "")
    return if (isOnboarding) localizedLanguageName(strippedCode) else strippedCode
}

/**
 * Modal Request-Source dialog — single body field (URL). The complaint type is fixed at
 * `ComplaintType.SITES_ADD` server-side (VM-internal constant, not exposed in this UI), so
 * no category dropdown is needed (vs the Settings hub's [FeedbackDialog] which DOES show a
 * dropdown because the type varies). Same dismissal-gating + body-length validation posture
 * as the Settings feedback dialog (`SettingsScreen.kt` private `FeedbackDialog`):
 *
 * **Submit gating**: matches the legacy `RepoSettingsScreen.kt:215-248`'s implicit gate
 * (the legacy `FeedbackDialog` accepts any non-empty body; the rework adds an explicit
 * minimum-length check of 5 chars matching the Settings hub dialog for consistency).
 * Native-parity reconciliation (GAP-SET-11): `SendComplaintUseCase` now gates on
 * `MIN_BODY_LENGTH = 5`, so any body that passes this UI gate (>= 5) also passes the use
 * case — no more silent 5–7 char server-side rejection.
 *
 * **Dismissal gating during submission**: [DialogProperties] disables back-press and
 * outside-tap dismissal while [isSubmitting] is `true`; the Cancel button is also disabled.
 * The VM-side guard (in [SourcesViewModel.handle]'s OnDismissComplaintDialog branch) is
 * defence-in-depth.
 *
 * **Body length cap**: 500 chars max (matches the Settings feedback dialog). Past 500 the
 * `onValueChange` short-circuits without updating, preventing runaway input.
 *
 * **Social-media footer** (GAP-SRC-SOCIAL, native FeedbackDialog.kt:184-201): below the input the
 * dialog renders a [HorizontalDivider], a "Connect with us in social media" label, the
 * prompt-response copy, and a [KiraSocialMediaRow] whose brand links open via the [onOpenUrl]
 * callback — matching the native add-source surface (which is the full FeedbackDialog) and the
 * sibling Language request dialog. The category dropdown is intentionally omitted: the complaint
 * type is fixed at SITES_ADD server-side, so there is nothing for the user to pick.
 */
@Composable
private fun RequestSourceDialog(
    isSubmitting: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    // GAP-SRC-SOCIAL — social-media row URL-open callback (native FeedbackDialog.kt:200 renders
    // SocialMediaRow in the request dialog footer). Forwarded by the host route adapter to the
    // platform IntentLauncher.openUrl.
    onOpenUrl: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val scrollState = rememberScrollState()
    var body by rememberSaveable { mutableStateOf("") }

    val submitEnabled = body.length >= 5 && !isSubmitting

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isSubmitting,
            dismissOnClickOutside = !isSubmitting,
        ),
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        title = {
            // Parity-fix: native FeedbackDialog.kt:58-72 wraps the header + a "We'd love to hear
            // from you" subtitle in a Column (the native add-source surface IS the shared
            // FeedbackDialog with ComplaintType.SITES_ADD). The KMP dialog previously showed only
            // the header; the subtitle is added here, matching the sibling Language request dialog.
            Column {
                Text(
                    // Copy parity-fix: native "Request Adding Source/Site" via
                    // request_adding_source_full (see RequestSourceRow).
                    text = stringResource(Res.string.request_adding_source_full),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.we_d_love_to_hear_from_you),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                Text(
                    text = stringResource(Res.string.we_will_add_it_as_soon_it_possible),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { if (it.length <= 500) body = it },
                    enabled = !isSubmitting,
                    label = { Text(stringResource(Res.string.enter_the_site_url)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    minLines = 4,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    isError = body.isNotEmpty() && body.length < 5,
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (body.isNotEmpty() && body.length < 5) {
                                    stringResource(Res.string.minimum_5_characters_required)
                                } else {
                                    ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = "${body.length}/500",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                // GAP-SRC-SOCIAL — social-media footer (divider + "Connect with us in social media"
                // label + prompt-response copy + KiraSocialMediaRow), matching native
                // FeedbackDialog.kt:184-201 and the sibling Language request dialog. Reuses the
                // shared callback-only row so the brand links open via the route adapter's
                // IntentLauncher.openUrl, same as the About / Settings / Language surfaces.
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Text(
                        text = stringResource(Res.string.connect_with_us_in_social_media),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(Res.string.you_ll_receive_a_prompt_response),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    KiraSocialMediaRow(onOpenUrl = onOpenUrl)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = submitEnabled,
                onClick = { onSubmit(body) },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = if (isSubmitting) {
                        stringResource(Res.string.submitting)
                    } else {
                        stringResource(Res.string.submit)
                    },
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.cancel),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}
