//package me.manga.yamiapk.sources_repositry.ar.comick_io
//
//import me.manga.yamiapk.core.storage.DataStoreHelper
//import me.manga.yamiapk.data.local.dao.SourcesDao
//import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
//import me.manga.yamiapk.sources_repositry.data.MangaSource
//import me.manga.yamiapk.sources_repositry.en.comick_io.ComickRepository
//import javax.inject.Inject
//
//class ComickRepositoryAr @Inject constructor(
//    api: IMangaDataApiServices,
//    dataStore: DataStoreHelper,
//    sourcesRepository: SourcesDao,
//) : ComickRepository(api, dataStore, sourcesRepository) {
//    override val API: String
//        get() = MangaSource.COMICKIO_AR.API
//
//
//    override val LANGUAGE: String
//        get() = MangaSource.COMICKIO_AR.LANGUAGE.Language
//
//
//    override val language: String = "ar"
//}