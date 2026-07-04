package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.kira.core.platform.HideNavigationBarSideEffect
import me.manga.kira.core.platform.encodeImageBitmapToPng
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.PageProgressRepository
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.navigation.safePopBackStack
import me.manga.kira.platform.image.ScreenshotProvider
import me.manga.kira.presentation.reader.ReaderIntent
import me.manga.kira.presentation.reader.ReaderViewModel
import me.manga.kira.reader.ReaderHostSwitch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the architecture-rework Reader screen (Phase 8.x.reader).
 *
 * Adapter between the NavHost (Nav 2.9.2 type-safe
 * `composable<Screen.ChapterImagesRework>`) and the `:ui/.../reader/ReaderScreen` composable.
 * Owns the rework [ReaderViewModel] via Koin and translates the screen's `onNavigateBack`
 * callback into a `safePopBackStack` call.
 *
 * **Scope**: end-to-end proof of the rework Reader slice —
 *
 *  1. **Koin DI** (`readerReworkModule` from `:composeApp/commonMain/di/`) resolves the
 *     `ReaderViewModel` constructor's `factory`-scoped `FetchChapterPagesUseCase`, which in
 *     turn depends on the `single`-scoped `ChapterPagesRepository` (legacy
 *     `SourcesRepository` from `:shared` + rework `DispatcherProvider`).
 *  2. **`:presentation` MVI** plumbing emits `ReaderState` via `StateFlow` and
 *     `ReaderEffect` via the unbounded `Channel` from the base `MviViewModel`.
 *  3. **`:ui` Compose** renders the page list, dispatches intents via `viewModel.submit(...)`,
 *     and forwards `NavigateBack` / `ShowError` effects through this adapter / snackbar.
 *
 * **`Manga` + `Chapter` reconstruction**: nav args carry the identity tuple of the pure-domain
 * `Manga` and `Chapter` instances. See [Screen.ChapterImagesRework] KDoc for the rationale on
 * which fields the route omits (`rating` / `genres` / `date` / `isDownloaded` /
 * `isBookmarked` — none consumed by the Reader screen). `date` defaults to `null`; the two
 * boolean flags default to `false`. The VM's `OnEnter` re-entry guard keys on
 * `(manga.api, manga.language, manga.title)` + `chapter.url`, all carried in the route args.
 *
 * **Why this lives in `:composeApp` and not in `:ui`**: `Screen.ChapterImagesRework` is owned
 * by the `:composeApp` nav graph. `:ui` deliberately exposes a generic `() -> Unit` callback
 * (`onNavigateBack`) so the screen stays nav-host-agnostic and reusable. Mirrors the same
 * boundary as [MangaDetailsReworkScreenRoute] and [LibraryReworkScreenRoute].
 *
 * **No `BackHandler`**: matches sibling rework adapters deliberately. System back is the
 * default; the cross-platform `BackHandler` gap pending an expect/actual shim is documented at
 * `App.kt` KDoc point 4.
 *
 * @param navController parent nav controller for `safePopBackStack` on back-effect.
 * @param backStackEntry NavBackStackEntry — args are read here via `toRoute<...>()`; the VM is
 *                       `koinViewModel()`-scoped (process-wide on Android via Koin's
 *                       ViewModelStoreOwner integration).
 */
