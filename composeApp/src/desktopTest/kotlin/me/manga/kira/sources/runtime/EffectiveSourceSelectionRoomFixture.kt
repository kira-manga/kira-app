package me.manga.kira.sources.runtime

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.local.EffectiveSourceSelectionSchema
import me.manga.kira.data.local.MIGRATION_13_14
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.ReaderProgressConstraints
import me.manga.kira.data.local.dao.EffectiveSourceSelectionDao
import me.manga.kira.data.local.entity.ActiveSourceCatalogEntity
import me.manga.kira.data.local.entity.EffectiveSourceSelectionEntity
import me.manga.kira.data.local.entity.SourcesEntity
import me.manga.kira.data.repository.SourceUrlMigrator
import me.manga.kira.sources.config.IncrementalSourceCatalogManager
import me.manga.kira.sources.contracts.CommittedSourceSelection
import me.manga.kira.sources.contracts.ConfigSignatureMetadata
import me.manga.kira.sources.contracts.RemoteSourceCatalog
import me.manga.kira.sources.contracts.RemovedSourceEntry
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceCatalogStore
import me.manga.kira.sources.contracts.SourceConfigParser
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import me.manga.kira.sources.contracts.SourceSelectionExpectation
import me.manga.kira.sources.contracts.SourceSelectionLimits
import me.manga.kira.sources.contracts.SourceSelectionToken
import me.manga.kira.sources.contracts.VerifiedSourceSelection
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry

/** File-backed generated Room + production coordinator/provider. No network or production queue workers. */
internal class EffectiveSourceSelectionRoomFixture : Closeable {
    private val directory = Files.createTempDirectory("kira-effective-selection-").toFile()
    private val file = File(directory, "selection.db")
    private var opened: MangaDatabase? = null
    private var runtime: SelectionRoomRuntime? = null
    private val durableInvalidationEmissions = AtomicInteger()
    var bundleJson = CONFIG_BACKED_SOURCES_JSON
    var keys = BootstrapSignedCatalogFixture.PINNED_KEYS
    val prepared = mutableListOf<VerifiedSourceSelection>()
    val db: MangaDatabase get() = opened ?: openDatabase().also { opened = it }
    private val session: SelectionRoomRuntime get() = runtime ?: createRuntime().also { runtime = it }
    val store: SourceCatalogStore get() = session.store
    val provider: RoomSourceAliasSnapshotProvider get() = session.provider
    val migration: EmptyFixtureSelectionMigration get() = session.migration
    val processTransitions get() = session.readiness.transitions
    /** Observe real DAO emissions; a readiness-recovery test must not pass because of an unrelated DB signal. */
    val durableInvalidationsSeen: Int get() = durableInvalidationEmissions.get()

    fun manager(remote: RemoteSourceCatalog = NoSelectionUpdates) = IncrementalSourceCatalogManager(
        store, Ed25519ConfigSignatureVerifier(keys), DefaultSourceConfigValidator(DefaultStrategyRegistry()), remote,
    )

    fun reopen() {
        runtime?.store?.invalidateSelection()
        opened?.close()
        opened = null
        runtime = null
        prepared.clear()
    }

    override fun close() {
        reopen()
        check(directory.deleteRecursively())
    }

    suspend fun <T> writer(block: suspend () -> T): T =
        db.useWriterConnection { connection -> connection.immediateTransaction { block() } }

    suspend fun execute(sql: String) {
        db.useWriterConnection { connection -> connection.usePrepared(sql) { it.step() } }
    }

    suspend fun number(sql: String): Long = db.useReaderConnection { connection ->
        connection.usePrepared(sql) { statement -> check(statement.step()); statement.getLong(0) }
    }

    suspend fun state() = SelectionRoomState(
        db.effectiveSourceSelectionDao().boundedSelection(SourceSelectionLimits.PAYLOAD_BYTES),
        db.effectiveSourceSelectionDao().nextGeneration(), db.sourceCatalogDao().activePointer(),
        db.sourcesDao().getAllSourcesOnce(),
        listOf("source_catalog_manifests", "source_revision_artifacts", "source_catalog_entries").map { number("SELECT count(*) FROM $it") },
    )

    private fun createRuntime(): SelectionRoomRuntime {
        val migration = EmptyFixtureSelectionMigration(db)
        val images = SourceUrlMigrator(db.mangaDao(), db.chapterDao(), db.historyDao(), db.notificationDao())
        val selected = db.effectiveSourceSelectionDao()
        val observed = object : EffectiveSourceSelectionDao by selected {
            override fun selectionInvalidations() =
                selected.selectionInvalidations().onEach { durableInvalidationEmissions.incrementAndGet() }
            override fun allocatorInvalidations() =
                selected.allocatorInvalidations().onEach { durableInvalidationEmissions.incrementAndGet() }
        }
        val commit = RoomSourceSelectionCommit(db, db.sourceCatalogDao(), observed,
            RoomSourceCatalogProjection(db.sourcesDao(), images), migration)
        val actual = RoomSourceCatalogStore(db.sourceCatalogDao(), commit, bundleJson)
        return SelectionRoomRuntime(
            RecordingSelectionStore(actual, prepared), RoomSourceAliasSnapshotProvider(commit), migration, commit.readiness,
        )
    }

