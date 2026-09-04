# Kira Manga navigation implementation

This guide is derived from the current Kotlin and Swift implementation. It explains the complete
navigation shape and, in particular, the iOS interactive swipe-back behavior for both LTR and RTL
languages such as Arabic.

## The short answer

Kira does **not** implement swipe-back with a custom Swift `UINavigationController`,
`interactivePopGestureRecognizer`, or a custom `UIScreenEdgePanGestureRecognizer`.

It uses this combination:

1. One standard Compose Multiplatform `NavHost` with its default transitions.
2. Compose UI's native iOS screen-edge recognizers.
3. `endEdgePanGestureBehavior = EndEdgePanGestureBehavior.Back`, so the Compose host publishes a
   back event from both physical edges.
4. `LocalLayoutDirection` derived from the selected app language, so Navigation accepts the left
   edge in LTR and the right edge in RTL.
5. The root UIKit view's `semanticContentAttribute` synchronized to the same direction, so the
   native recognizers also treat the correct edge as the start edge.
6. A `didMoveToWindow()` refresh after a live language change, so Compose UI 1.11.1 re-samples the
   root view direction without recreating the controller, Compose tree, `NavController`, or stack.

For Arabic, the result is:

```text
right edge -> drag left -> interactive back transition -> pop current NavHost entry
```

For English and other LTR languages, the result is:

```text
left edge -> drag right -> interactive back transition -> pop current NavHost entry
```

## Versions this implementation relies on

The important versions and dependencies are in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml) and
[`composeApp/build.gradle.kts`](../composeApp/build.gradle.kts):

```toml
compose-multiplatform = "1.11.1"
navigation-compose = "2.9.2"
lifecycle = "2.10.0"

androidx-navigation-compose = {
    group = "org.jetbrains.androidx.navigation",
    name = "navigation-compose",
    version.ref = "navigation-compose"
}
```

The module also applies Kotlin serialization and includes the navigation dependency:

```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.androidx.navigation.compose)
        }
    }
}
```

Use the JetBrains `org.jetbrains.androidx.navigation` artifact for KMP. The Google-only Android
artifact is not the dependency used by this app.

## Navigation architecture

```text
SwiftUI iOS host
  -> ComposeView / UIViewControllerRepresentable
  -> Kotlin MainViewController()
  -> ComposeUIViewController
  -> App root locale + layout-direction providers
  -> MainScreen remembers one NavHostController
  -> one flat, type-safe NavHost
  -> route adapter in :composeApp
  -> UI screen + ViewModel in lower modules
```

The important ownership rule visible in the code is that screens do not receive or import a
`NavController`. Route adapters in `:composeApp` own navigation, convert typed arguments to domain
models, collect navigation effects, and pass plain callbacks such as `onNavigateBack` into UI.

The live graph contains one `rememberNavController()`, one `NavHost`, no nested navigation graph,
and 29 typed destination registrations. `CrashDiagnostics` is the one registration included only
when its runtime flag is enabled.

## 1. Type-safe destinations

All destinations live in
[`Screen.kt`](../composeApp/src/commonMain/kotlin/me/manga/kira/navigation/Screen.kt) as a
serializable sealed hierarchy:

```kotlin
@Serializable
sealed class Screen(val route: String) {
    @Serializable
    object Library : Screen("me.manga.kira.navigation.Screen.Library")

    @Serializable
    object Home : Screen("me.manga.kira.navigation.Screen.Home")

    @Serializable
    data class MangaDetails(
        val mangaUrl: String,
        val api: String,
    ) : Screen("me.manga.kira.navigation.Screen.MangaDetails")

    @Serializable
    data class ChapterImagesRework(
        val api: String,
        val language: String,
        val title: String,
        val mangaUrl: String,
        val coverUrl: String,
        val chapterNumber: String,
        val chapterName: String,
        val chapterUrl: String,
    ) : Screen("me.manga.kira.navigation.Screen.ChapterImagesRework")
}
```

