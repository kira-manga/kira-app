package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.presentation.history.HistoryViewModel
import me.manga.kira.ui.history.HistoryScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the [me.manga.kira.navigation.Screen.History] nav entry — **Phase
 * 7.x.history.swap**.
 *
 * **What changed in this slice**: this adapter no longer renders the legacy
 * `:composeApp/.../features/history/ui/screens/HistoryScreen.kt` composable backed by the legacy
 * `me.manga.kira.presentation.features.history.ui.viewmodel.HistoryViewModel`. It now renders
 * the architecture-rework [me.manga.kira.ui.history.HistoryScreen] backed by
 * [me.manga.kira.presentation.history.HistoryViewModel] (Koin-bound via
 * `historyReworkModule`). The `Screen.History` route entry stays the same shape (`object History`),
 * so the existing caller (`BottomNavigationBar.kt:62` — bottom-nav tab) stays working without
 * modification. Both this route and the parallel `Screen.HistoryRework` route now converge on the
 * same rework screen + rework VM through different `NavBackStackEntry`-scoped VM instances (see
 * **Koin lifecycles** below).
 *
 * **Pre-conditions met by Phase 7.x.history (Task #239)**: the rework History slice already
 * shipped the full feature surface — date-grouped list (Today / Yesterday / N days ago / MMM d,
 * yyyy), per-row cover + manga title + chapter title + relative-date subtitle, per-row delete,
 * top-bar clear-all, empty state ("No reading history yet"), loading spinner. Both routes consume
 * the same legacy `:shared` `HistoryRepository` through the strangler-fig
 * [me.manga.kira.data.repository.HistoryRepositoryImpl], so the entries MUST agree across the
 * two routes for the same user data.
 *
 * **Affordance parity vs the legacy adapter**:
 *  - `onMangaClick` (legacy) → `HistoryIntent.OnMangaClick(entry)` → `HistoryEffect.NavigateToDetails`
 *    → [Screen.MangaDetailsRework] with the full identity tuple (the GAP-NAV fix — see the
 *    inline body comment; rating/genres aren't part of History data). The rework History screen
 *    collects `HistoryEffect` internally and forwards through the screen-level
 *    `onNavigateToDetails(dest)` callback this adapter supplies.
 *  - `onChapterClick` (legacy) → `HistoryIntent.OnChapterClick(entry)` → `HistoryEffect.NavigateToReader`
 *    → [Screen.ChapterImagesFragment] (legacy reader route — same target as legacy). The
 *    field-for-field nav-arg mapping is identical: `isHome = false`, `api = entry.api`,
 *    `language = entry.language`, `mangaId = entry.mangaId`, `chapterId = entry.id`,
 *    `mangatitle = entry.mangaTitle`, `mangaUrl = entry.mangaUrl`,
 *    `mangaImgUrl = entry.mangaImageUrl`, `chapterNumber = entry.chapterTitle` (legacy quirk —
 *    History row's chapter-title field doubles as the nav arg's `chapterNumber`),
 *    `chapterUrl = entry.chapterUrl`, `paths = entry.localImagePaths.takeIf { it.isNotEmpty() }`,
 *    `isDownload = entry.isDownloaded`. Same observable behaviour as the legacy adapter's
 *    `onChapterClick` handler at the prior `HistoryScreenRoute.kt` lines 44-61.
 *  - `onDeleteHistory(it)` (legacy) — per-row delete; the rework HistoryScreen wires the row's
 *    "Delete" TextButton to `HistoryIntent.OnDeleteEntry(entry)`. Same observable behaviour
 *    (per-row removal of the entry from the underlying Room table).
 *  - `onDeleteAllHistory()` (legacy) — top-bar clear-all; the rework HistoryScreen wires the
 *    top-bar "Clear all" TextButton to `HistoryIntent.OnDeleteAll`. Same observable behaviour
 *    (full History table clear).
 *
 * **Visual delta vs the legacy**: the legacy History screen uses
 * `compose.materialIconsExtended` icons (`.filled.DeleteForever` for clear-all,
 * `.outlined.Delete` for per-row); the rework substitutes labelled `TextButton`s ("Clear all" /
 * "Delete") — same posture as the icon-free rework Library / Details / Statistics screens. Phase
 * 10's i18n + icon-strategy decision will swap them to a `:ui`-local icon set if needed; the
 * deferral mirrors the rework HistoryScreen KDoc lines 71-78 verbatim. No affordance loss — every
 * legacy clickable affordance has a labelled rework counterpart.
 *
 * **Layer-boundary preservation**: same posture as [RepoSettingsScreenRoute] (Phase
 * 7.x.reposettings.swap, §123) / [StatisticsScreenRoute] (Phase 7.x.statistics.swap, §124) and
 * the other rework-screen adapters. `:ui` deliberately does NOT depend on `androidx.navigation`
 * (which is `:composeApp`-level wiring); the rework HistoryScreen exposes generic
 * `(api: String, mangaUrl: String) -> Unit` and `(HistoryEntry) -> Unit` callbacks so the screen
 * stays nav-host-agnostic. The route adapter at this layer threads the actual `navController.
 * safeNavigate(...)` calls.
 *
 * **Back-press delta vs the legacy adapter**: the legacy adapter did not pass an `onBack` to the
 * legacy HistoryScreen — History is a bottom-nav tab destination, not a pushed entry, so back is
 * already handled by the parent NavHost / bottom-nav state. The rework HistoryScreen's
 * `TopAppBar` has no `navigationIcon` for the same reason. Zero delta in observable back-press
 * behaviour.
 *
 * **MVI surface**: the rework's [HistoryViewModel] exposes a single `StateFlow<HistoryState>`
 * carrying the list of entries + `isLoading` + `isEmpty` flags (vs the legacy VM's `uiState`
 * StateFlow + direct `deleteHistory(...)` / `deleteAllHistory()` methods). All mutations flow
 * through `HistoryIntent` (`OnDeleteEntry` / `OnDeleteAll` / `OnMangaClick` / `OnChapterClick`).
 * One-shot nav effects (`HistoryEffect.NavigateToDetails` / `NavigateToReader`) are collected
 * internally by the rework HistoryScreen and forwarded to the route's `onNavigateToDetails` /
 * `onNavigateToReader` callbacks.
 *
 * **Koin lifecycles** — note that the [HistoryViewModel] resolved here via [koinViewModel] and
 * the one resolved by [HistoryReworkScreenRoute] are scoped to their respective NavBackStackEntry
 * (the `ViewModelStoreOwner` integration provided by `androidx.lifecycle.viewmodel.compose`). So
 * even though both routes ultimately render the same screen and rely on the same Koin binding,
 * each route's VM instance is independent. The underlying repository
 * ([me.manga.kira.domain.repository.HistoryRepository] — `single`-scoped via
 * `historyReworkModule`) is shared across both, so the persisted state is identical across the
 * two routes; any deletion on either route surfaces on the other through the upstream `Flow`
 * re-emit.
 *
 * The legacy composable file itself
 * (`composeApp/.../features/history/ui/screens/HistoryScreen.kt`) plus the legacy
 * `me.manga.kira.presentation.features.history.ui.viewmodel.HistoryViewModel` are no longer
 * user-reachable through this adapter, but stay on disk until **Phase 9.x route-swap retirement
 * sweep** retires them in a coordinated deletion sweep across all retired legacy screens.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster13.staleKdocSweep.cascade,
 * Task #469, 2026-05-28): multiple stale citations into the §357-retired
 * legacy `:composeApp/.../features/history/ui/screens/HistoryScreen.kt`
 * appear above (the path stated in the original prose was a §357-era
 * relocation from `:shared/.../HistoryScreen.kt` — both legacy
 * locations are now retired):
 *  - Lines 16-18 ("What changed in this slice" opener): "this adapter
 *    no longer renders the legacy `:composeApp/.../features/history/
 *    ui/screens/HistoryScreen.kt` composable backed by the legacy
 *    `me.manga.kira.presentation.features.history.ui.viewmodel.
 *    HistoryViewModel`".
 *  - Lines 35-57 ("Affordance parity vs the legacy adapter" 4
 *    sub-bullets): cite specific legacy adapter callbacks
 *    (`onMangaClick`, `onChapterClick`, `onDeleteHistory`,
 *    `onDeleteAllHistory`) + the prior `HistoryScreenRoute.kt`
 *    lines 38-43 + 44-61 references that no longer exist post-swap.
 *  - Lines 59-64 ("Visual delta vs the legacy" para): legacy icons
 *    (`.filled.DeleteForever`, `.outlined.Delete`) describe the
 *    retired legacy surface.
 *  - Lines 99-103 (retirement-forecast paragraph): "The legacy
 *    composable file itself plus the legacy
 *    `HistoryViewModel` are no longer user-reachable through this
 *    adapter, but stay on disk until **Phase 9.x route-swap
 *    retirement sweep** retires them in a coordinated deletion sweep".
 * The legacy `:shared/.../features/history/ui/screens/HistoryScreen.kt`
 * (along with `HistoryItem`) was retired in Phase 9.x.history.legacyui.retire
 * (§357 sweep, commits `5b7cd58` "(1/2): drop unreachable legacy
 * HistoryScreen + HistoryItem" + `8d714b1` "(2/2): docs close-out");
 * verified by a filesystem check returning zero hits for that path.
 * The lines 99-103 retirement forecast was a PARTIAL fulfilled
 * prediction — the legacy HistoryScreen composable WAS retired
 * exactly as anticipated, but the legacy
 * `me.manga.kira.presentation.features.history.ui.viewmodel.
 * HistoryViewModel` REMAINS LIVE post-§357 (per the inline §357
 * postscript on that VM at lines 39-41 — Reader's read-mark + insert/
 * update path keeps it on a 4-LIVE-member-reduced surface). The
 * affordance-parity rationales (manga-click → Details nav, chapter-
 * click → Reader nav with full field-for-field arg mapping including
 * the History-row `chapterTitle` → nav-arg `chapterNumber` legacy
 * quirk, per-row delete, top-bar clear-all) all stand on their own
 * merits past the §357 retire — they describe what the rework
 * adapter does, and the legacy comparisons are historical record of
 * the design lineage. The visual-delta enumeration (icons → labelled
 * TextButtons) stands as a design decision independent of the
 * now-retired legacy comparator. The same-`history_items`-Room-table
 * invariant stands — the `:data` impl continues to delegate to the
 * legacy `:shared` HistoryRepository which REMAINS LIVE post-§357.
 * The same-`Screen.History`-route-key invariant stands — both
 * `Screen.History` and `Screen.HistoryRework` continue to converge on
 * the same rework `:ui` screen, each scoped to its own
 * NavBackStackEntry-owned VM. The Back-press-delta posture (no
 * navigationIcon on bottom-nav destinations) stands. Original §253-
 * era prose preserved verbatim per the audit-trail-preservation
 * convention — the citations are historical record of the design
 * lineage; the rework HistoryScreenRoute remains LIVE as the
 * canonical renderer for `Screen.History` past the §357 retire.
 *
 * @param navController parent nav controller — used to forward to manga-details / reader.
 * @param backStackEntry passed through for parity with sibling route adapters; unused (the VM is
 *                      `koinViewModel()`-scoped via Koin's ViewModelStoreOwner integration).
 */
@Composable
fun HistoryScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: HistoryViewModel = koinViewModel()

    HistoryScreen(
        viewModel = viewModel,
        onNavigateToDetails = { dest ->
            // Full-tuple rework route → DetailsIntent.OnEnter, so a saved manga binds its library
            // membership / title / cover from Room up-front instead of flashing an empty placeholder
            // and always refetching (the URL-only Screen.MangaDetails regression — GAP-NAV). Rating/
            // genres aren't part of History data; Details re-fetches them.
            navController.safeNavigate(
                Screen.MangaDetailsRework(
                    api = dest.api,
                    language = dest.language,
                    title = dest.title,
                    url = dest.mangaUrl,
                    coverUrl = dest.coverUrl,
                    rating = null,
                    genres = emptyList(),
                ),
            )
        },
        onNavigateToReader = { entry ->
            navController.safeNavigate(
                Screen.ChapterImagesFragment(
                    isHome = false,
                    api = entry.api,
                    language = entry.language,
                    mangaId = entry.mangaId,
                    chapterId = entry.id,
                    mangatitle = entry.mangaTitle,
                    mangaUrl = entry.mangaUrl,
                    mangaImgUrl = entry.mangaImageUrl,
                    chapterNumber = entry.chapterTitle,
                    chapterUrl = entry.chapterUrl,
                    paths = entry.localImagePaths.takeIf { it.isNotEmpty() },
                    isDownload = entry.isDownloaded,
                ),
            )
        },
    )
}
