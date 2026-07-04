# Observability Report — Phase 8 / Section 45

> Mandatory output per `MIGRATION_PROMPT.md` Section 45 ("Observability Preservation Rule").

## Source observability stack

| Tool | Use in source | Scope |
|---|---|---|
| Firebase Analytics | screen tracking, custom events | Android (FirebaseModule.kt) |
| Firebase Crashlytics | crash reporting + non-fatal exception reporting | Android (FirebaseModule.kt + Crashlytics plugin in `app/build.gradle.kts`) |
| Firebase Crashlytics breadcrumbs | `FirebaseCrashlytics.getInstance().log(…)` calls scattered through source | Android |
| Firebase Firestore | user complaints / what's new content | Android (FirebaseModule.kt) |
| AdMob analytics | impression tracking | Android |
| `android.util.Log` | dev logging | Android |

## Migration to commonMain via expect/actual

### Analytics

```kotlin
expect class Analytics {
    fun logEvent(name: String, params: Map<String, Any> = emptyMap())
    fun setUserProperty(key: String, value: String)
    fun setUserId(id: String?)
}
```

- Android: `FirebaseAnalytics` wrapper (Phase 11)
- iOS: `NoopAnalytics` (Firebase iOS SDK out of scope)
- Desktop: `NoopAnalytics`

### Crash reporter

```kotlin
expect class CrashReporter {
    fun log(message: String)            // breadcrumb
    fun reportException(t: Throwable, fatal: Boolean = false)
    fun setKey(key: String, value: String)
    fun setUserId(id: String?)
}
```

- Android: `FirebaseCrashlytics` wrapper
- iOS: `NoopCrashReporter`
- Desktop: `NoopCrashReporter`

### Logger facade

```kotlin
// Kermit-backed; behind an AppLogger interface so app code doesn't depend on Kermit directly
interface AppLogger {
    fun v(tag: String, msg: () -> String)
    fun d(tag: String, msg: () -> String)
    fun i(tag: String, msg: () -> String)
    fun w(tag: String, msg: () -> String, throwable: Throwable? = null)
    fun e(tag: String, msg: () -> String, throwable: Throwable? = null)
}
```

- Implementation: a single `KermitAppLogger(kermitLogger: co.touchlab.kermit.Logger)` in `commonMain`.
- Per-platform writer attached at startup:
  - Android: Logcat + Crashlytics WARN/ERROR mirror via `kermit-crashlytics:2.0.4`
  - iOS: OSLog
  - Desktop: println

## Events tracked in source (preservation list)

Phase 11 batches move each call site that does:

- `FirebaseAnalytics.getInstance(this).logEvent("manga_opened", bundle)` → `Analytics.logEvent("manga_opened", mapOf(...))`
- `FirebaseAnalytics.getInstance(this).setUserProperty(...)` → `Analytics.setUserProperty(...)`
- `FirebaseCrashlytics.getInstance().log(...)` → `CrashReporter.log(...)`
- `FirebaseCrashlytics.getInstance().recordException(e)` → `CrashReporter.reportException(e)`

Audit will produce an exhaustive event list per file in Phase 11.

## Logging migration

Per Section 9 of `dependency-replacement-report.md` (R9: `android.util.Log` → Kermit):
- Every `Log.d(tag, msg)`, `Log.i(...)`, `Log.e(...)`, etc., call in source gets rewritten as `AppLogger.d(tag) { msg }`, `.i(tag) { msg }`, `.e(tag, throwable) { msg }`, etc., during the per-file move (Phase 9/10/11).
- The lazy `{ msg }` form avoids string concatenation cost when the log level is disabled.

## Status

| Item | Status |
|---|---|
| Source observability stack catalogued | ✅ |
| `expect class Analytics` / `CrashReporter` / `AppLogger` defined | ⏳ Phase 8 |
| Per-call-site event preservation list | ⏳ Phase 11 (one row per call site) |
| `kermit-crashlytics:2.0.4` Crashlytics writer wired on Android | ⏳ Phase 11 |
| `OSLogWriter` (iOS) / `CommonWriter` (Desktop) | ⏳ Phase 8 |
| `android.util.Log` → `AppLogger` rewrite | ⏳ Phase 9/10/11 (incremental per file move) |
