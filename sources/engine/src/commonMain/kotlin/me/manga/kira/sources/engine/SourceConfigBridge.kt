package me.manga.kira.sources.engine

import kotlinx.serialization.json.Json
import me.manga.kira.source.contracts.StrategyRegistry as SharedStrategyRegistry
import me.manga.kira.source.contracts.model.SourceConfig as SharedSourceConfig
import me.manga.kira.source.engine.SourceDeclarationCapabilities
import me.manga.kira.source.engine.SourceDeclarationFinding
import me.manga.kira.sources.contracts.StrategyRegistry
import me.manga.kira.sources.contracts.model.SourceConfig

private val configBridgeJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * One model conversion for both validation and execution across the separate contract packages.
 * Keep defaults and map declaration order; this is not backend canonical-byte serialization.
 */
internal fun SourceConfig.toSharedConfig(): SharedSourceConfig {
    val raw = configBridgeJson.encodeToString(SourceConfig.serializer(), this)
    return configBridgeJson.decodeFromString(SharedSourceConfig.serializer(), raw)
}

/** Adapts the caller's compiled strategy catalog without replacing the App's other validation rules. */
internal class SourceDeclarationBridge(strategies: StrategyRegistry) {
    private val capabilities =
        SourceDeclarationCapabilities(
            strategies =
                object : SharedStrategyRegistry {
                    override fun hasTransform(name: String): Boolean = strategies.hasTransform(name)

                    override fun hasImageStrategy(name: String): Boolean = strategies.hasImageStrategy(name)

                    override fun hasDateStrategy(name: String): Boolean = strategies.hasDateStrategy(name)

                    override fun hasPagination(name: String): Boolean = strategies.hasPagination(name)
                },
        )

    fun validate(source: SourceConfig): List<SourceDeclarationFinding> = capabilities.validate(source.toSharedConfig())
}
