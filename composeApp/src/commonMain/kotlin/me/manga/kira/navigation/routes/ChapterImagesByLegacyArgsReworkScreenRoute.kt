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
 * Route host for the architecture-rework Reader screen behind the **legacy** route key
 * [Screen.ChapterImagesFragment] (Reader-convergence slice R4).
 *
 * Twin of [ChapterImagesReworkScreenRoute]: both adapt the NavHost to the same
 * `:ui/.../reader/ReaderScreen` composable backed by the same rework [ReaderViewModel]. The only
 * difference is the route key they read:
 *  - [ChapterImagesReworkScreenRoute] reads the rework-native [Screen.ChapterImagesRework] (the
 *    minimal full-identity-tuple shape introduced by Phase 8.x.reader for the debug route).
 *  - This adapter reads the **legacy** [Screen.ChapterImagesFragment], the user-facing route key
 *    that the 3 caller sites (Home, History, Updates) already emit. Per ADR-8 the route-swap keeps
 *    the legacy key and flips only the `App.kt` composable block, so every caller site is
 *    untouched — they keep navigating to `Screen.ChapterImagesFragment(...)` and now land on the
 *    rework Reader.
 *
 * **`Manga` + `Chapter` reconstruction from the legacy args**: the legacy
 * [Screen.ChapterImagesFragment] carries a wider arg tuple than the rework screen consumes (it was
 * shaped for the legacy `:shared` Reader's history/chapter-list plumbing). This adapter projects
 * only the fields the rework `ReaderScreen` needs onto the pure-domain models:
 *  - `Manga(api, language, title = mangatitle, url = mangaUrl, coverUrl = mangaImgUrl,
 *    rating = null, genres = emptyList())` — `rating`/`genres` aren't carried by the legacy route
 *    and aren't read by the Reader (no rating row, no chip bar). The `(api, language, title)`
 *    triple drives the VM's `OnEnter` re-entry guard.
 *  - `Chapter(number = chapterNumber, name = "", url = chapterUrl, date = null,
 *    isDownloaded = isDownload, isBookmarked = false)` — the legacy route carries only a
 *    `chapterNumber` string and NO chapter name, so `name` is left blank (GAP-RDR-16) rather than
 *    duplicating the number into it. The Reader top bar resolves its title through `ReaderScreen`'s
 *    shared `chapterDisplayTitle` fallback (`name.ifBlank { number }`), so the number still shows;
 *    keeping `name` blank means BOTH reader entry paths drive the title through the same fallback
 *    (the rework-native [ChapterImagesReworkScreenRoute] passes the real `args.chapterName`). `date`
 *    defaults to `null`; `isBookmarked` defaults to `false` (the Reader consults its own bookmark
 *    use case, not the nav arg).
 *
 * The reconstructed `manga` + `chapter` are passed to `ReaderScreen`, whose `OnEnter` intent
 * dispatch (keyed on the identity tuple) drives the fetch — no new intent is introduced for the
 * legacy-args path.
 *
 * **Parity blockers closed**: R3a (history recording, commit 2561caa) and R3b (isRead marking,
 * commit 21a12aa) brought the rework Reader to behavioural parity with the legacy Reader's
 * history/read-state side effects, which is what made this route-swap safe.
 *
 * Everything else mirrors [ChapterImagesReworkScreenRoute] verbatim: [HideNavigationBarSideEffect]
 * at the top, `koinViewModel()` [ReaderViewModel], `koinInject()` [PageProgressRepository] bridged
 * to `onReportProgress`, `onNavigateBack` → `safePopBackStack`, and `onOpenInWebView` → the legacy
 * `Screen.WebView` in-app browser. See [ChapterImagesReworkScreenRoute] KDoc for the full
 * three-layer (Koin DI / `:presentation` MVI / `:ui` Compose) rationale.
 *
 * **R5 cleanup leftovers** (not retired by this slice): the legacy [ChapterImagesScreenRoute]
 * file, the legacy `:shared` `ReaderViewModel`/`HistoryViewModel`/`SharedChaptersViewModel` Reader
 * wiring, and the Home call site's `sharedChaptersVm.setChaptersToReaderChaptersList(...)` (now a
 * harmless no-op — the rework Reader doesn't route chapter lists through `SharedChaptersViewModel`)
 * remain in place for the R5 retirement slice.
 *
 * @param navController parent nav controller for `safePopBackStack` on back-effect.
 * @param backStackEntry NavBackStackEntry — legacy args are read here via
 *                       `toRoute<Screen.ChapterImagesFragment>()`; the VM is `koinViewModel()`-scoped.
 */
@Composable
fun ChapterImagesByLegacyArgsReworkScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
) {
    // Hide the system navigation bar while this route is on the back stack; restore on dispose.
    // Mirrors [ChapterImagesReworkScreenRoute]; hosted at the route adapter — not inside
    // `:ui/.../ReaderScreen` — to keep `:ui` multiplatform-pure (Contract §4).
    HideNavigationBarSideEffect()

    val viewModel: ReaderViewModel = koinViewModel()

    // Per-page download/decode progress reporter bridged to the rework's in-memory progress
    // repository (mirrors [ChapterImagesReworkScreenRoute]).
    val pageProgressRepo: PageProgressRepository = koinInject()

    // Reader parity item #5 (share current page) + #6 (auto-403→WebView recovery). Both mirror
    // [ChapterImagesReworkScreenRoute] verbatim — the existing `:platform` ScreenshotProvider SPI
    // for the share, and the shared [rememberCloudflareChallengeSolver] helper for the 403
    // auto-recovery (WebView + auto-retry-on-return).
    val screenshotProvider: ScreenshotProvider = koinInject()
    val shareScope = rememberCoroutineScope()
    val solveCloudflare = rememberCloudflareChallengeSolver(
        navController = navController,
        ownerEntry = backStackEntry,
        onRetry = { viewModel.submit(ReaderIntent.OnRetry) },
    )

    val args = backStackEntry.toRoute<Screen.ChapterImagesFragment>()

    val manga = Manga(
        api = args.api,
        language = args.language,
        title = args.mangatitle,
        url = args.mangaUrl,
        coverUrl = args.mangaImgUrl,
        rating = null,
        genres = emptyList(),
    )

    val chapter = Chapter(
        number = args.chapterNumber,
        // GAP-RDR-16: the legacy [Screen.ChapterImagesFragment] arg tuple carries NO chapter name
        // (only `chapterNumber`). Leave `name` blank rather than masquerading the number as a name,
        // so the model honestly reflects what the route carries. The Reader top bar resolves its
        // title via `ReaderScreen`'s shared `chapterDisplayTitle` fallback (`name.ifBlank { number }`),
        // so a blank name still renders the chapter number — and BOTH reader entry paths (this
        // legacy-args adapter and the rework-native [ChapterImagesReworkScreenRoute], which passes the
        // real `args.chapterName`) now drive the top-bar title through the same fallback chain, with
        // no per-adapter divergence. History recording is unaffected: `HistoryRepositoryImpl.record`
        // persists `chapter.number` (not `name`) as the history row's chapter title.
        name = "",
        url = args.chapterUrl,
        date = null,
        isDownloaded = args.isDownload,
        isBookmarked = false,
    )

    ReaderHostSwitch(
        viewModel = viewModel,
        manga = manga,
        chapter = chapter,
        onNavigateBack = { navController.safePopBackStack() },
        onOpenInWebView = { url, api ->
            navController.safeNavigate(Screen.WebView(url, api))
        },
        // Reader parity item #5: encode the captured page bitmap to PNG and share via the
        // `:platform` SPI (mirrors [ChapterImagesReworkScreenRoute] — encode hops to
        // Dispatchers.Default to keep the tall-strip PNG encode off the main thread). Inline
        // literal share title copies the legacy chooser wording verbatim.
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
