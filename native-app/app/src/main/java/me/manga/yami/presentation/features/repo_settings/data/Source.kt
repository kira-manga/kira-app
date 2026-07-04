package me.manga.yamiapk.presentation.features.repo_settings.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourceState

@Serializable(with = Source.Serializer::class)
data class Source(
    val name: String,
    val api: String,
    val baseUrl: String,
    val baseVersion: Int,
    val state: SourceState,
    val imageBaseUrl: String,
    val imageUrlVersion: Int,
    val shouldDelete: Boolean,

    ) {
    object Serializer : KSerializer<Source> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("Source")

        override fun deserialize(decoder: Decoder): Source {
            val jsonDecoder = decoder as? JsonDecoder
                ?: throw SerializationException("This serializer can be used only with Json")
            val element = jsonDecoder.decodeJsonElement()
            val obj = element.jsonObject

            fun JsonObject.getString(key: String, default: String = ""): String =
                this[key]?.jsonPrimitive?.content ?: default

            fun JsonObject.getInt(key: String, default: Int = 0): Int =
                this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: default

            fun JsonObject.getBoolean(key: String, default: Boolean = false): Boolean {
                val el = this[key] ?: return default
                val prim = el.jsonPrimitive
                // Prefer real boolean if present
                prim.booleanOrNull?.let { return it }
                // Otherwise try parsing textual content
                prim.contentOrNull?.let { text ->
                    return when {
                        text.equals("true", ignoreCase = true) -> true
                        text.equals("false", ignoreCase = true) -> false
                        text == "1" -> true
                        text == "0" -> false
                        else -> default
                    }
                }
                return default
            }
            // Resolve state: prefer "state" string, fallback to "isWorking" boolean for backward compatibility
            val state: SourceState = when {
                obj.containsKey("state") -> {
                    val raw = obj["state"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
                    try {
                        if (!raw.isNullOrEmpty()) SourceState.valueOf(raw) else SourceState.STOPPED
                    } catch (e: Exception) {
                        // unknown value -> STOPPED as safe default
                        SourceState.STOPPED
                    }
                }
                obj.containsKey("isWorking") -> {
                    val isWorkingRaw = obj["isWorking"]?.jsonPrimitive?.contentOrNull?.trim()
                    val isWorking = isWorkingRaw?.equals("true", ignoreCase = true) == true
                    if (isWorking) SourceState.WORKING else SourceState.STOPPED
                }
                else -> {
                    // no info -> choose a safe default (STOPPED)
                    SourceState.STOPPED
                }
            }

            return Source(
                name = obj.getString("name"),
                api = obj.getString("api"),
                baseUrl = obj.getString("baseUrl"),
                baseVersion = obj.getInt("baseVersion"),
                state = state,
                imageBaseUrl = obj.getString("imageBaseUrl"),
                imageUrlVersion = obj.getInt("imageUrlVersion"),
                shouldDelete = obj.getBoolean("delate")
            )
        }

        override fun serialize(encoder: Encoder, value: Source) {
            val jsonEncoder = encoder as? JsonEncoder
                ?: throw SerializationException("This serializer can be used only with Json")

            // We write the new "state" string (not the old boolean)
            val jo = buildMap {
                put("name", JsonPrimitive(value.name))
                put("api", JsonPrimitive(value.api))
                put("baseUrl", JsonPrimitive(value.baseUrl))
                put("baseVersion", JsonPrimitive(value.baseVersion))
                put("state", JsonPrimitive(value.state.name))
                put("imageBaseUrl", JsonPrimitive(value.imageBaseUrl))
                put("imageUrlVersion", JsonPrimitive(value.imageUrlVersion))
            }
            jsonEncoder.encodeJsonElement(JsonObject(jo))
        }
    }
}
