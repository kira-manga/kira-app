package me.manga.yamiapk.sources_repositry.ar.mangapark

import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.sources_repositry.data.MangaSource
import me.manga.yamiapk.sources_repositry.en.mangapark.MangaParkRepository
import javax.inject.Inject

class MangaParkRepositoryAr @Inject constructor(
    api: IMangaDataApiServices,
    dataStore: DataStoreHelper,
    sourcesRepository: SourcesDao,
) : MangaParkRepository(api, dataStore, sourcesRepository) {
    override val language: String
        get() = "ar"

    override val API: String
        get() = MangaSource.MANGAPARKAR.API
    override val LANGUAGE: String
        get() = MangaSource.MANGAPARKAR.LANGUAGE.Language
    override val BASE_URL: String
        get() = MangaSource.MANGAPARKAR.BASEURL
}