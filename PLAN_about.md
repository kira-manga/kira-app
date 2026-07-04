# Phase 7.x.about — About screen rework (`:domain` → `:data` → `:presentation` → `:ui` + Koin/nav wiring)

## Context

Per Rule 3 auto-continue after Phase 7.x.theme + Phase 7.x.theme.pureblack
closed. About is the lowest-risk remaining slice in the settings-adjacent
catalog — it's a **stateless display + URL-launch surface** with NO
preference writes, NO list loads, NO multi-step flows, and NO async data
beyond the one-shot `versionName` + `packageName` lookup.

Block-and-ask triggers (a)-(d) all NOT met: no contract library blocker,
no observable behaviour change (legacy route preserved; rework route is
parallel + debug-guarded), no compile risk (the slice fits the established
strangler-fig pattern), no SOLID violation forced by the design.

The legacy About screen at
`composeApp/.../features/about/screen/AboutScreen.kt` (184 lines) consumes
the legacy `:shared` `AppVersionProvider` expect class + the legacy
`:shared` `IntentLauncher` expect class — both already bound `single` by
`PlatformModule.{android,ios,desktop}.kt`. The screen has:

- An app icon header (250.dp `Res.drawable.ic_launcher_foreground`).
- Six action rows (Version display, Check-for-update, Rate-our-app,
  What's-new nav, Source-code placeholder with "soon" subtitle, Privacy-
  policy URL).
- A six-icon SocialMediaRow (Twitter, Facebook, Instagram, WhatsApp,
  Discord, Website).
- Click handlers route through `koinInject<IntentLauncher>().openUrl(url)`
  or `.openPlayStorePage(packageName)`; `What's new` goes through a nav
  callback to `Screen.WhatsNewScreen(isFirstOpen = false)`.

The rework mirrors the Phase 7.x.theme + .pureblack established pattern:
`:domain` interface + thin use case → strangler-fig `:data` impl over the
legacy `:shared` `AppVersionProvider` → `:presentation` MVI surface with
**Intent → Effect** flow for URL launches (matching the Reader's
`OnOpenInWebView` → `OpenChapterInWebView` posture) → `:ui` composable
collecting effects via `LaunchedEffect` → `:composeApp` Koin module + nav
route adapter.

## Approach

### `:domain` shape

```kotlin
// domain/.../model/about/AppMetadata.kt
data class AppMetadata(
    val versionName: String,
    val packageName: String,
)

// domain/.../repository/AboutRepository.kt
interface AboutRepository {
    suspend fun getMetadata(): AppMetadata
}

// domain/.../usecase/about/GetAppMetadataUseCase.kt
class GetAppMetadataUseCase(private val repo: AboutRepository) {
    suspend operator fun invoke(): AppMetadata = repo.getMetadata()
}
```

**Why `suspend fun getMetadata()` not `Flow<AppMetadata>`**: the
version + package id are immutable for the running process. A `suspend`
one-shot is the precise shape; a `Flow` would be misleading (single
emission, never re-emits). Matches `IsAdultContentUseCase` from Phase
6.3.4 (also a one-shot suspend that resolves immediately on subscription).

### `:data` strangler-fig

```kotlin
// data/.../repository/AboutRepositoryImpl.kt
class AboutRepositoryImpl(
    private val legacy: LegacyAppVersionProvider, // :shared
) : AboutRepository {
    override suspend fun getMetadata(): AppMetadata = AppMetadata(
        versionName = legacy.versionName,
        packageName = legacy.packageName,
    )
}
```

Same posture as `ThemeRepositoryImpl` — single legacy facade constructor
arg, sync property reads, no IO. `:shared` legacy `AppVersionProvider` is
already bound `single` by `PlatformModule.*` — no new Koin bindings on
the legacy side.

### `:presentation` MVI shape

