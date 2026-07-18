package me.manga.kira.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed class Screen(val route: String) {

    @Serializable
    object Welcome : Screen("me.manga.kira.navigation.Screen.Welcome")

    @Serializable
    object Theme : Screen("me.manga.kira.navigation.Screen.Theme")

    @Serializable
    data class StartReading(
        val onboarding: Boolean = false,
    ) : Screen("me.manga.kira.navigation.Screen.StartReading")

    @Serializable
    object Sources : Screen("me.manga.kira.navigation.Screen.Sources")

    @Serializable
    object Home : Screen("me.manga.kira.navigation.Screen.Home")

    @Serializable
    object Library : Screen("me.manga.kira.navigation.Screen.Library")

    @Serializable
    object History : Screen("me.manga.kira.navigation.Screen.History")

    @Serializable
    object Updates : Screen("me.manga.kira.navigation.Screen.Updates")

    @Serializable
    object Setting : Screen("me.manga.kira.navigation.Screen.Setting")

    /** Internal-release-only fatal-crash harness used to verify Firebase Crashlytics delivery. */
    @Serializable
    object CrashDiagnostics : Screen("me.manga.kira.navigation.Screen.CrashDiagnostics")

    @Serializable
    data class WhatsNewScreen(
        val isFirstOpen: Boolean = false
    ) : Screen("me.manga.kira.navigation.Screen.WhatsNewScreen")

    @Serializable
    data class RepoSettings(
        val isFirstOpen: Boolean = false
    ) : Screen("me.manga.kira.navigation.Screen.RepoSettings")

    @Serializable
    data class MangaDetails(
        val mangaUrl: String,
        val api: String
    ) : Screen("me.manga.kira.navigation.Screen.MangaDetails")

    @Serializable
    data class ChapterImagesFragment(
        val isHome: Boolean = false,
        val api: String,
        val language: String,
        val mangaId: Long = 0,
        val chapterId: Long = 0,
        val mangatitle: String,
        val mangaUrl: String,
        val mangaImgUrl: String,
        val chapterNumber: String,
        val chapterUrl: String,
        val paths: List<String>?,
        val isDownload: Boolean,
    ) : Screen("me.manga.kira.navigation.Screen.ChapterImagesFragment")

    @Serializable
    data class WebView(
        val url: String,
        val api: String,
    ) : Screen("me.manga.kira.navigation.Screen.WebView")

    @Serializable
    object Complaint : Screen("me.manga.kira.navigation.Screen.Complaint")

    @Serializable
    object ComplaintAdmin : Screen("me.manga.kira.navigation.Screen.ComplaintAdmin")

    /**
     * Architecture-rework Library screen (Phase 8.y).
     * Architecture-rework Manga Details screen (Phase 8.x).
     *
     * Renders the new `:ui/.../details/DetailsScreen` composable backed by the rework
     * `DetailsViewModel` in `:presentation`, bound through `detailsReworkModule` in
     * `:composeApp/commonMain/di/`. Coexists with [MangaDetails] (legacy `MangaDetailsScreen`
     * in `:shared`) — both routable simultaneously, deliberately, for side-by-side comparison.
     *
     * **Argument shape** carries the full identity tuple of the pure-domain
     * `me.manga.kira.domain.model.Manga` (api + language + title + url + coverUrl + rating +
     * genres) so the route adapter can reconstruct the `Manga` instance without an extra
     * lookup — the screen needs `title` as the top-bar placeholder before the fetch lands, and
     * the (api, language, title) identity triple drives the VM's `OnEnter` re-entry guard
     * (§43.4 / §43.5). The legacy [MangaDetails] carries only `(mangaUrl, api)` because the
     * legacy screen looks up the rest from `SourcesRepository` + cached state.
     *
     * **Discoverability**: not surfaced in any user-facing entry yet. Reachable via
     * `navController.navigate(Screen.MangaDetailsRework(...))` from a future developer trigger
     * or a test/debug helper that holds the `NavController`. The rework Library route
     * ([LibraryRework]) currently navigates to the legacy [MangaDetails], not here — both
     * graphs stay independently exercisable until parity slices (image loading, share / bookmark
     * / download actions, sort+filter sheets) land.
     *
     * @see me.manga.kira.navigation.routes.MangaDetailsReworkScreenRoute
     *
     * **Audit-trail postscript** (Phase 9.x.cluster10.staleKdocSweep.cascade,
     * Task #466, 2026-05-28): three stale citations into the §430-retired
     * legacy `:shared/.../features/details/ui/screens/MangaDetailsScreen.kt`
     * appear above:
     *  - Lines 94-95 (coexistence bullet): "Coexists with [MangaDetails]
     *    (legacy `MangaDetailsScreen` in `:shared`) — both routable
     *    simultaneously, deliberately, for side-by-side comparison".
     *  - Lines 102-103 (argument-shape contrast): "The legacy
     *    [MangaDetails] carries only `(mangaUrl, api)` because the legacy
     *    screen looks up the rest from `SourcesRepository` + cached state".
     *  - Lines 107-110 (Library-route-navigates-to-legacy aside): "The
     *    rework Library route ([LibraryRework]) currently navigates to the
     *    legacy [MangaDetails], not here — both graphs stay independently
     *    exercisable until parity slices [...] land".
     * The legacy `:shared/.../features/details/ui/screens/
     * MangaDetailsScreen.kt` + legacy `MangaDerailsViewModel` + legacy
     * `:composeApp/.../navigation/routes/MangaDetailsScreenRoute.kt` were
     * retired in Phase 9.x.mangadetails.retire (§430 sweep, commits
     * `b0c6e98` "Slice 5a — delete legacy MangaDetails screen + route +
     * components" + `ecf1e65` "Slice 5b — drop legacy MangaDerailsViewModel
     * + Koin binding + orphan dialogs + KDoc fix"); verified by a
     * filesystem check returning zero hits for those paths. The argument-
     * shape contrast bullet has been resolved via §429 Slice 4's
     * `OnEnterByUrl` intent — both `(mangaUrl, api)` legacy callers and
     * full-tuple `Screen.MangaDetailsRework` callers now feed the same
     * rework `DetailsViewModel`, with the legacy two-arg shape hydrated
     * from the source fetch. The Library-route-navigates-to-legacy aside
     * is also resolved post-§429: the route-adapter swap at
     * `Screen.MangaDetails` re-points to the rework adapter, so the
     * rework Library route now navigates to the rework Details screen
     * (no more "legacy [MangaDetails], not here" topology). The
     * `Screen.MangaDetails` + `Screen.MangaDetailsRework` route keys both
     * remain LIVE per ADR-7/§429 — two routes, one screen, one VM.
     * Original §253-era prose preserved verbatim per the audit-trail-
     * preservation convention — the citations are historical record of
     * the design lineage; the rework `Screen.MangaDetailsRework` continues
     * to carry the canonical full-identity-tuple argument shape past the
     * §430 retire.
     */
    @Serializable
    data class MangaDetailsRework(
        val api: String,
        val language: String,
        val title: String,
        val url: String,
        val coverUrl: String,
        val rating: Int?,
        val genres: List<String>,
    ) : Screen("me.manga.kira.navigation.Screen.MangaDetailsRework")

    /**
     * Backup & restore (feature/backup). [scopeJson] is a JSON-encoded
     * `List<BackupScopeKey>` (see `navigation/routes/BackupReworkScreenRoute.kt`) naming the
     * mangas a scoped export covers; empty = full-library mode (Settings entry). One string arg
     * instead of a `List` NavType keeps titles separator-safe.
     */
    @Serializable
    data class BackupRework(
        val scopeJson: String = "",
        val completeStartFlowOnImport: Boolean = false,
    ) : Screen("me.manga.kira.navigation.Screen.BackupRework")

    /**
     * Architecture-rework Reader screen (Phase 8.x.reader).
     *
     * Renders the new `:ui/.../reader/ReaderScreen` composable backed by the rework
     * `ReaderViewModel` in `:presentation`, bound through `readerReworkModule` in
     * `:composeApp/commonMain/di/`. Coexists with [ChapterImagesFragment] (legacy `:shared`
     * Reader screen) — both routable simultaneously, deliberately, for side-by-side comparison.
     *
     * **Argument shape** carries the identity tuple needed to reconstruct the pure-domain
     * `Manga` and `Chapter` instances the screen's `OnEnter` intent expects:
     *  - Manga: (api, language, title, url, coverUrl) — `rating` / `genres` are not used by the
     *    Reader screen (no rating row, no chip bar) and are omitted to keep the nav arg payload
     *    minimal. The `(api, language, title)` triple is the VM's re-entry identity key
     *    (mirrors §43.4 / §43.5 for Details).
     *  - Chapter: (number, name, url) — these three are everything the Reader displays
     *    (`number` + `name` populate the top bar; `url` is the canonical fetch address).
     *    `date` / `isDownloaded` / `isBookmarked` are intentionally omitted: `date` is
     *    `LocalDate?` and would force a `kotlinx.datetime.LocalDateSerializer` registration on
     *    every nav graph just to carry an unused value, and `isDownloaded` / `isBookmarked`
     *    are Library-flow concerns the Reader doesn't read (when the bookmark / download
     *    slices land they'll consult their own repositories via use cases, not navigation).
     *    The route adapter reconstructs `Chapter` with `date = null` and the two booleans set
     *    to `false`.
     *
     * **Discoverability**: not surfaced in any user-facing entry yet. Reachable via
     * `navController.navigate(Screen.ChapterImagesRework(...))` from the [MangaDetailsRework]
     * adapter's `onNavigateToReader` callback (Phase 8.x.reader replaces the placeholder toast
     * shown by Phase 8.x). The legacy `Screen.MangaDetails` → `Screen.ChapterImagesFragment`
     * path is unchanged.
     *
     * @see me.manga.kira.navigation.routes.ChapterImagesReworkScreenRoute
     */
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

    /**
     * Architecture-rework Statistics screen (Phase 7.x.statistics).
     *
     * Renders the new `:ui/.../statistics/StatisticsScreen` composable backed by the rework
     * `StatisticsViewModel` in `:presentation`, bound through `statisticsReworkModule` in
     * `:composeApp/commonMain/di/`. Coexists with [Statistics] (legacy `StatisticsScreen` in
     * `:shared`) — both routable simultaneously, deliberately, for side-by-side comparison.
     *
     * Both routes consume the same legacy
     * [me.manga.kira.presentation.features.statistics.domain.StatisticsRepository] flows
     * under the hood (the rework's `:data` impl is strangler-fig over the legacy aggregates) —
     * so the numbers MUST agree across the two routes for the same user data. This is the
     * primary smoke-test for the slice.
     *
     * **No nav arguments**: Statistics is a pure display screen with no per-route input. The
     * eight aggregate numbers come from app-wide state (Room queries + read-minutes counter)
     * not from nav args.
     *
     * **Discoverability**: not surfaced in any user-facing entry yet. Reachable via
     * `navController.navigate(Screen.StatisticsRework)` from a future developer trigger or a
     * test/debug helper that holds the `NavController`. Phase 9.x route-swap retires the
     * legacy [Statistics] binding once parity is verified.
     *
     * @see me.manga.kira.navigation.routes.StatisticsReworkScreenRoute
     *
     * **Audit-trail postscript** (Phase 9.x.cluster9.staleKdocSweep.cascade,
     * Task #465, 2026-05-28): two stale citations into the §349-retired
     * legacy `:composeApp/.../features/statistics/ui/screens/StatisticsScreen.kt`
     * appear above:
     *  - Line 174 (coexistence-with-legacy bullet): "Coexists with
     *    [Statistics] (legacy `StatisticsScreen` in `:shared`) — both
     *    routable simultaneously, deliberately, for side-by-side
     *    comparison".
     *  - Lines 189-190 (retirement-forecast paragraph): "Phase 9.x
     *    route-swap retires the legacy [Statistics] binding once parity is
     *    verified".
     * The legacy `:composeApp/.../features/statistics/ui/screens/
     * StatisticsScreen.kt` + legacy `StatisticsViewModel` were retired in
     * Phase 9.x.statistics.retire (§349 sweep, commits `35084d6` "(1/2):
     * drop unreachable legacy Statistics screen + VM" + `0634428` "(2/2):
     * docs close-out"); verified by a filesystem check returning zero hits
     * for those paths. The lines 189-190 retirement forecast was a
     * fulfilled prediction — the "Phase 9.x route-swap retires the legacy
     * [Statistics] binding once parity is verified" materialised exactly as
     * anticipated through the §286 route-swap (Phase 7.x.statistics.swap)
     * followed by the §349 retire. Both routes (`Screen.Statistics` +
     * `Screen.StatisticsRework`) now render the rework Statistics screen,
     * and the `Screen.Statistics` route key is retained per the route-
     * adapter-swap pattern (the rework adapter handles the existing key
     * shape, so caller nav sites stay untouched). The same-upstream-
     * StatisticsRepository invariant stands — the `:data` impl
     * [me.manga.kira.data.repository.ReadingStatisticsRepositoryImpl]
     * continues to delegate to the legacy `:shared` `StatisticsRepository`
     * which REMAINS LIVE post-§349. Original §253-era prose preserved
     * verbatim per the audit-trail-preservation convention — the citations
     * are historical record of the design lineage; the rework
     * `Screen.StatisticsRework` continues to render the canonical
     * Statistics screen past the §349 retire.
     */
    @Serializable
    object StatisticsRework : Screen("me.manga.kira.navigation.Screen.StatisticsRework")

    /**
     * Architecture-rework Theme picker screen (Phase 7.x.theme).
     *
     * Renders the new `:ui/.../themepicker/ThemeScreen` composable backed by the rework
     * `ThemeViewModel` in `:presentation`, bound through `themeReworkModule` in
     * `:composeApp/commonMain/di/`. Coexists with [Theme] (legacy onboarding-flavoured
     * `ThemeSelectionScreen` in `:shared`) — both routable simultaneously, deliberately, for
     * side-by-side comparison.
     *
     * Both routes consume the SAME `darkMode` + `followSystem` `SharedPreferences` booleans
     * through the legacy
     * [me.manga.kira.presentation.features.settings.domain.SettingsRepository] facade — the
     * rework's `:data` impl is strangler-fig over it — so toggling the theme in EITHER route
     * flips the same two booleans, and the change propagates to the other screen via the
     * upstream `darkModeFlow` / `followSystemFlow` flow re-emit. This is the primary
     * smoke-test for the slice.
     *
     * **No nav arguments**: Theme is a flow-driven picker with no per-route input. The legacy
     * onboarding [Theme] route is part of the Welcome → Theme → Sources → RepoSettings →
     * Library wizard chain and exposes an `onContinue` callback to advance to the next
     * onboarding step, plus animated-background overlay + notification-permission grant
     * chrome. The rework slice deliberately omits all of that — the rework
     * `Screen.ThemeRework` is a standalone theme-picker surface, not part of an onboarding
     * wizard. Phase 9.x route-swap is its own slice; this slice only adds the parallel route.
     *
     * **Discoverability**: not surfaced in any user-facing entry yet. Reachable via
     * `navController.navigate(Screen.ThemeRework)` from a future developer trigger or a
     * test/debug helper that holds the `NavController`.
     *
     * **Audit-trail postscript** (Phase 9.x.cluster41.staleKdocSweep.cascade,
     * Task #497, 2026-05-28): three stale citations into the §307-retired
     * legacy `:shared/.../ThemeSelectionScreen.kt` + §307-retired
     * AnimatedBackground appear above, plus one fulfilled-forecast
     * citation and several ancillary references that require
     * disambiguation:
     *  - Lines 453-455 (coexistence bullet): "Coexists with [Theme]
     *    (legacy onboarding-flavoured `ThemeSelectionScreen` in
     *    `:shared`) — both routable simultaneously, deliberately, for
     *    side-by-side comparison".
     *  - Lines 465-471 (legacy onboarding chain paragraph): "the
     *    legacy onboarding [Theme] route is part of the Welcome →
     *    Theme → Sources → RepoSettings → Library wizard chain and
     *    exposes an `onContinue` callback to advance to the next
     *    onboarding step, plus animated-background overlay +
     *    notification-permission grant chrome".
     *  - Line 471 (forecast bullet): "Phase 9.x route-swap is its own
     *    slice; this slice only adds the parallel route".
     *  Classified as follows:
     *  (a) Lines 453-455 — STALE-SYMBOL-REFERENCE. The legacy
     *  `:shared/.../features/onboarding/ui/screens/
     *  ThemeSelectionScreen.kt` was DELETED in Phase 9.x.onboarding.
     *  legacy_retire (§307 sweep, commit `6c83364` "delete 5
     *  unreachable legacy onboarding files") — a recursive Glob for
     *  `ThemeSelectionScreen.kt` returns NO MATCHES. The
     *  "side-by-side comparison" framing is stale at the file level
     *  — `Screen.ThemeRework` is no longer compared against a
     *  separate legacy `:shared` ThemeSelectionScreen composable,
     *  because the legacy `Screen.Theme` route key's adapter
     *  (`ThemeSelectionScreenRoute.kt`) was REWRITTEN under §291
     *  (Phase 7.x.theme.swap) to host the SAME rework `:ui`
     *  ThemeScreen backed by the rework `ThemeViewModel`. Both
     *  `Screen.Theme` and `Screen.ThemeRework` now route to the same
     *  rework surface (each scoped to its own `NavBackStackEntry`-
     *  owned VM) — the same pattern §289 established for
     *  Updates / UpdatesRework (per §444 + §445 postscripts on the
     *  [UpdatesRework] KDoc above).
     *  (b) Lines 465-471 — STALE-SYMBOL-REFERENCE for three
     *  sub-cites in one paragraph. The "Welcome → Theme → Sources →
     *  RepoSettings → Library wizard chain" describes the legacy
     *  onboarding flow whose `:shared` composables were §307-
     *  retired; the chain itself survives in modified form as route-
     *  adapters that now dispatch to rework `:ui` screens (per
     *  §286/§289-§295/§301/§305 swap landings + the §291 theme
     *  swap). The `onContinue` callback survives LIVE on the rework
     *  `:ui` ThemeScreen (per the three deferred-onboarding
     *  parameters added in §136/§137: `onContinue`,
     *  `hasNotificationPermission`, `onRequestNotificationPermission`)
     *  and is wired by the rewritten `ThemeSelectionScreenRoute.kt`
     *  when routing the wizard's step 2. The "animated-background
     *  overlay" cite is stale at the file level — the legacy
     *  `:composeApp/.../presentation/features/onboarding/components/
     *  AnimatedBackground.kt` was DELETED in §307 along with 4
     *  sibling legacy onboarding files (recursive Glob returns NO
     *  MATCHES); the cosmetic-port intent survives as a deferred
     *  design decision per §142 migration log L795
     *  `Brush.linearGradient`-sweep substitution pattern. The
     *  "notification-permission grant chrome" cite is LIVE in
     *  modified form — the cross-platform `expect`/`actual`
     *  `NotificationPermissionRequester` interface in
     *  `:composeApp/commonMain/.../core/platform/` (with Android
     *  actual at `:composeApp/androidMain/.../
     *  RememberNotificationPermissionRequester.android.kt`)
     *  continues to gate the wizard step 2 grant, but the legacy
     *  one-file `NotificationPermissionRequester` helper class that
     *  lived inside `ThemeSelectionScreenRoute.kt` was rewritten
     *  into the cross-platform `:platform` shape under §291; the
     *  prose description of "grant chrome" stands as a LIVE
     *  description of the user-facing surface but the cite-target
     *  helper class shape is now historical.
     *  (c) Line 471 — FULFILLED-PREDICTION. The forecast "Phase 9.x
     *  route-swap is its own slice" was FACTUALLY FULFILLED EARLY
     *  by §291 (Phase 7.x.theme.swap, NOT 9.x as the forecast
     *  predicted) — the §291 swap re-pointed `Screen.Theme`'s
     *  rendering adapter (`ThemeSelectionScreenRoute.kt`) to host
     *  the rework `:ui` ThemeScreen backed by the rework
     *  `ThemeViewModel`. The "this slice only adds the parallel
     *  route" framing was historically accurate at §253-era
     *  authoring (when both `Screen.Theme` + `Screen.ThemeRework`
     *  rendered different surfaces) but post-§291 both routes
     *  render the SAME rework screen — the parallel-routes
     *  invariant is preserved, but the side-by-side comparison
     *  framing is no longer literally accurate.
     *  Ancillary references in the same KDoc are LIVE-NOT-STALE and
     *  require no individual stale-classification on their own
     *  merits:
     *  (d) Lines 451-452 — `:ui/.../themepicker/ThemeScreen` cite +
     *  `ThemeViewModel` cite + `themeReworkModule` Koin module cite
     *  all resolve LIVE (`ui/.../themepicker/ThemeScreen.kt` is
     *  present, `presentation/.../theme/ThemeViewModel.kt` is
     *  present, `composeApp/.../di/ThemeReworkModule.kt` is wired
     *  into the active Koin graph);
     *  (e) Lines 457-462 — the strangler-fig "consume the SAME
     *  `darkMode` + `followSystem` `SharedPreferences` booleans
     *  through the legacy `[SettingsRepository]` facade" reference
     *  resolves LIVE — `:shared/.../features/settings/domain/
     *  SettingsRepository.kt` is present as the strangler-fig
     *  back-end, the `darkModeFlow` / `followSystemFlow` /
     *  `setDarkMode` / `setFollowSystem` four-member surface
     *  survives on its interface, and the rework `:data`
     *  `ThemeRepositoryImpl` continues to delegate to it (per the
     *  cluster11 / §467 postscript on `ThemeRepositoryImpl.kt`);
     *  (f) Line 466 — `[Theme]` Dokka link to the legacy
     *  `Screen.Theme` route key resolves LIVE (the route key
     *  remains in this file post-§291 swap; only the underlying
     *  adapter's rendered composable changed, not the route key
     *  itself — same pattern preserved across §289/§291/§293/§295
     *  early swap landings);
     *  (g) Lines 473-475 — discoverability paragraph "Reachable via
     *  `navController.navigate(Screen.ThemeRework)`" resolves LIVE
     *  (the route is wired in `composeApp/.../App.kt`'s nav graph
     *  via `composable<Screen.ThemeRework> { ... }`).
     *  Original §253-era prose preserved verbatim per the
     *  audit-trail-preservation convention — the citations are
     *  historical record of the design lineage including the
     *  §307-retired ThemeSelectionScreen + AnimatedBackground
     *  cites and the §291-fulfilled-early route-swap forecast; the
     *  rework `Screen.ThemeRework` continues to render the
     *  canonical Theme picker surface past the §307 retire +
     *  §291 swap.
     *
     * @see me.manga.kira.navigation.routes.ThemeReworkScreenRoute
     */
    @Serializable
    object ThemeRework : Screen("me.manga.kira.navigation.Screen.ThemeRework")

    /**
     * Architecture-rework About screen (Phase 7.x.about).
     *
     * Renders the new `:ui/.../about/AboutScreen` composable backed by the rework
     * `AboutViewModel` in `:presentation`, bound through `aboutReworkModule` in
     * `:composeApp/commonMain/di/`. Coexists with [AboutScreen] (legacy `AboutScreen` in
     * `:composeApp/.../features/about/screen/`) — both routable simultaneously, deliberately,
     * for side-by-side comparison.
     *
     * Both routes consume the SAME `versionName` + `packageName` from the legacy `:shared`
     * [me.manga.kira.core.platform.AppVersionProvider] facade — the rework's `:data` impl is
     * strangler-fig over it — so the version + package strings MUST agree across the two
     * routes. This is the primary smoke-test for the slice.
     *
     * **No nav arguments**: About is a flow-driven metadata display with no per-route input. The
     * three actionable rows (Check for update, Rate our app, Privacy policy) all dispatch to the
     * legacy [me.manga.kira.core.platform.IntentLauncher] facade — same target as the legacy
     * [AboutScreen] route uses. Phase 9.x route-swap retires the legacy [AboutScreen] binding
     * once parity is verified.
     *
     * **Reduced surface vs the legacy [AboutScreen]**: the rework slice omits the Whats-new row
     * (no `Screen.WhatsNewRework` yet), the disabled Source-code row (legacy already no-op), the
     * app-icon image (no compose-resources binding in `:ui/commonMain`), and the SocialMediaRow
     * (needs `material-icons-extended`, forbidden in the rework `:ui` module). All four lift in
     * follow-on slices once their respective dependencies land — strict-MVI OCP §6 keeps the
     * MVI surface open to extension (sealed `AboutIntent` / `AboutEffect` accept new variants
     * without breaking existing ones).
     *
     * **Discoverability**: not surfaced in any user-facing entry yet. Reachable via
     * `navController.navigate(Screen.AboutRework)` from a future developer trigger or a
     * test/debug helper that holds the `NavController`. The legacy [AboutScreen] route remains
     * bound to the legacy `AboutScreenRoute` (with its Whats-new + Source-code + SocialMediaRow
     * surface).
     *
     * **Audit-trail postscript** (Phase 9.x.cluster10.staleKdocSweep.cascade,
     * Task #466, 2026-05-28): three stale citations into the §354-retired
     * legacy `:composeApp/.../features/about/screen/AboutScreen.kt`
     * appear above:
     *  - Lines 455-457 (coexistence bullet): "Coexists with [AboutScreen]
     *    (legacy `AboutScreen` in `:composeApp/.../features/about/screen/`)
     *    — both routable simultaneously, deliberately, for side-by-side
     *    comparison".
     *  - Lines 467-468 ("nav arguments" para): "the legacy [AboutScreen]
     *    route uses. Phase 9.x route-swap retires the legacy [AboutScreen]
     *    binding once parity is verified".
     *  - Lines 480-482 ("Discoverability" para): "The legacy [AboutScreen]
     *    route remains bound to the legacy `AboutScreenRoute` (with its
     *    Whats-new + Source-code + SocialMediaRow surface)".
     * The legacy `:composeApp/.../features/about/screen/AboutScreen.kt`
     * was retired in Phase 9.x.legacysettings.retire (§354 sweep, commit
     * `5cc42d2` "11-file legacy Settings+About retire"); verified by a
     * filesystem check returning zero hits for that path. The lines
     * 467-468 retirement forecast was a fulfilled prediction — the
     * "Phase 9.x route-swap retires the legacy [AboutScreen] binding
     * once parity is verified" materialised exactly as anticipated. The
     * coexistence framing is stale at the file level — `Screen.AboutRework`
     * is the sole About rendering surface; the legacy `Screen.AboutScreen`
     * route key remains in this file (callers untouched) but its
     * rendering adapter no longer points at the retired
     * `:composeApp/.../features/about/screen/AboutScreen.kt`. The
     * same-`versionName` + `packageName` invariant stands — the `:data`
     * impl continues to delegate to the legacy `:shared`
     * `AppVersionProvider` facade which REMAINS LIVE post-§354. The
     * three actionable rows continue to dispatch through the
     * `IntentLauncher` facade. The reduced-surface disclaimer's
     * deferrals (Whats-new row + Source-code row + app-icon image +
     * SocialMediaRow) all stand on their own merits — strict-MVI OCP §6
     * keeps the MVI surface open to extension. Original §253-era prose
     * preserved verbatim per the audit-trail-preservation convention —
     * the citations are historical record of the design lineage; the
     * rework `Screen.AboutRework` continues to render the canonical
     * About surface past the §354 retire.
     *
     * @see me.manga.kira.navigation.routes.AboutReworkScreenRoute
     */
    @Serializable
    object AboutRework : Screen("me.manga.kira.navigation.Screen.AboutRework")

    /**
     * Architecture-rework What's New screen (Phase 7.x.whatsnew foundation).
     *
     * Renders the new `:ui/.../whatsnew/WhatsNewScreen` composable backed by the rework
     * `WhatsNewViewModel` in `:presentation`, bound through `whatsNewReworkModule` in
     * `:composeApp/commonMain/di/`. Coexists with [WhatsNewScreen] (legacy `WhatsNewScreen` in
     * `:composeApp/.../features/whatsnew/ui/`) — both routable simultaneously, deliberately, for
     * side-by-side comparison.
     *
     * Both routes consume the SAME upstream wire — the legacy `:shared`
     * [me.manga.kira.presentation.features.whatsnew.data.WhatsNewRemoteDataSource] Ktor fetcher
     * and the SAME `whats_new_last_shown_*` prefs keys via [me.manga.kira.core.storage.SharedPrefsHelper]
     * — so marking-seen on EITHER route updates the same persisted state, and the feature list
     * is identically sourced. This is the primary smoke-test for the slice.
     *
     * **No nav arguments**: WhatsNew is a flow-driven feature display with no per-route input.
     * The legacy [WhatsNewScreen] data class carries `isFirstOpen: Boolean` for the onboarding-
     * vs-update branch (which controls back-button behaviour and post-dismiss nav). The rework
     * foundation deliberately omits that — the standalone rework route is not part of any
     * onboarding wizard; the gate (auto-trigger on app launch) deferred to
     * `Phase 7.x.whatsnew.gate`.
     *
     * **Reduced surface vs the legacy [WhatsNewScreen]**: the rework foundation slice omits:
     *  - HorizontalPager + tab indicators + nav arrows (defer to `Phase 7.x.whatsnew.pager`).
     *  - Coil image rendering for `imageResName` / `imageUrl` / `imageUrlList`
     *    (defer to `Phase 7.x.whatsnew.images` — needs `coil3-compose` in `:ui` and
     *    load-bearing-fix preservation audit for the singleton ImageLoader).
     *  - Platform `VideoPlayer` for `videoUrl` (defer to `Phase 7.x.whatsnew.video` — needs the
     *    `:platform` MediaPlayer SPI to land first).
     *  - FullscreenMediaViewer + pinch-to-zoom overlay (defer to `Phase 7.x.whatsnew.fullscreen`,
     *    after `.images` + `.video`).
     *  - Auto-trigger / should-show gate (defer to `Phase 7.x.whatsnew.gate` — comparator Flow
     *    between current `versionName` + persisted last-shown version + presence of features).
     *  - i18n: hardcoded `"en"` language code (defer to Phase 10 i18n lift).
     *
     * All deferrals lift via strict-MVI OCP §6 — sealed `WhatsNewIntent` / `WhatsNewEffect`
     * accept new variants without breaking existing ones; `WhatsNewState` accepts new fields
     * additively.
     *
     * **Discoverability**: not surfaced in any user-facing entry yet. Reachable via
     * `navController.navigate(Screen.WhatsNewRework)` from a future developer trigger or a
     * test/debug helper that holds the `NavController`. The legacy [WhatsNewScreen] route
     * remains bound to the legacy `WhatsNewScreenRoute` (with its HorizontalPager + image/video
     * + fullscreen-viewer surface).
     *
     * @see me.manga.kira.navigation.routes.WhatsNewReworkScreenRoute
     */
    @Serializable
    object WhatsNewRework : Screen("me.manga.kira.navigation.Screen.WhatsNewRework")

    /**
     * Architecture-rework Language picker route (Phase 7.x.language foundation).
     *
     * Renders the new `:ui/.../language/LanguageScreen` composable backed by the rework
     * [me.manga.kira.presentation.language.LanguageViewModel], bound through
     * `languageReworkModule` in `:composeApp/commonMain/di/`. Coexists with [LanguageScreen]
     * (legacy `LanguageScreenRoute` in `:composeApp/.../navigation/routes/`) — both routable
     * simultaneously, deliberately, for side-by-side comparison.
     *
     * Both routes consume the SAME upstream wire — the legacy `:shared`
     * [me.manga.kira.presentation.features.settings.domain.SettingsRepository] facade's
     * `languageFlow` + `setLanguage` pair (DataStore-backed via `DataStoreHelper.languageFlow`).
     * So picking a language in either route writes the same IETF tag and the other screen
     * reflects it via the upstream `languageFlow` re-emit. This is the primary smoke-test for
     * the slice. Both routes ALSO fire the same `core.locale.applyApplicationLocale(code)`
     * platform locale-switch side effect — Android recreates the activity tree under the new
     * locale; iOS/Desktop are no-ops (the persisted preference takes effect on next launch per
     * `LocaleSwitcher.kt`).
     *
     * **No nav arguments**: Language is a flat picker of 11 supported entries with no
     * per-route input. The legacy [LanguageScreen] data class also carries no args (the
     * legacy `LanguageScreenRoute` passes a hardcoded `availableLanguages` list constructed
     * in-route from a local map). The rework reads the list from the
     * [me.manga.kira.presentation.language.LanguageState.languages] field (sourced from
     * the `:data` impl's compile-time constant) — no route arg needed.
     *
     * **Reduced surface vs the legacy [LanguageScreen]**: the rework foundation slice omits:
     *  - "Request a new language" entry + [me.manga.kira.presentation.common.componants.dialogs.FeedbackDialog]
     *    + [me.manga.kira.presentation.features.complaint.viewmodel.ComplaintViewModel]
     *    + Snackbar success/failure host (defer to `Phase 7.x.language.request` — adds
     *    `LanguageIntent.OnRequestLanguage` + `LanguageEffect.ShowLanguageRequestResult`
     *    variants and a `ComplaintRepository` use case in `:domain`, all OCP appends to the
     *    existing MVI surface).
     *  - i18n: hardcoded inline English labels (defer to Phase 10 i18n lift, which routes
     *    both legacy and rework consumers through the `:data`
     *    `LanguageRepositoryImpl.SUPPORTED_LANGUAGES` list in one pass).
     *
     * All deferrals lift via strict-MVI OCP §6 — sealed
     * [me.manga.kira.presentation.language.LanguageIntent] /
     * [me.manga.kira.presentation.language.LanguageEffect] accept new variants without
     * breaking the existing one
     * ([me.manga.kira.presentation.language.LanguageIntent.OnSelectLanguage]).
     *
     * **Discoverability**: not surfaced in any user-facing entry yet. Reachable via
     * `navController.navigate(Screen.LanguageRework)` from a future developer trigger or a
     * test/debug helper that holds the `NavController`. The legacy [LanguageScreen] route
     * remains bound to the legacy `LanguageScreenRoute` (with its Request-Language dialog +
     * Snackbar host).
     *
     * @see me.manga.kira.navigation.routes.LanguageReworkScreenRoute
     */
    @Serializable
    object LanguageRework : Screen("me.manga.kira.navigation.Screen.LanguageRework")

    /**
     * Architecture-rework user-side Complaint LIST route (Phase 7.x.complaint.foundation —
     * "Feedback Manager" screen).
     *
     * Renders the new `:ui/.../complaint/ComplaintScreen` composable backed by the rework
     * [me.manga.kira.presentation.complaint.ComplaintViewModel], bound through
     * `complaintReworkModule` in `:composeApp/commonMain/di/`. Coexists with [Complaint] (legacy
     * `ComplaintScreenRoute` in `:composeApp/.../navigation/routes/`) — both routable
     * simultaneously, deliberately, for side-by-side comparison.
     *
     * Both routes consume the SAME upstream Firestore `complaints` collection via the legacy
     * [me.manga.kira.presentation.features.complaint.usecase.GetUserComplaintUseCase] — the
     * rework's `:data` impl is strangler-fig over it — so a submission via the legacy
     * [me.manga.kira.presentation.features.complaint.viewmodel.ComplaintViewModel.sendComplaint]
     * path or via the Request-Language slice's
     * [me.manga.kira.presentation.language.LanguageViewModel] surfaces on BOTH list screens.
     * This is the primary smoke-test for the slice.
     *
     * **No nav arguments**: the user-side LIST is a flat collection-of-submissions screen with
     * no per-route input. The user/device identity that filters the query is sourced
     * server-side via [me.manga.kira.domain.auth.UserIdProvider] (platform-stable), not from
     * nav args.
     *
     * **Reduced surface vs the legacy [Complaint]**: the rework foundation slice omits:
     *  - Reply / edit / delete dialogs (defer to `Phase 7.x.complaint.actions` — additive sealed
     *    intent variants `OnReply` / `OnEdit` / `OnDelete` + matching effect variants for the
     *    confirmation Snackbar host).
     *  - `onShowMessage` -> [me.manga.kira.core.platform.ToastShower] feedback wiring (same
     *    deferral — the action slice introduces the Snackbar host in the route adapter).
     *  - `onHelp` URL (legacy 403-permission-denied branch — defers with the action slice).
     *  - i18n: hardcoded inline English labels (defer to Phase 10 i18n lift, which routes both
     *    legacy and rework consumers through `Res.string.*` in one pass).
     *
     * All deferrals lift via strict-MVI OCP §6 — sealed
     * [me.manga.kira.presentation.complaint.ComplaintIntent] /
     * [me.manga.kira.presentation.complaint.ComplaintEffect] accept new variants without
     * breaking the existing four intents (`OnRetry`, `OnSearchChange`, `OnStatusFilter`,
     * `OnClearSearch`) or the (empty) effect surface.
     *
     * **Discoverability**: not surfaced in any user-facing entry yet. Reachable via
     * `navController.navigate(Screen.ComplaintRework)` from a future developer trigger or a
     * test/debug helper that holds the `NavController`. The legacy [Complaint] route remains
     * bound to the legacy [me.manga.kira.navigation.routes.ComplaintScreenRoute] (with its
     * reply / edit / delete dialog surface). The unrelated admin-side [ComplaintAdmin] route
     * is untouched and continues to host the
     * [me.manga.kira.navigation.routes.AdminComplaintScreenRoute].
     *
     * @see me.manga.kira.navigation.routes.ComplaintReworkScreenRoute
     */
    @Serializable
    object ComplaintRework : Screen("me.manga.kira.navigation.Screen.ComplaintRework")

    /**
     * Architecture-rework admin Complaint dashboard route (Phase 7.x.complaint.admin foundation —
     * "Admin Complaints" screen).
     *
     * Renders the new `:ui/.../complaint/admin/AdminComplaintScreen` composable backed by the
     * rework [me.manga.kira.presentation.complaint.admin.AdminComplaintViewModel], bound
     * through `complaintAdminReworkModule` in `:composeApp/commonMain/di/`. Coexists with
     * [ComplaintAdmin] (legacy `AdminComplaintScreenRoute` in
     * `:composeApp/.../navigation/routes/`) — both routable simultaneously, deliberately, for
     * side-by-side comparison.
     *
     * Both routes consume the SAME upstream Firestore `complaints` collection — the legacy admin
     * screen via `AdminComplaintViewModel.loadAll` (`:shared`); the rework admin screen via
     * [me.manga.kira.domain.usecase.complaint.ObserveAllComplaintsUseCase] →
     * [me.manga.kira.data.repository.AdminComplaintListRepositoryImpl] → legacy `:shared`
     * `GetAllComplaintUseCase`. A submission via the legacy user-side
     * [me.manga.kira.presentation.features.complaint.viewmodel.ComplaintViewModel.sendComplaint]
     * path or via the Request-Language slice's
     * [me.manga.kira.presentation.language.LanguageViewModel] surfaces on BOTH admin LIST
     * screens. This is the primary smoke-test for the slice.
     *
     * **No nav arguments**: the admin LIST is a flat collection-wide screen with no per-route
     * input. The collection-wide query (no userId filter) is what distinguishes admin from the
     * user-side [ComplaintRework] — admin sees EVERY user's submissions; the user-side view sees
     * only the current user's own.
     *
     * **Admin-only access**: navigation TO this route is gated by
     * [me.manga.kira.admin.Admin.isAdmin] in the Settings hub adapter (see
     * [me.manga.kira.navigation.routes.SettingsReworkScreenRoute]). The route itself does
     * NOT re-check admin status — same posture as the legacy [ComplaintAdmin] route, which also
     * delegates the gate to its caller. If a non-admin somehow lands here (e.g., via a deep link
     * in a debug build), the underlying Firestore query will surface a 403-permission-denied
     * error which the screen's inline error pattern handles gracefully.
     *
     * **Reduced surface vs the legacy [ComplaintAdmin]**: the rework foundation slice omits:
     *  - Status-change dialog (legacy 6-status menu) — deferred to
     *    `Phase 7.x.complaint.admin.actions` (additive sealed intent variants
     *    `OnSelectComplaintForStatusChange` / `OnConfirmStatusChange`).
     *  - Edit dialog (legacy admin edit of complaint subject + body) — same deferral
     *    (`OnEditComplaint` intent + matching effect for the snackbar confirmation).
     *  - Closure-reason dialog (legacy admin closure-reason field) — same deferral
     *    (`OnAddClosureReason` intent + matching effect).
     *  - Delete dialog with confirmation — same deferral (`OnDeleteComplaint` intent + matching
     *    effect).
     *  - Bulk-update / bulk-delete via long-press multi-select — same deferral.
     *  - Statistics aggregation card (legacy admin top-of-screen "X open, Y resolved" summary) —
     *    same deferral (`OnToggleStatsCard` intent + a `state.stats` substate).
     *  - Sort dropdown (7 sort modes: DATE_ASC / DATE_DESC / STATUS / TYPE / USER_ID /
     *    APP_VERSION / APP_VERSION_DESC) — same deferral (`OnSortChange(SortOption)` intent +
     *    a `state.sortOption` field).
     *  - App-version filter chip — same deferral (`OnAppVersionFilter(version)` intent + a
     *    `state.selectedAppVersion` field).
     *  - Long-press body-copy affordance — same deferral (`CopyToClipboard(text)` effect).
     *  - i18n: hardcoded inline English labels (defer to Phase 10 i18n lift).
     *
     * All deferrals lift via strict-MVI OCP §6 — sealed
     * [me.manga.kira.presentation.complaint.admin.AdminComplaintIntent] /
     * [me.manga.kira.presentation.complaint.admin.AdminComplaintEffect] accept new variants
     * without breaking the existing five intents (`OnRetry`, `OnSearchChange`, `OnClearSearch`,
     * `OnStatusFilter`, `OnTypeFilter`) or the (empty) effect surface.
     *
     * **Two filter axes vs the user-side's one**: the admin foundation surface matches the
     * legacy admin's 2-axis filter (status + type), vs the user-side [ComplaintRework]'s
     * status-only filter. Parity intent, parity surface — legacy admin filters by both axes
     * (`AdminComplaintScreen.kt:140-141`), legacy user-side filters by status only.
     *
     * **Discoverability**: reachable from the rework Settings hub via the
     * `OnNavigate(COMPLAINT)` intent when [me.manga.kira.admin.Admin.isAdmin] is `true`. The
     * legacy [ComplaintAdmin] route remains bound to
     * [me.manga.kira.navigation.routes.AdminComplaintScreenRoute] (with its 6 mutation
     * dialogs + statistics card + sort dropdown + app-version filter); Phase 9.x route-swap
     * retires the legacy admin route once parity slices land.
     *
     * @see me.manga.kira.navigation.routes.AdminComplaintReworkScreenRoute
     */
    @Serializable
    object ComplaintAdminRework : Screen("me.manga.kira.navigation.Screen.ComplaintAdminRework")

    /**
     * Architecture-rework Downloads screen (Phase 7.x.downloads.foundation).
     *
     * Parallel route to the legacy [DownloadsScreen] — both bind their respective screens
     * via the NavHost. Coexists with the legacy until the Phase 9.x route-swap collapses to
     * the rework path. Both routes consume the SAME upstream `DownloadRepository.observeAll
     * Downloads()` flow under the hood (the rework `:data` `DownloadsRepositoryImpl` is
     * strangler-fig over the legacy interface), so the list MUST agree across the two
     * routes for the same user data and add/cancel/state-transition events from either
     * route propagate to the other through Room's `Flow<List<...>>`.
     *
     * **Mutation surface (Phase 7.x.downloads.actions)**: per-row buttons render exactly
     * the legacy's affordances — "Cancel" on RUNNING (via OnCancelRunning) / QUEUED /
     * COMPRESSING rows (via OnCancel), "Retry" + "Delete" on FAILED rows, "Delete" on
     * SUCCESS rows. Failures surface as a Snackbar via `DownloadsEffect.ShowError`.
     *
     * **Discoverability**: the rework Settings hub's Downloads row points at this route
     * (Phase 7.x.downloads.actions swap — see
     * [me.manga.kira.navigation.routes.SettingsReworkScreenRoute]). The legacy
     * [DownloadsScreen] route remains bound in [me.manga.kira.App] for parity testing
     * but is no longer surfaced from any user-reachable entry — Phase 9.x route-swap
     * retires it once on-device parity is verified.
     *
     * @see me.manga.kira.navigation.routes.DownloadsReworkScreenRoute
     */
    @Serializable
    object DownloadsRework : Screen("me.manga.kira.navigation.Screen.DownloadsRework")
}
