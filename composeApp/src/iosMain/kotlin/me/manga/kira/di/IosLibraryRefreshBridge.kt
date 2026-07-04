package me.manga.kira.di

import co.touchlab.kermit.Logger
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.manga.kira.core.result.AppResult
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
 *    cancellation loses only the un-fetched remainder).
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

    private val log = Logger.withTag("LibraryBgRefresh")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Start a background library refresh. Returns a cancel handle for the BG task's
     * expiration handler. [onComplete] fires exactly once (success / failure / cancelled).
     */
    @OptIn(ExperimentalTime::class)
    fun run(onComplete: (Boolean) -> Unit): () -> Unit {
        val koin = KoinPlatform.getKoin()
        val refreshAllChapters = koin.get<RefreshAllLibraryChaptersUseCase>()
        val libraryPrefs = koin.get<LibraryPrefsRepository>()

        var completed = false
        fun completeOnce(success: Boolean) {
            if (completed) return
            completed = true
            onComplete(success)
        }

        val job = scope.launch {
            log.i { "bg refresh started" }
            val result = refreshAllChapters()
            when (result) {
                is AppResult.Success -> {
                    // Same cell the inline manual path + the Android worker write, so the
                    // Library "Last updated" header reflects background runs too.
                    libraryPrefs.setLastUpdated(Clock.System.now())
                    log.i { "bg refresh done: ${result.value} manga refreshed" }
                    completeOnce(true)
                }
                is AppResult.Failure -> {
                    log.w { "bg refresh failed: ${result.error}" }
                    completeOnce(false)
                }
            }
        }
        job.invokeOnCompletion { cause ->
            // Cancellation (BG window expired) or an unexpected throw both settle as failure so
            // Swift always gets its setTaskCompleted call.
            if (cause != null) {
                log.w { "bg refresh ended without result: ${cause.message}" }
                completeOnce(false)
            }
        }
        return { job.cancel() }
    }
}
