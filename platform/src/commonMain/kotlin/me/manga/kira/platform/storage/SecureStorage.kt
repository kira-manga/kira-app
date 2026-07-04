package me.manga.kira.platform.storage

/**
 * Encrypted key/value store for sensitive material (auth tokens, refresh tokens, API keys).
 *
 * Backed per-platform by:
 *  - Android: `EncryptedSharedPreferences` (`androidx.security:security-crypto`) with an
 *    AES-256-GCM master key.
 *  - iOS:     Keychain Services via `platform.Security.*` (`kSecClassGenericPassword`,
 *             access policy `kSecAttrAccessibleAfterFirstUnlock`).
 *  - Desktop: AES-256-GCM encrypted properties file under `~/.kira-manga/` with a per-user
 *             32-byte master key persisted to `~/.kira-manga/.key` (best-effort 0600 / DOS-hidden).
 *
 * Operations are suspending because the underlying backends do real disk I/O (Android
 * EncryptedSharedPreferences, Desktop file writes) or short blocking native calls
 * (iOS Keychain). Callers should treat them as IO-bound.
 *
 * Phase 5.v relocation of legacy `:shared/.../core/storage/SecureStorage.kt` (expect class)
 * into the clean `:platform` layer. The on-disk format on every target is preserved
 * byte-for-byte from the legacy implementation, so existing installations transparently
 * keep their stored secrets across the rework cut-over.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster145.staleKdocSweep.cascade,
 * Task #601, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-fifty-eighth sibling of the cluster57-144
 * sweep — first file of the wave-26 :platform tier cluster145 5-leaf
 * storage-plus-net-plus-notif-plus-push batch alongside SettingsFactory
 * plus ConnectivityObserver plus NotificationPresenter plus
 * PushTokenProvider):
 *  (a) "Encrypted-key-value-store-for-sensitive-material-auth-tokens-
 *  refresh-tokens-API-keys + Backed-per-platform-by-Android-Encrypted-
 *  SharedPreferences-androidx.security-security-crypto-with-an-AES-256-
 *  GCM-master-key + iOS-Keychain-Services-via-platform.Security-
 *  kSecClassGenericPassword-access-policy-kSecAttrAccessibleAfterFirst-
 *  Unlock + Desktop-AES-256-GCM-encrypted-properties-file-under-yami-
 *  manga-with-a-per-user-32-byte-master-key-persisted-to-yami-manga-
 *  key-best-effort-0600-DOS-hidden + Operations-are-suspending-because-
 *  the-underlying-backends-do-real-disk-IO-or-short-blocking-native-
 *  calls + Callers-should-treat-them-as-IO-bound" — LIVE-NOT-STALE.
 *  Verified: 3 actuals shipped at platform/src/{android,ios,desktop}-
 *  Main/storage/ (Android EncryptedSharedPreferences + AES-256-GCM
 *  master-key, iOS Keychain Services with kSecAttrAccessibleAfterFirst-
 *  Unlock, Desktop AES-256-GCM properties-file under ~/.kira-manga/
 *  with per-user .key). All 3 operations (put + get + remove) honored
 *  by every actual; the suspend signature consistently uses IO-bound
 *  dispatcher in implementations.
 *  (b) "Phase-5.v-relocation-of-legacy-:shared-core-storage-SecureStorage-
 *  expect-class-into-the-clean-:platform-layer + The-on-disk-format-on-
 *  every-target-is-preserved-byte-for-byte-from-the-legacy-
 *  implementation-so-existing-installations-transparently-keep-their-
 *  stored-secrets-across-the-rework-cut-over" — LIVE-NOT-STALE plus
 *  PARTIALLY-FULFILLED-FORECAST. Verified: the legacy `:shared` expect-
 *  class facade at shared/src/commonMain/kotlin/me/manga/yamiapk/core/
 *  storage/SecureStorage.kt is still LIVE — wired via :shared
 *  PlatformModule.{android,ios,desktop}.kt and consumed by legacy
 *  auth/token handlers + app/src/main/java/me/manga/yamiapk/MyApp.kt
 *  bootstrap (cross-classified at Task #422 BLOCKER on the §250 shadow-
 *  legacy-facade retire path). The byte-for-byte on-disk-format-
 *  preservation contract is honored — the rework actuals reuse the
 *  legacy key names + cipher modes so a single device's stored-secrets
 *  remain readable through both facades simultaneously during the
 *  strangler-fig transition.
 *  Two classifications STAND on their own merits. Original Phase 5.v
 *  (Task #172) :platform-relocation prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
interface SecureStorage {

    /** Persist [value] under [key], overwriting any prior value at that key. */
    suspend fun put(key: String, value: String)

    /** Retrieve the stored value for [key], or `null` if absent / corrupted / cipher-mismatched. */
    suspend fun get(key: String): String?

    /** Remove [key] from the store. Idempotent — absent keys are a no-op. */
    suspend fun remove(key: String)
}
