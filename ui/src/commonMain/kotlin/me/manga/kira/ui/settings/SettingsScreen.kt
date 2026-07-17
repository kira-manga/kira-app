package me.manga.kira.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionLToR
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.domain.model.settings.SettingsToggle
import me.manga.kira.presentation.settings.SettingsDestination
import me.manga.kira.presentation.settings.SettingsEffect
import me.manga.kira.presentation.settings.SettingsIntent
import me.manga.kira.presentation.settings.SettingsState
import me.manga.kira.presentation.settings.SettingsViewModel
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.about
import me.manga.kira.ui.generated.resources.backup_title
import me.manga.kira.ui.generated.resources.app_information_and_updates
import me.manga.kira.ui.generated.resources.auto_convert_on_download
import me.manga.kira.ui.generated.resources.but_apply
import me.manga.kira.ui.generated.resources.but_revert
import me.manga.kira.ui.generated.resources.cache_calculating
import me.manga.kira.ui.generated.resources.cached_size
import me.manga.kira.ui.generated.resources.cancel
import me.manga.kira.ui.generated.resources.category
import me.manga.kira.ui.generated.resources.cbz_conversion_error_body
import me.manga.kira.ui.generated.resources.chapter
import me.manga.kira.ui.generated.resources.chapters_converted_successfully
import me.manga.kira.ui.generated.resources.chapters_remaining
import me.manga.kira.ui.generated.resources.clear_chapter_cache
import me.manga.kira.ui.generated.resources.close
import me.manga.kira.ui.generated.resources.closure_reason_done
import me.manga.kira.ui.generated.resources.completed
import me.manga.kira.ui.generated.resources.compress_existing_downloads
import me.manga.kira.ui.generated.resources.compress_existing_downloads_desc
import me.manga.kira.ui.generated.resources.complaint_features
import me.manga.kira.ui.generated.resources.complaint_languages
import me.manga.kira.ui.generated.resources.complaint_other
import me.manga.kira.ui.generated.resources.complaint_site_error
import me.manga.kira.ui.generated.resources.complaint_sites_add
import me.manga.kira.ui.generated.resources.complaint_technical
import me.manga.kira.ui.generated.resources.connect_with_us_in_social_media
import me.manga.kira.ui.generated.resources.conversion_complete_
import me.manga.kira.ui.generated.resources.conversion_failed
import me.manga.kira.ui.generated.resources.conversion_stopped
import me.manga.kira.ui.generated.resources.conversion_stopped_by_user
import me.manga.kira.ui.generated.resources.converting
import me.manga.kira.ui.generated.resources.converting_to_cbz
import me.manga.kira.ui.generated.resources.current
import me.manga.kira.ui.generated.resources.default_reading_mode
import me.manga.kira.ui.generated.resources.describe_issue_or_feature
import me.manga.kira.ui.generated.resources.downloads
import me.manga.kira.ui.generated.resources.feedback_retry
import me.manga.kira.ui.generated.resources.request_failed
import me.manga.kira.ui.generated.resources.feedback_submitted
import me.manga.kira.ui.generated.resources.feedbacks_and_complaints
import me.manga.kira.ui.generated.resources.follow_system_theme
import me.manga.kira.ui.generated.resources.cache_cleaner
import me.manga.kira.ui.generated.resources.help
import me.manga.kira.ui.generated.resources.ic_complaint
import me.manga.kira.ui.generated.resources.ic_day_night
import me.manga.kira.ui.generated.resources.ic_reader_setting
import me.manga.kira.ui.generated.resources.incognito_svgrepo_com
import me.manga.kira.ui.generated.resources.switchthemes
import me.manga.kira.ui.generated.resources.language
import me.manga.kira.ui.generated.resources.lpm_compress_toggle
import me.manga.kira.ui.generated.resources.lpm_compress_toggle_desc
import me.manga.kira.ui.generated.resources.minimum_5_characters_required
import me.manga.kira.ui.generated.resources.please_don_t_close_the_app_until_conversion_is_complete
import me.manga.kira.ui.generated.resources.pure_black_mode_title
import me.manga.kira.ui.generated.resources.reading_mode
import me.manga.kira.ui.generated.resources.remaining
import me.manga.kira.ui.generated.resources.reading_mode_continuous
import me.manga.kira.ui.generated.resources.reading_mode_default
import me.manga.kira.ui.generated.resources.reading_mode_ltr
import me.manga.kira.ui.generated.resources.reading_mode_rtl
import me.manga.kira.ui.generated.resources.reading_mode_vertical
import me.manga.kira.ui.generated.resources.reading_mode_webtoon
import me.manga.kira.ui.generated.resources.request_feature_bug
import me.manga.kira.ui.generated.resources.select_a_category
import me.manga.kira.ui.generated.resources.report_bug_feature_title
import me.manga.kira.ui.generated.resources.report_bug_feature_desc
import me.manga.kira.ui.generated.resources.theme_custom_title
import me.manga.kira.ui.generated.resources.setting_downloaded_only
import me.manga.kira.ui.generated.resources.setting_downloaded_only_desc
import me.manga.kira.ui.generated.resources.setting_incognito
import me.manga.kira.ui.generated.resources.setting_incognito_desc
import me.manga.kira.ui.generated.resources.settings_screen_title
import me.manga.kira.ui.generated.resources.sources_title
import me.manga.kira.ui.generated.resources.start_reading_settings_activated_description
import me.manga.kira.ui.generated.resources.start_reading_settings_locked_description
import me.manga.kira.ui.generated.resources.start_reading_title
import me.manga.kira.ui.generated.resources.start_conversion
import me.manga.kira.ui.generated.resources.statistics
import me.manga.kira.ui.generated.resources.stop_conversion
import me.manga.kira.ui.generated.resources.submit
import me.manga.kira.ui.generated.resources.submitting
import me.manga.kira.ui.generated.resources.system_theme
import me.manga.kira.ui.generated.resources.theme_dark
import me.manga.kira.ui.generated.resources.theme_light_desc
import me.manga.kira.ui.generated.resources.theme_dark_desc
import me.manga.kira.ui.generated.resources.theme_screen_title
import me.manga.kira.ui.generated.resources.follow_system_theme_desc
import me.manga.kira.ui.generated.resources.use_kira_compressor
import me.manga.kira.ui.generated.resources.use_kira_compressor_desc
import me.manga.kira.ui.generated.resources.we_d_love_to_hear_from_you
import me.manga.kira.ui.generated.resources.what_s_new
import me.manga.kira.ui.generated.resources.you_ll_receive_a_prompt_response
import me.manga.kira.ui.generated.resources.your_feedback
import me.manga.kira.ui.components.KiraSocialMediaRow
import me.manga.kira.ui.theme.LocalBottomBarPadding
import me.manga.kira.ui.theme.LocalSpacing
import me.manga.kira.ui.util.formatByteSize
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings hub screen — Compose entry point for the Settings MVI slice.
 *
 * Phase 7.x.settings.foundation rework. Renders [SettingsState]'s visible toggle booleans + the
 * formatted cache-size string in three sections (General toggles / Theme toggles / Navigation
 * rows) plus a clear-cache action row. Dispatches the 3 [SettingsIntent] variants and routes
 * the 2 [SettingsEffect] variants — [SettingsEffect.NavigateTo] flows out through the
 * [onNavigate] callback (the `:ui` module can't reference `NavController`; the `:composeApp`
 * route adapter provides the callback that calls `navController.navigate(Screen.<X>Rework)`),
 * and [SettingsEffect.FeedbackResult] feeds a Material 3 [SnackbarHost] owned by this screen.
 *
 * **`onNavigate` callback bridge**: matches the established `:ui` posture for nav-emitting
 * screens — [me.manga.kira.ui.complaint.ComplaintScreen]'s `onBack`,
 * [me.manga.kira.ui.details.DetailsScreen]'s `onOpenReader`. The route adapter wraps the
 * destination in a `navController.navigate(...)` call. Translating the [SettingsEffect.
 * NavigateTo] effect into a callback at the `:ui` boundary (rather than asking the route
 * adapter to collect the effect flow) avoids the single-receiver constraint of
 * [me.manga.kira.presentation.mvi.MviViewModel.effects] — the screen owns the one
 * `LaunchedEffect` collector and routes each variant to its appropriate sink.
 *
 * **Visual parity vs the legacy `composeApp/.../features/settings/ui/screens/SettingsScreen.
 * kt`** (foundation slice scope):
 *  - **Three sections** in a [LazyColumn]: General (2 toggles), Theme (3 toggles), Navigation
 *    (6 rows + an inert Help placeholder added by Phase 7.x.settings.help), plus a Storage
 *    section with the clear-cache action row. The legacy has the same grouping conceptually
 *    but adds a header image (still deferred — purely decorative).
 *  - **Dark mode row is gated on `!followSystemTheme`** (Phase 7.x.settings.themegating —
 *    landed atop the foundation) — matches legacy parity (legacy `:228-241` wraps only the
 *    dark/light SwitchItem inside `if (!isFollowSystem)`; legacy's Pure Black row stays
 *    always-visible after the conditional block, and the rework mirrors that exactly). The
 *    same single source of truth (`pureBlackFlow`, `darkModeFlow`, `followSystemFlow`) drives
 *    both legacy and rework consumers, so toggling on the legacy screen reflects on the rework
 *    screen and vice versa.
 *  - **Rows are icon-led** (SET-PFIX-01) — every native settings row renders a 24.dp leading
 *    glyph + a 16.dp gap (native `SwitchItem.kt:44-52` / `SettingsNavigationItem.kt:51-60`), and
 *    the rework now mirrors that exactly. Material glyphs (CloudOff / Outlined.DarkMode /
 *    QueryStats / Language / Download / AutoMirrored Message + Help / Info) come from
 *    `compose.materialIconsExtended` (UP-2a); the custom native vectors (incognito_svgrepo_com /
 *    switchthemes / ic_day_night / ic_complaint / ic_reader_setting / cache_cleaner, plus the
 *    red ic_pluss18 testing-mode glyph) are ported into `:ui` `composeResources/drawable` and
 *    wired per-row through the [RowIcon] helpers. The KMP-extra Theme + What's-new nav rows have
 *    no native counterpart and stay icon-less.
 *  - **Labels resolve through compose-resources `stringResource(Res.string.*)`** (Phase
 *    11.ui.UP-3k). The 4 enum-label helpers (settingsToggleLabel / settingsDestinationLabel /
 *    complaintTypeLabel / readingModeLabel) are `@Composable` and resolve per entry; reused
 *    legacy keys carry Arabic, the rest are new en-only keys pending trusted Arabic.
 *  - **Design tokens**: [LocalSpacing] + Material 3 `Card` / `HorizontalDivider` / `Switch` /
 *    `Text` directly. The legacy uses ad-hoc `.dp` literals + custom `SwitchItem` /
 *    `SettingsNavigationItem` / `ItemsGroup` composables.
 *
 * **Loading state**: a centered [CircularProgressIndicator] while `state.isLoading == true` —
 * covers the gap between subscription and the first emission from the upstream pref `combine`.
 * The gap is sub-millisecond in practice (`SharedPreferences.booleanPrefFlow` reads + the
 * okio cache-size walk on `dispatchers.io`); the spinner avoids a one-frame "all defaults"
 * flash on cold start.
 *
 * **Cache-clear row disabled during the in-flight write**: the row's `clickable` is gated on
 * `!state.isClearingCache`. The VM-side guard (in [SettingsViewModel.handleClearCache]) is
 * defence-in-depth — together they prevent a double-tap from queuing two `OnClearCache`
 * intents through [me.manga.kira.presentation.mvi.MviViewModel]'s unbounded intent channel.
 *
 * **Snackbar duration**: `withDismissAction = false`, no explicit duration — Material 3
 * defaults to `SnackbarDuration.Short`. Same defaults as
 * [me.manga.kira.ui.complaint.ComplaintScreen] / [me.manga.kira.ui.language.
 * LanguageScreen].
 *
 * **`viewModel` reference in `LaunchedEffect`**: keying on `viewModel` tears down + restarts
 * the collector if a different VM instance arrives (e.g., on nav-back to a freshly resolved
 * VM). In practice the VM is stable for the lifetime of this screen, but keying on
 * `viewModel` is the safe pattern — same posture as
 * [me.manga.kira.ui.complaint.ComplaintScreen].
 *
 * Stateless inner [SettingsScreenContent] mirrors the established convention — separating
 * "wire to VM" from "render state". The inner takes a `Flow<SettingsEffect>` directly so
 * the effect collector co-locates with the snackbar host that consumes it; the nav callback
 * propagates through to the inner so its `LaunchedEffect` can dispatch.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster3.staleKdocSweep.cascade,
 * Task #458, 2026-05-28): a stale citation into the §354-retired legacy
 * `composeApp/.../features/settings/ui/screens/SettingsScreen.kt` appears
 * above:
 *  - Lines 80-81 (Visual parity preamble): "Visual parity vs the legacy
 *    `composeApp/.../features/settings/ui/screens/SettingsScreen.kt`".
 * The legacy `composeApp/.../features/settings/ui/screens/SettingsScreen.kt`
 * was retired in Phase 9.x.settings_about.legacyui.retire (§354, multi-commit
 * chain starting `5cc42d2` "(1/4): drop unreachable legacy SettingsScreen +
 * AboutScreen + SettingsNavigationItem"); verified by a filesystem check
 * returning zero hits for that path. The visual-parity bullets (three-section
 * grouping with Storage + clear-cache row, Dark-mode gating on
 * !followSystemTheme, intentional icon omission to avoid the
 * material-icons-extended dep, inline literal strings, design-token
 * migration) all stand on their own merits — the rework `:ui` design
 * language's terminal-screen Material 3 posture, the
 * `SharedPreferences`-cell single-source-of-truth pref invariants, the
 * Phase 10 i18n lift strategy, and the LocalSpacing + Material 3
 * design-token discipline are documented inline above and independent of
 * which legacy file originally implemented the parity precedent. Original
 * §253-era prose preserved verbatim per the audit-trail-preservation
 * convention — the citation is historical record of the design lineage;
 * the rework SettingsScreen continues to render the Settings hub correctly
 * through the legacy retire.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigate: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
    sourceAccessActivated: Boolean = false,
    // GAP-SET-12 — Feedback dialog social-media row. `:ui` is callback-only; the route adapter
    // forwards each URL to the platform `IntentLauncher.openUrl`. Defaults to a no-op so existing
    // callers compile unchanged.
    onOpenUrl: (String) -> Unit = {},
    // iOS-only: whether to show the "compress during Low Power Mode" toggle in the Downloads section.
    // The route adapter passes true only on iOS; defaults false so other callers / previews compile.
    lowPowerCompressionToggleVisible: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    SettingsScreenContent(
        state = state,
        effects = viewModel.effects,
        onIntent = viewModel::submit,
        onNavigate = onNavigate,
        modifier = modifier,
        sourceAccessActivated = sourceAccessActivated,
        onOpenUrl = onOpenUrl,
        lowPowerCompressionToggleVisible = lowPowerCompressionToggleVisible,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    state: SettingsState,
    effects: Flow<SettingsEffect>,
    onIntent: (SettingsIntent) -> Unit,
    onNavigate: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
    sourceAccessActivated: Boolean = false,
    onOpenUrl: (String) -> Unit = {},
    lowPowerCompressionToggleVisible: Boolean = false,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Launch snackbars off the effect collector so showing one never blocks a later navigation
    // effect for the snackbar's duration (the user couldn't leave a screen while a snackbar showed).
    val scope = rememberCoroutineScope()
    // GAP-SET-13 — feedback success / failure copy + Retry action label resolved in composable
    // scope (stringResource can't run inside the effect-collector coroutine below). The error
    // snackbar offers a Retry action that re-opens the feedback dialog, with Long duration.
    val feedbackSubmittedMessage = stringResource(Res.string.feedback_submitted)
    // #22: localized failure message — drop the raw-exception interpolation (it leaked an untranslated
    // English exception string into the snackbar). request_failed is a clean localized string.
    val feedbackFailedMessage = stringResource(Res.string.request_failed)
    val feedbackRetryLabel = stringResource(Res.string.feedback_retry)
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is SettingsEffect.NavigateTo -> onNavigate(effect.destination)
                // GAP-SET-16 — the CBZ conversion terminal outcome is rendered by the
                // CbzConversionDialog (driven by the progress Flow), not a snackbar, matching
                // native which shows ONLY the dialog. No ConversionResult effect branch.
                is SettingsEffect.FeedbackResult -> scope.launch {
                    if (effect.success) {
                        snackbarHostState.showSnackbar(
                            message = feedbackSubmittedMessage,
                            duration = SnackbarDuration.Short,
                        )
                    } else {
                        val result = snackbarHostState.showSnackbar(
                            message = feedbackFailedMessage,
                            actionLabel = feedbackRetryLabel,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            onIntent(SettingsIntent.OnOpenFeedbackDialog)
                        }
                    }
                }
            }
        }
    }

    // P2-SET (F1) — native parity: the native SettingsScreen uses a bare, chromeless Scaffold
    // with ONLY a snackbarHost + background colour (native SettingsScreen.kt:101-104). There is
    // NO TopAppBar — the screen scrolls from the top with the launcher image as the first list
    // item, content centred. The prior rework TopAppBar (title "Settings", SemiBold) had no native
    // counterpart and shifted the whole layout down; removed to match the source of truth.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        // Settings has no TopAppBar, so keep the top status-bar inset (content must clear it) but drop
        // the bottom inset — the floating bottom nav's footprint reaches the list via
        // LocalBottomBarPadding (added to the list contentPadding below) instead.
        contentWindowInsets = WindowInsets.statusBars,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                SettingsList(
                    state = state,
                    onIntent = onIntent,
                    sourceAccessActivated = sourceAccessActivated,
                    lowPowerCompressionToggleVisible = lowPowerCompressionToggleVisible,
                )
            }
        }
    }

    if (state.feedbackDialogOpen) {
        FeedbackDialog(
            isSubmitting = state.isSubmittingFeedback,
            // P2-SET (F9) — pass the localized category display name as the subject (native parity).
            onSubmit = { type, subject, body ->
                onIntent(SettingsIntent.OnSubmitFeedback(type, subject, body))
            },
            onDismiss = { onIntent(SettingsIntent.OnDismissFeedbackDialog) },
            onOpenUrl = onOpenUrl,
        )
    }

    if (state.readingModeDialogOpen) {
        ReadingModeDialog(
            currentMode = state.readingMode,
            onApply = { onIntent(SettingsIntent.OnSelectReadingMode(it)) },
            onDismiss = { onIntent(SettingsIntent.OnDismissReadingModeDialog) },
        )
    }

    // GAP-SET-16 — CBZ conversion progress dialog (native parity). Driven by the live
    // `state.cbzConversion` progress snapshot from the domain progress Flow: it renders the
    // determinate progress bar + converted/total counts + the current manga/chapter + a Stop
    // button while converting, and the terminal Error / Success / Stopped states once the run
    // finishes. Visibility mirrors native (`isConverting || error != null || successMessage !=
    // null`); dismissal is blocked while converting. The Stop button fires `OnStopConversion`.
    CbzConversionDialog(
        progress = state.cbzConversion,
        onStop = { onIntent(SettingsIntent.OnStopConversion) },
        onDismiss = { onIntent(SettingsIntent.OnDismissConversionDialog) },
    )
}