Objects represent destinations without arguments. Serializable data classes carry route
arguments. Callers navigate with the route object rather than building strings:

```kotlin
navController.safeNavigate(
    Screen.MangaDetails(
        mangaUrl = manga.url,
        api = manga.api,
    ),
)
```

The graph decodes arguments with `toRoute<T>()`:

```kotlin
composable<Screen.MangaDetails> { backStackEntry ->
    val args = backStackEntry.toRoute<Screen.MangaDetails>()
    // Render the route using args.mangaUrl and args.api.
}
```

The explicit `route` property is used for hierarchy comparisons such as bottom-bar visibility.
Its values match the serialization route names, so package/name changes must be treated as route
schema changes.

## 2. One controller and one default NavHost

The root implementation is in [`App.kt`](../composeApp/src/commonMain/kotlin/me/manga/kira/App.kt).
`MainScreen` creates the controller once and observes its current entry:

```kotlin
@Composable
private fun MainScreen(crashDiagnosticsEnabled: Boolean) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Scaffold and route-dependent chrome...
    AppNavHost(navController, crashDiagnosticsEnabled)
}
```

The start destination is selected from persisted onboarding state, then the graph registers typed
routes:

```kotlin
val firstLaunch = remember { prefs.getBoolean(StorageKeys.FIRST_LAUNCH, true) }
val rootStart: Screen = if (firstLaunch) Screen.Welcome else Screen.Library

NavHost(
    navController = navController,
    startDestination = rootStart,
) {
    composable<Screen.Welcome> { entry ->
        WelcomeScreenRoute(navController, entry)
    }

    composable<Screen.Library> { entry ->
        LibraryScreenRoute(navController, entry)
    }

    composable<Screen.MangaDetails> { entry ->
        MangaDetailsByUrlReworkScreenRoute(navController, entry)
    }
}
```

The absence of transition arguments is important on iOS. With Navigation 2.9.2, omitting custom
`enterTransition`, `exitTransition`, `popEnterTransition`, and `popExitTransition` selects the
dedicated UIKit default-transition path. That path supplies the interactive iOS pop animation and
filters swipe events by `LocalLayoutDirection`.

Do not add explicit transition lambdas when copying this setup unless you also reimplement and test
the UIKit edge filtering and interactive transition behavior.

## 3. Route adapters isolate navigation from UI

[`MangaDetailsReworkScreenRoute.kt`](../composeApp/src/commonMain/kotlin/me/manga/kira/navigation/routes/MangaDetailsReworkScreenRoute.kt)
shows the standard pattern:

```kotlin
@Composable
fun MangaDetailsReworkScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
) {
    val viewModel: DetailsViewModel = koinViewModel()
    val args = backStackEntry.toRoute<Screen.MangaDetailsRework>()

    val manga = Manga(
        api = args.api,
        language = args.language,
        title = args.title,
        url = args.url,
        coverUrl = args.coverUrl,
        rating = args.rating,
        genres = args.genres,
    )

    DetailsScreen(
        viewModel = viewModel,
        manga = manga,
        onNavigateBack = { navController.safePopBackStack() },
        onNavigateToReader = { mangaArg, chapter ->
            navController.safeNavigate(
                Screen.ChapterImagesRework(
                    api = mangaArg.api,
                    language = mangaArg.language,
                    title = mangaArg.title,
                    mangaUrl = mangaArg.url,
                    coverUrl = mangaArg.coverUrl,
                    chapterNumber = chapter.number,
                    chapterName = chapter.name,
                    chapterUrl = chapter.url,
                ),
            )
        },
    )
}
```

This gives one navigation owner, keeps UI reusable, and makes ViewModel effects easy to translate
into route operations.

## 4. Guarded navigation and defensive back buttons

The helpers are in
[`safePopBackStack.kt`](../composeApp/src/commonMain/kotlin/me/manga/kira/navigation/safePopBackStack.kt).

