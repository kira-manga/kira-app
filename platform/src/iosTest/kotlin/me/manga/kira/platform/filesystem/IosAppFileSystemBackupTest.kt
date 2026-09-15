package me.manga.kira.platform.filesystem

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import okio.FileSystem
import okio.Path
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.numberWithBool
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real Foundation resource values in an isolated filesystem; no app host or network is needed. */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
class IosAppFileSystemBackupTest {
    private val system = FileSystem.SYSTEM

    @Test
    fun createdMangaRootIsExcludedAndChildPublicationPreservesThePolicy() =
        withSandbox { sandbox ->
            assertFalse(system.exists(sandbox.mangaRoot))
            val files = IosAppFileSystem(sandbox.documents, sandbox.cache)
            assertEquals(sandbox.documents, files.filesDir)
            assertEquals(sandbox.cache, files.cacheDir)
            assertTrue(isExcludedFromBackup(sandbox.mangaRoot))

            val chapter = files.chapterDir(3, 7)
            assertEquals(sandbox.documents / "manga" / "3" / "chapter_7", chapter)
            system.createDirectories(chapter)
            val incoming = sandbox.cache / "incoming.tmp"
            val bytes = "downloaded-page-sentinel".encodeToByteArray()
            system.write(incoming) { write(bytes) }
            val retained = chapter / ".image_0.partial"
            assertTrue(
                NSFileManager.defaultManager.moveItemAtURL(
                    NSURL.fileURLWithPath(incoming.toString()),
                    toURL = NSURL.fileURLWithPath(retained.toString()),
                    error = null,
                ),
            )
            assertTrue(isExcludedFromBackup(sandbox.mangaRoot))
            val published = chapter / "image_0.jpg"
            system.atomicMove(retained, published)
            assertContentEquals(bytes, system.read(published) { readByteArray() })
            assertTrue(isExcludedFromBackup(sandbox.mangaRoot))
            assertUserStateUnchanged(sandbox)
        }

    @Test
    fun existingMangaRootIsRepairedWithoutChangingStoredBytes() =
        withSandbox { sandbox ->
            val chapter = sandbox.mangaRoot / "3" / "chapter_7"
            val media =
                mapOf(
                    chapter / "image_0.jpg" to "existing-page".encodeToByteArray(),
                    chapter / "chapter_7.cbz" to "existing-archive".encodeToByteArray(),
                    chapter / "manifest.json" to "existing-manifest".encodeToByteArray(),
                    chapter / "chapter_7.cbz.part" to "interrupted-archive".encodeToByteArray(),
                )
            writeFiles(media)
            // First upgrade, an already-marked root, then a lost flag: no create-only/sticky repair.
            for (initiallyExcluded in listOf(false, true, false)) {
                setBackupExclusion(sandbox.mangaRoot, initiallyExcluded)
                IosAppFileSystem(sandbox.documents, sandbox.cache)
                assertTrue(isExcludedFromBackup(sandbox.mangaRoot))
                assertBytesUnchanged(media)
                assertUserStateUnchanged(sandbox)
            }
        }

    private fun withSandbox(block: (BackupSandbox) -> Unit) {
        val sandbox = BackupSandbox(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ios-backup-policy-${NSUUID().UUIDString}")
        try {
            system.createDirectories(sandbox.documents)
            system.createDirectories(sandbox.cache)
            writeFiles(sandbox.userState)
            (listOf(sandbox.documents, sandbox.cache) + sandbox.userState.keys).forEach { path ->
                setBackupExclusion(path, excluded = false)
            }
            block(sandbox)
        } finally {
            system.deleteRecursively(sandbox.root, mustExist = false)
        }
    }

    private fun writeFiles(files: Map<Path, ByteArray>) {
        files.forEach { (path, bytes) ->
            system.createDirectories(assertNotNull(path.parent))
            system.write(path) { write(bytes) }
        }
    }

    private fun assertBytesUnchanged(files: Map<Path, ByteArray>) {
        files.forEach { (path, bytes) ->
            assertContentEquals(bytes, system.read(path) { readByteArray() })
        }
    }

    private fun assertUserStateUnchanged(sandbox: BackupSandbox) {
        assertFalse(isExcludedFromBackup(sandbox.documents))
        assertFalse(isExcludedFromBackup(sandbox.cache))
        sandbox.userState.keys.forEach { assertFalse(isExcludedFromBackup(it)) }
        assertBytesUnchanged(sandbox.userState)
    }

    private fun isExcludedFromBackup(path: Path): Boolean =
        memScoped {
            val resourceValue = alloc<ObjCObjectVar<Any?>>()
            resourceValue.value = null
            val nativeError = alloc<ObjCObjectVar<NSError?>>()
            nativeError.value = null
            assertTrue(
                NSURL.fileURLWithPath(path.toString()).getResourceValue(
                    value = resourceValue.ptr,
                    forKey = NSURLIsExcludedFromBackupKey,
                    error = nativeError.ptr,
                ),
                "Backup resource read failed (code=${nativeError.value?.code})",
            )
            assertNotNull(resourceValue.value as? NSNumber, "Expected a native Boolean resource").boolValue
        }

    private fun setBackupExclusion(
        path: Path,
        excluded: Boolean,
    ) {
        memScoped {
            val nativeError = alloc<ObjCObjectVar<NSError?>>()
            nativeError.value = null
            assertTrue(
                NSURL.fileURLWithPath(path.toString()).setResourceValue(
                    value = NSNumber.numberWithBool(excluded),
                    forKey = NSURLIsExcludedFromBackupKey,
                    error = nativeError.ptr,
                ),
                "Backup resource setup failed (code=${nativeError.value?.code})",
            )
        }
    }
}

private class BackupSandbox(val root: Path) {
    val documents = root / "Documents"
    val cache = root / "Caches"
    val mangaRoot = documents / "manga"
    val userState =
        mapOf(
            documents / "kira_manga.db" to "library-history-bookmarks".encodeToByteArray(),
            documents / "kira_manga.db-wal" to "pending-user-state".encodeToByteArray(),
        )
}