@Composable
private fun SettingsList(
    state: SettingsState,
    onIntent: (SettingsIntent) -> Unit,
    sourceAccessActivated: Boolean = false,
    lowPowerCompressionToggleVisible: Boolean = false,
) {
    val spacing = LocalSpacing.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = spacing.md, bottom = spacing.md + LocalBottomBarPadding.current),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // Redesign 2026-06 — big bold title header at the top of the list (mockup `.header`:
        // eyebrow + large "Settings" title), replacing the prior decorative launcher-foreground
        // image. Mirrors HomeScreen's `HomeHeader` look (large title). The decorative eyebrow is
        // intentionally omitted (no "Settings"/"Preferences" string key exists and the task forbids
        // adding one); the title is a literal, matching the established `Text("YAMI")` redesign
        // posture in HomeHeader (no new Res.string key).
        item(key = "header-title") {
            SettingsHeader()
        }

        // GAP-SET-04 — native section grouping (4 native groups), restructured from the prior
        // 7-section split. Native order:
        //   1. General — downloaded-only, incognito, follow-system, dark (gated), pure-black,
        //      [testing]  (theme toggles live INLINE in General, not a separate Theme section).
        //   2. Download/CBZ — use-kira-compressor, [auto-convert (KMP-extra)], compress-existing.
        //   3. Navigation — feedbacks&complaints, default reading mode, statistics, app language,
        //      downloads  (+ the KMP-extra Theme + What's-new nav rows, intentional rework
        //      additions per GAP-THM-11 / GAP-SET-22 — retained, not removed).
        //   4. Other — clear-cache, request-feature/bug, about, help.
        item(key = "section-general") {
            // Redesign 2026-06 — uppercase `.glabel` group label above the card (mockup). Literal
            // text (no "Appearance"/"General" Res.string key exists and the task forbids adding one);
            // consistent with the literal title above. Group ordering/contents are unchanged.
            SectionCard(label = "General") {
                ToggleRow(
                    label = settingsToggleLabel(SettingsToggle.DOWNLOADED_ONLY),
                    // GAP-SET-21 — legacy `downloaded_only_desc` (lifted to stringResource).
                    description = stringResource(Res.string.setting_downloaded_only_desc),
                    checked = state.downloadedOnly,
                    onCheckedChange = { onIntent(SettingsIntent.OnToggle(SettingsToggle.DOWNLOADED_ONLY, it)) },
                    // SET-PFIX-01 — native CloudOff leading icon (native SettingsScreen.kt:132).
                    leadingIcon = { RowIcon(Icons.Default.CloudOff) },
                )
                SectionDivider()
                ToggleRow(
                    label = settingsToggleLabel(SettingsToggle.INCOGNITO),
                    // GAP-SET-21 — legacy `incognito_mode_desc` (lifted to stringResource).
                    description = stringResource(Res.string.setting_incognito_desc),
                    checked = state.incognito,
                    onCheckedChange = { onIntent(SettingsIntent.OnToggle(SettingsToggle.INCOGNITO, it)) },
                    // SET-PFIX-01 — native incognito vector (native SettingsScreen.kt:141).
                    leadingIcon = { RowIcon(Res.drawable.incognito_svgrepo_com) },
                )
                // GAP-SET-04 — theme toggles live inline in General per native (no separate Theme
                // section); follow-system → dark (gated on !followSystem) → pure-black.
                SectionDivider()
                ToggleRow(
                    // SET-PFIX-P3 (F2) — native parity: the follow-system row TITLE is the
                    // `system_theme` string ("System Theme") and the DESCRIPTION is
                    // `follow_system_theme` ("Follow System Theme") (native SettingsScreen.kt:147-148).
                    // The prior rework used the `follow_system_theme` value ("Follow system theme") as
                    // the title and `follow_system_theme_desc` as the description, so the native title
                    // "System Theme" was missing. Title now resolves the new `system_theme` key; the
                    // description keeps `follow_system_theme_desc` ("Follow System Theme") which already
                    // carries the native subtitle text.
                    label = stringResource(Res.string.system_theme),
                    description = stringResource(Res.string.follow_system_theme_desc),
                    checked = state.followSystemTheme,
                    onCheckedChange = { onIntent(SettingsIntent.OnToggle(SettingsToggle.FOLLOW_SYSTEM_THEME, it)) },
                    // SET-PFIX-01 — native switchthemes vector (native SettingsScreen.kt:149).
                    leadingIcon = { RowIcon(Res.drawable.switchthemes) },
                )
                if (!state.followSystemTheme) {
                    SectionDivider()
                    ToggleRow(
                        // P2-SET (F4) — native parity: the dark/light row TITLE is the static
                        // `theme_title` ("Custom Theme"), with a DYNAMIC subtitle that toggles
                        // "Dark Mode" / "Light Mode" by themeMode (native SettingsScreen.kt:156-162).
                        // The prior rework used `theme_dark` ("Dark Mode") as the title, which both
                        // dropped "Custom Theme" and duplicated the subtitle ("Dark Mode" over "Dark
                        // Mode"). Title restored to the native static "Custom Theme".
                        label = stringResource(Res.string.theme_custom_title),
                        // GAP-SET-21 — legacy `theme_dark` / `theme_light` (lifted to stringResource).
                        description = if (state.darkMode) {
                            stringResource(Res.string.theme_dark_desc)
                        } else {
                            stringResource(Res.string.theme_light_desc)
                        },
                        checked = state.darkMode,
                        onCheckedChange = { onIntent(SettingsIntent.OnToggle(SettingsToggle.DARK_MODE, it)) },
                        // SET-PFIX-01 — native ic_day_night vector (native SettingsScreen.kt:159).
                        leadingIcon = { RowIcon(Res.drawable.ic_day_night) },
                    )
                }
                SectionDivider()
                ToggleRow(
                    // Legacy Pure-black row had NO description (SettingsScreen.kt:166-171).
                    label = settingsToggleLabel(SettingsToggle.PURE_BLACK),
                    checked = state.pureBlack,
                    onCheckedChange = { onIntent(SettingsIntent.OnToggle(SettingsToggle.PURE_BLACK, it)) },
                    // SET-PFIX-01 — native Outlined.DarkMode leading icon (native SettingsScreen.kt:168).
                    leadingIcon = { RowIcon(Icons.Outlined.DarkMode) },
                )
            }
        }

        item(key = "section-cbz") {
            // Redesign 2026-06 — `.glabel` group label (mockup). Literal (no key); group unchanged.
            SectionCard(label = "Downloads") {
                ToggleRow(
                    // GAP-SET-21 — Kira Compressor section labels lifted to stringResource (legacy
                    // `use_kira_compressor` / `use_kira_compressor_to_..._save_storage`).
                    label = stringResource(Res.string.use_kira_compressor),
                    description = stringResource(Res.string.use_kira_compressor_desc),
                    checked = state.useCbzFormat,
                    onCheckedChange = { onIntent(SettingsIntent.OnToggle(SettingsToggle.USE_CBZ_FORMAT, it)) },
                )
                if (state.useCbzFormat) {
                    // P2-SET (F6) — native parity: native renders ONLY the useCbz toggle + the
                    // Compress-Existing action under this section (native SettingsScreen.kt:195-237).
                    // The `autoConvertToCbz` flow exists in native's CbzConversionViewModel but is
                    // NOT surfaced as a Settings row — the prior rework added an "Auto-convert on
                    // Download" ToggleRow here that native does not show, letting the user flip
                    // auto-convert from Settings (a behavioural addition). Row removed to match the
                    // source of truth; the `autoConvertToCbz` state + AUTO_CONVERT_TO_CBZ intent
                    // remain wired in :presentation (mirrors native keeping the flow in the VM).
                    SectionDivider()
                    CompressExistingRow(
                        isCompressing = state.isCompressingDownloads,
                        onCompress = { onIntent(SettingsIntent.OnCompressExistingDownloads) },
                    )
                    // iOS-only: allow the background CBZ compression/finalize to run during Low Power
                    // Mode (default off = respect battery intent). Hidden on Android/Desktop via the
                    // route-adapter visibility flag. When off, a finished download whose compression is
                    // deferred by Low Power Mode shows a clear "Paused" state (Details row + notification)
                    // instead of an endless "Finalizing…".
                    if (lowPowerCompressionToggleVisible) {
                        SectionDivider()
                        ToggleRow(
                            label = stringResource(Res.string.lpm_compress_toggle),
                            description = stringResource(Res.string.lpm_compress_toggle_desc),
                            checked = state.allowCompressionInLowPower,
                            onCheckedChange = {
                                onIntent(
                                    SettingsIntent.OnToggle(SettingsToggle.ALLOW_COMPRESSION_IN_LOW_POWER, it),
                                )
                            },
                        )
                    }
                }
            }
        }

        item(key = "section-navigation") {
            // Redesign 2026-06 — `.glabel` group label (mockup). Literal (no key); group unchanged.
            SectionCard(label = "Navigation") {
                NavRow(
                    label = if (sourceAccessActivated) {
                        stringResource(Res.string.sources_title)
                    } else {
                        stringResource(Res.string.start_reading_title)
                    },
                    description = if (sourceAccessActivated) {
                        stringResource(Res.string.start_reading_settings_activated_description)
                    } else {
                        stringResource(Res.string.start_reading_settings_locked_description)
                    },
                    onClick = {
                        onIntent(SettingsIntent.OnNavigate(SettingsDestination.SOURCE_MANAGEMENT))
                    },
                    leadingIcon = { RowIcon(Icons.Outlined.AutoStories) },
                )
                SectionDivider()
                // GAP-SET-04 — native Navigation group: feedbacks&complaints → default reading
                // mode → statistics → app language → downloads. Reading-mode is a dialog-opening
                // nav-style row here (native places it in Navigation, not its own section).
                NavRow(
                    label = settingsDestinationLabel(SettingsDestination.COMPLAINT),
                    onClick = { onIntent(SettingsIntent.OnNavigate(SettingsDestination.COMPLAINT)) },
                    // SET-PFIX-01 — native ic_complaint vector (native SettingsScreen.kt:249).
                    leadingIcon = { RowIcon(Res.drawable.ic_complaint) },
                )
                SectionDivider()
                ReadingModeRow(
                    onClick = { onIntent(SettingsIntent.OnOpenReadingModeDialog) },
                    // SET-PFIX-01 — native ic_reader_setting vector (native SettingsScreen.kt:260).
                    leadingIcon = { RowIcon(Res.drawable.ic_reader_setting) },
                )
                SectionDivider()
                NavRow(
                    label = settingsDestinationLabel(SettingsDestination.STATISTICS),
                    onClick = { onIntent(SettingsIntent.OnNavigate(SettingsDestination.STATISTICS)) },
                    // SET-PFIX-01 — native Outlined.QueryStats icon (native SettingsScreen.kt:267).
                    leadingIcon = { RowIcon(Icons.Outlined.QueryStats) },
                )
                SectionDivider()
                NavRow(
                    label = settingsDestinationLabel(SettingsDestination.LANGUAGE),
                    onClick = { onIntent(SettingsIntent.OnNavigate(SettingsDestination.LANGUAGE)) },
                    // SET-PFIX-01 — native Outlined.Language icon (native SettingsScreen.kt:272).
                    leadingIcon = { RowIcon(Icons.Outlined.Language) },
                )
                SectionDivider()
                NavRow(
                    label = settingsDestinationLabel(SettingsDestination.DOWNLOADS),
                    onClick = { onIntent(SettingsIntent.OnNavigate(SettingsDestination.DOWNLOADS)) },
                    // SET-PFIX-01 — native Outlined.Download icon (native SettingsScreen.kt:276).
                    leadingIcon = { RowIcon(Icons.Outlined.Download) },
                )
                // KMP-EXTRA (GAP-THM-11 / GAP-SET-22) — Theme deep-link + What's-new nav rows are
                // intentional rework additions (a separate Theme picker + WhatsNew screen exist).
                // Retained per the task's skip-KMP-EXTRA rule; appended after the native rows.
                // Redesign 2026-06 — these two rows had no native counterpart and were icon-less; the
                // mockup gives every row a leading medallion, so they get a Palette / NewReleases
                // glyph for visual consistency (decorative, no behaviour change).
                SectionDivider()
                NavRow(
                    label = settingsDestinationLabel(SettingsDestination.THEME),
                    onClick = { onIntent(SettingsIntent.OnNavigate(SettingsDestination.THEME)) },
                    leadingIcon = { RowIcon(Icons.Outlined.Palette) },
                )
                SectionDivider()
                NavRow(
                    label = settingsDestinationLabel(SettingsDestination.WHATSNEW),
                    onClick = { onIntent(SettingsIntent.OnNavigate(SettingsDestination.WHATSNEW)) },
                    leadingIcon = { RowIcon(Icons.Outlined.NewReleases) },
                )
            }
        }

        item(key = "section-other") {
            // GAP-SET-04 — native "Other" group: clear-cache → request-feature/bug → about → help.
            // Redesign 2026-06 — `.glabel` group label (mockup). Literal (no key); group unchanged.
            SectionCard(label = "Other") {
                CacheRow(
                    cacheSizeBytes = state.cacheSizeBytes,
                    isClearing = state.isClearingCache,
                    onClick = { onIntent(SettingsIntent.OnClearCache) },
                    // SET-PFIX-01 — native cache_cleaner vector (native SettingsScreen.kt:287).
                    leadingIcon = { RowIcon(Res.drawable.cache_cleaner) },
                )
                SectionDivider()
                // feature/backup — Backup & restore entry (full-library export + merge-import).
                NavRow(
                    label = settingsDestinationLabel(SettingsDestination.BACKUP),
                    onClick = { onIntent(SettingsIntent.OnNavigate(SettingsDestination.BACKUP)) },
                    leadingIcon = { RowIcon(Icons.Outlined.SettingsBackupRestore) },
                )
                SectionDivider()
                NavRow(
                    // P2-SET (F5) — native parity: row title "Report Bug / Feature"
                    // (request_feature_bug_title) + description "Tap to send us your feedback"
                    // (request_feature_bug_desc) (native SettingsScreen.kt:293-297). The prior
                    // rework used the dialog-header string `request_feature_bug` ("Request feature
                    // / bug") as a single line with no subtitle.
                    label = stringResource(Res.string.report_bug_feature_title),
                    description = stringResource(Res.string.report_bug_feature_desc),
                    onClick = { onIntent(SettingsIntent.OnOpenFeedbackDialog) },
                    // SET-PFIX-01 — native AutoMirrored.Outlined.Message icon (native
                    // SettingsScreen.kt:296).
                    leadingIcon = { RowIcon(Icons.AutoMirrored.Outlined.Message) },
                )
                SectionDivider()
                NavRow(
                    label = settingsDestinationLabel(SettingsDestination.ABOUT),
                    // SET-PFIX-P3 (F5) — native parity: the About row carries a descriptive subtitle
                    // "App information and updates. Contact us on social media" (native
                    // `app_information_and_updates_contact_us_on_social_media`, native
                    // SettingsScreen.kt:301). The prior rework rendered the About row label-only; the
                    // subtitle is restored via the new `app_information_and_updates` key.
                    description = stringResource(Res.string.app_information_and_updates),
                    onClick = { onIntent(SettingsIntent.OnNavigate(SettingsDestination.ABOUT)) },
                    // SET-PFIX-01 — native Outlined.Info icon (native SettingsScreen.kt:302).
                    leadingIcon = { RowIcon(Icons.Outlined.Info) },
                )
                // Phase 7.x.settings.help — inert placeholder row, mirrors legacy
                // `SettingsScreen.kt:350-353` SettingsNavigationItem(title = Res.string.help)
                // which is rendered with no `onClick` (no destination wired). Null onClick =>
                // NavRow renders without a clickable modifier (no ripple, no tap response).
                SectionDivider()
                NavRow(
                    label = stringResource(Res.string.help),
                    onClick = null,
                    // SET-PFIX-01 — native AutoMirrored.Outlined.Help icon (native
                    // SettingsScreen.kt:309).
                    leadingIcon = { RowIcon(Icons.AutoMirrored.Outlined.Help) },
                )
            }
        }
    }
}