The first guard allows navigation only while the source entry is `RESUMED`. When a transition
begins, that entry drops to `STARTED`, so a rapid second tap cannot push or pop a second route:

```kotlin
private fun NavController.isReadyForNavigation(): Boolean {
    val entry = currentBackStackEntry ?: return true
    return entry.lifecycle.currentState == Lifecycle.State.RESUMED
}
```

Forward navigation uses typed routes, applies optional `NavOptions`, and treats an invalid request
as a no-op rather than crashing the host:

```kotlin
fun NavController.safeNavigate(
    route: Any,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    if (!isReadyForNavigation()) return
    try {
        navigate(route) { builder() }
    } catch (_: Exception) {
        // Invalid or unavailable route: no-op.
    }
}
```

Explicit back buttons call `safePopBackStack()`. It first tries to pop. If no previous entry exists,
the code navigates defensively to Library with single-top and saved-state behavior:

```kotlin
fun NavController.safePopBackStack(
    libraryRoute: String = Screen.Library.route,
): Boolean {
    if (!isReadyForNavigation()) return false

    val startDestinationId = runCatching {
        graph.findStartDestination().id
    }.getOrNull()

    return try {
        if (previousBackStackEntry == null) {
            navigateToLibrary(libraryRoute, startDestinationId)
        } else if (popBackStack()) {
            true
        } else {
            navigateToLibrary(libraryRoute, startDestinationId)
        }
    } catch (_: Throwable) {
        navigateToLibrary(libraryRoute, startDestinationId)
    }
}

private fun NavController.navigateToLibrary(
    libraryRoute: String,
    startDestinationId: Int?,
): Boolean = try {
    navigate(libraryRoute) {
        launchSingleTop = true
        restoreState = true
        startDestinationId?.let {
            popUpTo(it) {
                inclusive = false
                saveState = true
            }
        }
    }
    true
} catch (_: Exception) {
    false
}
```

These helpers are for buttons, row taps, and ViewModel effects. The iOS interactive edge swipe does
not call `safePopBackStack()`; Navigation's predictive-back handler directly drives and completes
the `ComposeNavigator` pop. It is enabled only when the Compose back stack has more than one entry.

## 5. Bottom-tab stack behavior

The five root tabs are implemented in
[`BottomNavigationBar.kt`](../composeApp/src/commonMain/kotlin/me/manga/kira/presentation/common/componants/BottomNavigationBar.kt).

Selection is based on the active destination hierarchy, and tab navigation uses the standard
save/restore combination:

```kotlin
val selected = currentDestination
    ?.hierarchy
    ?.any { it.route == screen.route } == true

navController.navigate(screen) {
    popUpTo(Screen.Library) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
```

This prevents duplicate tab destinations and restores each saved tab state. Kira deliberately uses
`Screen.Library` as the tab root instead of `findStartDestination()` because the first-launch graph
can initially start at Welcome.

[`BottomBarVisibility.kt`](../composeApp/src/commonMain/kotlin/me/manga/kira/navigation/BottomBarVisibility.kt)
keeps bottom-bar ownership separate from rendering:

```kotlin
private val bottomBarRoutes = setOf(
    Screen.Library.route,
    Screen.Updates.route,
    Screen.Home.route,
    Screen.History.route,
    Screen.Setting.route,
)

internal fun shouldShowBottomBar(routes: Sequence<String?>): Boolean =
    routes.any { it in bottomBarRoutes }
```

## 6. Onboarding clears its temporary stack

The onboarding flow is:

```text
Welcome -> Theme -> Start Reading -> Sources -> Library
```

Normal steps use `safeNavigate`. Finishing onboarding makes Library the new root:

```kotlin
prefs.putBoolean(StorageKeys.FIRST_LAUNCH, false)
navController.navigate(Screen.Library) {
    popUpTo(navController.graph.startDestinationId) {
        inclusive = true
    }
    launchSingleTop = true
}
```

