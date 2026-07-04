package me.manga.kira.navigation.routes

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.presentation.updates.UpdatesViewModel
import me.manga.kira.ui.updates.UpdatesScreen
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route host for the Updates feed.
 *
 * **Phase 7.x.updates.swap** — this file previously hosted the legacy
 * `composeApp/.../features/notifications/ui/screens/UpdatesScreen` backed by the legacy
 * `NotificationsViewModel` + `DownloadViewModelv2` (commit history pre-`5b44b9c`; legacy
 * screen retired in §145, legacy VM retired in §144). The swap rewires the legacy
 * [Screen.Updates] entry to render the architecture-rework `:ui/.../updates/UpdatesScreen`
 * backed by the rework `:presentation/.../updates/UpdatesViewModel` (Koin-bound via
 * `updatesReworkModule`). Mirrors the swap shape from Phase 7.x.history.swap
 * (commit `eab80c0`) — single-file route adapter rewrite, no `Screen.kt` / `App.kt` /
 * `BottomNavigationBar.kt` changes (the route entry's identity stays `Screen.Updates`).
 *
 * Both the legacy [Screen.Updates] entry (bottom-nav tab) AND the parallel
 * [Screen.UpdatesRework] debug route now converge on the same rework screen, each scoped to
 * its own [NavBackStackEntry]-owned `UpdatesViewModel`. The single-scoped
 * `UpdatesRepositoryImpl` (a strangler-fig over the legacy
 * `:shared/.../notifications/domain/NotificationRepository`) means the underlying
 * `notifications` Room table is the same source of truth across both routes — list contents,
 * mark-read, delete, undo, and download mutations all propagate identically.
 *
 * Affordance parity vs the pre-swap legacy adapter:
 *  - **Manga-thumbnail tap** → `onNavigateToDetails(dest)` → [Screen.MangaDetailsRework]
 *    with the full identity tuple (the GAP-NAV fix — see the inline body comment;
 *    rating/genres aren't part of Updates data).
 *  - **Chapter-row tap** → `onNavigateToReader(entry)` →
 *    [Screen.ChapterImagesFragment] with the SAME field-for-field nav-arg mapping as the
 *    pre-swap `onNotificationClick` (line 44-61): `isHome = false`, `api`, `language`,
 *    `mangaId`, `chapterId = entry.chapterId`, `mangatitle`, `mangaUrl`, `mangaImgUrl`,
 *    `chapterNumber`, `chapterUrl`, `paths = entry.localImagePaths.takeIf { it.isNotEmpty() }`,
 *    `isDownload = entry.isDownloaded`. The legacy `ChapterNotification` Room entity carries
 *    `chapterId` as a dedicated field — no History-style "id-doubles-as-chapterId" quirk.
 *  - **Mark-read per row / Mark all read / Delete per row / Delete all** — internalised in
 *    the rework `UpdatesViewModel`'s reducer. The pre-swap callbacks
 *    (`onMarkAsRead` / `onMarkAllAsRead` / `onDeleteAll`) are no longer threaded by the
 *    route host because the rework screen submits the corresponding `UpdatesIntent`
 *    variants via its own state owner.
 *  - **Undo-snackbar on delete** (per-row delete with undo) — internalised in the rework
 *    VM + `:ui`-side `SnackbarHost` per Phase 7.x.updates.undosnackbar (commit `ab4adfa`).
 *    The pre-swap `onDeleteWithUndo` / `onUndoDelete` / `onConfirmDelete` callbacks are no
 *    longer threaded because the rework surface owns the entire stage / confirm / undo
 *    lifecycle internally.
 *  - **Per-row download trigger** — internalised in the rework VM per
 *    Phase 7.x.updates.downloadbutton.wire (commit `bad2e53`). The pre-swap
 *    `onNotificationDownloadClick` is no longer threaded because the rework
 *    `EnqueueDownloadUseCase` runs entirely on the `:domain` → `:data` → legacy facade path.
 *    Enqueue failures surface via `UpdatesEffect.ShowError` → the rework screen's
 *    `SnackbarHost`.
 *
 * Visual delta vs the pre-swap legacy screen:
 *  - Mark-read / Delete / Download affordances are labelled `TextButton`s rather than
 *    `IconButton`s — same icon-free posture as the rework Library / History / Statistics /
 *    Details / Reader screens (the rework `:ui` module deliberately omits
 *    `compose.materialIconsExtended`). No affordance loss.
 *  - The pre-swap screen used swipe-to-dismiss for delete; the rework uses a per-row
 *    `TextButton`. Trigger gesture differs; post-trigger flow + DB write semantics +
 *    snackbar Undo restore semantics are identical (per Phase 7.x.updates.undosnackbar
 *    audit in §132).
 *
 * @param navController parent nav controller for forwarding to manga-details / reader.
 * @param backStackEntry passed through for parity with sibling route adapters; unused — the
 *                      `UpdatesViewModel` is `koinViewModel()`-scoped via Koin's
 *                      `ViewModelStoreOwner` integration.
 *
 * **Audit-trail postscript** (Phase 9.x.mangadetailsswap.staleKdocSweep.cascade, Task #448,
 * 2026-05-28): two stale citations in the prose above, both cascade-attributable to
 * post-§253 retires:
 *  - Lines 15-18 (Phase 7.x.updates.swap paragraph): "backed by the legacy
 *    `NotificationsViewModel` + `DownloadViewModelv2` (commit history pre-`5b44b9c`;
 *    legacy screen retired in §145, legacy VM retired in §144)" attributes retirement
 *    of *one* legacy VM (NotificationsViewModel — retired in Phase 9.z.dead_vm_retire,
 *    §309 / cited "§144" is the file-internal phase number) but doesn't account for the
 *    second one — `DownloadViewModelv2` was independently retired in
 *    Phase 9.x.downloadvmv2.retire (§439) once its sole user-reachable callers became
 *    cascade-orphan after the §295 / §425 swap chain. Both legacy VMs are now gone;
 *    the pre-swap "backed by ... + DownloadViewModelv2" framing is historical record.
 *  - Line 35 (Affordance parity vs the pre-swap legacy adapter): "[Screen.MangaDetails]
 *    (the LEGACY manga-details route, identical to the pre-swap `onNotificationImgClick`
 *    target — see `UpdatesScreenRoute` pre-swap line 62-69)" describes the destination
 *    as the LEGACY route. Post-Phase 9.x.mangadetails.swap (§429, Slice 4 of the
 *    Phase 7.x.details.parity campaign), `composable<Screen.MangaDetails>` in App.kt
 *    routes to the rework [MangaDetailsByUrlReworkScreenRoute] adapter, not to the
 *    legacy `MangaDetailsScreenRoute` (which was deleted in §430, Slice 5). The route
 *    KEY `Screen.MangaDetails` remains valid per ADR-7 / ADR-8 — only the cited
 *    "LEGACY" framing of the destination is now stale. The wire has since been redirected
 *    further (GAP-NAV fix): `onNavigateToDetails` now emits `Screen.MangaDetailsRework`
 *    with the full identity tuple (see the inline body comment), no longer the URL-only
 *    `Screen.MangaDetails(mangaUrl, api)` shape this sweep re-verified.
 * Original prose preserved verbatim per §253 — both staleness facts are historical record
 * of the design lineage; the wire continues to work correctly through both retires.
 */
@Composable
fun UpdatesScreenRoute(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") backStackEntry: NavBackStackEntry,
) {
    val viewModel: UpdatesViewModel = koinViewModel()

    UpdatesScreen(
        viewModel = viewModel,
        onNavigateToDetails = { dest ->
            // Full-tuple rework route → DetailsIntent.OnEnter, so a saved manga binds its library
            // membership / title / cover from Room up-front instead of flashing an empty placeholder
            // and always refetching (the URL-only Screen.MangaDetails regression — GAP-NAV). Rating/
            // genres aren't part of Updates data; Details re-fetches them.
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
                    chapterId = entry.chapterId,
                    mangatitle = entry.mangaTitle,
                    mangaUrl = entry.mangaUrl,
                    mangaImgUrl = entry.mangaImageUrl,
                    chapterNumber = entry.chapterNumber,
                    chapterUrl = entry.chapterUrl,
                    paths = entry.localImagePaths.takeIf { it.isNotEmpty() },
                    isDownload = entry.isDownloaded,
                ),
            )
        },
    )
}
