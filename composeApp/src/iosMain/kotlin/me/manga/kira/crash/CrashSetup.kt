package me.manga.kira.crash

import co.touchlab.crashkios.crashlytics.CrashlyticsKotlin
import co.touchlab.crashkios.crashlytics.enableCrashlytics
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.system.exitProcess

/**
 * Wires CrashKiOS so uncaught Kotlin/Native exceptions are reported to Firebase Crashlytics as REAL
 * FATAL crashes WITH the symbolicated Kotlin stack — each crash grouped by its ACTUAL Kotlin type +
 * stack, not collapsed into one generic issue.
 *
 * Called from the iOS host (`AppDelegate`) AFTER `FirebaseApp.configure()`.
 *
 * ## Why a hand-rolled hook instead of CrashKiOS's `setCrashlyticsUnhandledExceptionHook()`
 *
 * CrashKiOS's helper records the *correct* fatal (real type/message/Kotlin stack, via
 * `FIRCLSExceptionRecordNSException`) — but it then calls `terminateWithUnhandledException()` →
 * `std::terminate()` → `abort()`. Firebase installs its OWN C++ terminate handler
 * (`FIRCLSTerminateHandler`); on that abort it catches the in-flight Kotlin/Native runtime object and
 * records a SECOND fatal typed `(anonymous namespace)::ExceptionObjHolderImpl`. Crashlytics keeps only
 * ONE fatal per session, and that generic second report wins — so every Kotlin crash collapses into a
 * single `ExceptionObjHolderImpl` issue (the bug we hit).
 *
 * ## The fix
 *
 * Record the good fatal ourselves via [CrashlyticsKotlin.sendFatalException] (the SAME recording path
 * CrashKiOS uses — correct type/message/stack, persisted to disk synchronously by Firebase), then exit
 * WITHOUT going through `std::terminate`/`abort`, so `FIRCLSTerminateHandler` never runs and the only
 * report Crashlytics keeps is the meaningful one. Distinct Kotlin crashes then become distinct issues.
 *
 * This is NOT swallowing and does NOT keep the process alive: the process terminates immediately and the
 * crash is reported as a fatal (uploaded on next launch). The only difference from a raw abort is the
 * OS-level exit — irrelevant to Crashlytics, which reports the persisted fatal `NSException`. We avoid
 * `abort()`/`SIGABRT` specifically because Firebase's signal + terminate handlers are what produce the
 * competing `ExceptionObjHolderImpl` report.
 *
 * Android keeps the default Firebase Crashlytics handler (JVM exceptions already report + group there),
 * so this iOS-only setup is all that's needed.
 */
@OptIn(ExperimentalNativeApi::class)
fun setupCrashlytics() {
    // Switch CrashKiOS from its no-op default to the real Crashlytics implementation. Required before
    // CrashlyticsKotlin.sendFatalException can record anything.
    enableCrashlytics()

    setUnhandledExceptionHook { throwable ->
        // Persist the real Kotlin fatal (type + message + symbolicated Kotlin stack).
        CrashlyticsKotlin.sendFatalException(throwable)
        // Terminate WITHOUT std::terminate/abort so Firebase's FIRCLSTerminateHandler never records a
        // competing `ExceptionObjHolderImpl` fatal. The report above is already on disk.
        exitProcess(0)
    }
}
