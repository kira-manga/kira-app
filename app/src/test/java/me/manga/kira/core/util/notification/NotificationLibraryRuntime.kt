package me.manga.kira.core.util.notification

import android.content.Context
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.flowOf
import me.manga.kira.data.identity.AcceptedSourceAliasRule
import me.manga.kira.data.identity.SourceAliasReadiness
import me.manga.kira.data.identity.SourceAliasSnapshot
import me.manga.kira.data.identity.SourceAliasSnapshotProvider
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.repository.LibraryRepositoryImpl
import me.manga.kira.di.allReworkModules
import me.manga.kira.di.allSharedModules
import me.manga.kira.di.platformModule
import me.manga.kira.domain.repository.LibraryMetadataRepository
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.sources.contracts.PreviousHostAuthority
import me.manga.kira.sources.contracts.SelectedCatalogIdentity
import me.manga.kira.sources.contracts.SelectedCatalogKind
import me.manga.kira.sources.contracts.SelectedPreviousHost
import me.manga.kira.sources.contracts.SourceSelectionProofRef
import me.manga.kira.sources.contracts.SourceSelectionToken
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.TimeUnit

private const val WORK_MANAGER_CLOSE_SECONDS = 5L

/** Real production writer/removal/artifact graph over the notification fixture's one Room database. */
internal class NotificationLibraryRuntime(
    context: Context,
    db: MangaDatabase,
    root: File,
) : AutoCloseable {
    private var previousWorkManager: WorkManagerImpl? = null
    private val workManager = lazy {
        previousWorkManager = WorkManagerImpl.getInstance()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        WorkManagerImpl.getInstance(context)
    }
    private val files = object : AppFileSystem {
        override val filesDir = File(root, "files").apply { check(mkdirs()) }.absolutePath.toPath()
        override val cacheDir = File(root, "cache").apply { check(mkdirs()) }.absolutePath.toPath()
        override fun fileSystem() = FileSystem.SYSTEM
    }
    private val overrides = module {
        single<MangaDatabase> { db }
        single<AppFileSystem> { files }
        single<SourceAliasSnapshotProvider> { NotificationAliasSnapshot() }
        single<WorkManager> { workManager.value }
    }
    // All definitions are lazy. Resolving the actual library does not schedule any download;
    // real unused removal collaborators are retained rather than replaced by permission stubs.
    private val app = koinApplication {
        androidContext(context)
        modules(allSharedModules() + platformModule() + allReworkModules() + overrides)
    }

    val library: LibraryRepository
        get() = app.koin.get<LibraryRepository>().also { check(it is LibraryRepositoryImpl) }

    val metadata: LibraryMetadataRepository get() = app.koin.get()

    override fun close() {
        if (workManager.isInitialized()) {
            val owned = workManager.value
            check(WorkManagerImpl.getInstance() === owned) { "Notification WorkManager ownership changed" }
            owned.cancelAllWork().result.get(WORK_MANAGER_CLOSE_SECONDS, TimeUnit.SECONDS)
            WorkManagerTestInitHelper.closeWorkDatabase()
            WorkManagerImpl.setDelegate(previousWorkManager)
        }
        app.close() // Also closes the real, uncached page HTTP client if it was constructed.
    }
}

/** Finite explicit test policy only; production still acquires accepted policy in its Room writer. */
private class NotificationAliasSnapshot : SourceAliasSnapshotProvider {
    private val token = SourceSelectionToken(
        1, SelectedCatalogIdentity(SelectedCatalogKind.SIGNED, 1, "a".repeat(64)), "b".repeat(64),
    )
    private val previous = SelectedPreviousHost(
        "alias.example",
        PreviousHostAuthority.PROVEN_COMPATIBLE_ROOT,
        SourceSelectionProofRef(
            SelectedCatalogIdentity(SelectedCatalogKind.BUNDLED, 0, "c".repeat(64)), "notification-fixture", null,
        ),
    )
    private val snapshot = SourceAliasSnapshot(
        token,
        listOf(AcceptedSourceAliasRule("notification-fixture", "https://manga.example", listOf(previous))),
    )
    override val readiness = flowOf<SourceAliasReadiness>(SourceAliasReadiness.Ready(token))
    override suspend fun readInTransaction() = snapshot
}
