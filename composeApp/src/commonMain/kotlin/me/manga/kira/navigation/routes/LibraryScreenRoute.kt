package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.presentation.features.whatsnew.viewmodel.WhatsNewViewModel
import me.manga.kira.presentation.library.LibraryViewModel
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.presentation.common.componants.images.rememberSourceImageRequest
import me.manga.kira.ui.library.LibraryScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the user-facing `Screen.Library` entry.
 *
 * **Phase 9.x.library.swap (Task #346)** route-swap: this adapter now renders the rework
 * `:ui/.../library/LibraryScreen` via the Koin-bound rework `LibraryViewModel` from
 * `:presentation/.../library/`. Before this swap, the same `Screen.Library` binding rendered
 * the legacy `:presentation/features/library/ui/screens/LibraryScreen` via the legacy VM. The
 * §150 Library ladder closed at rung 19 (`§179` — `Phase 7.x.library.actionrow`, Task #345)
 * with full feature parity vs the legacy screen; the swap was unblocked at that point.
 *
 * Same swap shape as §138/§140/§285-§295/§301/§305 — the rework composable becomes the
 * canonical renderer for an existing `Screen.*` enum case; the parallel
 * `Screen.LibraryRework` debug route + adapter + legacy `LibraryScreen` retirement is a
 * follow-on `Phase 9.x.library.retire` slice (deferred to respect the 5-file commit cap).
 *
 * **Preserved at the adapter level**: the `WhatsNewViewModel` first-launch redirect
 * orchestration block. This is route-host-level launch plumbing (the user lands on this
 * screen as the root start destination on a non-first-launch session, and *iff* the app
 * version-code bumped since the last launch, this VM's `shouldShowWhatsNew` flips true and
 * the adapter navigates the user away to the What's New screen). It is NOT Library state —
 * hosting it in the rework `LibraryViewModel` would conflate two unrelated concerns
 * (SRP violation). Hosting it in `App.kt` would push composition state into the root
 * composable. The adapter is the right home, parallel to how the legacy adapter has hosted
 * it for the entire project lifetime.
 *
 * **Dropped from the legacy adapter**:
 *  - `RefreshViewModel` + `onRefreshLibrary` helper → rework VM owns refresh via §147/§148.
 *  - `AlertDialog` delete-confirmation → rework `:ui` owns its own ConfirmRemoveDialog (§144).
 *  - `DownloadViewModelv2` parameter → rework Library wires the download-progress badge
 *    via `ObserveActiveDownloadsUseCase` injected by `LibraryReworkModule` (§161).
 *  - `onOpenRandomClick: (State<List<MangaDisplayItem>>) -> Unit` → rework owns Random
 *    internally (§149 Task #315).
 *  - `onLibraryMangaClick: (Long) -> Unit` → rework uses `(Manga) -> Unit` from
 *    `LibraryEffect.NavigateToDetails`; the schema mismatch is handled here (`manga.url` +
 *    `manga.api` → `Screen.MangaDetails(mangaUrl, api)`). Same intentional schema delta as
 *    the §138/§140/§295 prior swaps.
 *  - `onDownloadClick` → no top-bar Downloads shortcut on rework Library yet. If user
 *    feedback wants this, an additive `Phase 7.x.library.downloadshortcut` slice adds it
 *    via `LibraryEffect.NavigateToDownloads`. OCP-friendly.
 *
 * **Manga URL/API mapping**: the rework `Manga` model (`:domain`) carries `url` and `api`
 * directly. Same shape `Screen.MangaDetails(mangaUrl, api)` consumes — no translation
 * needed. Wire-format compatibility for existing user libraries is preserved (contract §8 /
 * baseline §8); the source-identity tuple stays the same across the layer rework.
 *
 * @param navController parent nav controller for forwarding to `MangaDetails` and the
 *                      WhatsNew first-launch redirect.
 * @param backStackEntry passed through for parity with sibling route adapters; the rework
 *                       `LibraryViewModel` is `koinViewModel()`-scoped (process-wide on
 *                       Android via Koin's ViewModelStoreOwner integration), so we don't
 *                       consult `backStackEntry` for VM scoping here.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster35.staleKdocSweep.cascade,
 * Task #491, 2026-05-28): five stale citations appear in this file's
 * KDoc above, all referencing legacy Library + cascade-orphan
 * symbols that have since been retired:
 *  - Lines 25-27 ("the same `Screen.Library` binding rendered the
 *    legacy `:presentation/features/library/ui/screens/LibraryScreen`
 *    via the legacy VM").
 *  - Lines 29-32 ("the parallel `Screen.LibraryRework` debug route
 *    + adapter + legacy `LibraryScreen` retirement is a follow-on
 *    `Phase 9.x.library.retire` slice (deferred to respect the
 *    5-file commit cap)").
 *  - Line 45 ("`RefreshViewModel` + `onRefreshLibrary` helper →
 *    rework VM owns refresh via §147/§148").
 *  - Lines 47-48 ("`DownloadViewModelv2` parameter → rework Library
 *    wires the download-progress badge via `ObserveActiveDownloadsUseCase`").
 *  - Lines 49-50 ("`onOpenRandomClick: (State<List<MangaDisplayItem>>) -> Unit`").
 *  Five citations classified — four as STALE-SYMBOL-REFERENCE, one
 *  as FULFILLED-PREDICTION:
 *  (i) Lines 25-27 legacy `:presentation/features/library/ui/screens/
 *  LibraryScreen` is STALE — Phase 9.x.library.retire (§347) DELETED
 *  the legacy LibraryScreen + its parallel debug route + the legacy
 *  Library VM. A recursive grep for the cited path returns matches
 *  only in this file's own historical KDoc + documentation Markdown
 *  (PLAN_library_retire.md, PLAN_library_swap.md, ARCHITECTURE.md,
 *  SOLID_AUDIT.md) — the Kotlin source itself is retired.
 *  (ii) Lines 29-32 the "follow-on `Phase 9.x.library.retire` slice
 *  (deferred to respect the 5-file commit cap)" is FULFILLED — §347
 *  DELIVERED exactly this retirement (legacy LibraryScreen + parallel
 *  `Screen.LibraryRework` debug route + adapter all deleted, with
 *  the §348 cascade also dropping the dead `AnimatedPreloader`
 *  composable). The "deferred" forecast is fulfilled; the prediction
 *  STANDS as a historical record of the campaign sequencing.
 *  (iii) Line 45 `RefreshViewModel` is STALE — Phase 9.x.refreshvm.
 *  retire (§359) DELETED the orphan `:shared` `RefreshViewModel`. A
 *  recursive Glob for `RefreshViewModel.kt` returns NO MATCHES.
 *  (iv) Lines 47-48 `DownloadViewModelv2` is STALE — Phase 9.x.
 *  downloadvmv2.retire (§439) DELETED the cascade-orphan legacy
 *  download VM. A recursive Glob for `DownloadViewModelv2.kt`
 *  returns NO MATCHES.
 *  (v) Lines 49-50 `MangaDisplayItem` is STALE — Phase 9.x.
 *  mangadisplayitem.retire (§380) DELETED the orphan
 *  `MangaDisplayItem.kt` data class + dead `getDisplayItemsFlow`.
 *  The bare `MangaDisplayItem` symbol survives only as a string
 *  fragment in `:domain/.../LibraryManga.kt` (an unrelated
 *  identifier) plus historical docs; the dedicated data-class
 *  file is retired. The legacy `State<List<...>>` wrapper type is
 *  also STALE — superseded by the rework's MVI `LibraryState` +
 *  `LibraryEffect.NavigateToRandom` pattern. HOWEVER — this
 *  rework adapter for `Screen.Library` (different binding shape:
 *  Koin-bound `:presentation` LibraryViewModel + `:ui` LibraryScreen
 *  via `koinViewModel()`) is LIVE as the canonical render path
 *  for the `Screen.Library` enum case post-§346 route-swap; all
 *  five "Dropped from the legacy adapter" rationales STAND on their
 *  own merits past the §347/§359/§380/§439 fulfilled landings as
 *  LIVE rework realizations: (a) the WhatsNewViewModel first-launch
 *  redirect orchestration block is preserved verbatim in this
 *  adapter as route-host-level launch plumbing (NOT Library state,
 *  SRP-preserving); (b) the rework VM owns refresh via the
 *  reactive use-case chain (no `RefreshViewModel` needed —
 *  refresh is a `LibraryIntent.OnRefresh` arm); (c) the rework
 *  `:ui` owns its own `ConfirmRemoveDialog` (no shared `AlertDialog`
 *  delete-confirmation hoist); (d) download-progress badge wires
 *  via `ObserveActiveDownloadsUseCase` injected by
 *  `LibraryReworkModule` (no `DownloadViewModelv2` parameter); (e)
 *  the rework owns Random internally via `LibraryEffect.
 *  NavigateToRandom` (no `(State<List<MangaDisplayItem>>) -> Unit`
 *  callback); (f) the rework `Manga` model carries `url` + `api`
 *  directly (`onLibraryMangaClick: (Long) -> Unit` schema-delta
 *  preserved verbatim — no Long-id-vs-source-identity-tuple
 *  translation needed); (g) the `onDownloadClick` deferral
 *  ("If user feedback wants this, an additive `Phase 7.x.library.
 *  downloadshortcut` slice adds it via `LibraryEffect.
 *  NavigateToDownloads`. OCP-friendly.") STANDS as a deferred
 *  forecast LIVE — no user feedback has triggered the additive
 *  slice yet, so the gap remains intentional. The §138/§140/
 *  §285-§295/§301/§305 swap-shape references are LIVE phase
 *  numbers from the rework migration log (cumulative route-swap
 *  history). The §144/§147/§148/§149/§161/§179 internal phase
 *  numbers are LIVE migration history; the §150 Library ladder
 *  reference is LIVE as the canonical Library rework campaign
 *  identifier. Original Phase 9.x.library.swap-era prose preserved
 *  verbatim per the audit-trail-preservation convention — the
 *  citations are historical record of the design lineage including
 *  all five parity rationales that were subsequently fulfilled
 *  (legacy LibraryScreen + RefreshViewModel + DownloadViewModelv2
 *  + MangaDisplayItem retired) across §347/§359/§380/§439.
 */
@Composable
fun LibraryScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: LibraryViewModel = koinViewModel()
    val whatsNewViewModel: WhatsNewViewModel = koinViewModel()

    val shouldShowWhatsNew by whatsNewViewModel.shouldShowWhatsNew.collectAsState()
    val isLoading by whatsNewViewModel.isLoading.collectAsState()

    // Track if we've already navigated to prevent loops.
    var hasNavigatedToWhatsNew by remember { mutableStateOf(false) }

    // Only navigate to What's New if we should show it AND we haven't navigated yet.
    LaunchedEffect(shouldShowWhatsNew, isLoading) {
        if (shouldShowWhatsNew && !isLoading && !hasNavigatedToWhatsNew) {
            hasNavigatedToWhatsNew = true
            // Mark seen up-front (mark-on-enter), flipping shouldShowWhatsNew to false. Without
            // this, dismissing What's New pops back here and re-enters composition — which resets
            // the `hasNavigatedToWhatsNew` remember guard while shouldShowWhatsNew is still true,
            // so the redirect re-fires and the screen reopens until the next app restart. Mirrors
            // native, whose markWhatsNewAsSeen() flips the same flag false immediately.
            whatsNewViewModel.markSeen()
            navController.safeNavigate(Screen.WhatsNewScreen(true))
        }
    }

    // Reset the navigation flag only when shouldShowWhatsNew becomes false after being true.
    LaunchedEffect(shouldShowWhatsNew) {
        if (!shouldShowWhatsNew && hasNavigatedToWhatsNew) {
            hasNavigatedToWhatsNew = false
        }
    }

    LibraryScreen(
        viewModel = viewModel,
        onNavigateToDetails = { manga ->
            // Carry the FULL saved identity into Details (api + language + title + url + cover +
            // rating + genres) via the full-tuple rework route → DetailsIntent.OnEnter. This is
            // the regression fix for "opening a Library manga acts fresh": the legacy app opened
            // Library/History/Updates rows through `Screen.LibraryMangaDetails(mangaId)` which
            // loaded the *saved* row from Room and bound its state (library membership, read
            // progress) immediately. The rework unified on the network-details path, but routing
            // through the URL-only `Screen.MangaDetails(url, api)` shape threw the identity away
            // and only bound library membership AFTER the fetch landed (keyed on a blank
            // `language=""`), so the heart/title/cover flashed empty and the screen looked new.
            // Passing the full tuple makes `OnEnter` subscribe to library membership up-front with
            // the exact saved (api, language, title) triple and render the title/cover instantly.
            navController.safeNavigate(
                Screen.MangaDetailsRework(
                    api = manga.api,
                    language = manga.language,
                    title = manga.title,
                    url = manga.url,
                    coverUrl = manga.coverUrl,
                    rating = manga.rating,
                    genres = manga.genres,
                ),
            )
        },
        // Tapping the top-bar active-downloads badge navigates to the rework Downloads screen —
        // restoring the legacy Library's tap-to-navigate behaviour (fulfils the `onDownloadClick`
        // deferral documented in this adapter's KDoc). Routes to `Screen.DownloadsRework`, the same
        // rework Downloads key the MangaDetails downloads action navigates to.
        onNavigateToDownloads = { navController.safeNavigate(Screen.DownloadsRework) },
        // #32: build a source-aware Coil request per cover so library grids authenticate on
        // Cloudflare-protected sources whose cover CDN host differs from the source base host
        // (e.g. a source not visited yet this session). rememberSourceImageRequest hydrates the
        // per-source headers (one-shot ensureSiteInitialized) and is keyed on (url, api).
        coverModel = { item: LibraryManga ->
            rememberSourceImageRequest(url = item.manga.coverUrl, api = item.manga.api)
        },
    )
}
