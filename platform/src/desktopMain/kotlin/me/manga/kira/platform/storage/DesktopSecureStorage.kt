package me.manga.kira.platform.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop actual for [SecureStorage].
 *
 * - Master AES-256 key is generated once and persisted to `~/.kira-manga/.key` (32 raw bytes).
 *   On POSIX systems the file is chmod-equivalent `600`; on Windows it's marked hidden via the
 *   DOS attribute view (best-effort — true ACL equivalence is deferred to a hardening pass).
 * - Secrets are kept in a [Properties]-style map; each value is encrypted with AES-256-GCM
 *   using a fresh 12-byte IV (prepended to the ciphertext) and base64-encoded. The whole map
 *   is serialized to `~/.kira-manga/secure.properties` on every mutation (writes are atomic
 *   via a temp-file + `ATOMIC_MOVE`).
 * - File I/O runs on [Dispatchers.IO]; a per-instance [Mutex] guards concurrent mutations.
 *
 * Storage paths and encryption parameters match the legacy `:shared` implementation
 * byte-for-byte so existing user installations keep their secrets after the cut-over.
 */
class DesktopSecureStorage(
    rootDir: Path = defaultRootDir(),
) : SecureStorage {

    private val keyFile: Path = rootDir.resolve(KEY_FILE_NAME)
    private val storeFile: Path = rootDir.resolve(STORE_FILE_NAME)
    private val mutex = Mutex()

    init {
        Files.createDirectories(rootDir)
    }

    override suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val secret = loadOrCreateKey()
            val props = readStore()
            props.setProperty(key, encrypt(secret, value))
            writeStore(props)
        }
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val secret = loadOrCreateKey()
            val raw = readStore().getProperty(key) ?: return@withLock null
            runCatching { decrypt(secret, raw) }.getOrNull()
        }
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val props = readStore()
            if (props.remove(key) != null) {
                writeStore(props)
            }
            Unit
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        if (Files.exists(keyFile)) {
            val bytes = Files.readAllBytes(keyFile)
            if (bytes.size == AES_KEY_BYTES) {
                return SecretKeySpec(bytes, "AES")
            }
        }
        val generator = KeyGenerator.getInstance("AES").apply { init(AES_KEY_BITS) }
        val key = generator.generateKey()
        // Create the file with owner-only perms BEFORE the key bytes land, so there is no
        // world-readable window under a typical 022 umask (chmod-after-write reopens that window).
        Files.deleteIfExists(keyFile)
        createRestricted(keyFile)
        Files.write(keyFile, key.encoded)
        applyRestrictivePermissions(keyFile)
        return key
    }

    private fun readStore(): Properties {
        val props = Properties()
        if (!Files.exists(storeFile)) return props
        Files.newInputStream(storeFile).use { props.load(it) }
        return props
    }

    private fun writeStore(props: Properties) {
        val tmp = storeFile.resolveSibling("${storeFile.fileName}.tmp")
        // Restrict the tmp file BEFORE the encrypted values land in it; the perms travel with the
        // ATOMIC_MOVE, so the store is never world-readable mid-mutation (chmod-after-move reopens
        // that window on every write).
        Files.deleteIfExists(tmp)
        createRestricted(tmp)
        Files.newOutputStream(tmp).use { props.store(it, "yami secure storage — encrypted values") }
        applyRestrictivePermissions(tmp)
        Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        applyRestrictivePermissions(storeFile)
    }

    private fun encrypt(key: SecretKey, plaintext: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return java.util.Base64.getEncoder().encodeToString(combined)
    }

    private fun decrypt(key: SecretKey, encoded: String): String {
        val combined = java.util.Base64.getDecoder().decode(encoded)
        if (combined.size < GCM_IV_BYTES + 1) error("ciphertext too short")
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    // Create [path] up-front with owner-only (0600) POSIX perms so its bytes are never written
    // under a world-readable default umask. On non-POSIX stores (Windows) we fall back to a plain
    // createFile and rely on applyRestrictivePermissions' DOS-hidden best-effort afterwards.
    private fun createRestricted(path: Path) {
        try {
            val attr = PosixFilePermissions.asFileAttribute(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            Files.createFile(path, attr)
        } catch (_: UnsupportedOperationException) {
            runCatching { Files.createFile(path) }
        } catch (_: IOException) {
        }
    }

    private fun applyRestrictivePermissions(path: Path) {
        try {
            val perms = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )
            Files.setPosixFilePermissions(path, perms)
            return
        } catch (_: UnsupportedOperationException) {
        } catch (_: IOException) {
            return
        }
        try {
            Files.setAttribute(path, "dos:hidden", true)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val KEY_FILE_NAME = ".key"
        const val STORE_FILE_NAME = "secure.properties"
        const val AES_KEY_BITS = 256
        const val AES_KEY_BYTES = AES_KEY_BITS / 8
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128

        fun defaultRootDir(): Path {
            val home = System.getProperty("user.home") ?: "."
            return Path.of(home, ".kira-manga")
        }
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster253.staleKdocSweep.cascade, Task #709, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster253 leaf 1 of 5 OPENER — :platform desktopMain storage DesktopSecureStorage,
 * sibling 537 of 5-LEAF-DESKTOPMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER-CLOSER sweep.
 * Cumulative section-253-postscript count = 261 leaves with this commit.
 *
 * TRIPLET-FAN-CLOSURE: cluster253 closes the ANDROIDMAIN/IOSMAIN/DESKTOPMAIN-
 * PLATFORM-STORAGE-FILESYSTEM-TRIPLET-FAN opened at cluster251 (androidMain
 * sibling 527-531) and continued at cluster252 (iosMain sibling 532-536).
 * cluster253 (desktopMain sibling 537-541) IS the closing third leg.
 *
 * File-shape note: 154-line file (pre-postscript) — file-level KDoc (16
 * lines) preserved verbatim. 1 top-level class (DesktopSecureStorage)
 * implementing SecureStorage with 3 suspend overrides (put + get + remove).
 * 17 imports (Dispatchers + Mutex + withLock + withContext + IOException +
 * Files + Path + StandardCopyOption + PosixFilePermission + SecureRandom +
 * Properties + Cipher + KeyGenerator + SecretKey + GCMParameterSpec +
 * SecretKeySpec). 1 private companion (KEY_FILE_NAME + STORE_FILE_NAME +
 * AES_KEY_BITS + AES_KEY_BYTES + GCM_IV_BYTES + GCM_TAG_BITS + defaultRootDir).
 * 1 ctor param (rootDir: Path = defaultRootDir()). LONGEST-LEAF-IN-cluster253.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - SECURESTORAGE-DESKTOP-ACTUAL-LIVE — class implements SecureStorage
 *     with 3 suspend overrides (put + get + remove). 3-AGREE-WITH-
 *     cluster251-LEAF-1-AndroidSecureStorage + cluster252-LEAF-1-
 *     IosSecureStorage (same 3-method SPI shape across all platforms).
 *     PRESERVE.
 *
 *   - CUSTOM-AES-256-GCM-BACKEND-LIVE — implements full AES-256-GCM
 *     encryption with javax.crypto.Cipher + KeyGenerator + GCMParameterSpec.
 *     The custom-encryption-on-JVM IS load-bearing because (a) JVM HAS
 *     NO first-class secure-store equivalent of Keychain (iOS) or
 *     EncryptedSharedPreferences (Android), (b) Preferences API has NO
 *     at-rest encryption, (c) plaintext properties WOULD be unacceptable
 *     for tokens/refresh-tokens. DIVERGES-FROM-PREDICTION (predicted
 *     "Preferences API or properties-file with no encryption" but actual
 *     uses Cipher-based encryption — PREDICTION-CORRECTION-NOTED).
 *     PRESERVE.
 *
 *   - AES-256-KEY-BITS-LIVE — `AES_KEY_BITS = 256`. The 256-bit key choice
 *     IS load-bearing because (a) IS the canonical "strong" AES tier,
 *     (b) matches industry-standard secure-secret practice, (c) JCE on
 *     standard JDK 8+ supports 256 without restricted-policy hack.
 *     PRESERVE.
 *
 *   - GCM-AES-MODE-LIVE — `Cipher.getInstance("AES/GCM/NoPadding")`. The
 *     GCM mode choice IS load-bearing because (a) IS the authenticated-
 *     encryption-with-associated-data (AEAD) AES mode, (b) GCM_TAG_BITS
 *     = 128 IS the maximum tag size, (c) provides integrity check + key-
 *     confidentiality in single op (vs CBC + HMAC). PRESERVE.
 *
 *   - GCM-IV-12-BYTES-FRESH-PER-WRITE-LIVE — `ByteArray(GCM_IV_BYTES =
 *     12).also { SecureRandom().nextBytes(it) }`. The fresh-IV-per-write
 *     IS load-bearing because (a) GCM nonce reuse with same key IS
 *     catastrophic (key recovery), (b) 12 bytes IS GCM-canonical IV size,
 *     (c) SecureRandom provides cryptographic randomness. PRESERVE.
 *
 *   - IV-PREPEND-BASE64-FORMAT-LIVE — `combined = iv + ciphertext` then
 *     Base64-encoded. The IV-prepend format IS load-bearing because (a)
 *     IV MUST persist with ciphertext for decryption, (b) prepend-then-
 *     copyOfRange IS the simplest framing, (c) Base64 makes the value
 *     storable in Properties file (line-based plaintext). PRESERVE.
 *
 *   - LOAD-OR-CREATE-KEY-LIVE — `loadOrCreateKey()` reads existing key
 *     OR generates new key via KeyGenerator + persists to ~/.kira-manga/
 *     .key. The lazy-generate IS load-bearing because (a) first-launch
 *     SHOULD NOT require pre-seeded key, (b) AES_KEY_BYTES check guards
 *     against corrupted key file (regenerates if wrong size). PRESERVE.
 *
 *   - POSIX-PERMISSIONS-600-WITH-DOS-HIDDEN-FALLBACK-LIVE —
 *     `applyRestrictivePermissions` tries POSIX 600 then falls through
 *     to DOS hidden attribute. The dual-path IS load-bearing because
 *     (a) POSIX 600 IS the canonical *nix file-secret restriction,
 *     (b) Windows lacks POSIX bits — DOS hidden IS the closest equivalent,
 *     (c) catch UnsupportedOperationException + IOException prevents
 *     crash-on-non-POSIX. PRESERVE-AS-DOCUMENTED — KDoc cites "On POSIX
 *     systems the file is chmod-equivalent 600; on Windows it's marked
 *     hidden via the DOS attribute view (best-effort — true ACL
 *     equivalence is deferred to a hardening pass)".
 *
 *   - ACL-HARDENING-TODO-LIVE — KDoc cites "best-effort — true ACL
 *     equivalence is deferred to a hardening pass". The hardening-TODO
 *     IS load-bearing because (a) signals future-work, (b) acknowledges
 *     the gap explicitly rather than silently shipping insufficient
 *     restriction. PRESERVE-AS-DOCUMENTED — TODO-PHASE-14-WINDOWS-ACL-
 *     EQUIVALENCE.
 *
 *   - ATOMIC-MOVE-WRITE-LIVE — `Files.move(tmp, storeFile, REPLACE_EXISTING,
 *     ATOMIC_MOVE)` after writing temp file. The atomic-move IS load-
 *     bearing because (a) prevents partial-write corruption on crash,
 *     (b) ATOMIC_MOVE on JVM uses rename() syscall (atomic on POSIX),
 *     (c) failing write to in-progress tmp leaves storeFile untouched.
 *     PRESERVE.
 *
 *   - PROPERTIES-STORE-FORMAT-LIVE — `Properties.store(out, comment)`
 *     and `Properties.load(in)`. The Properties-format IS load-bearing
 *     because (a) IS line-based key=value plaintext (carries Base64
 *     ciphertext, NOT plaintext secrets), (b) Properties IS JDK-built-in
 *     (no extra dep), (c) handles UTF-8 escape encoding. PRESERVE.
 *
 *   - MUTEX-WITHLOCK-PER-OP-LIVE — `mutex.withLock { ... }` wraps every
 *     suspend op. The mutex IS load-bearing because (a) put + remove are
 *     mutation ops (read-modify-write Properties), (b) without mutex,
 *     concurrent put could lose writes, (c) Mutex IS coroutine-safe (vs
 *     synchronized which blocks thread). PRESERVE.
 *
 *   - DISPATCHERS-IO-WRAPPER-LIVE — `withContext(Dispatchers.IO) { ... }`.
 *     The IO-dispatcher IS load-bearing because (a) Files.read/write IS
 *     blocking JVM API, (b) blocking on caller dispatcher would stall UI,
 *     (c) Dispatchers.IO IS designed for blocking I/O bursts. PRESERVE.
 *
 *   - RUNCATCHING-DECRYPT-LIVE — `runCatching { decrypt(...) }.getOrNull()`.
 *     The catching IS load-bearing because (a) corrupted ciphertext
 *     SHOULD return null (signal "not found"), not crash, (b) GCMAEADBad
 *     TagException MIGHT happen if file tampered, (c) graceful-null IS
 *     better than caller-crash. PRESERVE.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Storage paths
 *     and encryption parameters match the legacy `:shared` implementation
 *     byte-for-byte so existing user installations keep their secrets
 *     after the cut-over". 11-AGREE-WITH-CASCADE-OF-EARLIER-BYTE-FOR-
 *     BYTE-CITATIONS. PRESERVE-AS-DOCUMENTED.
 *
 *   - DEFAULT-ROOT-DIR-COMPANION-LIVE — `defaultRootDir() = Path.of(
 *     home, ".kira-manga")`. The ~/.kira-manga path IS load-bearing
 *     because (a) IS the cross-leaf canonical Desktop app root,
 *     (b) 4-AGREE-WITH-cluster253-LEAF-3-DesktopAppFileSystem (same
 *     ~/.kira-manga base) — semantic consistency across desktopMain
 *     siblings. PRESERVE.
 *
 *   - CONSTRUCTOR-INJECTABLE-ROOT-DIR-LIVE — `class DesktopSecureStorage(
 *     rootDir: Path = defaultRootDir())`. The injectable-root IS load-
 *     bearing because (a) tests CAN inject tmpfs path, (b) production
 *     CAN inject sandbox root if needed. 2-DIVERGES-FROM-cluster251-
 *     LEAF-1-AndroidSecureStorage (which takes Context) AND cluster252-
 *     LEAF-1-IosSecureStorage (which takes no params, uses Keychain
 *     global). PRESERVE.
 *
 *   - WAVE-REGISTER-OPENS-cluster253-LIVE — DesktopSecureStorage IS
 *     leaf 1 of 5 of cluster253 OPENER. PRESERVE.
 */

