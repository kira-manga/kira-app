package me.manga.kira.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.manga.kira.core.result.AppResult
import me.manga.kira.platform.backup.BackupImportStaging
import okio.IOException
import org.koin.compose.koinInject
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeZIP
import platform.darwin.NSObject

/**
 * iOS actual: `UIDocumentPickerViewController` presented off the root view controller (same
 * resolution as `IosScreenshotProvider`). Export uses `forExportingURLs + asCopy = true` — UIKit
 * copies the cache artifact to the user-chosen destination (Files/iCloud) and the artifact's own
 * file name is the suggested name by construction. Import opens in place; a security-scoped,
 * coordinated bounded copy is made on IO before the owned path is returned to the ViewModel.
 * Pure Kotlin/Native — no Swift, no Info.plist keys required.
 */
@Composable
actual fun rememberBackupFilePicker(): BackupFilePicker {
    val staging: BackupImportStaging = koinInject()
    val session = rememberBackupImportPickerSession()
    return remember(session, staging) { IosBackupFilePicker(session, staging) }
}

actual fun backupPlatformName(): String = "ios"

private class IosBackupFilePicker(
    private val session: BackupImportPickerSession,
    private val staging: BackupImportStaging,
) : BackupFilePicker {
    // UIKit holds its delegate weakly; the in-flight picker's delegate is retained here.
    private var activeDelegate: NSObject? = null

    override fun launchExport(
        sourcePath: String,
        suggestedName: String,
        onResult: (Boolean) -> Unit,
    ) {
        val rootVC = resolveRootViewController()
        if (rootVC == null) {
            onResult(false)
            return
        }
        val delegate =
            object : NSObject(), UIDocumentPickerDelegateProtocol {
                override fun documentPicker(
                    controller: UIDocumentPickerViewController,
                    didPickDocumentsAtURLs: List<*>,
                ) {
                    activeDelegate = null
                    onResult(true)
                }

                override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                    activeDelegate = null
                    onResult(false)
                }
            }
        activeDelegate = delegate
        val picker =
            UIDocumentPickerViewController(
                forExportingURLs = listOf(NSURL.fileURLWithPath(sourcePath)),
                asCopy = true,
            )
        picker.delegate = delegate
        rootVC.presentViewController(picker, animated = true, completion = null)
    }

    override fun launchImport(onResult: (AppResult<String?>) -> Unit) {
        if (!session.begin(onResult)) return
        val rootVC = resolveRootViewController()
        if (rootVC == null) {
            session.failPick(IOException("Backup picker presentation unavailable"))
            return
        }
        try {
            val delegate = importDelegate()
            activeDelegate = delegate
            val picker =
                UIDocumentPickerViewController(
                    forOpeningContentTypes = listOf(UTTypeZIP),
                    asCopy = false,
                )
            picker.delegate = delegate
            rootVC.presentViewController(picker, animated = true, completion = null)
        } catch (failure: Exception) {
            activeDelegate = null
            session.failPick(failure)
        }
    }

    private fun importDelegate() =
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(
                controller: UIDocumentPickerViewController,
                didPickDocumentsAtURLs: List<*>,
            ) {
                activeDelegate = null
                val picked = didPickDocumentsAtURLs.singleOrNull() as? NSURL
                if (picked == null) session.failPick(IOException("Backup picker returned no document"))
                else session.acquire { checkpoint -> acquireIosBackup(picked, staging, checkpoint) }
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                activeDelegate = null
                session.cancelPick()
            }
        }

    /**
     * Root-VC resolution copied from `IosScreenshotProvider`: prefer the key window of the
     * active foreground scene, fall back to the deprecated `keyWindow` for single-window setups.
     */
    @Suppress("DEPRECATION")
    private fun resolveRootViewController(): UIViewController? {
        val app = UIApplication.sharedApplication
        val sceneWindow =
            app.connectedScenes
                .filterIsInstance<UIWindowScene>()
                .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
                ?.let { scene ->
                    val windows = scene.windows.filterIsInstance<UIWindow>()
                    windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull()
                }
        return (sceneWindow ?: app.keyWindow)?.rootViewController
    }
}