/**
 * Redesign 2026-06 — grouped section card with an uppercase `.glabel` group label above it
 * (mockup `.glabel` + `.group`). The [label] is rendered as a small, letter-spaced, uppercase
 * caption in `onSurfaceVariant` (mockup: 12px / 800 / 1.2px tracking / muted), then the rounded
 * `surfaceContainerHigh` card below it.
 *
 * The prior rework rendered a caption-less card (native `ItemsGroup` parity); the approved redesign
 * reintroduces the group label, matching the mockup. The label text is a literal (no
 * "Appearance"/"General"/etc. string key exists and the task forbids adding one) — consistent with
 * the literal title in [SettingsHeader] and the `Text("YAMI")` literal in HomeHeader. Grouping,
 * order and row contents are unchanged.
 */
@Composable
private fun SectionCard(
    label: String,
    content: @Composable () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // SET-PFIX-P3 (F1) — the settings content sits 16.dp from the screen edge (spacing.lg),
            // so every grouped card + its label align to that inset.
            .padding(horizontal = spacing.lg),
    ) {
        // Redesign 2026-06 — uppercase group label above the card (mockup `.glabel`).
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = spacing.xs, top = spacing.sm, bottom = spacing.sm),
        )
        // GAP-SET-05 — surfaceContainerHigh container (redesign bumps corners 16.dp → 18.dp to
        // match the mockup `.group` radius).
        // Pure-black (AMOLED) fix: in pure-black mode KiraTheme blacks every surfaceContainer* slot,
        // so this card's container equals the background and the card becomes invisible. When that
        // collapse happens, outline a subtle 1.dp stroke (using `outline`, which pure-black leaves a
        // visible gray) so the card edge stays distinguishable. No border in any other theme.
        val scheme = MaterialTheme.colorScheme
        val cardBorder = if (scheme.surfaceContainerHigh == scheme.background) {
            BorderStroke(1.dp, scheme.outline.copy(alpha = 0.5f))
        } else {
            null
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            shape = RoundedCornerShape(18.dp),
            border = cardBorder,
        ) {
            content()
        }
    }
}

