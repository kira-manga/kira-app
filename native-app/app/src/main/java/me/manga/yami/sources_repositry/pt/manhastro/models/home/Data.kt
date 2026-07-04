package me.manga.yamiapk.sources_repositry.pt.manhastro.models.home
import kotlinx.serialization.Serializable


@Serializable
data class Data(
    val descricao: String? = "",
    val descricao_brasil: String? = "",
    val generos: String? = "",
    val imagem: String? = "",
    val manga_id: Int? = 0,
    val qnt_capitulo: Int? = 0,
//    val scan_atual: Any? = Any(),
    val titulo: String? = "",
    val titulo_brasil: String? = "",
    val ultimo_capitulo: String? = "",
    val views_mes: String? = ""
)