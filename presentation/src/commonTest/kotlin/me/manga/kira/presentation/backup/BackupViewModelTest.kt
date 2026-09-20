package me.manga.kira.presentation.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.backup.BackupPhase
import me.manga.kira.domain.model.backup.BackupProgress
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.model.backup.BackupSelection
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.presentation.testing.FakeSettingsRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioral tests for [BackupViewModel]: progress projection from the repository's hot stream,
 * the export/import picker round-trips, and the busy-guards (no run while one is in flight, while
 * the CBZ converter owns the chapter dirs, or import from a scoped route).
 *
 * Same harness as the other VM tests: `viewModelScope` resolves to `Dispatchers.Main`, so an
 * [UnconfinedTestDispatcher] is installed as Main (eager execution); real use cases over a hand
 * [FakeBackupRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val scopedKeys = listOf(
        BackupSelection(WorkLocator("azora", "https://source.test/work/one"), "Solo Leveling"),
    )

    // --- progress projection ---------------------------------------------------------------------

    @Test
    fun repository_progress_stream_is_projected_into_state() = runTest {
        val repo = FakeBackupRepository()
        val vm = buildBackupVm(repo)

        repo.progress.value = BackupProgress(
            phase = BackupPhase.EXPORTING,
            isRunning = true,
            totalMangas = 4,
            processedMangas = 2,
            currentTitle = "Solo Leveling",
        )

        assertEquals(BackupPhase.EXPORTING, vm.state.value.progress.phase)
        assertEquals(2, vm.state.value.progress.processedMangas)
        assertFalse(vm.state.value.canStartRun, "a running backup blocks a new run")
    }

    @Test
    fun cbz_conversion_busy_flag_is_projected_and_blocks_runs() = runTest {
        val repo = FakeBackupRepository()
        val settings = FakeSettingsRepository()
        val vm = buildBackupVm(repo, settings = settings)

        settings.conversionProgress.value = CbzConversionProgress(isConverting = true)

        assertTrue(vm.state.value.isCbzConversionRunning)
        vm.submit(BackupIntent.OnExport)
        assertTrue(repo.exportCalls.isEmpty(), "export refused while the CBZ converter runs")
    }

    // --- export ------------------------------------------------------------------------------------

    @Test
    fun export_success_launches_save_picker_with_the_artifact() = runTest {
        val repo = FakeBackupRepository()
        val vm = buildBackupVm(repo)
        val effects = mutableListOf<BackupEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(BackupIntent.OnToggleIncludeDownloads)
        vm.submit(BackupIntent.OnExport)

        assertEquals(listOf<Pair<BackupScope, Boolean>>(BackupScope.FullLibrary to true), repo.exportCalls)
        assertEquals(
            listOf<BackupEffect>(
                BackupEffect.LaunchExportPicker(
                    archivePath = "/cache/kira-backup.kira.zip",
                    suggestedName = "kira-backup.kira.zip",
                ),
            ),
            effects,
        )
        assertNull(vm.state.value.error)
        collector.cancel()
    }

    @Test
    fun scoped_route_exports_its_manga_keys() = runTest {
        val repo = FakeBackupRepository()
        val vm = buildBackupVm(repo, scope = BackupScope.Mangas(scopedKeys))

        vm.submit(BackupIntent.OnExport)

        assertEquals(BackupScope.Mangas(scopedKeys), repo.exportCalls.single().first)
        assertTrue(vm.state.value.isScoped)
        assertEquals(listOf("Solo Leveling"), vm.state.value.scopeTitles)
    }

    @Test
    fun export_failure_surfaces_the_typed_error_without_a_picker() = runTest {
        val repo = FakeBackupRepository()
        repo.exportOutcome = AppResult.Failure(AppError.Unexpected("boom"))
        val vm = buildBackupVm(repo)
        val effects = mutableListOf<BackupEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(BackupIntent.OnExport)

        assertEquals(AppError.Unexpected("boom"), vm.state.value.error)
        assertTrue(effects.isEmpty(), "no picker for a failed export")
        collector.cancel()
    }

    @Test
    fun cancelled_export_is_not_an_error() = runTest {
        val repo = FakeBackupRepository()
        repo.exportOutcome = AppResult.Failure(AppError.Cancelled())
        val vm = buildBackupVm(repo)

        vm.submit(BackupIntent.OnExport)

        assertNull(vm.state.value.error, "user-initiated stop never renders as a failure")
    }

    @Test
    fun export_handoff_discards_the_cache_artifact_on_both_outcomes() = runTest {
        val repo = FakeBackupRepository()
        val vm = buildBackupVm(repo)
        repo.progress.value = BackupProgress(phase = BackupPhase.EXPORTING, exportResult = backupTestExportResult)

        vm.submit(BackupIntent.OnExportDelivered(success = true))
        assertEquals(listOf("/cache/kira-backup.kira.zip"), repo.discardedArtifacts)
        assertEquals(0, repo.clearCount, "delivered: the terminal summary stays visible")

        repo.progress.value = BackupProgress(phase = BackupPhase.EXPORTING, exportResult = backupTestExportResult)
        vm.submit(BackupIntent.OnExportDelivered(success = false))
        assertEquals(2, repo.discardedArtifacts.size)
        assertEquals(1, repo.clearCount, "dismissed picker: summary dropped silently")
    }

    // --- import ------------------------------------------------------------------------------------

    @Test
    fun import_asks_for_the_platform_picker_only_in_full_library_mode() = runTest {
        val repo = FakeBackupRepository()
        val vm = buildBackupVm(repo)
        val effects = mutableListOf<BackupEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(BackupIntent.OnImport)
        assertEquals(listOf<BackupEffect>(BackupEffect.LaunchImportPicker), effects)
        collector.cancel()

        val scopedVm = buildBackupVm(FakeBackupRepository(), scope = BackupScope.Mangas(scopedKeys))
        val scopedEffects = mutableListOf<BackupEffect>()
        val scopedCollector = launch(dispatcher) { scopedVm.effects.collect { scopedEffects += it } }
        scopedVm.submit(BackupIntent.OnImport)
        assertTrue(scopedEffects.isEmpty(), "scoped export screen has no import")
        scopedCollector.cancel()
    }

    @Test
    fun picked_file_is_imported_and_cancelled_picker_is_a_no_op() = runTest {
        val repo = FakeBackupRepository()
        val vm = buildBackupVm(repo)

        vm.submit(BackupIntent.OnImportFilePicked(localPath = null))
        assertTrue(repo.importCalls.isEmpty(), "picker cancel imports nothing")
        assertTrue(repo.discardedImports.isEmpty())

        vm.submit(BackupIntent.OnImportFilePicked(localPath = "/cache/backup_import/picked.zip"))
        assertEquals(listOf("/cache/backup_import/picked.zip"), repo.importCalls)
        assertEquals(repo.importCalls, repo.discardedImports)
        assertNull(vm.state.value.error)
    }

    @Test
    fun import_failure_surfaces_the_typed_error() = runTest {
        val repo = FakeBackupRepository()
        repo.importOutcome = AppResult.Failure(AppError.Validation.Format("backup_file"))
        val vm = buildBackupVm(repo)

        vm.submit(BackupIntent.OnImportFilePicked(localPath = "/cache/bad.zip"))

        assertEquals(AppError.Validation.Format("backup_file"), vm.state.value.error)
        assertEquals(listOf("/cache/bad.zip"), repo.discardedImports)
    }

    @Test
    fun import_refused_while_a_run_is_in_flight() = runTest {
        val repo = FakeBackupRepository()
        val vm = buildBackupVm(repo)
        repo.progress.value = BackupProgress(phase = BackupPhase.EXPORTING, isRunning = true)

        vm.submit(BackupIntent.OnImportFilePicked(localPath = "/cache/late.zip"))

        assertTrue(repo.importCalls.isEmpty())
        assertEquals(listOf("/cache/late.zip"), repo.discardedImports)
    }

    @Test
    fun picker_failure_is_typed_and_never_starts_an_import() = runTest {
        val repo = FakeBackupRepository()
        val vm = buildBackupVm(repo)
        val error = AppError.Validation.OutOfRange("backup_size")
        vm.submit(BackupIntent.OnImportFilePickFailed(error))
        assertEquals(error, vm.state.value.error)
        assertTrue(repo.importCalls.isEmpty())
    }

    @Test
    fun scoped_late_picker_result_is_discarded_without_import() = runTest {
        val repo = FakeBackupRepository()
        val vm = buildBackupVm(repo, BackupScope.Mangas(scopedKeys))
        vm.submit(BackupIntent.OnImportFilePicked("/cache/scoped.zip"))
        assertTrue(repo.importCalls.isEmpty())
        assertEquals(listOf("/cache/scoped.zip"), repo.discardedImports)
    }

    @Test
    fun cancelled_import_still_discards_unclaimed_picker_custody() = runTest {
        val repo = FakeBackupRepository().apply { importCancellation = CancellationException("cancel import") }
        val vm = buildBackupVm(repo)
        vm.submit(BackupIntent.OnImportFilePicked("/cache/cancelled.zip"))
        assertEquals(listOf("/cache/cancelled.zip"), repo.discardedImports)
        assertNull(vm.state.value.error)
    }

    @Test
    fun picked_file_is_rejected_by_a_scoped_route_even_without_the_picker() = runTest {
        val repo = FakeBackupRepository()
        val vm = buildBackupVm(repo, scope = BackupScope.Mangas(scopedKeys))
        vm.submit(BackupIntent.OnImportFilePicked(localPath = "/cache/unexpected.zip"))
        assertTrue(repo.importCalls.isEmpty())
        assertEquals(listOf("/cache/unexpected.zip"), repo.discardedImports)
    }

    @Test
    fun invalid_or_empty_selection_is_typed_and_never_exports_imports_or_launches_pickers() = runTest {
        val invalid = listOf(
            BackupScope.Invalid,
            BackupScope.Mangas(emptyList()),
            BackupScope.Mangas(listOf(BackupSelection(WorkLocator("source", "")))),
        )
        invalid.forEach { scope ->
            val repo = FakeBackupRepository()
            val vm = buildBackupVm(repo, scope = scope)
            val effects = mutableListOf<BackupEffect>()
            val collector = launch(dispatcher) { vm.effects.collect { effects += it } }
            vm.submit(BackupIntent.OnExport)
            vm.submit(BackupIntent.OnImport)
            vm.submit(BackupIntent.OnImportFilePicked("/cache/unexpected.zip"))
            assertIs<AppError.Validation.Format>(vm.state.value.error)
            assertFalse(vm.state.value.canStartRun)
            assertTrue(vm.state.value.isScoped)
            assertTrue(repo.exportCalls.isEmpty() && repo.importCalls.isEmpty() && effects.isEmpty())
            assertEquals(listOf("/cache/unexpected.zip"), repo.discardedImports)
            collector.cancel()
        }
    }

    // --- stop / dismiss ----------------------------------------------------------------------------

    @Test
    fun stop_forwards_to_the_repository() = runTest {
        val repo = FakeBackupRepository()
        val vm = buildBackupVm(repo)

        vm.submit(BackupIntent.OnStop)

        assertEquals(1, repo.stopCount)
    }

    @Test
    fun dismiss_clears_terminal_progress_but_never_a_running_one() = runTest {
        val repo = FakeBackupRepository()
        repo.exportOutcome = AppResult.Failure(AppError.Unexpected("boom"))
        val vm = buildBackupVm(repo)
        vm.submit(BackupIntent.OnExport)

        repo.progress.value = BackupProgress(phase = BackupPhase.EXPORTING, isRunning = true)
        vm.submit(BackupIntent.OnDismissResult)
        assertEquals(0, repo.clearCount, "a running dialog cannot be dismissed away")

        repo.progress.value = BackupProgress(phase = BackupPhase.EXPORTING, failed = true)
        vm.submit(BackupIntent.OnDismissResult)
        assertEquals(1, repo.clearCount)
        assertNull(vm.state.value.error, "dismiss resets the typed error with the dialog")
    }

    @Test
    fun back_emits_the_navigation_effect() = runTest {
        val vm = buildBackupVm(FakeBackupRepository())
        val effects = mutableListOf<BackupEffect>()
        val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

        vm.submit(BackupIntent.OnBack)

        assertEquals(listOf<BackupEffect>(BackupEffect.NavigateBack), effects)
        collector.cancel()
    }
}
