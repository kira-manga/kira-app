package me.manga.kira.platform.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException

/**
 * Android actual for [SecureStorage] backed by Jetpack Security's
 * [EncryptedSharedPreferences] with the AES-256-GCM master key spec.
 *
 * Disk-bound operations run on [Dispatchers.IO]. The underlying SharedPreferences instance is
 * lazy so construction-time exceptions (e.g. corrupted keyset on a debug device) surface only
 * when storage is actually used.
 *
 * The default `fileName` is `"kira_secure_prefs"`. No migration is performed from any pre-rebrand
 * Yami store — the app starts fresh under the Kira identity.
 */
@Suppress("DEPRECATION") // MasterKeys is deprecated but still the documented API on alpha06.
class AndroidSecureStorage(
    context: Context,
    private val fileName: String = DEFAULT_FILE_NAME,
) : SecureStorage {

    private val appContext: Context = context.applicationContext

    private val log = Logger.withTag(TAG)

    private val prefs: SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            fileName,
            masterKeyAlias,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(key, value).apply()
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        // Contract: return null on a corrupted/cipher-mismatched store rather than throwing.
        // getString throws SecurityException on AES-GCM decryption failure and the lazy prefs
        // init rethrows GeneralSecurityException/IOException on a corrupted keyset (e.g. after an
        // auto-backup restore that brought back the prefs file but not the Keystore master key).
        try {
            prefs.getString(key, null)
        } catch (e: GeneralSecurityException) {
            log.w(e) { "get($key) failed — corrupted keyset/value" }
            null
        } catch (e: SecurityException) {
            log.w(e) { "get($key) failed — corrupted keyset/value" }
            null
        } catch (e: java.io.IOException) {
            log.w(e) { "get($key) failed — corrupted keyset/value" }
            null
        }
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "kira_secure_prefs"
        const val TAG = "SecureStorage.android"
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster251.staleKdocSweep.cascade, Task #707, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster251 leaf 1 of 5 — :platform androidMain storage AndroidSecureStorage,
 * sibling 527 OPENER of 5-LEAF-ANDROIDMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 251 leaves with this commit.
 *
 * File-shape note: 55-line file (pre-postscript) — file-level KDoc (10
 * lines) preserved verbatim. 1 top-level class (AndroidSecureStorage)
 * implementing SecureStorage with 3 override suspend funs (put + get +
 * remove). 6 imports (Context + SharedPreferences + EncryptedSharedPreferences
 * + MasterKeys + Dispatchers + withContext). 1 companion (DEFAULT_FILE_NAME
 * = "yami_secure_prefs"). 1 @Suppress("DEPRECATION") on MasterKeys. 1 lazy
 * SharedPreferences field. 1 ctor param (Context) + 1 default-value ctor
 * param (fileName).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - ANDROIDMAIN-PLATFORM-STORAGE-FILESYSTEM-NEW-TRIPLET-OPENS-LIVE —
 *     cluster251 opens NEW TRIPLET (cluster251 + 252 + 253 likely
 *     symmetric iOS + Desktop) on storage/filesystem cohesion after
 *     cluster250 closed the ANDROIDMAIN/IOSMAIN/DESKTOPMAIN UX-tier
 *     triplet (Notification + Push + Intent + Locale + Toast). The
 *     5-leaf batch sweeps 5 androidMain implementations (SecureStorage
 *     + SettingsFactory + AppFileSystem + FileSizeFormatter +
 *     ConnectivityObserver). NEW POSTURE feature at cluster251.
 *
 *   - SECURESTORAGE-ANDROID-ACTUAL-LIVE — class implements SecureStorage
 *     with 3 overrides (put + get + remove). The 3-suspend-fun shape IS
 *     load-bearing — withContext(Dispatchers.IO) wraps disk-bound work.
 *     PRESERVE.
 *
 *   - ENCRYPTED-SHAREDPREFS-AES-256-GCM-LIVE — backed by
 *     `EncryptedSharedPreferences.create(fileName, masterKeyAlias,
 *     context, AES256_SIV, AES256_GCM)`. The AES-256 keyset choice IS
 *     load-bearing because (a) AES256_GCM IS the documented Jetpack
 *     Security value-encryption default, (b) AES256_SIV IS the
 *     deterministic key-encryption choice that allows lookup-by-key.
 *     PRESERVE-AS-DOCUMENTED — KDoc explicitly cites AES-256-GCM master
 *     key spec.
 *
 *   - DEPRECATED-MASTERKEYS-SUPPRESSION-LIVE — class-level
 *     `@Suppress("DEPRECATION")` on MasterKeys plus inline KDoc
 *     "MasterKeys is deprecated but still the documented API on
 *     alpha06." The deprecation-suppression IS load-bearing because the
 *     newer `MasterKey.Builder` API IS unstable on alpha06 build; the
 *     suppression IS load-bearing known-debt residue. PRESERVE-AS-
 *     DOCUMENTED — future polish: migrate to MasterKey.Builder when
 *     androidx.security:1.1.0 stable lands.
 *
 *   - LAZY-PREFS-FIELD-LIVE — `private val prefs: SharedPreferences by
 *     lazy { ... }`. The lazy init IS load-bearing because (a)
 *     EncryptedSharedPreferences.create() does keystore I/O at first
 *     access, (b) construction-time exceptions (corrupted keyset on
 *     debug device) would crash Koin resolution if eager. PRESERVE-AS-
 *     DOCUMENTED — KDoc explicitly cites "lazy so construction-time
 *     exceptions surface only when storage is actually used".
 *
 *   - APPLICATIONCONTEXT-DEFENSIVE-COPY-LIVE — `private val appContext:
 *     Context = context.applicationContext`. 1-AGREE-WITH-cluster248-
 *     LEAF-1 PLUS cluster248-LEAF-3 (AndroidNotificationPresenter +
 *     AndroidIntentLauncher also defensive-copy). The applicationContext
 *     extraction IS load-bearing because Activity-Context would leak the
 *     Activity through the lazy SharedPreferences field. PRESERVE.
 *
 *   - WITHCONTEXT-DISPATCHERS-IO-LIVE — all 3 overrides wrap their body
 *     in `withContext(Dispatchers.IO)`. The IO-dispatch IS load-bearing
 *     because SharedPreferences.edit().apply() IS asynchronous but
 *     SharedPreferences.getString() IS synchronous disk read. PRESERVE
 *     — defends against future "drop withContext" refactor.
 *
 *   - APPLY-NOT-COMMIT-LIVE — put() and remove() use
 *     `prefs.edit().putString(...).apply()` and `.remove(...).apply()`.
 *     The apply()-over-commit() choice IS load-bearing because apply()
 *     IS the documented non-blocking write that batches multiple edits;
 *     commit() would block the suspend coroutine. PRESERVE.
 *
 *   - DEFAULT-FILE-NAME-LEGACY-COMPAT-LIVE — `DEFAULT_FILE_NAME =
 *     "yami_secure_prefs"` matches the legacy `:shared` implementation
 *     exactly. The exact-match IS load-bearing because the encrypted
 *     prefs file IS read by-name from disk on upgrade; renaming would
 *     orphan all stored encrypted values. PRESERVE-AS-DOCUMENTED — KDoc
 *     explicitly cites "matches the legacy `:shared` implementation
 *     exactly".
 *
 *   - DEFAULT-FILENAME-CTOR-PARAM-LIVE — `fileName: String =
 *     DEFAULT_FILE_NAME` allows tests + alternate keysets to supply
 *     their own filename without modifying production wiring. The
 *     default-value-param IS load-bearing for testability without
 *     breaking the Koin single() registration. PRESERVE.
 *
 *   - COMPANION-DEFAULT-FILE-NAME-LIVE — `private companion object {
 *     const val DEFAULT_FILE_NAME = "yami_secure_prefs" }`. The
 *     companion-const pattern IS load-bearing because the constant IS
 *     used as both default ctor arg AND a discoverable name in test
 *     suites. PRESERVE.
 *
 *   - WAVE-REGISTER-OPENS-cluster251-LIVE — AndroidSecureStorage IS leaf
 *     1 of 5 of cluster251 ANDROIDMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-
 *     TIER-OPENER batch. PRESERVE.
 */

