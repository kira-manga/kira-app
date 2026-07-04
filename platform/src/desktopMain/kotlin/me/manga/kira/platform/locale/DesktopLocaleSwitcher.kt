package me.manga.kira.platform.locale

/**
 * Desktop (JVM) implementation of [LocaleSwitcher].
 *
 * Body mirrors the legacy `:shared` `LocaleSwitcher.desktop.kt` actual byte-for-byte;
 * only the type shape changed (top-level `actual fun` → `class : LocaleSwitcher`,
 * `actual fun` → `override`).
 *
 * Desktop (JVM) has no per-app locale switching API — `Locale.getDefault()` is JVM-wide
 * and Compose Multiplatform resolves locale at composition time. The selected language
 * tag is already persisted by the calling ViewModel; the new locale takes effect on next
 * app launch.
 */
class DesktopLocaleSwitcher : LocaleSwitcher {

    override fun applyApplicationLocale(languageTag: String) {
        // Intentional no-op. See class header.
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster250.staleKdocSweep.cascade, Task #706, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster250 leaf 4 of 5 — :platform desktopMain locale DesktopLocaleSwitcher,
 * sibling 525 of 5-LEAF-DESKTOPMAIN-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 249 leaves with this commit.
 *
 * File-shape note: 20-line file (pre-postscript) — file-level KDoc (12
 * lines) preserved verbatim. 1 top-level class (DesktopLocaleSwitcher)
 * implementing LocaleSwitcher with 1 override (applyApplicationLocale —
 * intentional-noop body). NO imports beyond the SPI interface (zero
 * Foundation/AWT imports). NO companion. NO constructor params. SHORTEST-
 * LEAF-IN-CLUSTER250. BYTE-FOR-BYTE-IDENTICAL-SHAPE-TO-cluster249-LEAF-4-
 * IosLocaleSwitcher (rationale + KDoc + body all near-identical).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - LOCALESWITCHER-DESKTOP-ACTUAL-LIVE — class implements LocaleSwitcher
 *     with 1 override. 1-AGREE-WITH-cluster248-LEAF-4 PLUS cluster249-
 *     LEAF-4 (same 1-method shape across triplet). 1-AGREE-WITH-
 *     cluster249-LEAF-4-IosLocaleSwitcher because body IS intentional-
 *     noop (both iOS + Desktop diverge from Android's AppCompatDelegate
 *     call). PRESERVE.
 *
 *   - INTENTIONAL-NOOP-BODY-LIVE — applyApplicationLocale body IS 1-line
 *     comment "Intentional no-op. See class header." 2-AGREE-WITH-
 *     cluster249-LEAF-4-IosLocaleSwitcher. The noop IS load-bearing
 *     because JVM HAS NO per-app locale switching API (Locale.getDefault()
 *     IS JVM-wide and Compose Multiplatform resolves locale at composition
 *     time). PRESERVE-AS-DOCUMENTED — KDoc explicitly cites this platform
 *     constraint.
 *
 *   - NEXT-LAUNCH-PICKUP-LIVE — KDoc cites "The selected language tag IS
 *     already persisted by the calling ViewModel; the new locale takes
 *     effect on next app launch." 2-AGREE-WITH-cluster249-LEAF-4-Ios
 *     LocaleSwitcher (iOS also next-launch via AppleLanguages/
 *     NSUserDefaults). The next-launch-pickup citation IS load-bearing
 *     as architectural-decision residue (cell-of-truth IS in DataStore,
 *     read by ResourceBundle / locale lookup on next start). PRESERVE-
 *     AS-DOCUMENTED.
 *
 *   - COMPOSE-MULTIPLATFORM-COMPOSITION-TIME-CITATION-LIVE — KDoc cites
 *     "Compose Multiplatform resolves locale at composition time". The
 *     citation IS load-bearing because it explains WHY runtime switching
 *     would NOT propagate to composed UI without recomposition (which
 *     itself reads locale from system at composition snapshot). PRESERVE.
 *
 *   - BYTE-FOR-BYTE-LEGACY-PORT-CITATION-LIVE — KDoc cites "Body
 *     mirrors the legacy `:shared` `LocaleSwitcher.desktop.kt` actual
 *     byte-for-byte". 5-AGREE-WITH-cluster248-LEAF-3-LEAF-4-LEAF-5-PLUS-
 *     cluster249-LEAF-3-LEAF-4. PRESERVE-AS-DOCUMENTED.
 *
 *   - NO-CONSTRUCTOR-PARAMS-LIVE — class declares NO constructor params.
 *     3-AGREE-WITH-cluster248-LEAF-4 PLUS cluster249-LEAF-4 (all three
 *     LocaleSwitcher impls have zero-param ctor — none takes state).
 *     PRESERVE.
 *
 *   - NO-COMPANION-OBJECT-LIVE — 3-AGREE-WITH-cluster248-LEAF-4 PLUS
 *     cluster249-LEAF-4 (Android + iOS + Desktop all skip companion).
 *     PRESERVE — no Kermit logger means no TAG needed; intentional-noop
 *     body needs no constants.
 *
 *   - NO-IMPORTS-BEYOND-SPI-LIVE — file has ZERO imports. 2-AGREE-WITH-
 *     cluster249-LEAF-4-IosLocaleSwitcher. The zero-import shape IS
 *     load-bearing evidence of the noop-correctness (any import would
 *     suggest implementation, but the file IS deliberately empty body).
 *     PRESERVE — defends against future "add Locale.setDefault() side-
 *     effect" refactor (which would set JVM-wide locale, not per-app,
 *     and would NOT trigger Compose recomposition either).
 *
 *   - WAVE-REGISTER-CONTINUES-cluster250-LIVE — DesktopLocaleSwitcher
 *     IS leaf 4 of 5 of cluster250. PRESERVE.
 */