/**
 * Redesign 2026-06 — big bold title header at the top of the Settings list (mockup `.header`).
 * Large title in `onBackground`, mirroring HomeScreen's `HomeHeader` look. The decorative eyebrow
 * is intentionally omitted. The title resolves through `Res.string.settings_screen_title` (2026-07
 * audit — the earlier literal "Settings" rendered English on a primary tab for all 11 locales;
 * unlike HomeHeader's brand-name "YAMI", this is a translatable UI label). Horizontal inset
 * matches the section cards (spacing.lg = 16.dp).
 */
@Composable
private fun SettingsHeader() {
    val spacing = LocalSpacing.current
    Text(
        text = stringResource(Res.string.settings_screen_title),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 30.sp,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.lg, end = spacing.lg, top = spacing.sm, bottom = spacing.md),
    )
}

/**
 * Intra-card row divider (GAP-SET-05 / GAP-SET-10).
 *
 * Native's `ItemsGroup` separates inner rows with `Divider(color = background.copy(alpha = 0.8f))`,
 * a near-invisible hairline (not the default visible outline-variant divider). This shared helper
 * centralizes that idiom so every section card renders the same near-invisible separator.
 */
@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    // Phase 7.x.settings.descriptions — optional secondary description text under the title,
    // restoring the legacy `SwitchItem(description = ...)` subtitle parity. Null = title-only row
    // (matches the legacy Pure-black row which omitted its description).
    description: String? = null,
    // SET-PFIX-01 — native parity: every native SwitchItem renders a 24.dp leading Icon + a 16.dp
    // gap (native SwitchItem.kt:44-52). The icon slot supplies that glyph (`Icon(imageVector=…)`
    // for material icons / `Icon(painter=painterResource(…))` for the ported custom vectors); the
    // row tints it via the slot's own `tint` arg (defaulting to LocalContentColor / onSurface).
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(modifier = Modifier.width(spacing.lg))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            // GAP-SET-09 — native switch-row title 14.sp (titleMedium), description 12.sp
            // (bodySmall) at alpha 0.5; was bodyLarge (16.sp Bold) + plain onSurfaceVariant.
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
        // SET-PFIX-P3 (F10) — native parity: native SwitchItem styles the M2 Switch with
        // SwitchDefaults.colors(checkedThumb = primary, uncheckedThumb = surfaceVariant,
        // uncheckedTrack = onBackground @0.4 alpha) (native SwitchItem.kt:64-70). The prior rework
        // used the bare M3 Switch defaults. Mapped to the M3 SwitchColors API here: M3 has no
        // `uncheckedTrackAlpha` arg, so the 0.4 alpha is folded into `uncheckedTrackColor`. The M3
        // unchecked thumb sits on the surfaceVariant track with the default onSurfaceVariant border.
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                // M3 differs from native's M2 Switch: M2 auto-derived a lighter checked TRACK, but
                // M3 defaults checkedTrackColor to `primary`. The old code set checkedThumbColor =
                // primary too, so the active switch rendered as a solid primary pill with no visible
                // thumb. Use the standard M3 pairing (primary track + onPrimary thumb) so the thumb
                // reads as a light circle sliding on the colored track.
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            ),
        )
    }
}