This is why back or swipe-back from Library cannot reopen the wizard.

## 7. Push and URL navigation enter through app-scoped routers

Platform code never owns the `NavController`.

For notification taps:

```text
AppDelegate/MainActivity
  -> raw payload
  -> PushPayloadParser
  -> typed PushDestination
  -> NotificationRouter StateFlow
  -> MainScreen LaunchedEffect
  -> PushDestination.toScreen()
  -> NavController.navigate(...)
  -> router.consume()
```

The relevant code is under
[`navigation/push/`](../composeApp/src/commonMain/kotlin/me/manga/kira/navigation/push/).
`NotificationRouter` holds a nullable, sequence-tagged pending destination in `StateFlow`. This
makes cold-start delivery durable until the host is composed, avoids replay after consumption, and
allows the same logical destination to be submitted twice without `StateFlow` conflation.

The host intentionally does **not** apply the `RESUMED` navigation guard to a cold-start deep link:
the initial destination may still be `STARTED`, and applying `safeNavigate()` there would discard
the tap. It instead performs a crash-safe direct `navigate()` and consumes the pending item once.

On iOS, [`AppDelegate.swift`](../iosApp/iosApp/AppDelegate.swift) forwards notification taps through
[`IosPushBridge.kt`](../composeApp/src/iosMain/kotlin/me/manga/kira/di/IosPushBridge.kt). Universal
links and the custom scheme use the same router pattern through
[`IosSourceActivationBridge.kt`](../composeApp/src/iosMain/kotlin/me/manga/kira/di/IosSourceActivationBridge.kt).

## 8. Exact iOS LTR/RTL swipe-back implementation

### 8.1 Configure the Compose UIKit host

The complete app-specific gesture configuration is in
[`MainViewController.kt`](../composeApp/src/iosMain/kotlin/me/manga/kira/MainViewController.kt):

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
fun MainViewController(): UIViewController {
    val controller = ComposeUIViewController(
        configure = {
            endEdgePanGestureBehavior = EndEdgePanGestureBehavior.Back
        },
    ) {
        App()
    }

    IosHostLayoutDirection.bind(controller.view)
    return controller
}
```

Compose UI always publishes back events from the logical start edge. Its default for the end edge is
`Disabled`. Kira changes the end edge to `Back`, so both physical recognizers can publish back.

This is deliberate redundancy for live language switching: immediately after an LTR/RTL change,
the UIKit recognizer and the Compose-local direction can update on slightly different callbacks.
Publishing both edges ensures the newly correct event exists; the `NavHost` then accepts only the
edge matching its current `LocalLayoutDirection`.

Do **not** set the end edge to `Forward` if the goal is to reproduce Kira's behavior.

### 8.2 Derive Compose direction from the app language

[`LanguageDirection.kt`](../composeApp/src/commonMain/kotlin/me/manga/kira/locale/LanguageDirection.kt)
recognizes the language subtag rather than comparing only the full tag:

```kotlin
private val RTL_LANGUAGE_SUBTAGS =
    setOf("ar", "fa", "he", "iw", "ur", "ps", "sd", "ug", "yi", "dv")

internal fun isRtlLanguageTag(tag: String): Boolean =
    tag
        .trim()
        .substringBefore('-')
        .substringBefore('_')
        .lowercase() in RTL_LANGUAGE_SUBTAGS
```

Therefore `ar`, `ar-EG`, and `ar_EG` all select RTL.

At the app root, the selected language drives `LocalLayoutDirection`:

```kotlin
val language by remember { observeLanguage() }
    .collectAsState(initial = initialLanguage)

val layoutDirection = when {
    language.isBlank() || !LocalAppLocale.isLiveLocaleSwitchSupported ->
        LocalLayoutDirection.current
    isRtlLanguageTag(language) -> LayoutDirection.Rtl
    else -> LayoutDirection.Ltr
}

