package me.manga.kira.reader

/**
 * iOS-only switch selecting the reader implementation, with the **native Swift reader as the default**
 * and the shared Compose reader retained as an instant rollback.
 *
 * Default **ON** (`true`): iPhone/iPad ships the native `UICollectionView`/`UIScrollView` reader (see
 * `IOS_NATIVE_READER.md`), verified on-device. The Compose reader (`:ui/.../reader/ReaderScreen`) stays
 * compiled into the binary as the fallback — `ReaderHostSwitch` picks at the reader route, so flipping
 * this to `false` (and rebuilding) instantly reverts iOS to Compose with no other change. Mirrors the
 * `DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED` rollback pattern. **Android/Desktop never consult
 * this flag** — they always use the Compose reader.
 *
 * Why the native reader is the default: the Compose-Multiplatform iOS scroll/render pipeline stutters on
 * very tall webtoon strips (slow drag barely moves while a fling coasts) — iOS-only; Android/Desktop are
 * fine. The native `UICollectionView` owns the pan/scroll pipeline and native ImageIO downsampling avoids
 * the oversized-Skia-texture cost. See the migration plan / `IOS_NATIVE_READER.md`.
 */
object IosReaderFlags {
    /**
     * Native reader is the verified iOS default. Set to `false` to roll iOS back to the Compose reader
     * (the fallback stays in the binary). The native VC factory must also be registered from Swift
     * (`ReaderNativeBridge.viewControllerFactory`); if it is `null` the switch falls back to Compose
     * regardless of this flag, so a missing Swift registration can never leave iOS readerless.
     */
    const val NATIVE_READER_ENABLED: Boolean = true
}
