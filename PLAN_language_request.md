# Phase 7.x.language.request — Request-Language dialog + Snackbar feedback (extension of Phase 7.x.language foundation)

## Context

Phase 7.x.language (Task #249, commits `18f9835` → `030c743`) landed the
Language picker foundation: `:domain` `LanguageRepository` + 3 use cases
+ `Language` model, `:data` strangler-fig over legacy `SettingsRepository`,
`:presentation` MVI surface (`LanguageState` + empty `LanguageIntent` /
`LanguageEffect` apart from `OnSelectLanguage`), `:ui` `LanguageScreen`
(11-row Material 3 LazyColumn), `:composeApp` Koin module + nav route. The
slice **explicitly deferred** the legacy screen's bottom-of-list "Request a
new language" entry — a `FeedbackDialog` driven by the cross-cutting
`ComplaintViewModel` from `:shared` with `SnackbarHost` success/error
feedback. This slice closes that deferral.

The legacy `LanguageSelectionScreen.kt` (composeApp/.../features/language/
ui/screens/) shows the dialog inline with the picker:

1. Bottom row: `StatsItem(title = stringResource(Res.string.request_language),
   icon = Icons.Default.Add, onClick = { showFeedbackDialog = true })`.
2. `FeedbackDialog(visible = showFeedbackDialog, selectedType =
   ComplaintType.LANGUAGES, onSubmit = { type, body ->
   complaintViewModel.submit(type, languagesComplaintSubject, body,
   onSuccess = { snackbar(submittedMessage) }, onError = {
   snackbar(failedMessage, retryLabel) }) }, onDismiss = { ... },
   headerText = stringResource(Res.string.request_add_language),
   textFieldText = stringResource(Res.string.enter_your_language))`.
3. `ComplaintViewModel.submit(type, subject, body, onSuccess, onError)` is
   callback-based: `viewModelScope.launch(IODispatcher) { try {
   sendComplaintUseCase(complaint); onSuccess(id) } catch (t) {
   onError(t) } }`.
4. `SendComplaintUseCase` requires `subject.isNotBlank()` and
   `body.length >= 8` — throws `IllegalArgumentException` otherwise.

Block-and-ask triggers (a)-(d) all NOT met:
- (a) no contract library blocker — Koin already binds `UserIdProvider`,
  `DeviceInfoProvider`, `SendComplaintUseCase` via `SharedModule`
- (b) no observable behaviour change — legacy route preserved verbatim;
  rework adds a parallel surface that writes to the same Firestore
- (c) no compile risk — three layers extend with OCP appends to existing
  sealed interfaces, no signature changes to existing callers
- (d) no unresolvable SOLID violation — strangler-fig is the established
  posture; FeedbackRepository in `:domain` is a clean ISP slice over
  legacy SendComplaintUseCase + UserIdProvider + DeviceInfoProvider

## Approach

### Strangler-fig boundary: `:data` reaches into 3 `:shared` types

The rework `:data` `FeedbackRepositoryImpl` reaches into:
- `:shared`/`SendComplaintUseCase` — the actual Firestore-bound write
- `:shared`/`UserIdProvider` — to populate `Complaint.userId`
- `:shared`/`DeviceInfoProvider` — to populate `Complaint.metadata`

Same pattern as `LanguageRepositoryImpl` (1 dep), `ReadingSession
RepositoryImpl` (1 dep), `ReadingStatisticsRepositoryImpl` (1 dep), but
fan-in is 3. This is acceptable: each dep has ONE responsibility (write
the complaint / identify the user / describe the device), and the impl
orchestrates the assembly — same orchestration the legacy
`ComplaintViewModel.submit` performs today (lines 52-72 of
`shared/.../complaint/viewmodel/ComplaintViewModel.kt`).

### MVI extension: OCP append on existing surface

The dialog is part of the language-picker experience, so its state lives
on `LanguageState` and its intents on `LanguageIntent`. No new feature
surface. This:
- Avoids over-segmentation (one Koin module, one VM, one screen)
- Demonstrates the OCP-friendly empty sealed interfaces from the
  foundation slice work as designed: `LanguageEffect` gains two variants,
  `LanguageIntent` gains four — all appends, no modifications

**`LanguageState` additions** (4 new fields):
- `requestDialogVisible: Boolean = false` — controls `FeedbackDialog`
  visibility
- `requestText: String = ""` — current text in the dialog's `TextField`
- `requestSubmitting: Boolean = false` — disables Submit button +
  shows progress indicator while the coroutine is in flight
- (existing fields unchanged: `isLoading`, `languages`, `selectedCode`)

**`LanguageIntent` additions** (4 new variants):
- `data object OnOpenRequestDialog` — emitted by the "Request a
  language" row tap
- `data object OnDismissRequestDialog` — emitted by dialog scrim tap /
  Cancel button
- `data class OnRequestTextChange(val text: String)` — emitted on each
  TextField keystroke
- `data object OnSubmitRequest` — emitted by the dialog's Send button

**`LanguageEffect` additions** (2 new variants):
- `data object RequestSubmitted` — VM emits on `Result.success`; `:ui`
  shows the success Snackbar
- `data object RequestFailed` — VM emits on `Result.failure`; `:ui`
  shows the error Snackbar with retry action label

The variants are `data object` (no payload — the messages are i18n strings
captured in composition; the effect signals the EVENT, not the message).

### Validation: trust the legacy `require` blocks

The legacy `SendComplaintUseCase.invoke` validates `subject.isNotBlank()`
and `body.length >= 8`. The rework's `:data` impl calls the legacy use
case directly — any `IllegalArgumentException` propagates and becomes a
`Result.failure(t)` in the wrapper. The VM emits `RequestFailed` on any
failure (validation or Firestore). UI-side pre-validation is deferred to
a future `.polish` sub-slice if needed (e.g., disabling Send when text
length < 8).

### Subject hardcoded to "Languages"

The legacy screen passes `languagesComplaintSubject =
ComplaintType.LANGUAGES.displayName()` — a localized lookup that resolves
to "Languages" in English. The rework hardcodes the literal "Languages"
in the `:data` impl until Phase 10 i18n lift. Same pattern as the
foundation slice's inline-literal "Language" TopAppBar title — defer the
`Res.string.*` work to Phase 10 to keep slice scope bounded.

### Snackbar hosting: lives in `:ui` LanguageScreen

The Scaffold's `snackbarHost` slot lives in `LanguageScreenContent`. A
`LaunchedEffect(viewModel)` collects `viewModel.effects` and dispatches
to `SnackbarHostState.showSnackbar`. The success/error messages are
captured as `String` values inline (currently English; Phase 10
re-points to `Res.string.request_submitted_successfully` /
`Res.string.request_failed`).

Alternative considered (Snackbar in route adapter / global host): rejected
because the language picker is the only consumer for these specific
snackbars, and the `:ui` module already owns the Scaffold. Keeping the
host with the screen mirrors the legacy posture and avoids cross-cutting
plumbing in `:composeApp`.

### `:ui` FeedbackDialog — new reusable component

A new `:ui/.../feedback/FeedbackDialog.kt` Composable, stateless. Takes:
- `visible: Boolean` — gate
- `headerText: String` — dialog title
- `textFieldLabel: String` — hint inside the TextField
- `text: String` — current value
- `submitting: Boolean` — disables Send + shows progress
- `onTextChange: (String) -> Unit`
- `onSubmit: () -> Unit`
- `onDismiss: () -> Unit`

Implemented as a Material 3 `AlertDialog` with a single
`OutlinedTextField` body and Send/Cancel buttons. **No** ComplaintType
dropdown — the language picker hardcodes the type (same posture as the
legacy screen passing `selectedType = ComplaintType.LANGUAGES`
unconditionally). Future complaint surfaces can reuse this composable
with a different `headerText` / `textFieldLabel`; the polymorphic type
dropdown would be a separate `:ui` component, not a parameter of this one
(YAGNI for the language case; SRP for the Composable's concern).

### `:data` impl wiring

```kotlin
class FeedbackRepositoryImpl(
    private val sendComplaint: SendComplaintUseCase,
    private val userIdProvider: UserIdProvider,
    private val deviceInfoProvider: DeviceInfoProvider,
) : FeedbackRepository {

    override suspend fun sendLanguageRequest(body: String): Result<Unit> =
        runCatching {
            val complaint = Complaint(
                userId = userIdProvider.getUserId(),
                type = ComplaintType.LANGUAGES,
                subject = LANGUAGES_SUBJECT,
                body = body,
                createdAt = Clock.System.now(),
                status = ComplaintStatus.OPEN,
                metadata = deviceInfoProvider.getDeviceMetadata(),
            )
            sendComplaint(complaint)
        }.map { Unit }  // discard the returned id — VM doesn't need it

    private companion object {
        // English literal — Phase 10 i18n lift re-points to Res.string.*
        const val LANGUAGES_SUBJECT = "Languages"
    }
}
```

The `runCatching {}` wraps the orchestration; `IllegalArgumentException`
from `require` blocks AND any Firestore throw both surface as
`Result.failure`. The use case forwards verbatim.

## Commit roadmap

Seven commits, all ≤5 files per the standing cap. Build gates after every
source commit (Android + iOS Arm64 + iOS Simulator Arm64; Desktop for
commits 5-6 because the `:ui` Scaffold/FeedbackDialog touches commonMain
which links into the Desktop entry point).

1. **Plan commit** — `PLAN_language_request.md` only (1 file).

2. **`:domain` foundation** — 2 new files:
   - `domain/.../repository/FeedbackRepository.kt` — interface with
     `suspend fun sendLanguageRequest(body: String): Result<Unit>`. KDoc
     covers ISP (one method — language-request specifically; not a
     general feedback surface), strangler-fig posture, lifecycle expectation.
   - `domain/.../usecase/feedback/SendLanguageRequestUseCase.kt` — class
     wrapping `FeedbackRepository.sendLanguageRequest(body)`. Pure
     pass-through; the impl handles assembly + validation propagation.

3. **`:data` strangler-fig impl** — 1 new file:
   - `data/.../repository/FeedbackRepositoryImpl.kt` — assembles the
     `Complaint`, calls legacy `SendComplaintUseCase`, wraps in
     `Result<Unit>`. KDoc mirrors `LanguageRepositoryImpl` / `ReadingSession
     RepositoryImpl` posture. 3 :shared deps documented (fan-in is OK because
     each dep is single-responsibility).

4. **`:presentation` MVI extension** — 4 modified files:
   - `presentation/.../language/LanguageState.kt` — add 3 fields with
     `false` / `""` / `false` defaults. KDoc updated to cover the new fields.
   - `presentation/.../language/LanguageIntent.kt` — add 4 variants (3
     `data object` + 1 `data class`). KDoc updated; remove the
     "extensibility hook" example comment now that it's no longer
     hypothetical.
   - `presentation/.../language/LanguageEffect.kt` — add 2 `data object`
     variants. KDoc updated; remove the "empty sealed interface" rationale
     (replaced with the actual semantics).
   - `presentation/.../language/LanguageViewModel.kt` — inject
     `SendLanguageRequestUseCase`. Add 4 new branches to the `handle`
     `when`. `OnSubmitRequest` launches a coroutine that sets
     `requestSubmitting = true`, calls the use case, emits effect on
     fold, sets `requestSubmitting = false` + clears dialog state on
     success.

5. **`:ui` FeedbackDialog + screen wiring** — 2 files:
   - `ui/.../feedback/FeedbackDialog.kt` (NEW) — Material 3 `AlertDialog`
     with `OutlinedTextField` body + Send/Cancel buttons. Submit button
     disabled when `submitting == true`. KDoc covers parameters and the
     stateless / reusable posture.
   - `ui/.../language/LanguageScreen.kt` (MODIFIED) — add the bottom
     "Request a language" row inside `LanguageList`, render
     `FeedbackDialog` outside the `LazyColumn` (still inside the Scaffold),
     add `SnackbarHost` to the Scaffold's slot, add `LaunchedEffect`
     collecting `viewModel.effects` and showing snackbars. The
     `LanguageScreenContent` signature gains `effects: Flow<LanguageEffect>`
     so the stateless preview/test variant stays substitutable.

6. **`:composeApp` Koin** — 1 modified file:
   - `composeApp/.../di/LanguageReworkModule.kt` (MODIFIED) — add
     `single<FeedbackRepository> { FeedbackRepositoryImpl(get(), get(),
     get()) }` + `factory { SendLanguageRequestUseCase(get()) }`. Update
     the `viewModel { LanguageViewModel(get(), get(), get()) }` line to
     `viewModel { LanguageViewModel(get(), get(), get(), get()) }`
     (4 deps now).

   No nav-route changes needed: the Snackbar host lives in `:ui` so the
   route adapter stays the thinnest possible (VM resolve + screen call).

7. **Close-out** — 2 modified files:
   - `ARCHITECTURE.md` — new `## §93 — Phase 7.x.language.request` with
     subsections covering strategy, layer-by-layer surfaces, MVI shape
     rationale (OCP append on existing surface), strangler-fig boundary
     (3 :shared deps), files added/modified, deferrals (i18n lift, UI
     pre-validation, ComplaintType dropdown / reusable feedback surface).
   - `SOLID_AUDIT.md` — Phase 7.x.language.request entry with per-file
     SOLID 10-point checklists for all 7 new files + 4 modified files
     + end-of-slice verdict.

## Critical files

### New

- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/FeedbackRepository.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/feedback/SendLanguageRequestUseCase.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/FeedbackRepositoryImpl.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/feedback/FeedbackDialog.kt`
- `PLAN_language_request.md`

### Modified

- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/language/LanguageState.kt` — add 3 fields
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/language/LanguageIntent.kt` — add 4 variants
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/language/LanguageEffect.kt` — add 2 variants
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/language/LanguageViewModel.kt` — inject SendLanguageRequestUseCase + 4 handle branches
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/language/LanguageScreen.kt` — add Request row + FeedbackDialog + SnackbarHost + LaunchedEffect
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/LanguageReworkModule.kt` — add FeedbackRepository binding + use case factory + 4-arg VM resolution
- `ARCHITECTURE.md` — append §93
- `SOLID_AUDIT.md` — append Phase 7.x.language.request entry

### Untouched (verify by read, not modified)

- `shared/.../complaint/usecase/SendComplaintUseCase.kt` — legacy facade; the
  `:data` impl calls it but does not modify it
- `shared/.../complaint/model/Complaint.kt` — legacy data class; constructed
  in the `:data` impl
- `shared/.../complaint/model/ComplaintType.kt` — legacy enum; the impl
  pins `ComplaintType.LANGUAGES`
- `shared/.../domain/auth/UserIdProvider.kt` — interface; consumed by impl
- `shared/.../domain/device/DeviceInfoProvider.kt` — interface; consumed
- `composeApp/.../features/language/ui/screens/LanguageSelectionScreen.kt`
  — legacy screen; preserved for the legacy route
- `composeApp/.../navigation/routes/LanguageReworkScreenRoute.kt` —
  rework route adapter; stays the thinnest possible

## Reuse

- **Strangler-fig posture**: lifted from `LanguageRepositoryImpl` (Phase
  7.x.language) and `ReadingSessionRepositoryImpl` (Phase 6.4.x.statistics).
  Same `:shared` reach, same `single` Koin lifecycle, same KDoc structure.
- **MVI base class**: extends the existing `MviViewModel<S, I, E>` from
  the foundation slice — no signature changes, just one more use case in
  the constructor.
- **AlertDialog primitive**: Material 3 `AlertDialog` already used in
  the rework Details slice (`AdultConfirmationDialog`). Same pattern.
- **LaunchedEffect-collect-effects-flow**: same pattern used in the
  Reader's `ReaderEffect` collection inside `ReaderScreen` (Phase
  7.x.reader.modelayout.openwebview).
- **SnackbarHost + SnackbarHostState**: a standard Material 3 surface
  available from the existing `:ui` Material 3 dependency.

## Verification

After every source commit (steps 2-6):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile
  gate. Required for every commit because `:domain`/`:data`/
  `:presentation`/`:ui` changes propagate through to `:composeApp/commonMain`.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64. Required
  because commonMain changes link into the iOS framework.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64. Same rationale.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop. Required for
  commits 4-5 (`:presentation` + `:ui`) because the modified files are
  in commonMain and link into the Desktop entry point.

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Build with the rework debug flag, navigate to `Screen.LanguageRework`.
- Verify the new "Request a language" row appears at the bottom of the
  list with the "+" prefix glyph.
- Tap the row — `FeedbackDialog` opens with empty TextField + disabled
  Send button (when length < 8) or enabled Send.
- Type a 8+ char body, tap Send — Snackbar shows "Request submitted
  successfully", dialog dismisses, TextField clears.
- Force a failure (e.g., airplane mode), retry — Snackbar shows "Request
  failed" with "Retry" action label.
- Verify the legacy `Screen.LanguageScreen` route still works identically
  (same persistence wire, same locale switch).

Edge cases to mentally model during implementation:

- **Dialog dismissal during in-flight submit**: if the user taps scrim
  while `requestSubmitting = true`, the coroutine completes and emits an
  effect; the dialog is already closed but the Snackbar still shows on
  the underlying screen. Acceptable. The VM's
  `OnDismissRequestDialog` clears `requestDialogVisible` but NOT
  `requestSubmitting` — the in-flight launch finishes on its own.
- **Rotation / config change**: the VM survives via Koin's `viewModel
  Store`-aware binding; the dialog state is preserved on the StateFlow.
  The Channel-backed `effects` flow has UNLIMITED capacity so any in-flight
  effect emitted during the rotation gap is still delivered when the new
  composition re-collects.
- **Re-tap during in-flight**: `OnSubmitRequest` while
  `requestSubmitting = true` is a no-op guard in the VM — same posture as
  the Details slice's `onRetry` re-entrance guard.

## Deferrals

- **No i18n lift** — Snackbar messages, dialog title, TextField label,
  Send/Cancel button text, "Request a language" row text all stay as
  inline English literals. Phase 10 i18n lift re-points to
  `Res.string.*` in one pass across legacy + rework consumers.
- **No UI pre-validation** — the Send button is enabled regardless of
  text length. Validation propagates from the legacy `require(body.length
  >= 8)` block as a `Result.failure` and shows the "Request failed"
  snackbar. A future `.polish` sub-slice can disable Send when length < 8.
- **No ComplaintType dropdown** — the dialog hardcodes
  `ComplaintType.LANGUAGES`. A reusable feedback surface (e.g., a
  general "Send feedback" dialog with type selection) is a separate
  future slice — `Phase 7.x.complaint` for the full standalone complaint
  screen port.
- **No Snackbar retry action wiring** — the legacy passes
  `actionLabel = retryLabel` but doesn't wire a retry callback. The
  rework matches: shows the action label but no callback. A future
  `.polish` sub-slice can wire the retry to re-emit `OnSubmitRequest`
  with the persisted last-body.
- **No body persistence across dialog reopen** — closing the dialog
  clears `requestText`. The legacy clears too. A future `.polish` could
  keep the body until explicit submit success.
- **No nav graph route-swap** — legacy `Screen.LanguageScreen` stays
  bound. Phase 9.x route-swap is its own slice.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: Each new file has one rule. `FeedbackRepository` =
  "language-request submission gate". `SendLanguageRequestUseCase` =
  "thin pass-through orchestration". `FeedbackRepositoryImpl` =
  "assemble the Complaint payload + delegate to legacy use case". The
  `FeedbackDialog` Composable = "render an AlertDialog with TextField +
  Send/Cancel". Each modified file gains ONE new responsibility (the
  request flow); existing responsibilities preserved.
- **OCP**: `LanguageState` / `LanguageIntent` / `LanguageEffect` extend
  via appends. No existing variants removed or modified. The foundation
  slice's "empty sealed interface for OCP" rationale pays off here —
  this slice is purely additive on those types.
- **LSP**: `FeedbackRepositoryImpl` is substitutable for
  `FeedbackRepository`; the strangler-fig wrap returns a `Result<Unit>`
  whose `success`/`failure` semantics are identical to what any future
  impl would provide.
- **ISP**: `FeedbackRepository` declares ONE method
  (`sendLanguageRequest`). If a future complaint surface needs
  `sendSiteError(...)` etc., it gets a sibling repository, not a fatter
  interface. This is intentional — the language picker doesn't care
  about complaint types it doesn't issue.
- **DIP**: `:presentation` depends on the `:domain` use case; `:data`
  depends on the legacy `:shared` use case + interfaces. No leak of
  legacy types into `:presentation` / `:ui`.
- **Layer boundary**: changes touch `:domain` (2 new files), `:data`
  (1 new file), `:presentation` (4 modified files), `:ui` (1 new file +
  1 modified file), `:composeApp` (1 modified file + 2 close-out docs).
  No cross-layer reach beyond the strangler-fig `:data` → `:shared`
  permitted boundary.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. All flow
  operations are pure `kotlinx.coroutines`. The `runCatching {}` block
  catches `Throwable` — same posture as the legacy
  `ComplaintViewModel.submit`'s `try { ... } catch (t: Throwable) { ... }`.
  The legacy already accepts this; the rework matches verbatim.
- **MVI contract**: extends an existing slice's surfaces additively.
  Existing `OnSelectLanguage` semantics unchanged; new intents handled
  in independent `when` branches. No reducer collisions.
- **Strangler-fig**: ONE NEW `:data` → `:shared` reach (the
  `FeedbackRepositoryImpl` constructor injection of `SendComplaintUseCase`
  + `UserIdProvider` + `DeviceInfoProvider`). Same posture, same
  boundary as the foundation slice — just a wider fan-in into 3 :shared
  types instead of 1, because the legacy `ComplaintViewModel.submit`
  performs 3-dep orchestration.
- **Load-bearing fixes preserved**: this slice does NOT touch the Coil
  ImageLoader, Reader's per-request listener, Reader's decoder hints,
  OkHttp interceptor, AVIF decoder, HighQualitySkiaImageDecoder, or
  `:platform` — Language Request is pure Firestore-bound complaint
  submission. No load-bearing risk.
