package me.manga.yamiapk.sources_repositry.es.mangapark

import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.sources_repositry.en.mangapark.MangaParkRepository
import javax.inject.Inject

class MangaParkRepositoryEs @Inject constructor(
    api: IMangaDataApiServices,
    dataStore: DataStoreHelper,
    sourcesRepository: SourcesDao,
) : MangaParkRepository(api, dataStore, sourcesRepository) {
    override val language: String
        get() = "es"

    override val API: String
        get() = MangaSource.MANGAPARK_ES.API
    override val LANGUAGE: String
        get() = MangaSource.MANGAPARK_ES.LANGUAGE.Language
    override val BASE_URL: String
        get() = MangaSource.MANGAPARK_ES.BASEURL
}