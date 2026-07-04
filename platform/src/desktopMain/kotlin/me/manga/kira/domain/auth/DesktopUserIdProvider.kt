package me.manga.kira.domain.auth

import java.io.File
import java.util.UUID

/**
 * Desktop `UserIdProvider` — neither Android's ANDROID_ID nor iOS's identifierForVendor have a
 * direct JVM analog, so we synthesize a stable ID by generating a UUID on first launch and
 * persisting it under `~/.kira-manga/device-id`. Subsequent launches read the same file.
 *
 * Falls back to a transient UUID when the home directory can't be resolved (the read/write fails)
 * — analytics will see a per-session ID rather than a stable one, but the app still functions.
 */
class DesktopUserIdProvider : UserIdProvider {
    override fun getUserId(): String {
        val userHome = System.getProperty("user.home") ?: return UUID.randomUUID().toString()
        val appDir = File(userHome, ".kira-manga")
        if (!appDir.exists() && !appDir.mkdirs()) return UUID.randomUUID().toString()
        val idFile = File(appDir, "device-id")
        return if (idFile.exists()) {
            runCatching { idFile.readText().trim() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: generateAndPersist(idFile)
        } else {
            generateAndPersist(idFile)
        }
    }

    private fun generateAndPersist(target: File): String {
        val id = UUID.randomUUID().toString()
        runCatching { target.writeText(id) }
        return id
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster213a.staleKdocSweep.cascade, Task #669, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster213a leaf 3/3 CLOSER — :shared/desktopMain/domain/auth/ Desktop actual tier, sibling
 * 399. Cumulative §253-postscript count = 124 leaves with this commit.
 *
 * File-shape note: 35-line file — `DesktopUserIdProvider` 2-method class (1 override + 1
 * private helper) implementing UUID-on-first-launch with file-backed persistence under
 * ~/.kira-manga/device-id + 8-line class-level KDoc prose (lines 6-13) explaining the
 * JVM-has-no-native-device-ID design rationale + the transient-UUID home-dir-resolution-
 * failure fallback contract.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE + FULFILLED-PORT — Desktop actual fulfilling the cluster212 sibling 395
 *     commonMain `UserIdProvider` SPI interface. Bound via shared/src/desktopMain/di/
 *     PlatformModule.desktop.kt Koin platform module. Twin-fulfilled by AndroidUserIdProvider
 *     (sibling 397, this cluster) + IosUserIdProvider (sibling 398, this cluster).
 *
 *   • KDOC-FALLBACK-CONTRACT-LOAD-BEARING — 8-line KDoc prose documents the JVM-no-native-ID
 *     synthesized-UUID design ("neither Android's ANDROID_ID nor iOS's identifierForVendor
 *     have a direct JVM analog, so we synthesize a stable ID by generating a UUID on first
 *     launch and persisting it under ~/.kira-manga/device-id") + the transient-UUID degraded-
 *     mode fallback contract ("Falls back to a transient UUID when the home directory can't
 *     be resolved (the read/write fails) — analytics will see a per-session ID rather than a
 *     stable one, but the app still functions"). PRESERVE — design-intent doc; load-bearing
 *     for any future device-ID persistence-strategy adjustment (e.g. XDG_DATA_HOME migration,
 *     Keychain/Credential-Manager integration on macOS/Windows).
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 2 imports: java.io.File + java.util.UUID. LIVE —
 *     JVM-only SPI; uses JVM stdlib only, no Compose Desktop / coil3 / etc.
 *
 *   • CLUSTER213A CLOSER REGISTER — 3-leaf platform-actual fan-out for cluster212 sibling 395
 *     commonMain `UserIdProvider` SPI interface CLOSES. Posture-mix register: 3 LIVE-NOT-STALE
 *     + FULFILLED-PORT (all 3 actuals deliver the same SPI contract via 3 different platform-
 *     native mechanisms: Android ANDROID_ID, iOS IDFV, Desktop synthesized-persisted UUID).
 *     Cluster213b will follow with the 3-actual fan-out for DeviceInfoProvider (siblings
 *     400-402).
 */
