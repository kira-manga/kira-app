package me.manga.kira.domain.usecase.analytics

import me.manga.kira.domain.repository.AnalyticsPort

/**
 * Log the native-parity `manga_open` analytics event (#11) — thin pass-through to
 * [AnalyticsPort.logMangaOpen]. Fired once per opened manga identity from the Details VM. Bound
 * `factory`.
 */
class LogMangaOpenUseCase(
    private val analytics: AnalyticsPort,
) {
    operator fun invoke(api: String, title: String, sourceScreen: String = "home") =
        analytics.logMangaOpen(api = api, title = title, sourceScreen = sourceScreen)
}
