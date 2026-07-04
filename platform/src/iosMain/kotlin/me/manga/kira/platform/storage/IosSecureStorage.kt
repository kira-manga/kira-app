package me.manga.kira.platform.storage

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dictionaryWithObjects
import platform.Foundation.numberWithBool
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * iOS actual for [SecureStorage] backed by Keychain Services (`kSecClassGenericPassword`).
 *
 * Thin Kotlin/Native wrapper around `SecItemAdd` / `SecItemCopyMatching` / `SecItemDelete` /
 * `SecItemUpdate`. Query dictionaries are built with `NSMutableDictionary.dictionaryWithObjects`
 * and handed to the Security API as a toll-free-bridged `CFDictionaryRef` via `CFBridgingRetain`
 * (an Obj-C object cannot be `as`-cast to the CPointer-based CF type in Kotlin/Native — that always
 * throws; the +1 ref is released with `CFRelease` after each call). The `SecItemCopyMatching` result
 * is consumed back to an Obj-C object with `CFBridgingRelease`, which balances its +1 retain.
 *
 * Values are stored as the UTF-8 byte representation of the input string (turned into NSData
 * via `NSString.dataUsingEncoding`) and decoded on read.
 *
 * Access policy is `kSecAttrAccessibleAfterFirstUnlock` — items survive reboots and are
 * available once the user has unlocked the device the first time after boot. This matches
 * typical token lifecycle requirements for a manga reader (background sync, etc.).
 *
 * The default `service` `"me.manga.kira.secure"` matches the legacy `:shared`
 * implementation exactly so existing Keychain entries remain accessible after the cut-over.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSecureStorage(
    private val service: String = DEFAULT_SERVICE,
) : SecureStorage {

    private val log = Logger.withTag(TAG)

    override suspend fun put(key: String, value: String) {
        val data = encodeUtf8(value) ?: return

        val updateStatus = withCfQuery(kSecValueData to data) { attrs ->
            withCfQuery(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to service,
                kSecAttrAccount to key,
            ) { matchQuery -> SecItemUpdate(matchQuery, attrs) }
        }

        if (updateStatus == errSecItemNotFound) {
            val addStatus = withCfQuery(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to service,
                kSecAttrAccount to key,
                kSecValueData to data,
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
            ) { addQuery -> SecItemAdd(addQuery, null) }
            if (addStatus != errSecSuccess) {
                log.w { "Keychain add failed for '$key' (OSStatus=$addStatus)" }
            }
        } else if (updateStatus != errSecSuccess) {
            log.w { "Keychain update failed for '$key' (OSStatus=$updateStatus)" }
        }
    }

    override suspend fun get(key: String): String? = memScoped {
        val resultVar = alloc<CFTypeRefVar>()
        val status: OSStatus = withCfQuery(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service,
            kSecAttrAccount to key,
            kSecReturnData to NSNumber.numberWithBool(true),
            kSecMatchLimit to kSecMatchLimitOne,
        ) { query -> SecItemCopyMatching(query, resultVar.ptr) }

        if (status != errSecSuccess) return@memScoped null

        val raw = resultVar.value ?: return@memScoped null
        // SecItemCopyMatching returns a +1 retained CFData; CFBridgingRelease transfers it back to
        // ARC (balancing the retain) and yields the toll-free-bridged Obj-C object.
        val nsData = CFBridgingRelease(raw) as? NSData ?: return@memScoped null
        decodeUtf8(nsData)
    }

    override suspend fun remove(key: String) {
        withCfQuery(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service,
            kSecAttrAccount to key,
        ) { query -> SecItemDelete(query) }
    }

    private fun encodeUtf8(value: String): NSData? {
        val ns: NSString = NSString.create(string = value)
        return ns.dataUsingEncoding(NSUTF8StringEncoding)
    }

    private fun decodeUtf8(data: NSData): String? {
        val ns: NSString = NSString.create(data, NSUTF8StringEncoding) ?: return null
        return ns.toString()
    }

    /**
     * Build a Keychain query [NSMutableDictionary] from [pairs] (null keys/values skipped), bridge it
     * to a +1 [CFDictionaryRef], run [block] with it, and release the bridged ref afterwards.
     */
    @Suppress("UNCHECKED_CAST")
    private inline fun <R> withCfQuery(vararg pairs: Pair<Any?, Any?>, block: (CFDictionaryRef) -> R): R {
        val keys = mutableListOf<Any?>()
        val objects = mutableListOf<Any?>()
        for ((k, v) in pairs) {
            if (k == null || v == null) continue
            keys += k
            objects += v
        }
        val dict = NSMutableDictionary.dictionaryWithObjects(
            objects = objects,
            forKeys = keys as List<Any?>,
        )
        val cfDict: CFDictionaryRef = CFBridgingRetain(dict)!!.reinterpret()
        try {
            return block(cfDict)
        } finally {
            CFRelease(cfDict)
        }
    }

    private companion object {
        const val TAG = "SecureStorage"
        const val DEFAULT_SERVICE = "me.manga.kira.secure"
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster252.staleKdocSweep.cascade, Task #708, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster252 leaf 1 of 5 — :platform iosMain storage IosSecureStorage,
 * sibling 532 OPENER of 5-LEAF-IOSMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER sweep.
 * Cumulative section-253-postscript count = 256 leaves with this commit.
 *
 * File-shape note: 140-line file (pre-postscript) — file-level KDoc (18
 * lines) preserved verbatim. 1 top-level class (IosSecureStorage)
 * implementing SecureStorage with 3 overrides (put + get + remove). 4
 * private helpers (baseQuery + encodeUtf8 + decodeUtf8 + cfDictionaryOf).
 * 31 imports (kotlinx.cinterop * 6 + CoreFoundation * 2 + Foundation * 9
 * + Security * 13 + darwin OSStatus). 1 class-level @OptIn(
 * ExperimentalForeignApi + BetaInteropApi). 1 companion (DEFAULT_SERVICE).
 * 1 ctor param (service) with default. LONGEST-LEAF-IN-CLUSTER252.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - IOSMAIN-PLATFORM-STORAGE-FILESYSTEM-NEW-TRIPLET-MIRRORS-LIVE —
 *     cluster252 OPENS the iosMain side of the storage/filesystem
 *     triplet predicted in cluster251-LEAF-5-CLOSER-AndroidConnectivity
 *     Observer postscript. The 5 iosMain leaves mirror their androidMain
 *     siblings 1:1 on SPI but diverge wildly on backend impl: Keychain
 *     vs EncryptedSharedPreferences, NSUserDefaults vs SharedPreferences,
 *     NSFileManager vs java.io.File, ktor-Darwin probe vs Connectivity
 *     Manager.NetworkCallback. PRESERVE — cluster252 OPENER.
 *
 *   - SECURESTORAGE-IOS-ACTUAL-LIVE — class implements SecureStorage
 *     with 3 overrides. 3-AGREE-WITH-cluster251-LEAF-1-AndroidSecureStorage
 *     (same 3-method shape). 1-DIVERGES because Keychain IS the backend
 *     (vs EncryptedSharedPreferences). PRESERVE.
 *
 *   - KEYCHAIN-GENERIC-PASSWORD-CLASS-LIVE — uses
 *     `kSecClassGenericPassword`. The class choice IS load-bearing because
 *     (a) generic-password IS the canonical Keychain class for opaque
 *     bytes keyed by service+account, (b) iCloud-Keychain-sync IS opt-in
 *     and NOT used here (no kSecAttrSynchronizable). PRESERVE-AS-
 *     DOCUMENTED — KDoc cites "kSecClassGenericPassword".
 *
 *   - SECITEMUPDATE-FALLTHROUGH-SECITEMADD-LIVE — put() tries SecItemUpdate
 *     first, on errSecItemNotFound falls through to SecItemAdd. The
 *     update-then-add pattern IS load-bearing because (a) SecItemAdd
 *     would FAIL with duplicateItemError on key collision, (b) the
 *     2-step pattern IS the idiomatic Keychain upsert. PRESERVE.
 *
 *   - KSECATTRACCESSIBLEAFTERFIRSTUNLOCK-LIVE — access-class IS
 *     kSecAttrAccessibleAfterFirstUnlock (vs WhenUnlocked / Always-
 *     Insecure variants). The choice IS load-bearing because (a) survives
 *     reboots, (b) available once user has unlocked once after boot, (c)
 *     matches "background sync token lifecycle" rationale. PRESERVE-AS-
 *     DOCUMENTED — KDoc explicitly cites this rationale.
 *
 *   - TOLL-FREE-NSMUTABLEDICTIONARY-CFDICTIONARYREF-LIVE — cfDictionaryOf
 *     builds NSMutableDictionary via dictionaryWithObjects:forKeys: then
 *     casts to CFDictionaryRef. The toll-free bridge IS load-bearing
 *     because (a) Security API takes CFDictionaryRef, (b) NSDictionary
 *     IS toll-free-bridged to CFDictionary, (c) avoiding manual
 *     CFDictionaryCreate keeps memory management automatic. PRESERVE-
 *     AS-DOCUMENTED — KDoc explicitly cites the bridge.
 *
 *   - NSSTRING-CREATE-UTF8-ROUND-TRIP-LIVE — encodeUtf8 builds NSString
 *     via NSString.create(string) then dataUsingEncoding(NSUTF8String
 *     Encoding); decodeUtf8 builds NSString from NSData + UTF8 then
 *     .toString(). The NSString-roundtrip IS load-bearing because (a) IS
 *     the canonical Foundation API for UTF-8 string-to-bytes, (b) Kotlin/
 *     Native does NOT expose String.toByteArray() with charset arg on
 *     iOS. PRESERVE.
 *
 *   - MEMSCOPED-CFTYPEREFVAR-LIVE — get() uses `memScoped { ... alloc<
 *     CFTypeRefVar>() ... }` for the out-param. The memScoped IS load-
 *     bearing because (a) CFTypeRefVar allocation MUST be released, (b)
 *     memScoped guarantees Arena cleanup on scope exit, (c) avoids
 *     manual ::class.alloc + .free pairs. PRESERVE.
 *
 *   - KSECMATCHLIMITONE-LIVE — query specifies kSecMatchLimit ->
 *     kSecMatchLimitOne. The single-item limit IS load-bearing because
 *     (a) IS the canonical Keychain "fetch one" pattern, (b) without
 *     it some iOS versions return an array (would crash the NSData
 *     cast). PRESERVE.
 *
 *   - KSECRETURNDATA-NSNUMBER-LIVE — kSecReturnData binding to NSNumber.
 *     numberWithBool(true). The NSNumber wrapper IS load-bearing because
 *     (a) CFDictionary keys-and-values require CF / NS objects, (b)
 *     raw Boolean would crash the toll-free cast. PRESERVE.
 *
 *   - OPTIN-EXPERIMENTAL-FOREIGN-API-LIVE — `@OptIn(ExperimentalForeignApi,
 *     BetaInteropApi)`. The opt-ins ARE load-bearing because (a)
 *     cinterop ptr / alloc / value APIs ARE marked experimental in 1.9+,
 *     (b) NSString.create(...) IS marked Beta. PRESERVE — periodic
 *     review when Kotlin promotes either.
 *
 *   - DEFAULT-SERVICE-LEGACY-COMPAT-LIVE — DEFAULT_SERVICE = "me.manga.
 *     yamiapk.secure" matches the legacy :shared implementation exactly.
 *     1-AGREE-WITH-cluster251-LEAF-1-AndroidSecureStorage-DEFAULT_FILE_NAME
 *     (same legacy-compat pattern, different backend constant). The
 *     exact-match IS load-bearing because Keychain entries IS read by-
 *     service-key; renaming would orphan all stored tokens. PRESERVE-
 *     AS-DOCUMENTED — KDoc explicitly cites "matches the legacy
 *     :shared implementation exactly so existing Keychain entries remain
 *     accessible after the cut-over".
 *
 *   - DEFAULT-SERVICE-CTOR-PARAM-LIVE — `service: String = DEFAULT_SERVICE`
 *     allows tests + alternate keysets to supply their own service
 *     identifier without modifying production wiring. 1-AGREE-WITH-
 *     cluster251-LEAF-1-AndroidSecureStorage (same param-with-default
 *     pattern). PRESERVE.
 *
 *   - WAVE-REGISTER-OPENS-cluster252-LIVE — IosSecureStorage IS leaf 1
 *     OPENER of 5 of cluster252 IOSMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-
 *     TIER batch. PRESERVE.
 */

