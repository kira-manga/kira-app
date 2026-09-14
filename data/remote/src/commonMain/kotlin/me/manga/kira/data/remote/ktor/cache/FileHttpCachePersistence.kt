package me.manga.kira.data.remote.ktor.cache

import io.ktor.client.plugins.cache.storage.CachedResponseData
import io.ktor.http.Url
import okio.Buffer
import okio.BufferedSource
import okio.EOFException
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.utf8Size

private const val RECORD_MAGIC = 0x4B484331 // KHC1: length-prefixed metadata and raw body, no base64.
private const val RECORD_SUFFIX = ".khc"
private const val STAGING_NAME = ".record.staging"
private val RECORD_NAME = Regex("[0-9a-f]{64}\\.khc")
private val LEGACY_NAME = Regex("[0-9a-f]{64}")

/**
 * Persistence only; the owner serializes all calls and enforces aggregate admission/eviction.
 * Committed records fit its budget, plus at most one body+metadata+12-byte staging record.
 * Only recognized cache records are touched; no recursive sweep of app/download directories.
 */
internal class FileHttpCachePersistence(
    private val fileSystem: FileSystem,
    private val root: Path,
    private val policy: HttpCachePolicy,
) : HttpCachePersistence {
    private val codec = HttpCacheMetadataCodec(policy.maxMetadataBytes)
    private val staging = root / STAGING_NAME

    override fun load(consume: (CacheNamespace, CachedResponseData, ByteArray) -> Unit) {
        ensureLayout()
        removeLegacyAndStaging()
        CacheNamespace.entries.forEach { namespace ->
            fileSystem.list(directory(namespace)).filter { RECORD_NAME.matches(it.name) }.forEach { path ->
                val record = readRecord(path)
                if (record == null || entryPath(namespace, record.data.url, record.data.varyKeys) != path) {
                    fileSystem.delete(path)
                } else {
                    // Consume each body immediately; never accumulate a second collection during startup.
                    consume(namespace, record.data, record.metadata)
                }
            }
        }
    }

    override fun write(namespace: CacheNamespace, data: CachedResponseData, metadata: ByteArray) {
        require(metadata.size <= policy.maxMetadataBytes && data.body.size <= policy.maxBodyBytes)
        require(recordBytes(metadata.size, data.body.size) <= policy.maxTotalBytes)
        ensureLayout()
        fileSystem.delete(staging)
        try {
            fileSystem.sink(staging, mustCreate = true).buffer().use { sink ->
                sink.writeInt(RECORD_MAGIC)
                sink.writeInt(metadata.size)
                sink.writeInt(data.body.size)
                sink.write(metadata)
                sink.write(data.body)
            }
            fileSystem.atomicMove(staging, entryPath(namespace, data.url, data.varyKeys))
        } catch (failure: IOException) {
            try {
                fileSystem.delete(staging)
            } catch (cleanup: IOException) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override fun remove(namespace: CacheNamespace, url: Url, varyKeys: Map<String, String>) {
        ensureLayout()
        fileSystem.delete(entryPath(namespace, url, varyKeys))
    }

    override fun clear() {
        ensureLayout()
        removeLegacyAndStaging()
        CacheNamespace.entries.forEach { namespace ->
            fileSystem.list(directory(namespace)).filter { RECORD_NAME.matches(it.name) }.forEach { path ->
                fileSystem.delete(path)
            }
        }
    }

    private fun readRecord(path: Path): DiskRecord? {
        val information = fileSystem.metadataOrNull(path) ?: return null
        if (!information.isRegularFile || information.symlinkTarget != null) return null
        val size = information.size ?: return null
        if (size < CACHE_RECORD_PREFIX_BYTES || size > policy.maxTotalBytes) return null
        return try {
            fileSystem.source(path).buffer().use { source -> readRecord(source, size) }
        } catch (_: EOFException) {
            null // A truncated cache file is expendable; other I/O failures disable the owner's writes.
        }
    }

    private fun readRecord(source: BufferedSource, size: Long): DiskRecord? {
        if (source.readInt() != RECORD_MAGIC) return null
        val metadataSize = source.readInt()
        val bodySize = source.readInt()
        if (metadataSize !in 0..policy.maxMetadataBytes || bodySize !in 0..policy.maxBodyBytes) return null
        if (recordBytes(metadataSize, bodySize) != size) return null
        // All declared sizes and the physical file length were checked before either allocation.
        val metadata = source.readByteArray(metadataSize.toLong())
        val body = source.readByteArray(bodySize.toLong())
        if (!source.exhausted()) return null
        val data = codec.decode(metadata, body) ?: return null
        return DiskRecord(data, metadata)
    }

    private fun removeLegacyAndStaging() {
        fileSystem.delete(staging)
        // Old JVM FileStorage used flat URL-hash files and retained an unbounded live front cache.
        // It is intentionally not imported/migrated: only the new bounded format survives restart.
        fileSystem.list(root).filter { LEGACY_NAME.matches(it.name) }.forEach { fileSystem.delete(it) }
    }

    private fun ensureLayout() {
        ensureDirectory(root)
        CacheNamespace.entries.forEach { ensureDirectory(directory(it)) }
    }

    private fun ensureDirectory(path: Path) {
        val information = fileSystem.metadataOrNull(path)
        if (information != null && (!information.isDirectory || information.symlinkTarget != null)) {
            throw IOException("Unsafe HTTP cache directory")
        }
        fileSystem.createDirectories(path)
    }

    private fun directory(namespace: CacheNamespace): Path = root / namespace.directory

    private fun entryPath(namespace: CacheNamespace, url: Url, varyKeys: Map<String, String>): Path {
        val identity = Buffer()
        identity.writeIdentityPart(url.toString())
        varyKeys.entries.sortedBy { it.key }.forEach { (key, value) ->
            identity.writeIdentityPart(key)
            identity.writeIdentityPart(value)
        }
        return directory(namespace) / (identity.readByteString().sha256().hex() + RECORD_SUFFIX)
    }
}

private data class DiskRecord(val data: CachedResponseData, val metadata: ByteArray)

private fun recordBytes(metadataSize: Int, bodySize: Int): Long =
    CACHE_RECORD_PREFIX_BYTES + metadataSize + bodySize

private fun Buffer.writeIdentityPart(value: String) {
    writeInt(value.utf8Size().toInt())
    writeUtf8(value)
}