CompositionLocalProvider(
    LocalAppLocale provides language.ifBlank { null },
    LocalLayoutDirection provides layoutDirection,
) {
    MainScreen(...)
}
```

The provider wraps `MainScreen`; it does not recreate it. `rememberNavController()` remains in the
same composition slot, so a language change preserves the current destination and entire stack.

### 8.3 Synchronize UIKit to the same direction

Compose direction alone is not sufficient. The native iOS edge recognizer decides its start edge
from the root `UIView.effectiveUserInterfaceLayoutDirection`.

Kira synchronizes that root view in
[`IosHostLayoutDirection.kt`](../composeApp/src/iosMain/kotlin/me/manga/kira/IosHostLayoutDirection.kt):

```kotlin
internal object IosHostLayoutDirection {
    private var hostView: UIView? = null
    private var requestedRtl: Boolean? = null

    fun bind(view: UIView) {
        hostView = view
        requestedRtl?.let { apply(view, it) }
    }

    fun synchronize(isRtl: Boolean) {
        requestedRtl = isRtl
        hostView?.let { apply(it, isRtl) }
    }

    private fun apply(view: UIView, isRtl: Boolean) {
        val desired = if (isRtl) {
            UISemanticContentAttributeForceRightToLeft
        } else {
            UISemanticContentAttributeForceLeftToRight
        }

        if (view.semanticContentAttribute == desired) return
        view.semanticContentAttribute = desired

        if (view.window != null) {
            view.didMoveToWindow()
        }
    }
}
```

Storing `requestedRtl` makes initialization order safe: synchronization can happen before or after
the root view is bound.

In Compose UI 1.11.1, the Compose container's `didMoveToWindow()` callback re-runs its navigation
input setup. That code reads `effectiveUserInterfaceLayoutDirection`, assigns the start recognizer
to the left or right edge, and reattaches the recognizers. Kira calls it only when the view is
already attached to a window.

This refresh is version-sensitive implementation behavior. Re-test it when upgrading Compose
Multiplatform.

### 8.4 Trigger UIKit synchronization from the locale provider

The iOS locale implementation is
[`LocalAppLocale.ios.kt`](../composeApp/src/iosMain/kotlin/me/manga/kira/locale/LocalAppLocale.ios.kt).
Its provider computes the effective language and synchronizes UIKit in a post-composition
`SideEffect`:

```kotlin
@Composable
actual infix fun provides(value: String?): ProvidedValue<*> {
    val defaults = NSUserDefaults.standardUserDefaults
    val effectiveLanguage =
        value?.trim().takeUnless { it.isNullOrEmpty() } ?: systemDefaultTag

    SideEffect {
        IosHostLayoutDirection.synchronize(isRtlLanguageTag(effectiveLanguage))
    }

    val current = defaults.arrayForKey(APPLE_LANGUAGES_KEY)
    return if (value.isNullOrBlank()) {
        if (current != systemDefault) {
            defaults.setObject(systemDefault, APPLE_LANGUAGES_KEY)
        }
        LocalAppLocale.provides(systemDefaultTag)
    } else {
        if (current != listOf(value)) {
            defaults.setObject(listOf(value), APPLE_LANGUAGES_KEY)
        }
        LocalAppLocale.provides(value)
    }
}
```

The surrounding object defines `APPLE_LANGUAGES_KEY`, captures `systemDefault`, and exposes a
private composition local named `LocalAppLocale`; the final calls above provide values through that
composition local. If the other app already has locale switching, keep its implementation and add
the synchronization `SideEffect` at its root locale boundary.

### 8.5 The Swift host is intentionally plain

[`ContentView.swift`](../iosApp/iosApp/ContentView.swift) only embeds the controller:

```swift
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(
        _ uiViewController: UIViewController,
        context: Context
    ) {}
}
```

There is no Swift navigation stack to synchronize with the Compose stack.

The only `UINavigationController` matches elsewhere in the iOS source tree are historical comments
in a fast-scroller no-op file; no live Kotlin or Swift statement constructs one.

### 8.6 What Compose and Navigation do underneath

The following was traced in the resolved Compose UI 1.11.1 and Navigation 2.9.2 source artifacts
used by this build. The effective low-level edge selection is:

```kotlin
startRecognizer.edges = if (isRtl) rightEdge else leftEdge
endRecognizer.edges = if (isRtl) leftEdge else rightEdge

