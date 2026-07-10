package me.manga.kira.di

import me.manga.kira.presentation.features.settings.ui.viewmodel.SettingsViewModel
import me.manga.kira.presentation.features.webview.ui.viewmodel.WebViewViewModel
import me.manga.kira.presentation.features.whatsnew.viewmodel.WhatsNewViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Legacy ViewModels relocated from `:shared`'s `sharedModule` (strangler-fig Phase 5).
 *
 * These three legacy VMs are still consumed by `:composeApp` route adapters — `SettingsViewModel`
 * by `App.kt`, `WebViewViewModel` by `WebViewScreenRoute`, `WhatsNewViewModel` by
 * `LibraryScreenRoute`. Rework equivalents exist for Settings/WhatsNew (`:presentation`), but the
 * route swap is a behavioral change out of scope for the structural `:shared` retirement, so the
 * legacy VMs are preserved and relocated here alongside the routes that use them.
 *
 * Binding them in `:composeApp` (not `:shared`) is what lets their repository deps
 * (`SettingsRepository`/`WhatsNewRemoteDataSource`) move to `:data` without a `:shared -> :data`
 * cycle. Appended to [allReworkModules].
 */
val legacySharedViewModelsModule: Module = module {
    viewModel { WebViewViewModel(get(), get()) }
    viewModel { SettingsViewModel(get()) }
    viewModel { WhatsNewViewModel(get(), get(), get(), get()) }
}
