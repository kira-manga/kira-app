package me.manga.kira.di

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import me.manga.kira.data.remote.ktor.isHttpLoggingEnabled

/**
 * SECURITY: mirror the Android release floor (MyApp.kt) on Desktop. Packaged/release runs drop
 * Info/Debug/Verbose globally so the legacy scrapers' Info diagnostics — request URLs and header
 * maps including Cookie/cf_clearance/User-Agent values — never reach stdout; dev runs (`-ea`, the
 * same `desiredAssertionStatus()` probe [isHttpLoggingEnabled] uses) keep verbose logs.
 *
 * Lives in `:shared/desktopMain` because Kermit is an `implementation` dep of `:shared` and is not
 * on `:desktopApp`'s compile classpath. Called from `Main.kt` before `initKoin(...)`.
 */
fun applyDesktopLogFloor() {
    if (!isHttpLoggingEnabled) {
        Logger.setMinSeverity(Severity.Warn)
    }
}