    private fun openDatabase(): MangaDatabase = Room.databaseBuilder<MangaDatabase>(name = file.absolutePath)
        .addMigrations(MIGRATION_13_14)
        .addCallback(ReaderProgressConstraints)
        .addCallback(EffectiveSourceSelectionSchema)
        .setDriver(SelectionFixtureForeignKeysDriver(BundledSQLiteDriver()))
        .setQueryCoroutineContext(Dispatchers.Default)
        .build()
}

private data class SelectionRoomRuntime(
    val store: SourceCatalogStore,
    val provider: RoomSourceAliasSnapshotProvider,
    val migration: EmptyFixtureSelectionMigration,
    val readiness: SourceSelectionReadiness,
)

internal data class SelectionRoomState(
    val selected: EffectiveSourceSelectionEntity?,
    val next: Long?,
    val floor: ActiveSourceCatalogEntity?,
    val projection: List<SourcesEntity>,
    val catalogCounts: List<Long>,
)

private class RecordingSelectionStore(
    private val delegate: SourceCatalogStore,
    private val prepared: MutableList<VerifiedSourceSelection>,
) : SourceCatalogStore by delegate {
    override suspend fun commitSelection(candidate: VerifiedSourceSelection, expected: SourceSelectionExpectation): CommittedSourceSelection {
        prepared += candidate
        return delegate.commitSelection(candidate, expected)
    }
}

/** Explicit test-only exclusion: reject ALL saved/anchor/download families instead of pretending to migrate them. */
internal class EmptyFixtureSelectionMigration(private val db: MangaDatabase) : SourceCatalogSelectionMigration {
    private val exclusion = Mutex()
    private var held = false
    var beforeWriter: suspend (VerifiedSourceSelection) -> Unit = {}
    var insideWriter: suspend (VerifiedSourceSelection) -> Unit = {}
    var afterCommit: suspend (VerifiedSourceSelection) -> Unit = {}

    override suspend fun <T> withQuiescentSelection(candidate: VerifiedSourceSelection, block: suspend () -> T): T = exclusion.withLock {
        check(!held)
        held = true
        try {
            beforeWriter(candidate)
            block().also { afterCommit(candidate) }
        } finally { held = false }
    }

    override suspend fun migrateInTransaction(candidate: VerifiedSourceSelection, token: SourceSelectionToken) {
        check(held && token.generation > 0)
        db.useWriterConnection { connection ->
            connection.usePrepared("SELECT (SELECT count(*) FROM saved_manga) + (SELECT count(*) FROM saved_chapters) + " +
                "(SELECT count(*) FROM reader_work_state) + (SELECT count(*) FROM reader_chapter_state) + " +
                "(SELECT count(*) FROM chapter_downloads)") { statement ->
                check(statement.step() && statement.getLong(0) == 0L) { "fixture participant rejects nonempty ownership/queue state" }
            }
        }
        insideWriter(candidate)
    }
}

private class SelectionFixtureForeignKeysDriver(private val delegate: SQLiteDriver) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection = delegate.open(fileName).also { it.execSQL("PRAGMA foreign_keys = ON") }
}

private object NoSelectionUpdates : RemoteSourceCatalog {
    override suspend fun fetchManifest(etag: String?) = SourceCatalogManifestResult.Unavailable
    override suspend fun fetchSource(entry: SourceCatalogEntry): SourceRevisionArtifact = error("no remote source expected")
}

/** Locally signed EMPTY-only test vector, not a replacement/re-encoding of the backend bootstrap resource. */
internal fun emptySelectionRemote(): RemoteSourceCatalog {
    val document = (SourceConfigParser.parse(CONFIG_BACKED_SOURCES_JSON) as AppResult.Success).value
    val manifest = SourceCatalogManifest(1, 1, 101, BootstrapSignedCatalogFixture.CREATED_AT, emptyList(),
        document.sources.map { RemovedSourceEntry(it.api, "removed") })
    val payload = Json.encodeToString(SourceCatalogManifest.serializer(), manifest)
    val checksum = EffectiveSourceSelectionCodec.digest(payload)
    val metadata = ConfigSignatureMetadata("kira-source-catalog-manifest-v1", "Ed25519", BootstrapSignedCatalogFixture.KEY_ID,
        "", manifest.catalogRevision, checksum, manifest.generatedAt, null, null)
    val signingBytes = "${metadata.format}\n${metadata.revision}\n0\n-\n$checksum\n${metadata.createdAt}\n$payload".encodeToByteArray()
    val signed = SignedSourceCatalogManifest(payload, metadata.copy(signatureBase64 = signPublicEmptyTestVector(signingBytes)))
    check(Ed25519ConfigSignatureVerifier(BootstrapSignedCatalogFixture.PINNED_KEYS).verifyManifest(signed))
    return object : RemoteSourceCatalog {
        override suspend fun fetchManifest(etag: String?) = SourceCatalogManifestResult.Modified(signed)
        override suspend fun fetchSource(entry: SourceCatalogEntry): SourceRevisionArtifact = error("empty catalog must fetch no artifacts")
    }
}

private fun signPublicEmptyTestVector(bytes: ByteArray): String {
    // PUBLIC RFC 8032 §7.1 TEST 1 seed, never a production credential. PKCS#8 RFC 8410 encoding.
    val encoded = ("302e020100300506032b657004220420" +
        "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(encoded))
    return Signature.getInstance("Ed25519").run {
        initSign(key)
        update(bytes)
        Base64.getEncoder().encodeToString(sign())
    }
}
