package me.manga.kira.platform.storage

import me.manga.kira.platform.storage.InstallationMaterialGenerationResult.Unsupported

/** Desktop cannot acquire complaint installation material; no file or transport fallback exists. */
class UnsupportedInstallationCredentialMaterialGenerator : InstallationCredentialMaterialGenerator {
    /** Unsupported regardless of scope, without invoking entropy or any existing secure-storage facade. */
    override fun generate(dataScopeId: String): InstallationMaterialGenerationResult = Unsupported
}