navHostAllowedBackEdge = when (LocalLayoutDirection.current) {
    LayoutDirection.Ltr -> EDGE_LEFT
    LayoutDirection.Rtl -> EDGE_RIGHT
}
```

For these exact dependency versions:

- Compose UI installs a native screen-edge recognizer for the logical start edge.
- Because Kira sets `EndEdgePanGestureBehavior.Back`, it also enables the logical end-edge
  recognizer and classifies it as back.
- `NavHost` reads `LocalLayoutDirection` and accepts `EDGE_LEFT` for LTR or `EDGE_RIGHT` for RTL.
- Gesture progress drives Navigation's seekable predictive-back transition.
- Completion pops the current `ComposeNavigator` entry; cancellation animates back to the current
  entry.
- Compose UI completes a slow gesture after 30% of screen width, or completes an earlier gesture
  when its forward velocity exceeds the internal constant `100`. The library names these constants
  `BACK_GESTURE_SCREEN_SIZE = 0.3` and `BACK_GESTURE_VELOCITY = 100`; their units and values are
  implementation details owned by Compose UI 1.11.1, not by Kira application code.

Navigation 2.9.2's default UIKit visual treatment is also library-owned:

- 200 ms linear push/pop settling animation.
- Foreground page travels the full width.
- Underlying page uses approximately 30% parallax.
- A subtle black overlay reaches alpha `0.106` and fades with gesture progress.

This is a large part of why the navigation feels native even though the stack is Compose-owned.

## 9. Native reader navigation still belongs to Compose

The shipping iOS reader is a UIKit child controller, but it is not a second navigation system.

[`ReaderHostSwitch.ios.kt`](../composeApp/src/iosMain/kotlin/me/manga/kira/reader/ReaderHostSwitch.ios.kt)
embeds the Swift reader with `UIKitViewController` inside the current Compose destination. Its back
button ultimately calls the same route callback:

```text
Swift back button
  -> ReaderNativeSession.onBackClick()
  -> ReaderViewModel intent/effect
  -> onNavigateBack
  -> navController.safePopBackStack()
