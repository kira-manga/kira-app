package me.manga.kira.di

import me.manga.kira.core.util.notification.ChapterNotificationHelper
import me.manga.kira.work.CbzMigrationWorker
import me.manga.kira.work.LibraryRefreshWorker
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Phase 12.x — Koin module scoped to the `app/` Android entry-point module.
 *
 * Contains bindings for classes that live in `app/` and therefore cannot be registered from
 * `shared/src/androidMain/.../PlatformModule.android.kt` (which can't see the `app` module's
 * source set due to the build's module graph: `app -> composeApp -> shared`, never the reverse).
 *
 * Workers use `workerOf(::ClassName)` which is the Koin 4.x DSL for `androidx.work.ListenableWorker`
 * subclasses. The first two constructor parameters (`Context`, `WorkerParameters`) are supplied by
 * the `KoinWorkerFactory` at runtime; subsequent parameters are resolved via standard Koin lookup.
 *
 * Phase 12.x deferrals (with reasons — NOT silent stubs):
 *  - `NotificationWorker` — upstream is a 35-line debug stub posting a hardcoded "Delayed
 *    Notification" with `setContentText("This notification was delayed by 5 seconds.")`. It is
 *    never enqueued anywhere in the upstream codebase. Porting it would add dead code; intentionally
 *    skipped. If a real notification worker is ever needed, model it on `LibraryRefreshWorker`.
 *  - `MangaDownloadWorker` — upstream file is **entirely commented out** (every line begins with `//`).
 *    The actual active download worker is `DownloadWorkerV2` (already ported to
 *    `shared/src/androidMain/.../download/ui/test2/DownloadWorkerV2.kt`). Porting the commented-out
 *    upstream variant would resurrect dead code; intentionally skipped.
 */
val appKoinModule: Module = module {
    single { ChapterNotificationHelper(androidContext(), get(), get()) }

    workerOf(::CbzMigrationWorker)
    workerOf(::LibraryRefreshWorker)
}

/*
 * §253 audit-trail postscript — cluster283 §253 sweep (2026-05-29)
 * Classification: LIVE-HOST (android-host) — the app-local Koin module bound at process start.
 *
 * LIVE evidence:
 *  - MyApp.kt:78 calls modules(appKoinModule) inside the initKoin trailing lambda, so this module is
 *    loaded into the running GlobalContext at app launch — it is a wired binding, not dead code.
 *  - The workerOf bindings reference real worker classes that exist in this module:
 *    app/.../work/CbzMigrationWorker.kt and app/.../work/LibraryRefreshWorker.kt (both verified
 *    present via repo grep), and they are honored by the KoinWorkerFactory installed at MyApp.kt:77.
 *  - ChapterNotificationHelper resolves from app/.../core/util/notification/ChapterNotificationHelper.kt;
 *    it lives in :app and therefore CANNOT be registered from shared PlatformModule.android.kt — this
 *    module exists precisely to host app-only bindings (graph is app -> composeApp -> shared, never reverse).
 *  - Status: LIVE-HOST. Genuinely active wiring; no legacy logic — pure DI declaration.
 *
 * Delta-axes (binding seams):
 *  1. Koin startKoin wiring — appKoinModule is the app-local leaf appended after allReworkModules()
 *     and the shared graph; collisions with SharedModule surface as duplicate-binding diagnostics.
 *  2. WorkManager integration — workerOf(::ClassName) is the Koin 4.x ListenableWorker DSL; the first
 *     two ctor params (Context, WorkerParameters) come from KoinWorkerFactory, the rest via Koin lookup.
 *  3. Notification facade binding — single ChapterNotificationHelper(androidContext(), get(), get())
 *     supplies the Android Context and two injected collaborators to an app-tier notification helper.
 *  4. Deliberate deferrals — NotificationWorker (upstream debug stub, never enqueued) and
 *     MangaDownloadWorker (upstream file entirely comment-line code; active worker is DownloadWorkerV2)
 *     are intentionally NOT bound; porting either would resurrect dead code.
 *
 * Nested-comment hazard check: no interior slash-star, star-slash, nor slash-star-star sequences;
 * "comment-line code" is spelled out rather than using slash characters; block opens once, closes once.
 * Diff is purely additive.
 */