/**
 * Phase 7.x.settings.cbz — "Compress Existing Downloads" action row inside the Kira Compressor
 * section. Mirrors the legacy SettingsScreen.kt:206-236 block: a title + descriptive paragraph +
 * a full-width Button that shows a spinner + "Converting..." while in flight, "Start Conversion"
 * otherwise. Labels/descriptions are verbatim from the legacy `compress_existing_downloads` /
 * `compress_all_..._some_time` / `converting` / `start_conversion` strings.
 */
@Composable
private fun CompressExistingRow(
    isCompressing: Boolean,
    onCompress: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(Res.string.compress_existing_downloads),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.compress_existing_downloads_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onCompress,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isCompressing,
        ) {
            if (isCompressing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(Res.string.converting))
            } else {
                Text(text = stringResource(Res.string.start_conversion))
            }
        }
    }
}

@Composable
private fun NavRow(
    label: String,
    onClick: (() -> Unit)?,
    // SET-PFIX-01 — native parity: every native SettingsNavigationItem renders a 24.dp leading
    // Icon + a 16.dp gap (native SettingsNavigationItem.kt:51-60). Optional slot — the Help row
    // (legacy parity) and the request-feature/bug row pass their icons here; null = no leading
    // glyph.
    leadingIcon: (@Composable () -> Unit)? = null,
    // P2-SET (F5) — native parity: native SettingsNavigationItem renders an optional secondary
    // description line under the title (native SettingsNavigationItem.kt:32,64-78 — 12.sp at
    // onBackground alpha 0.8). The request-feature/bug + about rows carry one in native; null =
    // title-only row.
    description: String? = null,
) {
    val spacing = LocalSpacing.current
    // Phase 7.x.settings.help — nullable `onClick` mirrors the legacy
    // `SettingsNavigationItem` (composeApp/.../components/SettingsNavigationItem.kt:44)
    // which accepts `onClick: (() -> Unit)? = null` and conditionally applies the
    // clickable modifier. Null onClick = inert placeholder row (no ripple, no tap).
    // Used by the Help row at the bottom of the Navigation section.
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(horizontal = spacing.md, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(modifier = Modifier.width(spacing.lg))
        }
        // GAP-SET-09 — native nav-row title 14.sp (titleMedium); was bodyLarge (16.sp Bold).
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
            }
        }
        // P2-SET (F3) — native trailing navigation chevron. Native SettingsNavigationItem defaults
        // `endIcon = Icons.AutoMirrored.Filled.KeyboardArrowRight`, tinted onBackground, rendered at
        // the row's end on EVERY nav row (native SettingsNavigationItem.kt:35,80-86). Auto-mirrored
        // for RTL. The prior rework omitted it entirely, losing the navigational affordance; restored
        // here to match the source of truth (shown even on the inert Help row, which native renders
        // via the same SettingsNavigationItem with the default endIcon).
        NavChevron()
    }
}

@Composable
private fun ReadingModeRow(
    onClick: () -> Unit,
    // SET-PFIX-01 — native parity: the "Default Reading Mode" nav row carries the ic_reader_setting
    // 24.dp leading glyph + 16.dp gap (native SettingsScreen.kt:258-263).
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.md, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(modifier = Modifier.width(spacing.lg))
        }
        // SET-PFIX-P3 (F7) — native parity: native renders the reading-mode entry as a plain
        // SettingsNavigationItem(default_reading_mode = "Default Reading Mode") with the chevron and
        // NO subtitle (native SettingsScreen.kt:258-263) — tapping opens the picker dialog. The prior
        // rework used title `reading_mode` ("Reading mode") plus a current-mode subtitle (e.g.
        // "Default" / "RTL") that native does not show. Title now resolves the new
        // `default_reading_mode` key and the current-mode subtitle is dropped to match the source of
        // truth (the staged mode is still visible/selectable inside the picker dialog).
        Text(
            text = stringResource(Res.string.default_reading_mode),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // P2-SET (F3) — native trailing chevron (native renders the reading-mode row via
        // SettingsNavigationItem with the default endIcon, SettingsScreen.kt:258-263).
        NavChevron()
    }
}