```kotlin
// AboutState
data class AboutState(
    val isLoading: Boolean = true,
    val versionName: String = "",
    val packageName: String = "",
) : MviState

// AboutIntent
sealed interface AboutIntent : MviIntent {
    /** User tapped a URL-launching action row or social media icon. */
    data class OnOpenUrl(val url: String) : AboutIntent

    /** User tapped Check-for-update or Rate-our-app. */
    data object OnOpenPlayStore : AboutIntent
}

// AboutEffect
sealed interface AboutEffect : MviEffect {
    data class OpenUrl(val url: String) : AboutEffect
    data class OpenPlayStorePage(val packageName: String) : AboutEffect
}

// AboutViewModel
class AboutViewModel(
    private val getAppMetadata: GetAppMetadataUseCase,
) : MviViewModel<AboutState, AboutIntent, AboutEffect>(AboutState()) {

    init {
        viewModelScope.launch {
            val meta = getAppMetadata()
            updateState {
                it.copy(
                    isLoading = false,
                    versionName = meta.versionName,
                    packageName = meta.packageName,
                )
            }
        }
    }

    override suspend fun handle(intent: AboutIntent) {
        when (intent) {
            is AboutIntent.OnOpenUrl -> emitEffect(AboutEffect.OpenUrl(intent.url))
            is AboutIntent.OnOpenPlayStore -> emitEffect(AboutEffect.OpenPlayStorePage(state.value.packageName))
        }
    }
}
```

