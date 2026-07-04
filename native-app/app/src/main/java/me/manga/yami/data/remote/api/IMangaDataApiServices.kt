package me.manga.yamiapk.data.remote.api


import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url

interface IMangaDataApiServices {


    @GET
    suspend fun getData(@Url url: String): Response<String>
    @POST
    suspend fun getDataPost(@Url url: String): Response<String>


    @GET
    suspend fun getDataWithHeaders(@Url url: String, @HeaderMap headers: Map<String, String>): Response<String>

    @GET
    suspend fun getHomeData(@Url url: String): Response<String>

    @FormUrlEncoded
    @POST
    suspend fun searchManga(
        @Url url: String,
        @Field("action") action: String ,
        @Field("title") title: String,
        @HeaderMap headers: Map<String, String>? = emptyMap<String, String>()

    ): Response<String>

    @FormUrlEncoded
    @POST
    suspend fun searchManga3(
        @Url url: String,
        @Field("vars[s]") query: String,
        @Field("action") action: String?,
        @Field("template") template: String?= "",

        ): Response<String>

    @FormUrlEncoded
    @POST
    suspend fun searchMangaWithOrder(
        @Url url: String,
        @Field("vars[s]") query: String,
        @Field("action") action: String?,
        @Field("page") page: String?= "",
        @Field("template") template: String?= "",
        @Field("vars[orderby]") orderBy: String?= "",
        @Field("vars[paged]") paged: String?= "",
        @Field("vars[post_type]") postType: String?= "",
//        @Field("vars[post_status]") postStatus: String,
        @Field("vars[meta_key]") metaKey: String? = "",
        @Field("vars[order]") order: String? = "",
//        @Field("vars[sidebar]") sidebar: String,
        @Field("vars[manga_archives_item_layout]") layout: String?= "",
        @Field("vars[posts_per_page]") itemsPerPage: String?= "",
        @Field("vars[wp-manga-genre]") genre: String? = "",
        @HeaderMap headers: Map<String, String>? = emptyMap<String, String>()


    ): Response<String>

    @GET
    suspend fun getMangaData(@Url url: String): Response<String>
    @GET
    suspend fun getChapterData(@Url url: String): Response<String>
    @GET
    suspend fun getSearchData(@Url url: String): Response<String>

    @GET
    suspend fun getSearchData(@Url url: String, @HeaderMap headers: Map<String, String>): Response<String>

    @GET
    suspend fun getData(
        @Url url: String,
        @Header("Referer") referer: String,
    ): Response<String>
    @GET
    suspend fun getData(
        @Url url: String,
        @HeaderMap headers: Map<String, String>
    ): Response<String>



    @FormUrlEncoded
    @POST
    suspend fun loadMore(
        @Url url: String,
        @Field("action") action: String,
        @Field("page") page: String,
        @Field("template") template: String,
        @FieldMap vars: Map<String, String>
    ): Response<String>

    @POST
    suspend fun normalPost(
        @Url url: String,
        @Body body: RequestBody?,
        @HeaderMap headers: Map<String, String> = emptyMap()
    ): Response<String>
    @POST
//    @Headers("Content-Type: application/json")
    suspend fun post(
        @Url url: String,
        @Body body: RequestBody?,
        @HeaderMap headers: Map<String, String> = emptyMap()
    ): Response<String>

    @POST
    @Headers("Content-Type: application/x-www-form-urlencoded; charset=UTF-8")
    suspend fun postUrlencoded(
        @Url url: String,
        @Body body: RequestBody?,
        @HeaderMap headers: Map<String, String> = emptyMap()
    ): Response<String>

    @POST
    @Headers("Content-Type: application/json")

    suspend fun postJson(
        @Url url: String,
        @Body body: String,
        @HeaderMap headers: Map<String, String> = emptyMap()
    ): Response<String>


    @GET
    @Headers("Content-Type: application/json")

    suspend fun get(
        @Url url: String,
        @HeaderMap headers: Map<String, String> = emptyMap()
    ): Response<String>

}
