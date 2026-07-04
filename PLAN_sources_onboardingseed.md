# Phase 7.x.sources.onboardingseed — gap-lift adding default-language source auto-seeding to the rework Sources stack

## Context

`Phase 7.x.reposettings.swap` (Task #285) routed `Screen.RepoSettings` to the
rework `SourcesScreen` — but the OTHER onboarding consumer of Sources, the
legacy `Screen.Sources` step (Welcome → Theme → **Sources** → RepoSettings →
Library), still binds to the legacy `presentation/features/onboarding/sources/
SourcesScreen.kt`. Swapping that route is the natural next slice toward the
Phase 9.x legacy retirement sweep — but the legacy onboarding Sources screen
does ONE thing the rework Sources stack cannot reproduce today:

```kotlin
// legacy composeApp/.../onboarding/sources/SourcesScreen.kt:124-127
LaunchedEffect(userLanguageCode) {
    val tag = userLanguageCode.ifBlank { "en" }.uppercase()
    repoSettingsViewModel.setLanguageEnabledDefault("($tag)", true)
}
```

`setLanguageEnabledDefault` lives on the legacy `RepoSettingsViewModel`
(`shared/.../repo_settings/ui/viewmodel/RepoSettingsViewModel.kt:93-107`)
with this semantic:

```kotlin
fun setLanguageEnabledDefault(language: String, enabled: Boolean) {
    val all = sourcesRepository.allSources.first()
    var hits = all.filter { it.language == language }
    if (hits.isEmpty()) hits = all.filter { it.language == "(EN)" } // EN fallback
    hits.forEach { sourcesRepository.enableDisAbleSource(it.name, enabled) }
}
```

The user lands on onboarding step 3 (Sources) for the first time with EVERY
source disabled (the legacy `saveSources` seed sets `isEnabled = false`); the
`LaunchedEffect` auto-enables every source whose `language` column matches the
user's locale tag, falling back to English if the locale has no native sources
yet. Without this, the user lands on a screen with zero sources enabled and
must manually toggle the right ones before content appears anywhere downstream.

The rework `SourcesRepository` (`:domain`) exposes only `setLanguageEnabled
(language, enabled)` — no fallback variant. The rework `SourcesViewModel`
(`:presentation`) has `OnToggleSource` / `OnToggleLanguage` intents — neither
encodes the "with EN fallback" policy. The rework `:ui` `SourcesScreen` takes
no language code and fires no auto-seed.

This slice closes the gap so a future `Phase 7.x.sources.swap` is a thin
route-rewrite (analogous to how §136/§137 unblocked §138 for Theme).

Block-and-ask triggers (a)-(d) all NOT met:
 - (a) no contract library blocker — pure Kotlin code in existing modules
 - (b) no observable behavior change at this slice — the new capability is
   added but the route adapters that would invoke it are unchanged
 - (c) no compile-break risk — additive interface method + additive intent
   variant; both existing call sites (`Screen.SourcesRework` route +
   `Screen.RepoSettings` route) keep working because the new ui param defaults
   to `null`
 - (d) no SOLID violation forced — policy (EN fallback) lives in the use case
   not the repository; the repository owns mechanism only

## Approach

The semantic boundary follows the existing rework `setLanguageEnabled` shape:

- **Repository (`:domain` interface)**: owns the mechanism. New method
  `setLanguageEnabledWithFallback(primary: String, fallback: String, enabled:
  Boolean)` — snapshot once, filter by `primary`, if empty fall back to
  `fallback`, fan out per-source enables. No magic strings, no policy.

- **Use case (`:domain`)**: owns the policy. `EnableDefaultLanguageSources
  UseCase` accepts a `languageTag: String` (e.g. "en", "fr"), formats it as
  `"(${tag.uppercase()})"` (matching the legacy's parenthesised tag
  convention from `saveSources`), and invokes the repository with `fallback =
  "(EN)"`, `enabled = true`. The blank-tag → "en" coercion lives here too —
  the legacy applies the same coercion inline; the use case becomes the
  canonical owner of that policy.

- **Presentation (`:presentation`)**: adds an `OnSeedDefaultLanguage
  (languageTag: String)` intent. The `SourcesViewModel.handle` branch
  invokes the use case in `viewModelScope.launch`. No state field — the
  caller's `LaunchedEffect(key)` is the dedup mechanism, matching the legacy
  `LaunchedEffect(userLanguageCode)` posture.

- **UI (`:ui`)**: adds an optional `onboardingLanguageTag: String? = null`
  param to `SourcesScreen`. When non-null, a `LaunchedEffect(onboarding
  LanguageTag) { viewModel.submit(OnSeedDefaultLanguage(tag)) }` fires the
  seed. When null (the existing `Screen.SourcesRework` + post-§285
  `Screen.RepoSettings` consumers), no seed fires — preserves existing
  behavior verbatim. The `LaunchedEffect` key is the language tag itself,
  matching the legacy `LaunchedEffect(userLanguageCode)` re-fire-on-locale-
  change semantic.

- **Composition root**: `SourcesReworkModule` adds the new use case as
  `factory` and updates the `viewModel { SourcesViewModel(get(), get(), get(),
  get(), get()) }` arg count from 4 to 5.

No route adapter changes this slice — the wiring is end-to-end on the rework
stack, with the new param defaulting to `null` so the existing two routes
(`SourcesReworkScreenRoute` and `RepoSettingsScreenRoute`) keep compiling
without modification. The future `Phase 7.x.sources.swap` will rewrite the
existing `SourcesScreenRoute.kt` to consume `DataStoreHelper.languageFlow`
and pass it to the rework screen.

### Strangler-fig boundary

The `:data` `setLanguageEnabledWithFallback` impl mirrors the existing
`setLanguageEnabled` impl (same `legacy.allSources.first()` snapshot, same
`legacy.enableDisAbleSource` fan-out) with one additional fallback filter pass.
The legacy `:shared` `SourcesRepository` facade is the cell of truth — same
strangler-fig posture as the rest of the Sources rework slice (§84 in
`ARCHITECTURE.md`).

### Why a new repository method (not extending `setLanguageEnabled`)

Adding an optional `fallback: String? = null` parameter to `setLanguageEnabled`
would conflate the two semantics ("enable this language" vs "enable this
language OR a backup"). ISP favors narrow, single-purpose methods. The
existing `setLanguageEnabled` is invoked by the per-language Switch handler
(no fallback is wanted there — the user explicitly toggled a specific
language). A separate `setLanguageEnabledWithFallback` keeps the per-language
Switch handler unchanged while the onboarding-only seed gets its own surface.

### MVI shape — transient seed intent, no state field

The seed is a "do once, no UI state" command. Adding a `hasSeededDefault
Language: Boolean` state field would force the view to either persist it
across config changes (rememberSaveable in the view, against MVI) or burn
the seed-once invariant. The `LaunchedEffect(languageTag)` key-based dedup
matches the legacy posture exactly and keeps state surface minimal. Per-
language toggle UI state (`SourcesState.items[*].isEnabled`) updates
naturally via the upstream `allSources` flow re-emission after the seed's
fan-out — no extra plumbing.

## Commit roadmap

Five commits, all ≤5 files. Build gates after every source commit
(Android + iOS Arm64 + iOS SimulatorArm64; Desktop for commit 4 which
touches `:ui/commonMain`).

1. **Plan commit** — `PLAN_sources_onboardingseed.md` only (1 file).

2. **`:domain` interface + use case + `:data` impl** — 3 files:
   - `domain/.../repository/SourcesRepository.kt` (MODIFY) — add
     `setLanguageEnabledWithFallback(primary, fallback, enabled)` method
     with KDoc explaining the semantic + the `setLanguageEnabled` sibling
     relationship.
   - `domain/.../usecase/sources/EnableDefaultLanguageSourcesUseCase.kt`
     (NEW) — `operator fun invoke(languageTag: String)` that formats the
     tag and invokes the repository with EN fallback. KDoc cross-references
     the legacy `RepoSettingsViewModel.setLanguageEnabledDefault`.
   - `data/.../repository/SourcesRepositoryImpl.kt` (MODIFY) — implement
     `setLanguageEnabledWithFallback` by snapshotting `legacy.allSources
     .first()`, filtering by primary, falling back to filter by fallback,
     and fanning out `legacy.enableDisAbleSource`.

3. **`:presentation` MVI + Koin** — 3 files:
   - `presentation/.../sources/SourcesIntent.kt` (MODIFY) — add
     `data class OnSeedDefaultLanguage(val languageTag: String) :
     SourcesIntent`.
   - `presentation/.../sources/SourcesViewModel.kt` (MODIFY) — inject
     `EnableDefaultLanguageSourcesUseCase`, handle `OnSeedDefaultLanguage`
     in the `when` block with `viewModelScope.launch {
     enableDefaultLanguageSources(intent.languageTag) }`.
   - `composeApp/.../di/SourcesReworkModule.kt` (MODIFY) — bind the new
     use case as `factory`, update the VM factory to 5 args.

4. **`:ui` wiring** — 1 file:
   - `ui/.../sources/SourcesScreen.kt` (MODIFY) — add `onboardingLanguageTag:
     String? = null` param, fire `LaunchedEffect(onboardingLanguageTag) {
     if (onboardingLanguageTag != null) viewModel.submit(SourcesIntent
     .OnSeedDefaultLanguage(onboardingLanguageTag)) }` inside the screen.

5. **Close-out docs** — 2 files:
   - `ARCHITECTURE.md` (MODIFY) — new §139 section covering strategy,
     layer-by-layer surfaces, MVI shape rationale, why a separate repo
     method (ISP), why no state field, files touched, deferrals.
   - `SOLID_AUDIT.md` (MODIFY) — Phase 7.x.sources.onboardingseed entry
     with per-file SOLID 10-point checklist, end-of-slice verdict, build
     gates table, layer boundaries, behavior preservation (additive only —
     existing routes unchanged), MVI contract integrity (new intent
     variant; OCP-friendly extension), strangler-fig integrity (mirrors
     existing `setLanguageEnabled` impl posture), next-candidate.

## Critical files

### New
- `PLAN_sources_onboardingseed.md`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/sources/EnableDefaultLanguageSourcesUseCase.kt`

### Modified
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/SourcesRepository.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/SourcesRepositoryImpl.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/sources/SourcesIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/sources/SourcesViewModel.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/SourcesReworkModule.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/sources/SourcesScreen.kt`
- `ARCHITECTURE.md`
- `SOLID_AUDIT.md`

### Untouched
- `shared/.../repo_settings/domain/SourcesRepository.kt` — legacy facade; the
  strangler-fig delegates to its existing `allSources` + `enableDisAbleSource`
  methods. No new surface needed.
- `shared/.../repo_settings/ui/viewmodel/RepoSettingsViewModel.kt` — legacy VM
  `setLanguageEnabledDefault` stays put (the legacy onboarding route still
  consumes it until `Phase 7.x.sources.swap`).
- `composeApp/.../navigation/routes/SourcesScreenRoute.kt` — legacy onboarding
  route; preserved verbatim. `Phase 7.x.sources.swap` rewrites it later.
- `composeApp/.../navigation/routes/SourcesReworkScreenRoute.kt` — in-settings
  rework route; unchanged (passes `null` for the new param via the default).
- `composeApp/.../navigation/routes/RepoSettingsScreenRoute.kt` — post-§285
  rework route; unchanged (passes `null` for the new param via the default).

## Reuse

- **`:data` impl shape**: lifted from `SourcesRepositoryImpl.setLanguageEnabled`
  (snapshot + filter + per-source fan-out). One additional fallback filter
  pass.
- **`:domain` use case shape**: thin pass-through to repository, same posture
  as `SetLanguageEnabledUseCase` / `SetSourceEnabledUseCase`.
- **`:presentation` intent dispatch**: same `viewModelScope.launch` posture as
  `OnToggleSource` / `OnToggleLanguage`.
- **`:ui` LaunchedEffect posture**: mirrors the legacy
  `LaunchedEffect(userLanguageCode)` key-based dedup verbatim.

## Verification

After every source commit (2, 3, 4):
- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS simulator.

After commit 4 (`:ui` change):
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop. Required because
  `SourcesScreen.kt` lives in `:ui/commonMain` and links into the Desktop
  entry point.

On-device smoke tests (Windows-impossible, deferred to user's Mac):
- (Not actionable this slice — no consumer of the new path yet.) After
  `Phase 7.x.sources.swap` lands, verify the onboarding step 3 enables the
  user's locale's sources on first visit, falls back to EN when the locale
  has no native sources, and re-emits the per-source rows with the toggle
  state flipped.

## Deferrals

- **No route-swap** — legacy `Screen.Sources` stays bound to the legacy
  `SourcesScreenRoute`. `Phase 7.x.sources.swap` is its own slice; this
  slice only adds the capability.
- **No i18n lift** — the language tag is a raw string today; Phase 10 i18n
  lift will reconcile both the rework and legacy consumers with localized
  resource lookups in one pass.
- **No first-fire dedup beyond LaunchedEffect key** — the legacy
  `LaunchedEffect(userLanguageCode)` re-fires the seed if the locale changes
  while the user is on the screen. This is preserved verbatim; if a future
  UX requirement is "seed once per onboarding regardless of locale changes",
  add a `hasSeededDefaultLanguage: Boolean` state field then. No incident
  drives that today.
- **No "Reset to default" affordance** — the legacy doesn't have one; the
  rework doesn't add one. The seed is a one-shot onboarding helper, not a
  user-accessible operation.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: each new/touched file owns one rule. Repository owns mechanism;
  use case owns policy; intent variant declares the action; VM dispatches;
  screen fires the LaunchedEffect.
- **OCP**: repository interface gets one additive method; presentation
  intent gets one additive variant. Existing branches stay closed under
  modification.
- **DIP**: presentation depends on `:domain`'s `EnableDefaultLanguageSources
  UseCase`, never on the `:data` impl. Same boundary as the existing
  `SetLanguageEnabled` flow.
- **ISP**: the new repository method is narrow (3 params, one purpose).
  Not bolted onto `setLanguageEnabled` as an optional parameter.
- **Banned features**: no `!!`, no `Any`, no `lateinit`, no `Thread`. All
  flow operations are `kotlinx.coroutines.flow`. The use case formats the
  tag with `tag.ifBlank { "en" }.uppercase()` — no nullable juggling.
- **MVI contract**: new intent variant slots into the existing exhaustive
  `when` in `SourcesViewModel.handle` — compile-time enforcement that the
  reducer handles it.
- **Strangler-fig**: the `:data` impl reaches into `:shared` for the SAME
  two surfaces it already uses (`allSources`, `enableDisAbleSource`). No
  new reach.
- **Load-bearing fixes preserved**: this slice does NOT touch the Coil
  ImageLoader, the per-source header injection path (`findRepoByHost`),
  the reader's image-quality posture, or any prior load-bearing surface.
