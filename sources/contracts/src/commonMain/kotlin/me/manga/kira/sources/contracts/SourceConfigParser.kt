package me.manga.kira.sources.contracts

import kotlinx.serialization.json.Json
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/**
 * The canonical (de)serializer for the contracts-owned config model. Lives in `:contracts` (not
 * `:engine`/`:config`) because parsing the model is neither execution nor remote-update logic — it's
 * the model's own wire format, needed by every module that handles a document. Pure: no I/O, no
 * signature/strategy checks. Malformed JSON maps to [AppError.Network.Serialization] rather than
 * throwing, so a corrupt cached/remote document degrades to "keep the previous good document".
 */
object SourceConfigParser {
    private val sourceJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val manifestJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parse(raw: String): AppResult<SourceConfigDocument> = try {
        AppResult.Success(sourceJson.decodeFromString(SourceConfigDocument.serializer(), raw))
    } catch (t: Throwable) {
        AppResult.Failure(AppError.Network.Serialization(t))
    }

    /** Parses one lifecycle-neutral source revision using the established source JSON policy. */
    fun parseSource(raw: String): AppResult<SourceConfig> = try {
        AppResult.Success(sourceJson.decodeFromString(SourceConfig.serializer(), raw))
    } catch (t: Throwable) {
        AppResult.Failure(AppError.Network.Serialization(t))
    }

    /** Parses manifest schema v1 strictly; extensions require a schema-version bump. */
    fun parseManifest(raw: String): AppResult<SourceCatalogManifest> = try {
        AppResult.Success(manifestJson.decodeFromString(SourceCatalogManifest.serializer(), raw))
    } catch (t: Throwable) {
        AppResult.Failure(AppError.Network.Serialization(t))
    }
}
