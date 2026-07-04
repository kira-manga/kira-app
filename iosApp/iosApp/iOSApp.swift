import SwiftUI
import ComposeApp

@main
struct iOSApp: App {

    // Owns the UNUserNotificationCenter delegate (see AppDelegate.swift) so download-progress
    // notifications present correctly while the app is foregrounded.
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    init() {
        // Bootstraps Koin BEFORE any composable is mounted.
        // Calls into composeApp/iosMain `IosKoin.kt` -> `bootstrapIosKoin()`, which delegates to
        // shared/iosMain `KoinHelper.doInitKoin(allReworkModules())`. The indirection exists
        // because `:shared` cannot see `:composeApp`'s `allReworkModules()` aggregator.
        IosKoinKt.bootstrapIosKoin()

        // Register the native reader factory so the Kotlin reader route can embed the native Swift
        // reader when `IosReaderFlags.NATIVE_READER_ENABLED` is on. The flag SHIPS ON (the native
        // reader is the committed iOS default since 69efc2e6); flipping it OFF falls back to the
        // Compose reader. See IOS_NATIVE_READER.md.
        ReaderNativeBridge.shared.setViewControllerFactory { session in
            ReaderHostViewController(session: session)
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.keyboard) // matches Android `windowSoftInputMode=adjustResize`
        }
    }
}