@Composable
fun ChapterImagesReworkScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
) {
    // Hide the system navigation bar while this route is on the back stack; restore on dispose.
    // Mirrors native pre-KMP `HideSystemBars()` parity (Phase 7.x.reader.systembars). Hosted at
    // the route adapter — not inside `:ui/.../ReaderScreen` — to keep `:ui` multiplatform-pure
    // (Contract §4). See [HideNavigationBarSideEffect] KDoc for the full behaviour spec.
    HideNavigationBarSideEffect()

    val viewModel: ReaderViewModel = koinViewModel()

    // Per-page download/decode progress reporter (Phase 7.x.reader.modelayout.pageprogress).
    // Bridges Coil's per-request listener (attached in `:ui/.../ReaderPageItem`) to the
    // rework's in-memory progress repository. Repository is a `single` declared in
    // `readerReworkModule`; observation happens in `ReaderViewModel.startObservingProgress`
    // (per-URL collectors started in the `runFetch` Success branch). Splitting reporter +
    // observer across two consumers is intentional — `:ui` only knows the `:domain` callback
    // shape, never the repository type, which keeps `:ui` decoupled from `:data`.
    val pageProgressRepo: PageProgressRepository = koinInject()

    // Reader parity item #5 (share current page): the existing `:platform` ScreenshotProvider SPI
    // (relocated in Phase 5.y.3 — Android shares via Intent.ACTION_SEND + FileProvider, iOS via
    // UIActivityViewController, Desktop by copying the saved-file path to the clipboard). Bound as
    // a `single` in `:shared` PlatformModule.{android,ios,desktop}.kt; resolved here via Koin so
    // `:ui` never sees a `:platform` type — it just hands back a captured ImageBitmap.
    val screenshotProvider: ScreenshotProvider = koinInject()
    val shareScope = rememberCoroutineScope()

    // Reader parity item #6 (legacy auto-403→WebView recovery): the shared
    // `rememberCloudflareChallengeSolver` helper — navigates to the WebView to clear the
    // Cloudflare challenge, then arms a one-shot that re-dispatches OnRetry once the back-stack
    // returns to this Reader entry (cookies minted), so the chapter re-fetches automatically.
    val solveCloudflare = rememberCloudflareChallengeSolver(
        navController = navController,
        ownerEntry = backStackEntry,
        onRetry = { viewModel.submit(ReaderIntent.OnRetry) },
    )

    val args = backStackEntry.toRoute<Screen.ChapterImagesRework>()

    val manga = Manga(
        api = args.api,
        language = args.language,
        title = args.title,
        url = args.mangaUrl,
        coverUrl = args.coverUrl,
        rating = null,
        genres = emptyList(),
    )

    val chapter = Chapter(
        number = args.chapterNumber,
        name = args.chapterName,
        url = args.chapterUrl,
        date = null,
        isDownloaded = false,
        isBookmarked = false,
    )

    ReaderHostSwitch(
        viewModel = viewModel,
        manga = manga,
        chapter = chapter,
        onNavigateBack = { navController.safePopBackStack() },
        // `ReaderEffect.OpenChapterInWebView(url, api)` consumer (Phase
        // 7.x.reader.modelayout.openwebview). Routes to the legacy `Screen.WebView`
        // in-app browser — same target as the legacy reader's "Open in WebView"
        // button (composeApp/.../navigation/routes/ChapterImagesScreenRoute.kt line
        // 147–149). Sharing the nav target is deliberate during the strangler-fig
        // migration: the in-app WebView screen is a stable piece of infrastructure
        // that the rework reuses without porting. Phase 9.x route-swap reconciles
        // ownership of `Screen.WebView` if/when the rework gains its own WebView
        // composable.
        onOpenInWebView = { url, api ->
            navController.safeNavigate(Screen.WebView(url, api))
        },
        // Reader parity item #5: `:ui` hands back the captured page bitmap; the adapter encodes it
        // to PNG bytes and invokes the `:platform` share SPI. PNG-encode mirrors legacy
        // `ScreenshotUtils` (PNG, quality 100). Title is an inline literal copying the legacy
        // chooser title ("Share screenshot") verbatim. Launched on a remembered scope because
        // `shareBitmapBytes` is suspend; the encode hops to Dispatchers.Default because pages are
        // tall multi-megapixel strips (main-thread encode janks); a null encode (e.g. OOM on a
        // huge strip) no-ops the share.
        onSharePage = { bitmap ->
            shareScope.launch {
                val bytes = withContext(Dispatchers.Default) { encodeImageBitmapToPng(bitmap) }
                if (bytes != null) {
                    screenshotProvider.shareBitmapBytes(bytes, "Share screenshot")
                }
            }
        },
        // Reader parity item #6: AUTO 403→WebView recovery + auto-retry-on-return.
        onSolveCloudflareChallenge = solveCloudflare,
        onReportProgress = pageProgressRepo::report,
    )
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster157.staleKdocSweep.cascade,
 * Task #613, 2026-05-28): classified as follows after recursive symbol
 * verification (two-hundred-and-seventh sibling of the cluster57-156 sweep —
 * OPENING file of the wave-29 :composeApp/navigation/routes/ remaining 2-leaf
 * batch alongside WhatsNewReworkScreenRoute; OPENS routes/ tier 1/2):
 *  (a) "Route-host-for-the-architecture-rework-Reader-screen-Phase-8.x.reader
 *  + Adapter-between-the-NavHost-Nav-2.9.2-type-safe-composable-Screen.
 *  ChapterImagesRework-and-the-:ui-reader-ReaderScreen-composable + Owns-the-
 *  rework-ReaderViewModel-via-Koin-and-translates-the-screen-s-onNavigateBack-
 *  callback-into-a-safePopBackStack-call + Scope-end-to-end-proof-of-the-
 *  rework-Reader-slice + Koin-DI-readerReworkModule-from-:composeApp-
 *  commonMain-di-resolves-the-ReaderViewModel-constructor-s-factory-scoped-
 *  FetchChapterPagesUseCase-which-in-turn-depends-on-the-single-scoped-
 *  ChapterPagesRepository-legacy-SourcesRepository-from-:shared-plus-rework-
 *  DispatcherProvider + :presentation-MVI-plumbing-emits-ReaderState-via-
 *  StateFlow-and-ReaderEffect-via-the-unbounded-Channel-from-the-base-
 *  MviViewModel + :ui-Compose-renders-the-page-list-dispatches-intents-via-
 *  viewModel.submit-and-forwards-NavigateBack-ShowError-effects-through-this-
 *  adapter-snackbar + Manga-plus-Chapter-reconstruction-nav-args-carry-the-
 *  identity-tuple-of-the-pure-domain-Manga-and-Chapter-instances + See-Screen
 *  .ChapterImagesRework-KDoc-for-the-rationale-on-which-fields-the-route-
 *  omits-rating-genres-date-isDownloaded-isBookmarked-none-consumed-by-the-
 *  Reader-screen + date-defaults-to-null-the-two-boolean-flags-default-to-
 *  false + The-VM-s-OnEnter-re-entry-guard-keys-on-manga.api-manga.language-
 *  manga.title-plus-chapter.url-all-carried-in-the-route-args + Why-this-
 *  lives-in-:composeApp-and-not-in-:ui-Screen.ChapterImagesRework-is-owned-
 *  by-the-:composeApp-nav-graph + :ui-deliberately-exposes-a-generic-Unit-
 *  callback-onNavigateBack-so-the-screen-stays-nav-host-agnostic-and-reusable
 *  + Mirrors-the-same-boundary-as-MangaDetailsReworkScreenRoute-and-Library
 *  ReworkScreenRoute + No-BackHandler-matches-sibling-rework-adapters-
 *  deliberately + System-back-is-the-default-the-cross-platform-BackHandler-
 *  gap-pending-an-expect-actual-shim-is-documented-at-App.kt-KDoc-point-4 +
 *  Inline-HideNavigationBarSideEffect-rationale-mirrors-native-pre-KMP-Hide
 *  SystemBars-parity-Phase-7.x.reader.systembars-hosted-at-the-route-adapter
 *  -not-inside-:ui-ReaderScreen-to-keep-:ui-multiplatform-pure-Contract-§-4
 *  + Inline-pageProgressRepo-injection-rationale-Phase-7.x.reader.modelayout
 *  .pageprogress-bridges-Coil-s-per-request-listener-attached-in-:ui-
 *  ReaderPageItem-to-the-rework-s-in-memory-progress-repository + Repository
 *  -is-a-single-declared-in-readerReworkModule-observation-happens-in-Reader
 *  ViewModel.startObservingProgress-per-URL-collectors-started-in-the-
 *  runFetch-Success-branch + Splitting-reporter-plus-observer-across-two-
 *  consumers-is-intentional-:ui-only-knows-the-:domain-callback-shape-never-
 *  the-repository-type-which-keeps-:ui-decoupled-from-:data + onOpenInWebView
 *  -rationale-ReaderEffect.OpenChapterInWebView-url-api-consumer-Phase-7.x.
 *  reader.modelayout.openwebview-routes-to-the-legacy-Screen.WebView-in-app-
 *  browser-same-target-as-the-legacy-reader-s-Open-in-WebView-button + Sharing
 *  -the-nav-target-is-deliberate-during-the-strangler-fig-migration-the-in-
 *  app-WebView-screen-is-a-stable-piece-of-infrastructure-that-the-rework-
 *  reuses-without-porting + Phase-9.x-route-swap-reconciles-ownership-of-
 *  Screen.WebView-if-when-the-rework-gains-its-own-WebView-composable" —
 *  LIVE-NOT-STALE. Verified: @Composable fun ChapterImagesReworkScreenRoute
 *  (navController: NavController, backStackEntry: NavBackStackEntry) shipped
 *  as the route adapter. The "rework Reader adapter end-to-end proof" three-
 *  layer stance honored — (i) Koin DI resolves ReaderViewModel + Fetch
 *  ChapterPagesUseCase + ChapterPagesRepository chain via readerReworkModule;
 *  (ii) :presentation MVI emits ReaderState StateFlow + ReaderEffect Channel;
 *  (iii) :ui Compose renders the page list, submits intents, forwards effects
 *  through this adapter. The "Manga + Chapter reconstruction with rating /
 *  genres / date / isDownloaded / isBookmarked defaulted" identity-tuple
 *  posture honored — args.{api, language, title, mangaUrl, coverUrl} feed
 *  Manga(...), args.{chapterNumber, chapterName, chapterUrl} feed Chapter(...)
 *  with rating=null + genres=emptyList() + date=null + isDownloaded=false +
 *  isBookmarked=false defaults. The "HideNavigationBarSideEffect at route
 *  adapter, NOT inside :ui" Contract §4 multiplatform-pure :ui stance honored
 *  — HideNavigationBarSideEffect() invoked at the adapter's top. The "page-
 *  progress reporter + observer split across two consumers to keep :ui
 *  decoupled from :data" SRP stance honored — onReportProgress = page
 *  ProgressRepo::report bridges Coil's per-request listener (:ui side) to
 *  the rework's in-memory PageProgressRepository (:data side). The
 *  "onOpenInWebView routes to legacy Screen.WebView during strangler-fig
 *  migration" stable-infrastructure-reuse posture honored — { url, api ->
 *  navController.safeNavigate(Screen.WebView(url, api)) }. The "No
 *  BackHandler — system back is the default" sibling-adapter parity stance
 *  honored — no expect/actual BackHandler shim today. Consumed by App.kt's
 *  composable<Screen.ChapterImagesRework> route registration. OPENING FILE
 *  of the cluster157 :composeApp/navigation/routes/ remaining 2-leaf batch
 *  (1 of 2: ChapterImagesReworkScreenRoute + WhatsNewReworkScreenRoute).
 *  One classification. Original Phase 8.x.reader route-adapter prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