**Why two intents but two effects with payloads**: the URL strings live
on the `:ui` side (they're labels/UI concerns — the VM doesn't need to
know which specific social media URL was tapped, just that "a URL was
tapped"). The `OnOpenPlayStore` intent is parameterless because the VM
already holds `packageName` in state; the effect carries the value so
the route adapter doesn't need to re-resolve it.

**Why `init {}` not `OnEnter`**: matches the established posture — the
metadata read is structurally infallible (just two property accesses on
an injected `:shared` provider) and the VM has no lifecycle moment that
should mediate the read.

### `:ui` composable shape

```kotlin
// ui/.../about/AboutScreen.kt
@Composable
fun AboutScreen(
    viewModel: AboutViewModel,
    onBack: () -> Unit,
    onWhatsNewClicked: () -> Unit,
    onOpenUrl: (url: String) -> Unit,
    onOpenPlayStore: (packageName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is AboutEffect.OpenUrl -> onOpenUrl(effect.url)
                is AboutEffect.OpenPlayStorePage -> onOpenPlayStore(effect.packageName)
            }
        }
    }
    AboutScreenContent(
        state = state,
        onIntent = viewModel::submit,
        onBack = onBack,
        onWhatsNewClicked = onWhatsNewClicked,
        modifier = modifier,
    )
}
```

`AboutScreenContent` renders Scaffold + TopAppBar (with back arrow) + a
Column with the version display + the five action rows + the social
media row. Each clickable row dispatches `AboutIntent.OnOpenUrl(url)` or
`AboutIntent.OnOpenPlayStore` via the `onIntent` callback. The "Source
code" row dispatches nothing (matches legacy's `onClick = null`).

**Icon-free posture**: no `material-icons-extended` dep. Each action row
is a `ListItem` with a text label + (where applicable) a subtitle. The
SocialMediaRow uses Material 3 `IconButton` + Compose's built-in icons
where available; for missing brand icons (Twitter, Facebook, Instagram,
WhatsApp, Discord), use simple text labels in a `FilledTonalButton` or
omit them with a Phase 7.x.about.socials follow-on deferral note.

**Decision: omit the social media row for the first cut**. The brand
icons require `material-icons-extended` (or a `:ui`-vendored icon set);
adding either is scope creep that belongs in a follow-on
`Phase 7.x.about.socials` slice if user-visible requirements demand it.
The first slice ships ONLY the action rows + the app-icon header.

### `:composeApp` Koin + nav

Five files at the cap (matching Phase 7.x.theme's commit-6 shape):

1. NEW `composeApp/.../di/AboutReworkModule.kt`:
   ```kotlin
   val aboutReworkModule: Module = module {
       single<AboutRepository> { AboutRepositoryImpl(legacy = get()) }
       factory { GetAppMetadataUseCase(get()) }
       viewModel { AboutViewModel(get()) }
   }
   ```
2. MODIFIED `composeApp/.../di/ReworkModules.kt` — append `aboutReworkModule`.
3. NEW `composeApp/.../navigation/routes/AboutReworkScreenRoute.kt` — resolves
   the VM via `koinViewModel()`, resolves `LegacyIntentLauncher` via
   `koinInject()`, wires up `onBack`/`onWhatsNewClicked`/`onOpenUrl`/
   `onOpenPlayStore` callbacks.
4. MODIFIED `composeApp/.../navigation/Screen.kt` — add `object AboutRework :
   Screen("me.manga.kira.navigation.Screen.AboutRework")`.
5. MODIFIED `composeApp/.../App.kt` — add `composable<Screen.AboutRework> {
   ... AboutReworkScreenRoute(...) }` alongside the existing
   `composable<Screen.AboutScreen>` legacy entry + the import.

## Commit roadmap

Five commits, matching the Phase 7.x.theme.pureblack collapsed shape (the
parent §85 Theme slice was 7 commits; this slice has less work — no
preference writes, no dual-flow projection, no Boolean-pair translation —
so the smaller commit count is justified).

1. **Plan commit** — `PLAN_about.md` only (1 file).

2. **`:domain` + `:data` foundation** (4 files, all new):
   - `domain/.../model/about/AppMetadata.kt` — data class.
   - `domain/.../repository/AboutRepository.kt` — interface.
   - `domain/.../usecase/about/GetAppMetadataUseCase.kt` — thin pass-through.
   - `data/.../repository/AboutRepositoryImpl.kt` — strangler-fig over
     `:shared` legacy `AppVersionProvider`.

   Combined under one commit because the interface and its impl must
   move together (every commit must be green — adding an abstract method
   to a repository forces all impls to override, same lesson as Phase
   7.x.theme.pureblack commit 2).

3. **`:presentation` MVI surface** (4 files, all new):
   - `presentation/.../about/AboutState.kt`
   - `presentation/.../about/AboutIntent.kt`
   - `presentation/.../about/AboutEffect.kt`
   - `presentation/.../about/AboutViewModel.kt`

4. **`:ui` composable** (1 file, new):
   - `ui/.../about/AboutScreen.kt`.

5. **`:composeApp` Koin + nav + close-out** (≤5 files at the cap):
   - NEW `composeApp/.../di/AboutReworkModule.kt`.
   - MODIFIED `composeApp/.../di/ReworkModules.kt`.
   - NEW `composeApp/.../navigation/routes/AboutReworkScreenRoute.kt`.
   - MODIFIED `composeApp/.../navigation/Screen.kt`.
   - MODIFIED `composeApp/.../App.kt`.

   If the close-out (ARCHITECTURE.md §87 + SOLID_AUDIT.md entry) needs
   to go in this commit too, split into 5a (Koin + nav, 5 files at cap)
   + 5b (close-out, 2 files).

## Critical files

### New
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/model/about/AppMetadata.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/AboutRepository.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/about/GetAppMetadataUseCase.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/AboutRepositoryImpl.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/about/AboutState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/about/AboutIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/about/AboutEffect.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/about/AboutViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/about/AboutScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/AboutReworkModule.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/AboutReworkScreenRoute.kt`
- `PLAN_about.md`

### Modified
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ReworkModules.kt` — append `aboutReworkModule`.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/Screen.kt` — add `AboutRework`.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt` — add `composable<Screen.AboutRework>`.
- `ARCHITECTURE.md` — append §87 (close-out).
- `SOLID_AUDIT.md` — append Phase 7.x.about entry (close-out).

### Untouched (verify by read, not modified)
- `shared/.../core/platform/AppVersionProvider.kt` (the expect class).
- `shared/.../di/PlatformModule.*.kt` (the legacy `single { AppVersionProvider() }` binding).
- `shared/.../core/platform/IntentLauncher.kt` (the expect class).
- `composeApp/.../presentation/features/about/screen/AboutScreen.kt` (legacy screen; Phase 9.x retires).
- `composeApp/.../navigation/routes/AboutScreenRoute.kt` (legacy route; Phase 9.x retires).

## Reuse

- **Strangler-fig posture**: lifted directly from `ThemeRepositoryImpl` /
  `ReadingSessionRepositoryImpl` — same `:shared` legacy constructor
  arg, same `single` Koin lifecycle.
- **MVI Intent → Effect pattern for side effects**: mirrors the Reader's
  `OnOpenInWebView` → `OpenChapterInWebView` flow (Phase
  7.x.reader.modelayout.openwebview). Route adapter resolves the
  platform launcher; `:ui` collects effects via `LaunchedEffect`.
- **Koin module shape**: mirrors `themeReworkModule` — `single` for
  repo, `factory` for use case, `viewModel` for VM.
- **Nav route shape**: takes nav callbacks (`onBack`, `onWhatsNewClicked`)
  + effect callbacks (`onOpenUrl`, `onOpenPlayStore`). Same shape as the
  Reader's `onNavigateBack` + `onOpenInWebView` adapter.

## Verification

After every source commit (steps 2-5):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile gate.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS simulator arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop (required on the
  `:ui` commit because the new screen file is in `:ui/commonMain`).

Smoke tests (Windows-impossible — deferred to user's Mac):
- Navigate to `Screen.AboutRework`, verify the action rows render and
  each click dispatches the right effect (Play Store opens for
  Check-update + Rate-our-app; the privacy URL opens in the browser;
  What's new navigates to `Screen.WhatsNewScreen(isFirstOpen = false)`).
- Verify the `versionName` matches the legacy About screen's display
  (both routes read the same `:shared` `AppVersionProvider`).

## Deferrals

- **No social media row** — first cut ships ONLY the action rows + app
  icon header. The legacy's six-icon SocialMediaRow needs either
  `material-icons-extended` (forbidden in the rework `:ui` module) or a
  vendored brand-icon set. A follow-on `Phase 7.x.about.socials` slice
  can add it if user-visible requirements demand it.
- **No Material icons on action rows** — the legacy's row icons
  (AppRegistration, Update, StarRate, ManageSearch, Code) need
  `material-icons-extended` too. Action rows render as plain `ListItem`
  with text label + optional subtitle.
- **No i18n lift** — inline literal strings ("About", "Version",
  "Check for update", "Rate our app", "What's new", "Source code",
  "Privacy policy", "soon"). Phase 10's i18n lift swaps both legacy and
  rework consumers in one pass.
- **No nav graph route-swap** — legacy `Screen.AboutScreen` stays bound
  to the legacy adapter. Phase 9.x route-swap is its own slice.
- **No `:platform` rewire** — uses the legacy `:shared`
  `AppVersionProvider` + `IntentLauncher`. The new `:platform` SPIs
  (relocated in Phases 5.3 + 5.z.cleanup) are defined but not yet bound
  to Koin; rewiring About through them is a separate `Phase 8.z.platform-
  rewire` slice.
- **No "Source code" link** — the legacy shows a "soon" subtitle and
  `onClick = null`. The rework matches verbatim.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: Each new file has one rule (one ADT, one interface, one use
  case, one impl, one MVI surface part, one composable, one Koin
  module, one nav adapter).
- **OCP**: `AboutIntent` and `AboutEffect` are sealed interfaces — new
  variants slot in without touching the VM's base class. `AboutRepository`
  interface is closed; impl is substitutable via Koin binding.
- **DIP**: `:presentation` depends on `:domain`'s interface, not on
  `:data`'s impl. `:data`'s impl depends on legacy `:shared`'s
  `AppVersionProvider` — same strangler-fig posture as five prior impls.
- **Layer boundary**: changes touch `:domain` (3 new files), `:data` (1
  new file), `:presentation` (4 new files), `:ui` (1 new file),
  `:composeApp` (2 new + 3 modified incl. close-out). The only `:shared`
  reach is the existing strangler-fig boundary.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. Pure
  `kotlinx.coroutines` + Compose primitives.
- **MVI contract**: new slice — adds `AboutState` (3 fields) + sealed
  `AboutIntent` (2 variants) + sealed `AboutEffect` (2 variants). The
  Intent → Effect → route-adapter-callback flow mirrors the Reader's
  `OpenChapterInWebView` posture; the VM never reaches platform code.
- **Strangler-fig**: ONE `:data` → `:shared` reach
  (`AboutRepositoryImpl`'s constructor accepts the legacy
  `AppVersionProvider`). Same boundary as the existing six strangler-fig
  impls.
- **Load-bearing fixes preserved**: this slice touches NO load-bearing
  paths (no Coil, no per-host repo registry, no OkHttp interceptor, no
  Reader decoder hints, no maxBitmapSize, no AVIF decoder).