@Composable
private fun CacheRow(
    cacheSizeBytes: Long?,
    isClearing: Boolean,
    onClick: () -> Unit,
    // SET-PFIX-01 — native parity: the "Clear cache" row carries the cache_cleaner 24.dp leading
    // glyph + 16.dp gap (native SettingsScreen.kt:284-288).
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isClearing, onClick = onClick)
            .padding(horizontal = spacing.md, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(modifier = Modifier.width(spacing.lg))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            // GAP-SET-09 — clear-cache row title 14.sp (titleMedium) to match the other Settings
            // rows (ToggleRow / NavRow / ReadingModeRow); was bodyLarge (16.sp Bold).
            // SET-PFIX-P3 (F3) — native parity: the row title is "Clear Chapter Cache" (native
            // `clear_cache` = "Clear Chapter Cache", native strings.xml:133). The :ui base
            // `clear_cache` English value reads "Clear cache" (the non-English locale translations
            // already say the equivalent of "Clear Chapter Cache"); the base value can't be edited
            // here, so the title resolves the new `clear_chapter_cache` key carrying the native text.
            Text(
                text = stringResource(Res.string.clear_chapter_cache),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // SET-PFIX-P3 (F4) — native parity: native always renders the "Used: <size>" subtitle
            // (native SettingsScreen.kt:286). Before the first cache-size emission the rework
            // `cacheSizeBytes` is null (SettingsState default); show a "Calculating…" placeholder
            // for that brief window instead of hiding the line, so the subtitle never pops in late.
            // Typed wire (2026-07 backlog L15): the raw byte count is formatted here through the
            // localized size_* unit patterns (native parity, e.g. `1.23 Go` on French).
            Text(
                text = if (cacheSizeBytes != null) {
                    stringResource(Res.string.cached_size, formatByteSize(cacheSizeBytes))
                } else {
                    stringResource(Res.string.cache_calculating)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // P2-SET (F3) — native trailing chevron (native renders the clear-cache row via
        // SettingsNavigationItem with the default endIcon, SettingsScreen.kt:284-288). While the
        // clear is in flight the KMP-extra spinner (in-flight feedback, no native counterpart)
        // takes the trailing slot; the chevron returns once the clear completes.
        if (isClearing) {
            CircularProgressIndicator()
        } else {
            NavChevron()
        }
    }
}

/**
 * P2-SET (F3) — trailing navigation chevron for nav-style rows (NavRow / ReadingModeRow /
 * CacheRow). Mirrors native's `SettingsNavigationItem` default `endIcon = Icons.AutoMirrored.
 * Filled.KeyboardArrowRight` tinted `onBackground` (native SettingsNavigationItem.kt:35,80-86).
 * Auto-mirrored so the glyph points the correct way under RTL. The native item renders the icon
 * at its intrinsic size (no explicit `Modifier.size`), so this helper matches that.
 */
@Composable
private fun NavChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onBackground,
    )
}

/**
 * Redesign 2026-06 — leading icon "medallion" for a settings row (mockup `.si`): a ~38.dp rounded
 * (11.dp radius) coral-tinted container holding the row's coral-tinted glyph (~19.dp). Wraps both
 * [RowIcon] overloads so material-icons-extended vectors and the ported custom drawables share the
 * same medallion. The container uses `primary @ alpha 0.13` (mockup `--accent-soft`) and the glyph
 * uses `primary` (mockup `.si{color:var(--accent)}`).
 */
@Composable
private fun RowMedallion(glyph: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                shape = RoundedCornerShape(11.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        glyph()
    }
}

/**
 * SET-PFIX-01 — leading row glyph for an [ImageVector] (material-icons-extended), wrapped in the
 * redesign coral medallion ([RowMedallion]). The glyph sits at 19.dp inside the 38.dp container
 * (mockup `.si svg{19px}`). Tint defaults to `primary` so the icon reads coral on the soft-coral
 * medallion (mockup `.si{color:var(--accent)}`).
 */
