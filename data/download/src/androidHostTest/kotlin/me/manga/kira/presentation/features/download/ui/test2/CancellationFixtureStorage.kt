package me.manga.kira.presentation.features.download.ui.test2

import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings
import me.manga.kira.platform.filesystem.AndroidAppFileSystem
import me.manga.kira.platform.storage.DataStoreHelper
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Uses the real Android filesystem layout; deletes only this fixture's uniquely owned paths. */
internal class CancellationFixtureStorage(
    val context: Context,
) : AutoCloseable {
    val root: File = Files.createTempDirectory(context.cacheDir.toPath(), "app75-host-").toFile()
    val mangaId: Long = maxOf(1L, UUID.randomUUID().mostSignificantBits ushr 1)
    val mangaDirectory = File(context.filesDir, "manga/$mangaId")
    val fileSystem = AndroidAppFileSystem(context)
    private val previousNativeProperties = NATIVE_PROPERTIES.associateWith(System::getProperty)
    private var ownsMangaDirectory = false
    val settings =
        DataStoreHelper(
            SharedPreferencesSettings(context.getSharedPreferences(root.name, Context.MODE_PRIVATE)),
        )

    init {
        val initialized =
            runCatching {
                check(System.getProperty("os.name") == "Linux") { "This pinned host fixture requires Linux x64" }
                check(System.getProperty("os.arch") in setOf("amd64", "x86_64"))
                check(!mangaDirectory.exists())
                ownsMangaDirectory = mangaDirectory.mkdirs()
                check(ownsMangaDirectory)
                stageOwnedNative()
            }
        initialized.exceptionOrNull()?.let { failure ->
            runCatching(::close).exceptionOrNull()?.let(failure::addSuppressed)
        }
        initialized.getOrThrow()
    }

    private fun stageOwnedNative() {
        val staged = File(checkNotNull(System.getProperty("kira.app75.sqlite.native")))
        val bytes = staged.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(SQLITE_LINUX_NATIVE_SHA256, hash)
        val nativeDirectory = File(root, "native").apply { check(mkdir()) }
        File(nativeDirectory, "libsqliteJni.so").writeBytes(bytes)
        System.setProperty(NATIVE_PATH_PROPERTY, nativeDirectory.absolutePath)
        System.setProperty(NATIVE_NAME_PROPERTY, "libsqliteJni.so")
    }

    fun imagePaths(
        chapterId: Long,
        pageCount: Int,
    ): List<String> =
        List(pageCount) { index ->
            File(mangaDirectory, "chapter_$chapterId/image_$index.png").absolutePath
        }

    fun assertImages(paths: List<String>) {
        paths.forEach { path -> assertContentEquals(PAGE_PNG, File(path).readBytes()) }
    }

    fun restoreProperties() {
        previousNativeProperties.forEach { (name, value) ->
            if (value == null) System.clearProperty(name) else System.setProperty(name, value)
        }
    }

    fun removeOwnedFiles() {
        context.deleteSharedPreferences(root.name)
        if (ownsMangaDirectory) check(mangaDirectory.deleteRecursively())
        check(root.deleteRecursively())
    }

    override fun close() {
        restoreProperties()
        removeOwnedFiles()
    }
}

internal val PAGE_PNG: ByteArray =
    Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
    )
private const val NATIVE_PATH_PROPERTY = "androidx.sqlite.driver.bundled.path"
private const val NATIVE_NAME_PROPERTY = "androidx.sqlite.driver.bundled.name"
private val NATIVE_PROPERTIES = listOf(NATIVE_PATH_PROPERTY, NATIVE_NAME_PROPERTY)
private const val SQLITE_LINUX_NATIVE_SHA256 = "3033fdaed2b94c078a0deacb3aa8d011041f4773ff3a83599ac8a979cc8ed380"
