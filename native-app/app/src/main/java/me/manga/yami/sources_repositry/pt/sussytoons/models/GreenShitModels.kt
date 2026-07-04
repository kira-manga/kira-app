package me.manga.yamiapk.sources_repositry.pt.sussytoons.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ResultDto<T>(
    @SerialName("pagina")
    val currentPage: Int = 0,
    @SerialName("totalPaginas")
    val lastPage: Int = 0,
    @SerialName("obras")
    @JsonNames("resultado", "resultados")
    private val _results: T? = null
) {
    val results: T
        get() = _results ?: throw IllegalStateException("Results cannot be null")

    fun hasNextPage() = currentPage < lastPage
}

@Serializable
data class MangaDto(
    @JsonNames("obr_id", "id")
    val id: Int,
    @JsonNames("obr_nome", "name")
    val name: String,
    @JsonNames("obr_slug", "slug")
    var slug: String? = null,
    @JsonNames("obr_imagem", "image", "thumbnail")
    val thumbnail: String? = null,
    @JsonNames("obr_descricao", "description")
    val description: String? = null,
    @SerialName("scan_id")
    val scanId: Int = 1,
    @JsonNames("status", "obr_status")
    val status: MangaStatus = MangaStatus(null),
    @JsonNames("tags", "genres")
    val genres: List<Genre> = emptyList(),
    @JsonNames("capitulos", "chapters")
    val chapters: List<ChapterDto>? = null,
    @SerialName("type")
    val type: String? = null
)

@Serializable
data class Genre(
    @JsonNames("tag_id", "id")
    val id: Int? = null,
    @JsonNames("tag_nome", "name")
    val value: String
) {
    override fun toString() = value
}

@Serializable(with = MangaStatus.Serializer::class)
data class MangaStatus(
    val value: String? = null
) {
    object Serializer : KSerializer<MangaStatus> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("MangaStatus", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): MangaStatus {
            val jsonDecoder = decoder as? JsonDecoder ?: return MangaStatus(decoder.decodeString())
            val element = jsonDecoder.decodeJsonElement()

            val value = when (element) {
                is JsonPrimitive -> element.content
                is JsonObject -> listOf("stt_nome", "value", "name")
                    .firstNotNullOfOrNull { key -> element[key]?.jsonPrimitive?.content }
                else -> null
            }
            return MangaStatus(value)
        }

        override fun serialize(encoder: Encoder, value: MangaStatus) {
            encoder.encodeString(value.value ?: "")
        }
    }
}

@Serializable
data class ChapterDto(
    @JsonNames("cap_id", "id")
    val id: Int,
    @JsonNames("cap_nome", "name")
    val name: String,
    @JsonNames("cap_numero", "number")
    val number: Float? = null,
    @JsonNames("cap_lancado_em", "cap_liberar_em", "cap_criado_em")
    val updateAt: String? = null
)

@Serializable
data class ChapterPageDto(
    @SerialName("obr_id")
    val mangaId: Int? = null,
    @SerialName("cap_nome")
    val name: String = "",
    @SerialName("cap_numero")
    private val _chapterNumber: JsonPrimitive? = null,
    @SerialName("cap_paginas")
    val pages: List<PageDto> = emptyList(),
    @SerialName("cap_texto")
    val text: String? = null,
    @SerialName("cap_tipo")
    val type: String = "IMAGEM",
    @SerialName("obra")
    val manga: MangaReferenceDto? = null
) {
    val chapterNumber: String?
        get() = _chapterNumber?.content
}

@Serializable
data class MangaReferenceDto(
    @SerialName("obr_id")
    val id: Int,
    @SerialName("scan_id")
    val scanId: Int = 1
)

@Serializable
data class PageDto(
    @SerialName("src")
    val src: String,
    @SerialName("mime")
    val mime: String? = null,
    @SerialName("path")
    var path: String? = null,
    @SerialName("numero")
    private val _numero: JsonPrimitive? = null
) {
    val numero: String? get() = _numero?.content
}

@Serializable
data class FiltersDto(
    @SerialName("generos")
    val genres: List<GenreFilter> = emptyList(),
    @SerialName("formatos")
    val formats: List<Format> = emptyList(),
    @SerialName("status")
    val statuses: List<Status> = emptyList(),
    @SerialName("tags")
    val tags: List<Tag> = emptyList()
)

@Serializable
data class GenreFilter(
    @JsonNames("gen_id", "id")
    val id: Int,
    @JsonNames("gen_nome", "name")
    val name: String
)

@Serializable
data class Format(
    @JsonNames("formt_id", "id")
    val id: Int,
    @JsonNames("formt_nome", "name")
    val name: String
)

@Serializable
data class Status(
    @JsonNames("stt_id", "id")
    val id: Int,
    @JsonNames("stt_nome", "name")
    val name: String
)

@Serializable
data class Tag(
    @JsonNames("tag_id", "id")
    val id: Int,
    @JsonNames("tag_nome", "name")
    val name: String
)

@Serializable
data class TokenDto(
    @SerialName("token")
    val value: String
)

@Serializable
data class Token(
    val value: String = "",
    val updateAt: Long = System.currentTimeMillis()
) {
    fun isValid(): Boolean {
        return value.isNotEmpty() && !isExpired()
    }

    fun isExpired(): Boolean {
        val currentTime = System.currentTimeMillis()
        val expirationTime = updateAt + (3600000) // 1 hour in milliseconds
        return currentTime > expirationTime
    }

    companion object {
        fun empty() = Token()
    }
}

@Serializable
data class Credential(
    val email: String = "",
    val password: String = ""
) {
    fun isEmpty() = email.isBlank() || password.isBlank()
    fun isNotEmpty() = !isEmpty()
}

@Serializable
data class LoginRequest(
    @SerialName("usr_email")
    val email: String,
    @SerialName("usr_senha")
    val password: String
)

@Serializable
data class LoginResponse(
    @SerialName("resultado")
    val result: TokenDto
)