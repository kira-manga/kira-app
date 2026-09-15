package me.manga.kira.data.repository

import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.sources.contracts.MangaSourceClient
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor
import me.manga.kira.sources.contracts.model.SourceCatalogSnapshot
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun languageToggleDatabase(): MangaDatabase =
    Room
        .inMemoryDatabaseBuilder<MangaDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Unconfined)
        .build()

internal suspend fun cancelAndAssert(command: Deferred<Unit>) {
    command.cancel()
    assertFailsWith<CancellationException> { command.await() }
    command.join()
    assertTrue(command.isCancelled)
}

/** The DAO, not a conflated observer, is the oracle for committed flags and every untouched column. */
internal class SourcesLanguageToggleRoomFixture(
    private val database: MangaDatabase,
    scope: CoroutineScope,
) {
    val persisted: SourcesDao = database.sourcesDao()
    val dao = GatedEnablementDao(persisted)
    private val initialRows = toggleRows()
    val catalog =
        ToggleCatalog(
            initialRows.filterNot { it.name == "AbsentEnglish" }.map {
                fakeDescriptor(it.name, it.language).copy(
                    lifecycle = if (it.name == "HiddenEnglish") "disabled" else "active",
                )
            },
        )
    val repository = scope.sourceSettingsRepository(dao, catalog)

    suspend fun seed() {
        initialRows.forEach { persisted.insert(it) }
    }

    suspend fun assertOnlyEnabled(vararg names: String) {
        val expected = initialRows.map { it.copy(isEnabled = it.name in names) }
        assertEquals(expected, persisted.getAllSourcesOnce(), "Only the selected enablement flags may differ")
    }

    suspend fun installAbortTrigger() {
        // Abort after BOTH targets become enabled, irrespective of SQLite's row traversal order.
        // With old per-row commits the first row would survive the second statement's ABORT.
        execute(
            """
            CREATE TRIGGER abort_language_enable AFTER UPDATE OF isEnabled ON sources
            WHEN NEW.name IN ('EnglishOne', 'EnglishTwo') AND NEW.isEnabled = 1
              AND (SELECT COUNT(*) FROM sources
                   WHERE name IN ('EnglishOne', 'EnglishTwo') AND isEnabled = 1) = 2
            BEGIN SELECT RAISE(ABORT, 'language-toggle-test'); END
            """.trimIndent(),
        )
    }

    suspend fun dropAbortTrigger() {
        execute("DROP TRIGGER abort_language_enable")
    }

    private suspend fun execute(sql: String) {
        database.useWriterConnection { connection ->
            connection.usePrepared(sql) { it.step() }
        }
    }

    private fun toggleRows(): List<SourcesEntity> =
        listOf(
            "EnglishOne" to "(EN)",
            "EnglishTwo" to "(EN)",
            "French" to "(FR)",
            "HiddenEnglish" to "(EN)",
            "AbsentEnglish" to "(EN)",
        ).mapIndexed { index, (name, language) ->
            sourceRow(
                name = name,
                baseUrl = "https://sources.test/$name",
                imageBaseUrl = "https://images.test/$name",
                isEnabled = false,
                priority = index,
                language = language,
                baseVersion = index + 1,
                imageUrlVersion = index + 1,
            )
        }
}

internal class ToggleCatalog(
    var descriptors: List<RuntimeSourceDescriptor>,
) : SourceRegistry {
    var snapshotCalls = 0
        private set

    override val catalog: Flow<SourceCatalogSnapshot> =
        flow {
            snapshotCalls++
            emit(SourceCatalogSnapshot(1, descriptors.filter { it.isGeneric && it.lifecycle == "active" }))
        }

    override fun genericDescriptors(): List<RuntimeSourceDescriptor> = error("Capture the emitted catalog")

    override fun descriptor(api: String): RuntimeSourceDescriptor? = descriptors.firstOrNull { it.api == api }

    override fun isConfigBacked(api: String): Boolean = descriptor(api)?.lifecycle == "active"

    override fun get(api: String): MangaSourceClient? = error("Enablement must not resolve executable clients")
}

internal class EnablementGate {
    val reached = CompletableDeferred<Unit>()
    private val proceed = CompletableDeferred<Unit>()

    suspend fun pause() {
        reached.complete(Unit)
        proceed.await()
    }

    fun release() {
        proceed.complete(Unit)
    }
}

/** Intercepts both write shapes, so the old per-row implementation cannot pass a bulk-only gate. */
internal class GatedEnablementDao(
    private val actual: SourcesDao,
) : SourcesDao by actual {
    var beforeNextRead: EnablementGate? = null
    var beforeNextWrite: EnablementGate? = null
    var afterNextWrite: EnablementGate? = null
    var snapshots = 0
        private set

    val committedWrites = mutableListOf<Pair<Set<String>, Boolean>>()

    override suspend fun getAllSourcesOnce(): List<SourcesEntity> {
        snapshots++
        val gate = beforeNextRead.also { beforeNextRead = null }
        gate?.pause()
        return actual.getAllSourcesOnce()
    }

    override suspend fun setEnabledByName(
        name: String,
        enabled: Boolean,
    ): Int = persist(listOf(name), enabled) { actual.setEnabledByName(name, enabled) }

    override suspend fun setEnabledByNames(
        names: List<String>,
        enabled: Boolean,
    ): Int = persist(names, enabled) { actual.setEnabledByNames(names, enabled) }

    private suspend fun persist(
        names: List<String>,
        enabled: Boolean,
        write: suspend () -> Int,
    ): Int {
        val before = beforeNextWrite.also { beforeNextWrite = null }
        val after = afterNextWrite.also { afterNextWrite = null }
        before?.pause()
        val changed = write()
        committedWrites += names.toSet() to enabled
        // This is outside the real generated DAO call: its SQL transaction has already returned.
        after?.pause()
        return changed
    }
}
