package me.manga.kira.platform.locale

/**
 * iOS implementation of [LocaleSwitcher].
 *
 * Body mirrors the legacy `:shared` `LocaleSwitcher.ios.kt` actual byte-for-byte; only the
 * type shape changed (top-level `actual fun` → `class : LocaleSwitcher`,
 * `actual fun` → `override`).
 *
 * iOS does not support per-app runtime locale switching without a process restart. The
 * selected language tag is already persisted by the calling ViewModel (via
 * `DataStoreHelper.setLanguage`), so this no-op is the correct platform behaviour — the
 * new locale will be picked up on next launch when the system reads `AppleLanguages` from
 * `NSUserDefaults`.
 */
class IosLocaleSwitcher : LocaleSwitcher {

    override fun applyApplicationLocale(languageTag: String) {
        // Intentional no-op. See class header.
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster249.staleKdocSweep.cascade, Task #705, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster249 leaf 4 of 5 — :platform iosMain locale IosLocaleSwitcher,
 * sibling 520 of 5-LEAF-IOSMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 244 leaves with this commit.
 *
 * File-shape note: 22-line file (pre-postscript) — file-level KDoc (13
 * lines) preserved verbatim. 1 top-level class (IosLocaleSwitcher)
 * implementing LocaleSwitcher with 1 override (applyApplicationLocale —
 * intentional-noop body). NO imports beyond the SPI interface (no
 * Foundation imports needed for noop). NO companion. NO constructor
 * params. SECOND-SHORTEST-LEAF-IN-CLUSTER249-AFTER-LEAF-2.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - LOCALESWITCHER-IOS-ACTUAL-LIVE — class implements LocaleSwitcher
 *     with 1 override. 1-AGREE-WITH-cluster248-LEAF-4-AndroidLocale
 *     Switcher (same 1-method shape). 1-DIVERGES because body IS
 *     intentional-noop (vs Android's AppCompatDelegate.set
 *     ApplicationLocales call). PRESERVE.
 *
 *   - INTENTIONAL-NOOP-BODY-LIVE — applyApplicationLocale body IS 1-line
 *     comment "Intentional no-op. See class header." The noop IS load-
 *     bearing because iOS DOES NOT SUPPORT per-app runtime locale
 *     switching without process restart. PRESERVE-AS-DOCUMENTED — KDoc
 *     explicitly cites this platform constraint plus the cell-of-truth
 *     resolution path (DataStoreHelper.setLanguage persists the choice
 *     for next-launch pickup).
 *
 *   - DATASTOREHELPER-CELL-OF-TRUTH-CITATION-LIVE — KDoc cites "The
 *     selected language tag is already persisted by the calling
 *     ViewModel (via `DataStoreHelper.setLanguage`), so this no-op IS
 *     the correct platform behaviour — the new locale will be picked
 *     up on next launch when the system reads `AppleLanguages` from
 *     `NSUserDefaults`." The cell-of-truth citation IS load-bearing
 *     as architectural-decision residue (next-launch-pickup-from-
 *     NSUserDefaults IS the iOS canonical pattern). PRESERVE-AS-
 *     DOCUMENTED.
 *
 *   - APPLELANGUAGES-NSUSERDEFAULTS-NEXT-LAUNCH-LIVE — citation of
 *     "AppleLanguages" from "NSUserDefaults" IS the iOS-canonical
 *     localization mechanism. The citation IS load-bearing for future-
 *     maintainer who SHOULD NOT attempt runtime locale-switching with
 *     bundle hacks or NSLocale.swizzling (both ARE App-Store-rejection
 *     risks). PRESERVE-AS-DOCUMENTED.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body
 *     mirrors the legacy `:shared` `LocaleSwitcher.ios.kt` actual
 *     byte-for-byte". 4-AGREE-WITH-cluster248-LEAF-3-LEAF-4-LEAF-5-
 *     PLUS-cluster249-LEAF-3. PRESERVE-AS-DOCUMENTED.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     1-AGREE-WITH-cluster248-LEAF-4-AndroidLocaleSwitcher (Android side
 *     also has zero-param ctor — both LocaleSwitcher impls take no
 *     state). PRESERVE.
 *
 *   - NO-COMPANION-OBJECT-LIVE — 2-AGREE-WITH-cluster248-LEAF-4-Android
 *     LocaleSwitcher (Android side also has no companion). PRESERVE —
 *     no Kermit logger means no TAG needed; intentional-noop body
 *     needs no constants.
 *
 *   - NO-IMPORTS-BEYOND-SPI-LIVE — file has ZERO imports (no Foundation,
 *     no UIKit, no Kermit). The zero-import shape IS load-bearing
 *     evidence of the noop-correctness (any import would suggest
 *     implementation, but the file IS deliberately empty body).
 *     PRESERVE — defends against future "add NSUserDefaults write to
 *     side-effect the AppleLanguages key" refactor (which would NOT
 *     trigger system locale update without process restart anyway).
 *
 *   - WAVE-REGISTER-CONTINUES-cluster249-LIVE — IosLocaleSwitcher IS
 *     leaf 4 of 5 of cluster249. PRESERVE.
 */
