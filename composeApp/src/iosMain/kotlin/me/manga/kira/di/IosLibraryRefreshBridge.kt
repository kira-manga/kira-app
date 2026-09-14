package me.manga.kira.di

import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.manga.kira.domain.repository.LibraryPrefsRepository
import me.manga.kira.domain.usecase.library.RefreshAllLibraryChaptersUseCase
import org.koin.mp.KoinPlatform
import kotlin.time.Clock

/**
 * Swift-callable entry point for the iOS **background library refresh** (`BGAppRefreshTask`,
 * backlog M2). `AppDelegate` registers the `me.manga.kira.library.refresh` task id and calls
 * [run] from its launch handler; the bridge resolves the SAME shared inline-refresh pipeline the
 * manual pull-to-refresh uses on iOS/Desktop ([RefreshAllLibraryChaptersUseCase] — per-manga
 * chapter re-fetch + Room writes that feed the Updates tab) and stamps the "Last updated" header
 * cell on success, exactly like `LibraryRefreshRepositoryImpl`'s inline path.
 *
 * Contract with Swift:
 *  - [run] launches the refresh on a background scope and returns a **cancel handle**. The
 *    handler wires it to `BGAppRefreshTask.expirationHandler` so an expiring window cancels the
 *    in-flight refresh promptly (partial per-manga progress is already committed row-by-row —
 *    already-committed writes are not rolled back by cancellation).
 *  - [onComplete] is invoked exactly once with `true` on a successful full pass, `false` on
 *    failure OR cancellation — Swift forwards it to `task.setTaskCompleted(success:)`.
 *
 * Scope note: mirrors the `IosBackgroundBridge` pattern (Koin via [KoinPlatform] because this is
 * called from Swift, outside any composition). Posture note (updated 2026-07-04 audit): the native
 * app ships its periodic `LibraryRefreshWorker` request COMMENTED OUT, but KMP-Android DOES now
 * schedule a matching `PeriodicWorkRequest` (M2, `MyApp.onCreate` — the Android twin of this iOS
 * BGAppRefreshTask), so both mobile platforms carry periodic refresh; Desktop remains unwired.
 */
object IosLibraryRefreshBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Start a background library refresh. Returns a cancel handle for the BG task's
     * expiration handler. [onComplete] fires exactly once (success / failure / cancelled).
     */
    @OptIn(ExperimentalTime::class)
    fun run(onComplete: (Boolean) -> Unit): () -> Unit {
        val job =
            launchLibraryRefreshCompletion(
                scope = scope,
                // Resolve within the owned job too: DI failure must settle the OS callback as false.
                refresh = { KoinPlatform.getKoin().get<RefreshAllLibraryChaptersUseCase>()() },
                stampLastSuccess = {
                    KoinPlatform.getKoin().get<LibraryPrefsRepository>().setLastUpdated(Clock.System.now())
                },
                onComplete = onComplete,
            )
        return { job.cancel() }
    }
}
