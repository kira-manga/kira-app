# Navigation Migration Report — Phase 9

> Mandatory output per Phase 9 of `MIGRATION_PROMPT.md`. Documents the planned navigation migration from Android-only `androidx.navigation:navigation-compose:2.8.9` to the JetBrains Compose-Multiplatform port `org.jetbrains.androidx.navigation:navigation-compose:2.9.2`.

## Source navigation surface

Source uses Jetpack Navigation Compose 2.8.9 with type-safe routes already (`@Serializable` data classes). 19 routes live in `app/src/main/java/me/manga/yami/navigation/routes/`:

| Route | Args | Source file |
|---|---|---|
| `HomeRoute` | none | `routes/HomeRoute.kt` |
| `LibraryRoute` | none | `routes/LibraryRoute.kt` |
| `LibraryMangaRoute` | mangaId, ... | `routes/LibraryMangaRoute.kt` |
| `HistoryRoute` | none | `routes/HistoryRoute.kt` |
| `MangaDetailsRoute` | api, url, ... | `routes/MangaDetailsRoute.kt` |
| `ReadingScreenRoute` | chapter info | `routes/ReadingScreenRoute.kt` |
| `SettingsRoute` | none | `routes/SettingsRoute.kt` |
| `LanguageScreenRoute` | none | `routes/LanguageScreenRoute.kt` |
| `NotificationsRoute` | none | `routes/NotificationsRoute.kt` |
| `RepoSettingsScreenRoute` | none | `routes/RepoSettingsScreenRoute.kt` |
| `SourcesScreenRoute` | none | `routes/SourcesScreenRoute.kt` |
| `DownloadsScreenRoute` | none | `routes/DownloadsScreenRoute.kt` |
| `StatisticsRoute` | none | `routes/StatisticsRoute.kt` |
| `ComplaintScreenRoute` | none | `routes/ComplaintScreenRoute.kt` |
| `AdminComplaintScreenRoute` | none | `routes/AdminComplaintScreenRoute.kt` |
| `ThemeSelectionScreenRoute` | none | `routes/ThemeSelectionScreenRoute.kt` |
| `WhatsNewRoute` | none | `routes/WhatsNewRoute.kt` |
| `WelcomeScreenRoute` | none | `routes/WelcomeScreenRoute.kt` |
| `WebViewRoute` | url | `routes/WebViewRoute.kt` |

Plus the top-level `NavGraphV2.kt` containing the `NavHost { … }` composition, `NavigationLock.kt` (back-press lock helper), `safePopBackStack.kt` (extension), and `double_click/*.kt` (tab-reselect handlers).

## Migration target

`org.jetbrains.androidx.navigation:navigation-compose:2.9.2` (Compose-MP fork — iOS-capable). Catalog already locked at this coordinate.

All 19 routes are already `@Serializable` data classes/objects, so the migration is mechanical — package change from `androidx.navigation.*` import statements to `org.jetbrains.androidx.navigation.*` where they exist on the source's NavHost composition. Route classes themselves don't import navigation; they only use `@Serializable` from `kotlinx.serialization`.

## Type-safe routes pattern (locked)

Routes will use this pattern (preserved from source):

```kotlin
@Serializable data object HomeRoute
@Serializable data class MangaDetailsRoute(val api: String, val url: String)

NavHost(navController, startDestination = HomeRoute) {
    composable<HomeRoute> { backStack -> HomeScreen(...) }
    composable<MangaDetailsRoute> { backStack ->
        val route = backStack.toRoute<MangaDetailsRoute>()
        MangaDetailsScreen(api = route.api, url = route.url, ...)
    }
}
```

## Deep links

Source manifest (`AndroidManifest.xml`) has no `<intent-filter android:autoVerify="true">` for deep links — the app uses internal navigation only. **No deep link migration required.** AdMob/FCM `<intent-filter>` entries are ad/messaging system filters, unrelated to in-app navigation.

## Animations

Source's `NavGraphV2.kt` will be audited in Phase 10 for any `composable<Route>(enterTransition = ..., exitTransition = ...)` blocks. Compose Multiplatform 1.11 supports all standard navigation animations; they migrate verbatim.

## Back stack behavior

Source's `safePopBackStack.kt` and `NavigationLock.kt` are pure Compose Navigation helpers — they use `NavController` API surface that is identical between Google's Android-only and JetBrains' Compose-MP forks.

## ViewModels via Koin

Source uses `hiltViewModel<T>()` in composables. Migration replaces with Koin's `koinViewModel<T>()` from `koin-compose-viewmodel` (already in catalog). Navigation argument injection becomes `koinViewModel { parametersOf(backStack.toRoute<Route>()) }` — documented in `di-migration-report.md` mapping table.

## Migration steps (Phase 9 batches — deferred per file count)

1. **Batch 9.1**: Move all 19 route files from `app/src/main/java/me/manga/yami/navigation/routes/` to `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/`. Imports unchanged (only `@Serializable` used). Verify.
2. **Batch 9.2**: Move `NavGraphV2.kt`, `NavigationLock.kt`, `safePopBackStack.kt`, `double_click/HomeTabReselectedHandler.kt`, `double_click/NavigationHandlerHolder.kt` to `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/`. Replace each `androidx.navigation.*` import with `org.jetbrains.androidx.navigation.*`. Replace `hiltViewModel<T>()` with `koinViewModel<T>()`. Verify.
3. **Batches 9.3 → 9.10**: ViewModels — 24 of them across `presentation/features/*/ui/viewmodel/`, `presentation/common/viewmodel/`, plus the singletons (`AdViewModel`, `AdminComplaintViewModel`, `CbzConversionViewModel`, `TextViewModel`). Each gets `@HiltViewModel` removed, `@Inject constructor` removed (Koin DSL handles constructor injection), and a `viewModel { … }` registration added to the appropriate Koin module. The state types should already use `StateFlow` (no `LiveData` per Phase 1 audit).

## Status

| Item | Status |
|---|---|
| Coordinate locked (`org.jetbrains.androidx.navigation:navigation-compose:2.9.2`) | ✅ Phase 2 / Phase 3 (in `gradle/libs.versions.toml`) |
| `androidx.navigation.compose` already wired in `shared/build.gradle.kts` commonMain | ✅ |
| Route files moved | ⏳ Phase 9 batch 9.1 |
| `NavGraphV2.kt` moved + import-path rewrites | ⏳ Phase 9 batch 9.2 |
| 24 ViewModels moved + Hilt→Koin conversion | ⏳ Phase 9 batches 9.3+ |
| Deep links | n/a (no in-app deep links in source) |
| Animation parity | ⏳ Phase 10 verification |

## Verification policy

After Phase 9 batches land, verify with the standard triple build (`:shared:compileKotlinDesktop`, `:shared:compileKotlinIosArm64`, `:app:assembleDebug`). The Android app's navigation must compile and run identically post-Koin — UI behavior parity verified by manual smoke test in Phase 14 (navigate every screen, hit back-button, deep-link via `adb shell am start ...` if any).
