package me.manga.kira.data.repository

import me.manga.kira.domain.repository.AnalyticsPort
import me.manga.kira.platform.analytics.AnalyticsClient

/**
 * [AnalyticsPort] impl delegating to the `:platform` [AnalyticsClient] (#11) — same strangler
 * posture as `AboutRepositoryImpl(legacy = get())`: `:data` already depends on `:platform`, so this
 * bridges the `:domain` port to the platform SPI. The platform client is Firebase-backed on Android
 * and a no-op logger on iOS/Desktop.
 *
 * Event names + param keys mirror native verbatim: `app_open` (FirebaseAnalytics.Event.APP_OPEN) and
 * `manga_open` with `manga_api` / `manga_title` / `source_screen`.
 */
class AnalyticsRepositoryImpl(
    private val client: AnalyticsClient,
) : AnalyticsPort {

    override fun logAppOpen() {
        client.logEvent(EVENT_APP_OPEN)
    }

    override fun logMangaOpen(api: String, title: String, sourceScreen: String) {
        client.logEvent(
            EVENT_MANGA_OPEN,
            mapOf(
                "manga_api" to api,
                "manga_title" to title,
                "source_screen" to sourceScreen,
            ),
        )
    }

    private companion object {
        const val EVENT_APP_OPEN = "app_open"
        const val EVENT_MANGA_OPEN = "manga_open"
    }
}
