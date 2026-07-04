package me.manga.kira.di

import me.manga.kira.navigation.push.NotificationRouter
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Push / deep-link navigation bindings (Firebase push + in-app-message deep linking).
 *
 * Currently just the app-scoped [NotificationRouter] bus that carries a tapped notification's
 * deep-link from the platform edges (Android `MainActivity`, iOS `IosPushBridge`) to the navigation
 * host in `App.kt`. Appended to `allReworkModules()` so all three hosts share the one instance.
 */
val pushModule: Module = module {
    single { NotificationRouter() }
}
