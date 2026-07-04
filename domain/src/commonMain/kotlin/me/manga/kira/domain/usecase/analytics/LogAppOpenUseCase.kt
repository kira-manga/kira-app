package me.manga.kira.domain.usecase.analytics

import me.manga.kira.domain.repository.AnalyticsPort

/**
 * Log the native-parity `app_open` analytics event (#11) — thin pass-through to
 * [AnalyticsPort.logAppOpen]. Fired once from the app host's launch effect. Bound `factory`.
 */
class LogAppOpenUseCase(
    private val analytics: AnalyticsPort,
) {
    operator fun invoke() = analytics.logAppOpen()
}
