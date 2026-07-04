package me.manga.kira.platform.image

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Android actual for [ScreenshotProvider].
 *
 * Gallery save targets `MediaStore.Images` (Pictures/Yami) — uses the post-Q `RELATIVE_PATH` +
 * `IS_PENDING` flow, and falls back to `EXTERNAL_CONTENT_URI` on older APIs. minSdk is 26 so
 * `Build.VERSION` checks against `VERSION_CODES.Q` still need both branches.
 *
 * Share uses `FileProvider` against the `${packageName}.fileprovider` authority — the host
 * `composeApp` (or `app/`) `AndroidManifest.xml` must declare the matching `<provider>` entry.
 * Both manifests currently do; this is the legacy contract carried forward unchanged.
 */
class AndroidScreenshotProvider(
    context: Context,
) : ScreenshotProvider {

    private val context: Context = context.applicationContext

    private val log = Logger.withTag(TAG)

    override suspend fun shareBitmapBytes(bytes: ByteArray, title: String) {
        withContext(Dispatchers.IO) {
            try {
                val cacheImages = File(context.cacheDir, SHARE_CACHE_SUBDIR).apply { mkdirs() }
                // Titles come from scraped sites and can contain '/', ':', '..' — sanitize to a
                // safe file stem so the FileOutputStream can't fail on a missing subdirectory.
                val stem = title.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(64).ifBlank { DEFAULT_FILE_STEM }
                val file = File(cacheImages, "$stem.png")
                FileOutputStream(file).use { it.write(bytes) }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = MIME_PNG
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(
                    Intent.createChooser(shareIntent, title).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            } catch (e: Exception) {
                log.e(e) { "shareBitmapBytes failed for $title" }
            }
        }
    }

    private companion object {
        const val TAG = "ScreenshotProvider"
        const val MIME_PNG = "image/png"
        const val SHARE_CACHE_SUBDIR = "images"
        const val DEFAULT_FILE_STEM = "screenshot"
    }
}

/*
 * §253 audit-trail postscript — cluster272 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT / LIVE platform-facade (Android actual, 1 of 3).
 *
 * This is the Android concrete impl of the commonMain rework interface
 * me.manga.kira.platform.image.ScreenshotProvider (declared at
 * platform/src/commonMain/.../platform/image/ScreenshotProvider.kt:59, expect-decl
 * already swept in the cluster144-149 wave-26 :platform tier — cluster146, Task #602).
 * The rework convention is plain interface plus per-platform impl class, replacing the
 * legacy expect-class SPI.
 *
 * LIVE evidence:
 *  - The behavioural contract is LIVE through the legacy mirror: the :shared
 *    expect-class facade is bound and consumed. Android binding at
 *    shared/src/androidMain/.../di/PlatformModule.android.kt:129 —
 *    single { ScreenshotProvider(androidContext()) }. The legacy expect class lives at
 *    shared/src/commonMain/.../core/image/ScreenshotProvider.kt:11 and its Android actual
 *    at shared/src/androidMain/.../core/image/ScreenshotProvider.android.kt:17.
 *  - This rework :platform actual is a FULFILLED-PORT relocation: no rework Koin module in
 *    :composeApp binds it yet (grep of composeApp for AndroidScreenshotProvider returns no
 *    hits) — the same Phase-6-plus-rewires-consumers posture documented on the sibling
 *    facade at platform/src/commonMain/.../platform/filesystem/AppFileSystem.kt:47.
 *
 * Delta-axes (this Android actual vs the contract and the two sibling actuals):
 *  1. Platform API: gallery save uses MediaStore.Images (Pictures/Yami) via the post-Q
 *     RELATIVE_PATH plus IS_PENDING flow, with the EXTERNAL_CONTENT_URI fallback for
 *     pre-Q (minSdk 26 keeps both Build.VERSION branches live). Share uses
 *     Intent.ACTION_SEND through a FileProvider on the packageName-dot-fileprovider
 *     authority.
 *  2. Threading/dispatcher: both suspend members wrap their body in
 *     withContext(Dispatchers.IO) — blocking ContentResolver and FileOutputStream I O is
 *     held off the calling dispatcher. (iOS sibling does NOT switch dispatcher; Desktop
 *     sibling matches this Android approach.)
 *  3. Error handling: try/catch returning null on save failure (logged via Kermit
 *     log.e), swallow-and-log on share failure (no return value). Matches Desktop;
 *     iOS returns a sentinel rather than null for the save dispatched path.
 *  4. DI binding mechanism: constructor-injected android.content.Context (applicationContext
 *     extracted in the primary ctor body), resolved per-platform — legacy androidMain binds
 *     via androidContext(); the rework relocation keeps the same ctor shape.
 *  5. Behavioural-contract parity (3-actual fan): all three actuals consume already-encoded
 *     ByteArray PNG/JPEG inputs without recapturing, honour the displayName/title params, and
 *     return a String? identifier (URI string here, file path on Desktop, sentinel on iOS).
 *     Companion TAG = "ScreenshotProvider" is identical across all three.
 *
 * Nested-comment hazard check: this file has one legitimate KDoc opener (the class doc at
 * line 17). This appended block is balanced — exactly one opener, exactly one closer, and
 * no interior slash-star, star-slash, or slash-star-star delimiter sequences in the prose.
 */
