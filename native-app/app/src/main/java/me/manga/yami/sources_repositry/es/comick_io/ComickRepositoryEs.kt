//package me.manga.yamiapk.sources_repositry.es.comick_io
//
//import me.manga.yamiapk.core.storage.DataStoreHelper
//import me.manga.yamiapk.data.local.dao.SourcesDao
//import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
//import me.manga.yamiapk.sources_repositry.data.MangaSource
//import me.manga.yamiapk.sources_repositry.en.comick_io.ComickRepository
//import javax.inject.Inject
//
//class ComickRepositoryEs @Inject constructor(
//    api: IMangaDataApiServices,
//    dataStore: DataStoreHelper,
//    sourcesRepository: SourcesDao,
//) : ComickRepository(api, dataStore, sourcesRepository) {
//    override val API: String
//        get() = MangaSource.COMICKIO_ES.API
//
//
//    override val LANGUAGE: String
//        get() = MangaSource.COMICKIO_ES.LANGUAGE.Language
//
//
//    // CLDR code for Latin America Spanish: use "es-419" (Latin America & Caribbean)
//    override val language: String = "es-419"
//}