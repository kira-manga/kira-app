package me.manga.kira.domain.repository

/**
 * Domain port for the two native-parity analytics events (#11).
 *
 * `:presentation` depends only on `:core` + `:domain`, so it cannot inject the `:platform`
 * `AnalyticsClient` directly. ViewModels (and the app host via use cases) depend on this `:domain`
 * port; the `:data` impl delegates to the platform `AnalyticsClient` (Firebase on Android, no-op
 * logging on iOS/Desktop until Firebase iOS lands). Scope is intentionally just the two events
 * native fired (`app_open`, `manga_open`) — no setUserId/setUserProperty.
 */
interface AnalyticsPort {
    /** Native `FirebaseAnalytics.Event.APP_OPEN` — fired once on app launch. */
    fun logAppOpen()

    /**
     * Native `manga_open` (MangaDetailsScreen) — fired once per opened manga identity, carrying the
     * source api + title + the originating screen.
     */
    fun logMangaOpen(api: String, title: String, sourceScreen: String = "home")
}