@Composable
private fun RowIcon(
    imageVector: ImageVector,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    RowMedallion {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * SET-PFIX-01 — leading row glyph for a ported custom vector [DrawableResource] (incognito /
 * switchthemes / ic_day_night / ic_complaint / ic_reader_setting / cache_cleaner / ic_pluss18),
 * wrapped in the redesign coral medallion ([RowMedallion]). Drawn via `painterResource` + `Icon`,
 * which overlays the supplied [tint] (defaulting to `primary`) over the vector's own fill/stroke
 * colour — the glyph reads coral on the soft-coral medallion.
 */
@Composable
private fun RowIcon(
    drawable: DrawableResource,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    RowMedallion {
        Icon(
            painter = painterResource(drawable),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * Localized human-readable label for each [SettingsToggle] entry (Phase 11.ui.UP-3k) — resolves
 * through compose-resources `stringResource`. FOLLOW_SYSTEM_THEME / DARK_MODE / PURE_BLACK reuse
 * the legacy follow_system_theme / theme_dark / pure_black_mode_title keys (Arabic shipped); the
 * remaining two are new en-only keys.
 *
 * Defined here (in `:ui`) and not on the `:domain` [SettingsToggle] enum because labels are a
 * UI-only concern (a future picker with a different copy could change the label without touching
 * `:domain`).
 */
@Composable
private fun settingsToggleLabel(toggle: SettingsToggle): String = when (toggle) {
    SettingsToggle.DOWNLOADED_ONLY -> stringResource(Res.string.setting_downloaded_only)
    SettingsToggle.INCOGNITO -> stringResource(Res.string.setting_incognito)
    SettingsToggle.FOLLOW_SYSTEM_THEME -> stringResource(Res.string.follow_system_theme)
    SettingsToggle.DARK_MODE -> stringResource(Res.string.theme_dark)
    SettingsToggle.PURE_BLACK -> stringResource(Res.string.pure_black_mode_title)
    // Phase 7.x.settings.cbz — the Kira Compressor toggles render via their own dedicated rows
    // (with inline literal labels); these branches keep the `when` exhaustive without adding a new
    // string-resource key (en-only inline literals, matching the section copy).
    SettingsToggle.USE_CBZ_FORMAT -> stringResource(Res.string.use_kira_compressor)
    SettingsToggle.AUTO_CONVERT_TO_CBZ -> stringResource(Res.string.auto_convert_on_download)
    SettingsToggle.ALLOW_COMPRESSION_IN_LOW_POWER -> stringResource(Res.string.lpm_compress_toggle)
}

/**
 * Localized human-readable label for each [SettingsDestination] nav row (Phase 11.ui.UP-3k) —
 * resolves through compose-resources `stringResource`, reusing the statistics / about / what_s_new
 * / downloads keys (Arabic shipped) plus theme_screen_title and the new en-only language /
 * feedback_manager keys.
 *
 * Defined here (in `:ui`) and not on the `:presentation` [SettingsDestination] enum because
 * labels are a UI-only concern. The `:composeApp` route adapter uses the same enum values for
 * route mapping (`when (destination) { THEME -> Screen.ThemeRework; ... }`) — copy and routing
 * stay independent.
 */
@Composable
private fun settingsDestinationLabel(destination: SettingsDestination): String = when (destination) {
    SettingsDestination.SOURCE_MANAGEMENT -> stringResource(Res.string.sources_title)
    SettingsDestination.THEME -> stringResource(Res.string.theme_screen_title)
    SettingsDestination.STATISTICS -> stringResource(Res.string.statistics)
    SettingsDestination.LANGUAGE -> stringResource(Res.string.language)
    SettingsDestination.ABOUT -> stringResource(Res.string.about)
    // GAP-SET-06 — native nav-row label "Feedbacks & complaints" (was "Feedback Manager").
    SettingsDestination.COMPLAINT -> stringResource(Res.string.feedbacks_and_complaints)
    SettingsDestination.WHATSNEW -> stringResource(Res.string.what_s_new)
    SettingsDestination.DOWNLOADS -> stringResource(Res.string.downloads)
    SettingsDestination.BACKUP -> stringResource(Res.string.backup_title)
}

/**
 * [Saver] for the nullable [ComplaintType] staged in [FeedbackDialog], so the picked category
 * survives Android activity recreation alongside the body text (the dialog's open flag lives in
 * MVI state). Stored by enum `name` (a Bundle-safe `String`); `null` round-trips as the empty
 * string sentinel.
 */
private val complaintTypeSaver: Saver<ComplaintType?, String> = Saver(
    save = { it?.name ?: "" },
    restore = { name -> name.takeIf { it.isNotEmpty() }?.let { ComplaintType.valueOf(it) } },
)

/**
 * Localized human-readable label for each [ComplaintType] entry (Phase 11.ui.UP-3k) — resolves
 * through compose-resources `stringResource` against the new en-only complaint_* keys (pending
 * trusted Arabic).
 *
 * Defined here (in `:ui`) and not on the `:domain` [ComplaintType] enum because labels are a
 * UI-only concern (matches the [settingsToggleLabel] / [settingsDestinationLabel] helper posture
 * in this file).
 */
@Composable
private fun complaintTypeLabel(type: ComplaintType): String = when (type) {
    ComplaintType.TECHNICAL -> stringResource(Res.string.complaint_technical)
    ComplaintType.LANGUAGES -> stringResource(Res.string.complaint_languages)
    ComplaintType.SITES_ADD -> stringResource(Res.string.complaint_sites_add)
    ComplaintType.SITE_ERROR -> stringResource(Res.string.complaint_site_error)
    ComplaintType.FEATURES -> stringResource(Res.string.complaint_features)
    ComplaintType.CUSTOM -> stringResource(Res.string.complaint_other)
}

/**
 * Modal feedback dialog — category dropdown + free-form body field + Submit / Cancel buttons.
 *
 * Phase 7.x.settings.feedback rework. Mirrors the structure of the legacy
 * `componants/dialogs/FeedbackDialog.kt` (category dropdown + body field + Submit) but drops the
 * decorative SocialMediaRow footer (the rework `:ui` module deliberately omits the social-media
 * row dep; the core function is type+body+submit, same as the rework's other dialogs e.g.
 * [me.manga.kira.ui.complaint.ComplaintActionDialog]).
 *
 * **Local state, not MVI**: the dropdown selection ([selectedType]) and body text ([body]) live
 * in `rememberSaveable { mutableStateOf(...) }` in the composable, NOT in [SettingsState]. Matches
 * the §95 OnSubmitReply / OnSubmitEdit established posture — payloads ride along with the submit
 * intent rather than being mirrored into MVI state. Keeps the MVI state surface narrow
 * (2 fields: open + in-flight) and avoids per-keystroke intents. `rememberSaveable` (with
 * [complaintTypeSaver] for the enum) keeps the staged category + body across activity recreation,
 * since the dialog's open flag is MVI state that survives the same recreation.
 *
 * **Submit gating**: matches the legacy `submitEnabled = selectedTypeState != null &&
 * feedbackBody.length >= 5` exactly. Native-parity reconciliation (GAP-SET-11, 2026-05-31):
 * `SendComplaintUseCase` now gates on `MIN_BODY_LENGTH = 5` (was 8), so any body that passes
 * this UI gate (>= 5) also passes the use case — no more silent 5–7 char server-side rejection.
 *
 * **Dismissal gating during submission**: [DialogProperties] disables back-press and outside-tap
 * dismissal while [isSubmitting] is `true`; the Cancel button is also disabled. The VM-side
 * guard (in [SettingsViewModel.handle]'s OnDismissFeedbackDialog branch) is defence-in-depth
 * against any path that might still fire dismissal mid-submission.
 *
 * **Material 3 ExposedDropdownMenuBox**: the `.menuAnchor()` modifier extension is sourced from
 * `ExposedDropdownMenuBoxScope` (Compose-MP 1.11+); the deprecated parameterless overload is
 * intentionally tolerated here per the established `:ui` posture — the typed overload requires
 * `ExposedDropdownMenuAnchorType` (Material 3 1.4) which is not yet available across all rework
 * call sites. Deprecation warning is suppressed via `@Suppress("DEPRECATION")` to keep CI clean.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackDialog(
    // P2-SET (F9) — onSubmit carries the localized category display name as `subject` (resolved in
    // this composable via complaintTypeLabel), matching native's
    // `submit(it, it.getDisplayName(context), body, ...)`.
    isSubmitting: Boolean,
    onSubmit: (type: ComplaintType, subject: String, body: String) -> Unit,
    onDismiss: () -> Unit,
    // GAP-SET-12 — social-media row URL-open callback (forwarded to platform IntentLauncher).
    onOpenUrl: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    val scrollState = rememberScrollState()
    var selectedType by rememberSaveable(stateSaver = complaintTypeSaver) {
        mutableStateOf<ComplaintType?>(null)
    }
    var body by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    // P2-SET (F9) — localized display name for the staged category (null until one is picked),
    // resolved in composable scope so it can be passed as the complaint subject on submit.
    val selectedSubject = selectedType?.let { complaintTypeLabel(it) }
    val submitEnabled = selectedType != null && body.length >= 5 && !isSubmitting

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
            // GAP-SET-12 — title + "We'd love to hear from you" subtitle (native FeedbackDialog).
            Column {
                Text(
                    text = stringResource(Res.string.request_feature_bug),
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
                    text = stringResource(Res.string.category),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                @Suppress("DEPRECATION")
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (!isSubmitting) expanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedType?.let { complaintTypeLabel(it) } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        enabled = !isSubmitting,
                        label = { Text(stringResource(Res.string.select_a_category)) },
                        // SET-PFIX-P3 (F12) — native parity: native's category field renders a
                        // trailing ArrowDropDown glyph (native FeedbackDialog.kt:98-103). The prior
                        // rework relied on the bare ExposedDropdownMenuBox default with no trailing
                        // icon; restored here.
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(12.dp),
                        // SET-PFIX-P3 (F12) — native parity: custom focused/unfocused border colours
                        // (primary when focused, outline @0.5 alpha otherwise) (native
                        // FeedbackDialog.kt:108-111).
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        ),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        ComplaintType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = complaintTypeLabel(type),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                onClick = {
                                    selectedType = type
                                    expanded = false
                                },
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(Res.string.your_feedback),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { if (it.length <= 500) body = it },
                    enabled = !isSubmitting,
                    label = { Text(stringResource(Res.string.describe_issue_or_feature)) },
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

                // GAP-SET-12 — social-media section (divider + copy + prompt-response +
                // SocialMediaRow), matching the native FeedbackDialog footer.
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
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
                    Spacer(modifier = Modifier.height(spacing.xs))
                    KiraSocialMediaRow(onOpenUrl = onOpenUrl)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = submitEnabled,
                onClick = {
                    val type = selectedType
                    if (type != null) onSubmit(type, selectedSubject ?: type.name, body)
                },
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

/**
 * Localized human-readable label for each [ReadingMode] entry (Phase 11.ui.UP-3k) — resolves
 * through compose-resources `stringResource` against the new en-only reading_mode_* keys (pending
 * trusted Arabic).
 *
 * Defined here (in `:ui`) and not on the `:domain` [ReadingMode] enum because labels are a
 * UI-only concern (matches the [settingsToggleLabel] / [settingsDestinationLabel] /
 * [complaintTypeLabel] helper posture in this file).
 */
@Composable
private fun readingModeLabel(mode: ReadingMode): String = when (mode) {
    ReadingMode.DEFAULT -> stringResource(Res.string.reading_mode_default)
    ReadingMode.RIGHT_TO_LEFT -> stringResource(Res.string.reading_mode_rtl)
    ReadingMode.LEFT_TO_RIGHT -> stringResource(Res.string.reading_mode_ltr)
    ReadingMode.VERTICAL -> stringResource(Res.string.reading_mode_vertical)
    ReadingMode.WEBTOON -> stringResource(Res.string.reading_mode_webtoon)
    ReadingMode.CONTINUOUS_VERTICAL -> stringResource(Res.string.reading_mode_continuous)
}

/**
 * P2-SET (F8) — per-mode glyph for the reading-mode picker chips. Native `ReadingMode.kt` assigns
 * each entry a bespoke `ic_reader_*` drawable; those vectors are not ported into `:ui` here (that
 * would require adding drawables outside the `ui/settings` package), so each mode maps to the closest
 * material-icons-extended vector — identical mapping to the reader screen's already-ported
 * `ReadingModeDialog.readingModeIcon` (RDR-A) so the two pickers stay glyph-consistent:
 *  - DEFAULT / CONTINUOUS_VERTICAL → stacked panels ([Icons.Filled.ViewDay]) — native uses the
 *    same `ic_reader_continuous_vertical_24dp` drawable for both.
 *  - RIGHT_TO_LEFT / LEFT_TO_RIGHT → directional text-flow arrows.
 *  - VERTICAL → single portrait page; WEBTOON → tall device strip.
 *
 * Exhaustive `when` (compile error on a new entry), co-located with [readingModeLabel].
 */
private fun readingModeIcon(mode: ReadingMode): ImageVector = when (mode) {
    ReadingMode.DEFAULT -> Icons.Filled.ViewDay
    ReadingMode.RIGHT_TO_LEFT -> Icons.AutoMirrored.Filled.FormatTextdirectionRToL
    ReadingMode.LEFT_TO_RIGHT -> Icons.AutoMirrored.Filled.FormatTextdirectionLToR
    ReadingMode.VERTICAL -> Icons.Filled.StayCurrentPortrait
    ReadingMode.WEBTOON -> Icons.Filled.Smartphone
    ReadingMode.CONTINUOUS_VERTICAL -> Icons.Filled.ViewDay
}

/**
 * Modal reading-mode picker dialog — staged selection + explicit Revert/Apply footer
 * (GAP-SET-14).
 *
 * Native parity restore (legacy `reader/.../reading_mode_dialog/ReadingModeDialog.kt`): the
 * dialog holds a LOCAL `selected` ([androidx.compose.runtime.mutableStateOf]) seeded from
 * [currentMode]. Tapping a mode row only STAGES the selection (updates the radio); it does NOT
 * persist or close. The footer has two buttons:
 *  - **Revert** (`OutlinedButton`, [Res.string.but_revert]) — resets the staged selection and
 *    dismisses without committing (native: `selected = currentMode; onDismissRequest()`).
 *  - **Apply** (`Button` with a leading [Icons.Default.Check], [Res.string.but_apply]) — commits
 *    the staged selection via [onApply] (which the VM routes through
 *    [SettingsIntent.OnSelectReadingMode], persisting + closing the dialog).
 *
 * This replaces the prior single-tap-commit radio list, matching native's explicit two-step
 * commit semantics. The dialog surface uses `surfaceContainerHigh` + 16.dp corners + 8.dp tonal
 * elevation and the Apply button is colour-inverted (`onBackground` container / `background`
 * content) per the native dialog (GAP-SET-15, coupled to the Apply button's existence).
 *
 * **P2-SET (F8) — chip selector**: the body is a [FlowColumn] of [FilterChip]s (per-mode leading
 * icon + localized title), matching native's `ReadingModeChips` (native `ReadingModeChips.kt:34-101`
 * — FlowColumn of full-width FilterChips, 18.dp corners, 40.dp height, per-mode `iconRes` glyph).
 * The prior rework used a vertical RadioButton list. The per-mode glyph comes from
 * [readingModeIcon] (material-icons-extended vectors — the closest direction/orientation glyph for
 * each mode, mirroring the reader screen's already-ported `ReadingModeDialog`; native's custom
 * `ic_reader_*` drawables are not ported into `:ui` here to keep the change inside the `ui/settings` package).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ReadingModeDialog(
    currentMode: ReadingMode,
    onApply: (ReadingMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var selected by remember { mutableStateOf(currentMode) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        title = {
            Text(
                text = stringResource(Res.string.reading_mode),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                ),
            )
        },
        text = {
            // P2-SET (F8) — native ReadingModeChips: FlowColumn of full-width FilterChips, each with
            // a per-mode leading glyph + the localized title; the staged pick is highlighted via the
            // chip's `selected` state (native ReadingModeChips.kt:34-101).
            FlowColumn(modifier = Modifier.fillMaxWidth()) {
                ReadingMode.entries.forEach { mode ->
                    val isSelected = mode == selected
                    FilterChip(
                        selected = isSelected,
                        onClick = { selected = mode },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = readingModeIcon(mode),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                    },
                                )
                                Spacer(modifier = Modifier.width(spacing.sm))
                                Text(
                                    text = readingModeLabel(mode),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                                    },
                                )
                            }
                        },
                        // Native FilterChip colours (ReadingModeChips.kt:58-65): primary-filled when
                        // selected, faint inverseOnSurface container otherwise.
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f),
                        ),
                        // Native chip border (ReadingModeChips.kt:91-98): primary when selected, faint
                        // onSurface@12% otherwise.
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            },
                        ),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .height(40.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onApply(selected) },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onBackground,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.background,
                )
                Spacer(modifier = Modifier.width(spacing.xs))
                Text(
                    text = stringResource(Res.string.but_apply),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = {
                    selected = currentMode
                    onDismiss()
                },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground),
            ) {
                Text(
                    text = stringResource(Res.string.but_revert),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        },
    )
}

/**
 * CBZ conversion progress dialog (GAP-SET-16) — faithful port of the native `CbzConversionDialog`.
 *
 * Driven by the live [CbzConversionProgress] snapshot ([progress]) projected from the domain
 * progress Flow into [SettingsState.cbzConversion]. Renders three states, exactly as native:
 *  - **Converting** ([CbzConversionProgress.isConverting]) — Warning icon, "Converting to CBZ"
 *    title, the "please don't close the app" caution, a determinate [LinearProgressIndicator]
 *    (`convertedChapters / totalChapters`), the "Completed X / Y" + "Remaining Z" count rows, the
 *    "Current:" manga-title + chapter-number block (shown once a title is known), a spinner, and
 *    the Stop button. Dismissal is fully blocked (no-op `onDismissRequest`, back-press +
 *    outside-tap disabled).
 *  - **Error** ([CbzConversionProgress.error] non-null) — Error icon + the "Conversion Failed"
 *    line + a Close button.
 *  - **Success / Stopped** ([CbzConversionProgress.successMessage] non-null) — CheckCircle (or
 *    Warning when [CbzConversionProgress.wasStopped]) + the "Conversion Complete!" / "Conversion
 *    Stopped" title + the localized converted/remaining summary built from the count fields + a
 *    Done button.
 *
 * The terminal-state summary copy is built here (not in `:data`) from the structured count fields,
 * because the domain/data layers have no compose-resources access. The counts come straight from
 * the domain snapshot, so the displayed totals match native's.
 *
 * Visibility: when [progress] is the idle default (`isConverting = false`, both message fields
 * `null`) the composable renders nothing — same "nothing to show" rule as native's early-return.
 * [onStop] fires the Stop intent; [onDismiss] fires the dialog-dismiss intent (terminal states).
 */
@Composable
private fun CbzConversionDialog(
    progress: CbzConversionProgress,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val visible = progress.isConverting ||
        progress.error != null ||
        progress.successMessage != null
    if (!visible) return

    AlertDialog(
        // Dismissal blocked while converting (matches native); a terminal state routes the
        // back-press / outside-tap to the dismiss intent.
        onDismissRequest = { if (!progress.isConverting) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !progress.isConverting,
            dismissOnClickOutside = !progress.isConverting,
        ),
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            when {
                progress.error != null -> Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                progress.successMessage != null -> Icon(
                    imageVector = if (progress.wasStopped) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = if (progress.wasStopped) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                else -> Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        title = {
            val title = when {
                progress.error != null -> stringResource(Res.string.conversion_failed)
                progress.successMessage != null -> if (progress.wasStopped) {
                    stringResource(Res.string.conversion_stopped)
                } else {
                    stringResource(Res.string.conversion_complete_)
                }
                else -> stringResource(Res.string.converting_to_cbz)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                color = if (progress.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        text = {
            when {
                progress.error != null -> Text(
                    // The error body explains the failure rather than repeating the title; the domain
                    // `error` field is only a presence marker (the `:data` impl has no resources), so
                    // the failure copy is built here from a dedicated string.
                    text = stringResource(Res.string.cbz_conversion_error_body),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                progress.successMessage != null -> CbzConversionSummary(progress)
                else -> CbzConvertingBody(progress)
            }
        },
        confirmButton = {
            when {
                progress.error != null -> Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.close))
                }
                progress.successMessage != null -> Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.closure_reason_done))
                }
                else -> OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(Res.string.stop_conversion))
                }
            }
        },
    )
}

/**
 * GAP-SET-16 — the *converting* state body of [CbzConversionDialog]: the caution line, the
 * determinate progress bar, the "Completed X / Y" + "Remaining Z" count rows, the "Current:"
 * manga/chapter block (shown once a title is known), and a spinner. Faithful to native's
 * converting-state `Column` (native `CbzConversionDialog.kt:136-265`).
 */
@Composable
private fun CbzConvertingBody(progress: CbzConversionProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.please_don_t_close_the_app_until_conversion_is_complete),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(24.dp))

        val fraction = if (progress.totalChapters > 0) {
            progress.convertedChapters.toFloat() / progress.totalChapters.toFloat()
        } else {
            0f
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.completed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${progress.convertedChapters} / ${progress.totalChapters}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.remaining),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${progress.totalChapters - progress.convertedChapters}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        if (progress.currentMangaTitle.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = stringResource(Res.string.current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = progress.currentMangaTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(Res.string.chapter, progress.currentChapterNumber),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
        )
    }
}

/**
 * GAP-SET-16 — the terminal Success / Stopped summary text of [CbzConversionDialog], built from
 * the structured count fields. Mirrors native's `buildString` summaries: the Stopped path prepends
 * "Conversion stopped by user." then the converted + remaining counts (native
 * `CbzConversionViewModel.stopConversion()`); the Success path shows the converted count (native
 * `CbzConversionViewModel.startConversion()` completion message). The string lookups live here
 * because `:data` has no compose-resources access.
 */
@Composable
private fun CbzConversionSummary(progress: CbzConversionProgress) {
    val remaining = (progress.totalChapters - progress.convertedChapters).coerceAtLeast(0)
    val message = buildString {
        if (progress.wasStopped) {
            append(stringResource(Res.string.conversion_stopped_by_user))
            append('\n')
        }
        append(stringResource(Res.string.chapters_converted_successfully, progress.convertedChapters))
        if (progress.wasStopped && remaining > 0) {
            append('\n')
            append(stringResource(Res.string.chapters_remaining, remaining))
        }
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}
