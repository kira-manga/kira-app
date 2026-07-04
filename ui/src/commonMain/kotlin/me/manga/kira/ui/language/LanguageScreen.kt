package me.manga.kira.ui.language

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.language.Language
import me.manga.kira.ui.components.KiraSocialMediaRow
import me.manga.kira.presentation.language.LanguageEffect
import me.manga.kira.presentation.language.LanguageIntent
import me.manga.kira.presentation.language.LanguageState
import me.manga.kira.presentation.language.LanguageViewModel
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.at_least_n_characters
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.cancel
import me.manga.kira.ui.generated.resources.connect_with_us_in_social_media
import me.manga.kira.ui.generated.resources.enter_your_language
import me.manga.kira.ui.generated.resources.language_restart_hint
import me.manga.kira.ui.generated.resources.request_add_language
import me.manga.kira.ui.generated.resources.request_failed
import me.manga.kira.ui.generated.resources.request_language
import me.manga.kira.ui.generated.resources.request_language_prompt
import me.manga.kira.ui.generated.resources.request_submitted_successfully
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.generated.resources.select_language
import me.manga.kira.ui.generated.resources.selected
import me.manga.kira.ui.generated.resources.submit
import me.manga.kira.ui.generated.resources.we_d_love_to_hear_from_you
import me.manga.kira.ui.generated.resources.you_ll_receive_a_prompt_response
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Language selection screen — Compose entry point for the Language MVI slice.
 *
 * Phase 7.x.language rework foundation + Phase 7.x.language.request extension. Renders
 * [LanguageState] (the 11 supported languages + the currently-selected IETF code + the
 * Request-Language dialog state) and dispatches [LanguageIntent] variants to mutate the slice.
 *
 * **Visual parity vs the legacy `composeApp/.../features/language/ui/screens/
 * LanguageSelectionScreen.kt`**:
 *  - **Row shape preserved**: a Material 3 `LazyColumn` rendering one row per supported
 *    language. Each row shows the native endonym (display name, 14.sp onBackground) + the IETF
 *    code as a small caption (12.sp onBackground@0.8) matching native StatsItem typography; the
 *    selected row gets a trailing `Icons.Filled.Done` glyph tinted onBackground (parity-fix —
 *    matches native LanguageSelectionScreen.kt:107 / StatsItem.kt:51-56; was a primary-tinted
 *    `KiraIcons.Check`, itself a Phase 11.ui.UP-2b lift of an earlier "✓" text glyph). The legacy
 *    used `StatsItem` (a
 *    `composeApp/:shared`-only helper); the rework inlines a flat `LanguageRow` Composable to
 *    keep the screen pure-`:ui` (no `composeApp` dependencies).
 *  - **Request-Language entry restored** (Phase 7.x.language.request): a bottom row in the
 *    LazyColumn ("Request a language") opens a Material 3 `AlertDialog` with a multiline
 *    `OutlinedTextField` bound to [LanguageState.requestText]. Submit dispatches
 *    [LanguageIntent.OnSubmitRequest]; the VM hands off to
 *    [me.manga.kira.domain.usecase.feedback.SendLanguageRequestUseCase] and emits
 *    [LanguageEffect.RequestSubmitted] / [LanguageEffect.RequestFailed] on completion.
 *    The legacy screen used a `composeApp`-local `FeedbackDialog` with a category dropdown
 *    pre-set to `ComplaintType.LANGUAGES`; the rework dialog omits the dropdown because the
 *    `:data` impl hardcodes `subject = "Languages"` (single-purpose flow). Validation
 *    threshold (5 chars, [MIN_REQUEST_LENGTH]) matches the native `FeedbackDialog` submit
 *    gate (`feedbackBody.length >= 5`) and the current `SendComplaintUseCase` floor
 *    (`MIN_BODY_LENGTH = 5`).
 *  - **Snackbars** for submission feedback: [SnackbarHost] wired to a remembered
 *    [SnackbarHostState] that consumes effects via [LaunchedEffect]. RequestSubmitted →
 *    "Request submitted successfully"; RequestFailed → "Request failed". Same posture as
 *    LibraryScreen's effect-to-snackbar bridge.
 *  - **Bottom-bar visible**: same posture as Sources / History / Updates / Statistics / Theme —
 *    the Scaffold has no special bottom-bar suppression.
 *  - Chrome labels (top bar, Request-Language row + dialog, snackbars) resolve through
 *    compose-resources `stringResource(Res.string.*)` (Phase 11.ui.UP-3j) — reusing the legacy
 *    select_language / request_add_language / enter_your_language / request_* keys (Arabic
 *    shipped). The per-row native endonyms still come from the `:data` impl's
 *    `SUPPORTED_LANGUAGES` (single source of truth for the rework path — see
 *    `LanguageRepositoryImpl` KDoc); those are data values, not UI chrome.
 *  - Design tokens use [LocalSpacing] + Material 3 directly; legacy used ad-hoc `.dp` literals.
 *
 * **Row semantics**:
 *  - `onClick = dispatch(OnSelectLanguage(code))` — emits the selection intent; the VM
 *    forwards to the use case which triggers the persist-then-`applyApplicationLocale`
 *    pairing in the `:data` impl. The upstream pref flow re-emits, the trailing Done-icon
 *    moves to the new row reactively.
 *  - Re-tapping the currently-selected row is a no-op at the DataStore level (equal-value
 *    writes short-circuit).
 *
 * **Loading state**: a centered `CircularProgressIndicator` while `isLoading == true` —
 * covers the gap between subscription and first emission from the upstream pref flow.
 *
 * Constructor takes the [LanguageViewModel] directly (not the route's `NavController`) — the
 * route adapter in `:composeApp` is responsible for VM resolution, keeping the screen
 * nav-host-agnostic. Same posture as
 * [me.manga.kira.ui.sources.SourcesScreen] /
 * [me.manga.kira.ui.themepicker.ThemeScreen] (also terminal screens with no nav callbacks).
 *
 * **SRP (contract §6)**: owns rendering + intent dispatch + effect-to-snackbar bridging.
 * The Request-Language dialog body is a private composable in this file (it has no other
 * call sites). The Snackbar message text resolves via `stringResource` in composable scope
 * (UP-3j) and is captured into vals before the effect collector; state only carries the
 * dialog-visibility + submitting flags + body text.
 *
 * **Stateless inner [LanguageScreenContent]** for preview / test substitution — same
 * convention the other rework screens follow.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster3.staleKdocSweep.cascade,
 * Task #458, 2026-05-28): a stale citation into the §350-retired legacy
 * `composeApp/.../features/language/ui/screens/LanguageSelectionScreen.kt`
 * appears above:
 *  - Lines 54-55 (Visual parity preamble): "Visual parity vs the legacy
 *    `composeApp/.../features/language/ui/screens/LanguageSelectionScreen.kt`".
 * The legacy
 * `composeApp/.../features/language/ui/screens/LanguageSelectionScreen.kt`
 * was retired in Phase 9.x.language.retire (§350 sweep, commit `5c3acf0`
 * "(1/2): drop unreachable legacy LanguageSelectionScreen + VM +
 * LanguageOption"); verified by a filesystem check returning zero hits for
 * that path. The visual-parity bullets (LazyColumn row shape with native
 * endonym + IETF caption, "✓" now a real Material check Icon (UP-2b),
 * Request-Language entry restoration with single-purpose validation,
 * Snackbar-based submission feedback, bottom-bar visibility, inline literal
 * strings) all stand on their own merits — the rework `:ui` design
 * language, the `:data` impl's
 * `SUPPORTED_LANGUAGES` single-source-of-truth posture, the Phase 10 i18n
 * lift strategy, and the LocalSpacing + Material 3 design-token discipline
 * are documented inline above and independent of which legacy file
 * originally implemented the parity precedent. Original §253-era prose
 * preserved verbatim per the audit-trail-preservation convention — the
 * citation is historical record of the design lineage; the rework
 * LanguageScreen continues to render the language picker correctly through
 * the legacy retire.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(
    viewModel: LanguageViewModel,
    modifier: Modifier = Modifier,
    // GAP-LANG-01 — back IconButton in the top bar (native TopAppBarCom navigationIcon). The
    // route adapter wires this to `navController.safePopBackStack()`; default no-op keeps any
    // bottom-tab caller (where system back suffices) compiling unchanged.
    onBack: () -> Unit = {},
    // GAP-LANG-03 — Request-Language dialog social-media row. `:ui` is callback-only; the route
    // adapter forwards each URL to the platform `IntentLauncher.openUrl`. No-op default keeps
    // existing callers compiling unchanged.
    onOpenUrl: (String) -> Unit = {},
    // iOS-only: when the platform can't re-resolve strings in-session, show a "restart to apply"
    // hint. The route adapter passes `!LocalAppLocale.isLiveLocaleSwitchSupported`. Default false
    // keeps Android/Desktop (live switch) callers unchanged.
    restartHintVisible: Boolean = false,
) {
    val state by viewModel.state.collectAsState()
    LanguageScreenContent(
        state = state,
        effects = viewModel.effects,
        onIntent = viewModel::submit,
        modifier = modifier,
        onBack = onBack,
        onOpenUrl = onOpenUrl,
        restartHintVisible = restartHintVisible,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguageScreenContent(
    state: LanguageState,
    effects: Flow<LanguageEffect>,
    onIntent: (LanguageIntent) -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    restartHintVisible: Boolean = false,
) {
    val spacing = LocalSpacing.current
    // GAP-LANG-04 — first-run selected-language default. Native seeds the picker's selected value
    // with `Locale.getDefault().language` (LanguageSelectionScreen.kt:64-66) and its
    // `DataStoreHelper.languageFlow` falls back to the same when no preference is stored, so the
    // device language row shows a trailing Done-icon on first launch before the user picks one.
    // The KMP upstream pref flow defaults to "" (blank), so when the stored code is blank we mirror
    // native by falling back to the current platform locale's language code. Compose's
    // `Locale.current.language` returns the ISO-639 code ("en", "ar", ...) matching native's
    // `Locale.getDefault().language`.
    val effectiveSelectedCode = state.selectedCode.ifBlank { Locale.current.language }
    val snackbarHostState = remember { SnackbarHostState() }
    // Launch snackbars off the effect collector so showing one never blocks a later navigation
    // effect for the snackbar's duration (the user couldn't leave a screen while a snackbar showed).
    val scope = rememberCoroutineScope()
    // Snackbar copy resolved in composable scope — stringResource can't run inside the
    // effect-collector coroutine below.
    val submittedMessage = stringResource(Res.string.request_submitted_successfully)
    val failedMessage = stringResource(Res.string.request_failed)
    // GAP-LANG-05 — request-failed snackbar Retry action label (native LanguageSelectionScreen.kt
    // :146 `actionLabel = retry`). Resolved here in composable scope; the effect collector below
    // can't call stringResource.
    val retryLabel = stringResource(Res.string.retry)

    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is LanguageEffect.RequestSubmitted ->
                    // Native onSuccess uses SnackbarDuration.Short (LanguageSelectionScreen.kt:139).
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = submittedMessage,
                            duration = SnackbarDuration.Short,
                        )
                    }
                is LanguageEffect.RequestFailed ->
                    // GAP-LANG-05 — native onError surfaces a Retry action label with the longer
                    // SnackbarDuration.Long (LanguageSelectionScreen.kt:144-148). The failure path
                    // keeps the dialog open with the typed text preserved (LanguageState.requestText
                    // survives RequestFailed), so the Retry affordance routes the user back to the
                    // still-mounted dialog to resubmit — matching native, which likewise only shows
                    // the label and leaves the dialog/text intact rather than auto-resubmitting.
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = failedMessage,
                            actionLabel = retryLabel,
                            duration = SnackbarDuration.Long,
                        )
                    }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                // Title typography matches native `TopAppBarCom.kt:32-40` (used by
                // LanguageSelectionScreen.kt:76-77): titleLarge with an explicit 24.sp size +
                // FontWeight.Bold tinted onBackground, single line + ellipsis. The default
                // Material3 TopAppBar title is ~22.sp Medium — visibly lighter/smaller.
                title = {
                    Text(
                        text = stringResource(Res.string.select_language),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                            // Native tints the nav icon onBackground (LanguageSelectionScreen.kt:83).
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                // Native `TopAppBarCom.kt:29-31` sets the container color to background.
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.isLoading) {
            LoadingBox(innerPadding)
        } else {
            LanguageList(
                languages = state.languages,
                selectedCode = effectiveSelectedCode,
                restartHintVisible = restartHintVisible,
                onSelect = { code -> onIntent(LanguageIntent.OnSelectLanguage(code)) },
                onOpenRequestDialog = { onIntent(LanguageIntent.OnOpenRequestDialog) },
                contentPadding = innerPadding,
                // Native list horizontal padding is 24.dp (LanguageSelectionScreen.kt:99-101 —
                // LazyColumn `.padding(horizontal = 24.dp)`); spacing.xl == 24.dp. Was spacing.lg
                // (16.dp).
                paddingHorizontal = spacing.xl,
                paddingVertical = spacing.md,
            )
        }
    }

    if (state.requestDialogVisible) {
        LanguageRequestDialog(
            text = state.requestText,
            submitting = state.requestSubmitting,
            onTextChange = { onIntent(LanguageIntent.OnRequestTextChange(it)) },
            onSubmit = { onIntent(LanguageIntent.OnSubmitRequest) },
            onDismiss = { onIntent(LanguageIntent.OnDismissRequestDialog) },
            onOpenUrl = onOpenUrl,
        )
    }
}

