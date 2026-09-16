@file:Suppress("MagicNumber")

package me.manga.kira.core.platform

import android.net.Uri
import android.provider.OpenableColumns
import me.manga.kira.platform.backup.BackupImportLimitExceeded
import me.manga.kira.platform.backup.BackupImportPolicy
import me.manga.kira.platform.backup.BackupImportStaging
import me.manga.kira.platform.backup.BackupZipLimits
import me.manga.kira.platform.filesystem.AndroidAppFileSystem
import okio.use
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor
import java.io.ByteArrayInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real Android acquisition/staging with provider responses supplied by the existing host runner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidBackupAcquisitionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val files = AndroidAppFileSystem(context)
    private val system = files.fileSystem()
    private val resolver = shadowOf(context.contentResolver)
    private val staging = BackupImportStaging(files, BackupImportPolicy(archive = BackupZipLimits(maxArchiveBytes = 4)))
    private var nextUri = 0

    @After
    fun cleanup() = system.deleteRecursively(files.cacheDir / "backup_import", mustExist = false)

    @Test
    fun knownUnknownAndIncorrectProviderSizesEnforceTheActualStreamLimit() {
        for (declared in listOf(null, -1L, 0L, 1L, 4L)) {
            assertStreamLimit(declared, "1234", accepted = true)
            assertStreamLimit(declared, "12345", accepted = false)
        }
    }

    @Test
    fun oversizedProviderHintRejectsWithoutOpeningAndLeavesAcquisitionReusable() {
        for (contents in listOf("1234", "12345")) {
            val response = providerResponse(declared = 5, bytes = contents.encodeToByteArray())
            assertFailsWith<BackupImportLimitExceeded> {
                acquireAndroidBackup(context, response.uri, staging) {}
            }
            assertQueryClosed(response)
            assertEquals(0, response.opens)
            assertFalse(response.stream.closed, "an unopened provider stream was never transferred to staging")
            assertNoSnapshots()
        }
        assertStreamLimit(declared = 4, contents = "1234", accepted = true)
    }

    private fun assertStreamLimit(declared: Long?, contents: String, accepted: Boolean) {
        val bytes = contents.encodeToByteArray()
        val response = providerResponse(declared, bytes)
        if (accepted) {
            val path = acquireAndroidBackup(context, response.uri, staging) {}
            staging.claimOrCopy(path) {}.use { input ->
                assertContentEquals(bytes, system.read(input.path) { readByteArray() })
            }
        } else {
            assertFailsWith<BackupImportLimitExceeded> {
                acquireAndroidBackup(context, response.uri, staging) {}
            }
        }
        assertQueryClosed(response)
        assertEquals(1, response.opens)
        assertTrue(response.stream.closed)
        assertNoSnapshots()
    }

    private fun providerResponse(declared: Long?, bytes: ByteArray): ProviderResponse {
        val uri = Uri.parse("content://backup-boundary.test/${nextUri++}")
        val cursor = RoboCursor().apply {
            setColumnNames(listOf(OpenableColumns.SIZE))
            setResults(arrayOf(arrayOf<Any?>(declared)))
        }
        val response = ProviderResponse(uri, cursor, TrackedInputStream(bytes))
        resolver.setCursor(uri, cursor)
        resolver.registerInputStreamSupplier(uri) {
            response.opens++
            response.stream
        }
        return response
    }

    private fun assertQueryClosed(response: ProviderResponse) {
        assertEquals(response.uri, response.cursor.uri)
        assertContentEquals(arrayOf(OpenableColumns.SIZE), response.cursor.projection)
        assertTrue(response.cursor.closeWasCalled)
    }

    private fun assertNoSnapshots() {
        val directory = files.cacheDir / "backup_import"
        if (system.exists(directory)) assertTrue(system.list(directory).isEmpty())
    }

    private class ProviderResponse(val uri: Uri, val cursor: RoboCursor, val stream: TrackedInputStream) {
        var opens = 0
    }

    private class TrackedInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
