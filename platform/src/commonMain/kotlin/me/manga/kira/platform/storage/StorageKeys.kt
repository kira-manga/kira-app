package me.manga.kira.platform.storage

/**
 * Storage key constants consumed by [DataStoreHelper] (PC-8 Wave 1 relocation of the legacy
 * `:shared/.../core/storage/StorageKeys.kt` constants object into the clean `:platform` layer).
 *
 * The multiplatform-settings `Settings` surface is untyped — its methods are `getBoolean(key,
 * default)` / `getString(key, default)` / `getInt(key, default)` etc. — so the keys are plain
 * `String` constants and the value type is enforced by the call site.
 *
 * **Wire-format invariant**: every string literal here is byte-for-byte identical to the legacy
 * `:shared` `StorageKeys` object so a single device's existing preferences store remains
 * readable through both facades during the strangler-fig cut-over (the legacy facade still binds
 * the same `ObservableSettings`/store name in PlatformModule.{android,ios,desktop}.kt). Only the
 * keys actually read/written by [DataStoreHelper] are mirrored here; the theme keys
 * (`KEY_THEME_MODE` / `KEY_THEME_SYSTEM` / `KEY_PURE_BLACK`) and `ACTIVE_TAB` live with their
 * own consumers and are intentionally not duplicated into this object.
 */
internal object StorageKeys {
    const val NEW_SOURCES = "new_sources_added"
    const val DownloadedOnly = "downloaded_only"
    const val Incognito = "incognito_mode"
    const val READING_MODE = "reading_mode"

    const val SELECTED_LANGUAGE = "selected_language"

    const val KEY_USE_CBZ_FORMAT = "use_cbz_format"
    const val KEY_AUTO_CONVERT_TO_CBZ = "auto_convert_to_cbz"

    // iOS Low Power Mode compression opt-in. Default false = respect the user's battery-saving intent
    // (CBZ finalize stays deferred while Low Power Mode is active). When true, the iOS finalize engine
    // may compress even in Low Power Mode. Thermal serious/critical always defers regardless of this.
    const val KEY_ALLOW_COMPRESSION_IN_LOW_POWER = "allow_compression_low_power"

    const val HEADERS_MAP_JSON = "headers_map_json"

    const val READ_MINUTES = "read_minutes"
}