@Composable
private fun LoadingBox(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * iOS-only "restart to apply" hint shown above the language list. On iOS the chosen language is
 * persisted and takes effect on the next launch (compose-resources reads `NSLocale`, which can't move
 * mid-session); this banner makes that behavior explicit instead of a silent no-op. Android/Desktop
 * switch live, so the route adapters pass `restartHintVisible = false` there.
 */
@Composable
private fun LanguageRestartHint(paddingHorizontal: androidx.compose.ui.unit.Dp) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = paddingHorizontal, vertical = spacing.sm),
    ) {
        Text(
            text = stringResource(Res.string.language_restart_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(spacing.md),
        )
    }
}

@Composable
private fun LanguageList(
    languages: List<Language>,
    selectedCode: String,
    restartHintVisible: Boolean,
    onSelect: (String) -> Unit,
    onOpenRequestDialog: () -> Unit,
    contentPadding: PaddingValues,
    paddingHorizontal: androidx.compose.ui.unit.Dp,
    paddingVertical: androidx.compose.ui.unit.Dp,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        if (restartHintVisible) {
            item(key = "__restart_hint__") {
                LanguageRestartHint(paddingHorizontal = paddingHorizontal)
            }
        }
        items(languages, key = { it.code }) { language ->
            LanguageRow(
                language = language,
                selected = language.code == selectedCode,
                onClick = { onSelect(language.code) },
                paddingHorizontal = paddingHorizontal,
                paddingVertical = paddingVertical,
            )
            // Native draws `Divider(Modifier.padding(vertical = 12.dp))` after every row
            // (LanguageSelectionScreen.kt:110); the divider was previously unpadded. The 24.dp
            // list horizontal padding is applied per-row, so the divider keeps the list's edge
            // inset by sitting outside the row's horizontal padding (full-bleed within the list).
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        }
        item(key = "__request_language__") {
            RequestLanguageRow(
                onClick = onOpenRequestDialog,
                paddingHorizontal = paddingHorizontal,
                paddingVertical = paddingVertical,
            )
            // Native draws a trailing divider after the request row too
            // (LanguageSelectionScreen.kt:121) — the request item previously had none.
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

/**
 * One language row — native endonym + IETF code caption + trailing Done-icon when selected.
 *
 * Tap fires [onClick] which lifts to a [LanguageIntent.OnSelectLanguage] dispatch in the
 * parent [LanguageList]. The whole row is clickable (matching the legacy `StatsItem`
 * behaviour); no per-element click targets.
 *
 * **Trailing-icon slot**: a fixed-size `Box` always reserves space for the icon so all rows
 * share a uniform height regardless of selection state (no row-height jump on re-tap).
 */
@Composable
private fun LanguageRow(
    language: Language,
    selected: Boolean,
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
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Typography matches native `StatsItem.kt:63-74`: title 14.sp onBackground, code
            // 12.sp onBackground@0.8 (Material3 bodyLarge/bodySmall were ~16/12.sp with the
            // onSurfaceVariant code tint — visibly larger title + a different caption shade).
            Text(
                text = language.displayName,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = language.code,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            )
        }
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                // Selected-row checkmark matches native `LanguageSelectionScreen.kt:107` +
                // `StatsItem.kt:51-56`: `Icons.Default.Done` tinted onBackground (rework token for
                // native's onBackground). KMP keeps the trailing placement (a deliberate flat-row
                // design choice) but aligns the glyph + tint to native's deliberate tokens — was a
                // primary-tinted Check glyph.
                Icon(
                    imageVector = Icons.Filled.Done,
                    contentDescription = stringResource(Res.string.selected),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/**
 * Bottom "Request a language" row — appended after the language list. Tap opens the
 * Request-Language dialog via [LanguageIntent.OnOpenRequestDialog]. Single-line, primary-
 * coloured to distinguish from the language rows.
 */
@Composable
private fun RequestLanguageRow(
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
    ) {
        // GAP-LANG-02 — leading Add icon for affordance parity (native StatsItem(icon = Add) on
        // the Request-language row). KMP keeps its cleaner flat row (the native trailing "0" count
        // is a StatsItem reuse artifact and deliberately not reproduced) but adds the Add glyph.
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        // The clickable bottom row uses `request_language` ("Request Language") to match native
        // `LanguageSelectionScreen.kt:115` (StatsItem title). The dialog header keeps
        // `request_add_language` separately (native LanguageSelectionScreen.kt:158).
        Text(
            text = stringResource(Res.string.request_language),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Request-Language dialog — Material 3 [AlertDialog] with a multiline body field.
 *
 * **Why no category dropdown** (unlike legacy `FeedbackDialog`): the rework `:data` impl
 * hardcodes `subject = "Languages"` (single-purpose entry), so the user never picks a
 * category. The legacy dropdown was a generic feedback affordance; this dialog is the
 * language-specific projection. (The native FeedbackDialog DOES show a category dropdown there
 * because it is the generic complaint surface pre-set to LANGUAGES; the rework drops only the
 * dropdown, keeping the rest of the native footer — see below.)
 *
 * **Feedback-surface parity** (GAP-LANG-03, native FeedbackDialog.kt:58-201): the dialog mirrors
 * the native request surface — a "We'd love to hear from you" subtitle under the title, a live
 * "N/500" character counter on the body field (capped at [MAX_REQUEST_LENGTH]), a
 * [HorizontalDivider], a "Connect with us in social media" label, the prompt-response copy, and a
 * [KiraSocialMediaRow] whose links open via the [onOpenUrl] callback (forwarded to the platform
 * `IntentLauncher.openUrl` by the route adapter).
 *
 * **Validation**: `submitEnabled = !submitting && text.length >= MIN_REQUEST_LENGTH` (5). The
 * threshold matches the native `FeedbackDialog` submit gate (`feedbackBody.length >= 5`) and the
 * current `SendComplaintUseCase` floor (`MIN_BODY_LENGTH = 5`). Below 5 chars, the helper text
 * shows "At least 5 characters" in the error colour; the Send button is disabled.
 *
 * **Submitting state**: when `submitting == true`, the OutlinedTextField stays interactive
 * (the VM is fire-and-forget; the dialog stays mounted until the effect completes), but the
 * Send button shows a [CircularProgressIndicator] in place of the label and is disabled to
 * prevent double-submission. Cancel remains tappable — closing mid-submit hides the dialog;
 * the success/failure snackbar still shows on the underlying screen via the effect channel.
 */
@Composable
private fun LanguageRequestDialog(
    text: String,
    submitting: Boolean,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    // GAP-LANG-03 — social-media row URL-open callback (forwarded to platform IntentLauncher by
    // the route adapter). Native FeedbackDialog.kt:200 renders SocialMediaRow() in the request
    // dialog footer.
    onOpenUrl: (String) -> Unit,
) {
    // Local echo (2026-07 audit): the field renders from dialog-local state so fast typing / IME
    // composition never races the per-intent VM round-trip (each keystroke used to launch a fresh
    // coroutine whose async updateState could drop/reorder characters). Every change is still
    // forwarded to the VM, which remains the submit source; the dialog re-seeds from VM state
    // whenever it (re)enters composition.
    var localText by remember { mutableStateOf(text) }
    val submitEnabled = !submitting && localText.length >= MIN_REQUEST_LENGTH
    AlertDialog(
        onDismissRequest = onDismiss,
        // GAP-LANG-03 — title + "We'd love to hear from you" subtitle (native FeedbackDialog.kt
        // :58-72 wraps the header + subtitle in a Column).
        title = {
            Column {
                Text(stringResource(Res.string.request_add_language))
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.we_d_love_to_hear_from_you),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                Text(
                    text = stringResource(Res.string.request_language_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = localText,
                    // GAP-LANG-03 — cap input at MAX_REQUEST_LENGTH (500) so the live "N/500"
                    // counter below is a true budget, matching the native FeedbackDialog counter
                    // semantics (`"${feedbackBody.length}/500"`, FeedbackDialog.kt:173-177) and the
                    // sibling Settings request dialog (`if (it.length <= 500) body = it`).
                    onValueChange = {
                        if (it.length <= MAX_REQUEST_LENGTH) {
                            localText = it
                            onTextChange(it)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    minLines = 4,
                    maxLines = 6,
                    label = { Text(stringResource(Res.string.enter_your_language)) },
                    isError = localText.isNotEmpty() && localText.length < MIN_REQUEST_LENGTH,
                    // GAP-LANG-03 — supporting row carries the min-length error (start) and the
                    // live "N/500" character counter (end), mirroring native FeedbackDialog.kt
                    // :159-179 (Row { error-or-spacer ; "${len}/500" }).
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (localText.isNotEmpty() && localText.length < MIN_REQUEST_LENGTH) {
                                    stringResource(Res.string.at_least_n_characters, MIN_REQUEST_LENGTH)
                                } else {
                                    ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = "${localText.length}/$MAX_REQUEST_LENGTH",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
                // GAP-LANG-03 — social-media footer (divider + "Connect with us in social media"
                // label + prompt-response copy + SocialMediaRow), matching native FeedbackDialog.kt
                // :184-201. Reuses the shared KiraSocialMediaRow (callback-only :ui) so the brand
                // links open via the route adapter's IntentLauncher.openUrl, same as the About and
                // Settings surfaces.
                Spacer(modifier = Modifier.height(20.dp))
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
                onClick = onSubmit,
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(Res.string.submit))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

// Parity (native FeedbackDialog.kt:49-51) — native gates submit on `feedbackBody.length >= 5`
// and shows the min-length error at `< 5` (FeedbackDialog.kt:158,164). The shared
// SendComplaintUseCase floor was also lowered to 5 (MIN_BODY_LENGTH=5), so 5 matches both native
// and the current backend, and the sibling Sources/Settings request dialogs gate at 5 too.
private const val MIN_REQUEST_LENGTH = 5

// GAP-LANG-03 — upper bound for the live character counter ("N/500"), matching the native
// FeedbackDialog counter literal (`"${feedbackBody.length}/500"`, FeedbackDialog.kt:174) and the
// sibling Settings request dialog's 500-char cap.
private const val MAX_REQUEST_LENGTH = 500