```

The root Compose edge recognizer remains responsible for interactive swipe-back.

The reader has one additional interop safeguard: before navigating from the native reader to the
native `WKWebView` destination, it removes the current UIKit child for one frame, then performs the
Compose navigation. This prevents the outgoing child controller from remaining above the incoming
WebView and swallowing touches. It remounts the reader only when that destination becomes
`RESUMED` again.

## 10. Platform back-handler behavior

The common UI wrapper is under
[`ui/util/BackHandler.kt`](../ui/src/commonMain/kotlin/me/manga/kira/ui/util/BackHandler.kt):

- Android delegates to `androidx.activity.compose.BackHandler`, allowing an in-screen overlay to
  consume back before the graph pops.
- iOS is intentionally a no-op; the Compose navigation host owns edge swipe and explicit screen
  chrome owns button back.
- Desktop is also a no-op in this app.

This means Kira's iOS edge gesture pops the current route; it does not close an arbitrary in-screen
overlay first. If the other app needs that behavior, it needs an iOS `NavigationEventHandler`
integration rather than copying Kira's no-op wrapper unchanged.

## 11. Minimum transplant checklist

To reproduce the same behavior in another KMP app:

1. Use compatible Compose UI and JetBrains Navigation Compose versions.
2. Apply Kotlin serialization and define `@Serializable` typed routes.
3. Keep exactly one long-lived `rememberNavController()` around one `NavHost`.
4. Leave the root `NavHost` transitions unspecified on iOS.
5. Configure `ComposeUIViewController` with
   `endEdgePanGestureBehavior = EndEdgePanGestureBehavior.Back`.
6. Bind the controller's root view to an `IosHostLayoutDirection` object.
7. Derive `LocalLayoutDirection` from the selected BCP-47 language subtag.
8. In the same locale boundary, force the root UIKit semantic direction.
9. After a live direction change, refresh the attached Compose view with `didMoveToWindow()`.
10. Keep the locale provider around the existing `NavController`; do not key or recreate the
    controller by language.
11. Use route adapters and plain UI callbacks rather than passing `NavController` into screens.
12. Guard tap-driven navigation at `Lifecycle.State.RESUMED` to prevent duplicate pushes/pops.
13. Use `launchSingleTop + saveState + restoreState` for root tabs.
14. Clear onboarding routes with inclusive `popUpTo` when the wizard finishes.

## 12. Tests to copy and device checks to run

[`IosHostLayoutDirectionTest.kt`](../composeApp/src/iosTest/kotlin/me/manga/kira/IosHostLayoutDirectionTest.kt)
pins the two app-owned pieces:

```kotlin
@Test
fun navigationHost_publishesBackEventsFromBothPhysicalEdges() {
    val configuration = ComposeUIViewControllerConfiguration()
    configuration.configureKiraNavigationHost()
    assertEquals(
        EndEdgePanGestureBehavior.Back,
        configuration.endEdgePanGestureBehavior,
    )
}

@Test
fun synchronize_updatesUIKitSemanticAndEffectiveDirection() {
    val view = UIView()
    IosHostLayoutDirection.bind(view)

    IosHostLayoutDirection.synchronize(isRtl = true)
    assertEquals(
        UIUserInterfaceLayoutDirectionRightToLeft,
        view.effectiveUserInterfaceLayoutDirection,
    )

    IosHostLayoutDirection.synchronize(isRtl = false)
    assertEquals(
        UIUserInterfaceLayoutDirectionLeftToRight,
        view.effectiveUserInterfaceLayoutDirection,
    )
}
```

Also verify these behaviors on an iPhone or simulator:

| State | Expected accepted gesture | Expected rejected gesture |
|---|---|---|
| English/LTR, stack depth > 1 | left edge -> right | right edge -> left |
| Arabic/RTL, stack depth > 1 | right edge -> left | left edge -> right |
| Either direction, root entry | no pop | no pop |
| Switch English -> Arabic live | stack preserved; right edge works | old left edge does not pop |
| Switch Arabic -> English live | stack preserved; left edge works | old right edge does not pop |

During a partial drag, confirm that the destination follows the finger and returns smoothly when
cancelled. A completed swipe must remove exactly one back-stack entry.

## Common mistakes that break the Arabic gesture

- Setting only `LocalLayoutDirection = Rtl`: the UI mirrors, but UIKit may still listen on the old
  physical edge.
- Setting only `semanticContentAttribute`: the native recognizer moves, but `NavHost` may still
  filter for the opposite edge.
- Recreating `MainViewController` or keying the entire app by language: the back stack is lost.
- Leaving the end edge disabled while supporting an in-process direction change: there can be a
  refresh window in which the newly correct physical edge produces no back event.
- Passing custom root `NavHost` transitions: Navigation 2.9.2 no longer selects its dedicated
  default UIKit transition/filtering branch.
- Wrapping Compose in an unrelated Swift `UINavigationController` and assuming its
  `interactivePopGestureRecognizer` controls Compose routes: it does not know the Compose stack.
- Comparing only `language == "ar"`: tags such as `ar-EG` and `ar_EG` will be missed.
- Calling `didMoveToWindow()` while the view is detached: Kira guards it with `view.window != null`.
