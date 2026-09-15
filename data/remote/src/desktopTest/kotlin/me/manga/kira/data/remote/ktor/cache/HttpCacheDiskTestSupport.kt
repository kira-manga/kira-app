package me.manga.kira.data.remote.ktor.cache

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal suspend fun withCacheDirectory(block: suspend (CacheDiskFixture) -> Unit) {
    val home = Files.createTempDirectory("kira-http-cache-test").toFile()
    try {
        block(CacheDiskFixture(home))
    } finally {
        check(home.deleteRecursively())
    }
}

/** Real JVM filesystem tests exercise the common persistence used by Android, not a Desktop feature. */
internal class CacheDiskFixture(
    val home: File,
) {
    val root = File(home, "ktor_http_cache").absolutePath.toPath()
    val fileSystem = FileSystem.SYSTEM

    fun persistence(
        policy: HttpCachePolicy,
        storage: FileSystem = fileSystem,
    ): FileHttpCachePersistence = FileHttpCachePersistence(storage, root, policy)

    fun records(): List<Path> =
        CacheNamespace.entries.flatMap { namespace ->
            val directory = root / namespace.directory
            if (fileSystem.exists(directory)) {
                fileSystem.list(directory).filter { it.name.endsWith(".khc") }
            } else {
                emptyList()
            }
        }

    suspend fun assertMatches(
        cache: ManagedHttpCache,
        policy: HttpCachePolicy,
    ) {
        val snapshot = cache.snapshot()
        val files = records()
        cache.assertWithin(policy)
        assertEquals(snapshot.entries, files.size)
        assertEquals(snapshot.bytes, files.sumOf { fileSystem.metadata(it).size ?: 0L })
        assertTrue(files.all { (fileSystem.metadata(it).size ?: 0L) <= policy.maxTotalBytes })
        assertTrue(!fileSystem.exists(root / ".record.staging"))
    }
}

internal class StagingBudgetObserver(
    delegate: FileSystem,
    private val fixture: CacheDiskFixture,
    private val policy: HttpCachePolicy,
) : ForwardingFileSystem(delegate) {
    var moves = 0
        private set

    override fun atomicMove(
        source: Path,
        target: Path,
    ) {
        val stagedBytes = delegate.metadata(source).size ?: 0L
        val committedBytes = fixture.records().sumOf { delegate.metadata(it).size ?: 0L }
        val stagingLimit = CACHE_RECORD_PREFIX_BYTES + policy.maxBodyBytes + policy.maxMetadataBytes
        assertTrue(stagedBytes <= stagingLimit)
        assertTrue(committedBytes <= policy.maxTotalBytes)
        assertTrue(committedBytes + stagedBytes <= policy.maxTotalBytes + stagingLimit)
        assertEquals(".record.staging", source.name)
        super.atomicMove(source, target)
        moves++
        assertTrue(fixture.records().sumOf { delegate.metadata(it).size ?: 0L } <= policy.maxTotalBytes)
        assertTrue(fixture.records().size <= policy.maxEntries)
    }
}
