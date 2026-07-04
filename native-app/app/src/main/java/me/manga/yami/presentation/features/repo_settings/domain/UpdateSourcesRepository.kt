package me.manga.yamiapk.presentation.features.repo_settings.domain

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.manga.yamiapk.admin.Admin
import me.manga.yamiapk.data.local.dao.ChapterDao
import me.manga.yamiapk.data.local.dao.HistoryDao
import me.manga.yamiapk.data.local.dao.MangaDao
import me.manga.yamiapk.data.local.dao.NotificationDao
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.data.local.entity.SourcesEntity
import me.manga.yamiapk.data.remote.api.IMangaDataApiServices
import me.manga.yamiapk.presentation.features.repo_settings.data.Source
import java.net.URI
import javax.inject.Inject
import kotlin.collections.forEach
import kotlin.coroutines.ContinuationInterceptor

class UpdateSourcesRepository @Inject constructor(
    private val sourcesDao: SourcesDao,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val historyDao: HistoryDao,
    private val notificationDao: NotificationDao,
    private val api: IMangaDataApiServices,
    private val applicationScope: CoroutineScope
) {



    val allSources: Flow<List<SourcesEntity>> = sourcesDao.getAllSources()

     fun initializeSources() {
        try {

            Log.d("SourcesRepository", "Initializing sources  thread=${Thread.currentThread().name}")
            updateUrls()
        } catch (e: Exception) {
            Log.e("SourcesRepository", "Failed to initialize sources: ${e.message}", e)
        }
    }





    fun updateUrls() {
        applicationScope.launch {
            try {
//                val dispatcher = (kotlin.coroutines.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher)
////
//                Log.d("SourcesRepositorysdfsdf", "Initializing sources - dispatcher=$dispatcher thread=${Thread.currentThread().name}")

                val response = api.get(if (Admin.isAdmin) "https://yamimanga.me/dev/source" else "https://yamimanga.me/source/35" )

                if (!response.isSuccessful) {
                    Log.w("SourceUpdate", "API request failed with code: ${response.code()}")
                    return@launch
                }

                val responseBody = response.body()?.toString()
                if (responseBody.isNullOrBlank()) {
                    Log.w("SourceUpdate", "API response body is empty")
                    return@launch
                }

                val apiSources = parseApiResponse(responseBody) ?: return@launch
                val sourceVersionMap = getCurrentSourceVersions()
                val imageSourceVersionMap = getCurrentImageSourceVersions() // Add this line

                Log.i("sdljghsflgfdgsdfgdsfgsdfgsd",apiSources.toString())
                updateSourcesFromApi(apiSources, sourceVersionMap,imageSourceVersionMap)

            } catch (e: Exception) {
                Log.e("SourceUpdate", "Error updating sources: ${e.message}", e)
            }
        }
    }
    private suspend fun getCurrentImageSourceVersions(): Map<String, Int> {
        return try {
            val sources = allSources.first()
            val apiToVersionMap = mutableMapOf<String, Int>()

            sources.forEach { entity ->
                // The entity.name should match the repo.API since that's how we stored it
                // But let's be extra safe and create the mapping correctly
                apiToVersionMap[entity.name] = entity.imageUrlVersion
            }

            Log.d("SourceUpdate", "Current versions map: $apiToVersionMap")
            apiToVersionMap
        } catch (e: Exception) {
            Log.e("SourceUpdate", "Failed to get current sources: ${e.message}", e)
            emptyMap()
        }
    }

    private fun parseApiResponse(responseBody: String): List<Source>? {
        return try {
            val jsonParser = Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
                explicitNulls = false
                allowTrailingComma = true  // Add this line

            }
            jsonParser.decodeFromString<List<Source>>(responseBody)
        } catch (e: Exception) {
            Log.e("SourceUpdate", "Failed to parse API response: ${e.message}", e)
            null
        }
    }

    private suspend fun getCurrentSourceVersions(): Map<String, Int> {
        return try {
            val sources = allSources.first()
            val apiToVersionMap = mutableMapOf<String, Int>()

            sources.forEach { entity ->
                // The entity.name should match the repo.API since that's how we stored it
                // But let's be extra safe and create the mapping correctly
                apiToVersionMap[entity.name] = entity.baseVersion
                Log.d("SourceUpdatesafddf", "map: ${entity.name} ==================== ${apiToVersionMap[entity.name]?:"non"}")

            }

            Log.d("SourceUpdate", "Current versions map: $apiToVersionMap")
            apiToVersionMap
        } catch (e: Exception) {
            Log.e("SourceUpdate", "Failed to get current sources: ${e.message}", e)
            emptyMap()
        }
    }

    private suspend fun updateSourcesFromApi(
        apiSources: List<Source>,
        sourceVersionMap: Map<String, Int>,
        imageSourceVersionMap: Map<String, Int> // Add this parameter
    ) {
        var updatedCount = 0
        var skippedCount = 0
        var imageUpdatedCount = 0
        var imageSkippedCount = 0
        var stateUpdatedCount = 0


        apiSources.forEach { apiSource ->
            try {
                var hasUpdates = false
                Log.d("SourceUpdate0", "api $apiSource")

                if (apiSource.shouldDelete)
                {
                    Log.d("SourceUpdate1", "api $apiSource")

                    sourcesDao.deleteSourceByName(apiSource.api)
                    return@forEach
                }
                Log.d("SourceUpdate2", "api $apiSource")

                // Handle base URL updates
                val currentVersion = sourceVersionMap[apiSource.api] ?: -1

                if (apiSource.baseVersion > currentVersion) {
                    propagateUrlChanges(apiSource.api, apiSource.baseUrl)

                    sourcesDao.updateBaseUrlAndVersionByName(
                        apiSource.api,
                        apiSource.baseUrl,
                        apiSource.baseVersion
                    )
                    updatedCount++


                    Log.d("SourceUpdate", "Updated ${apiSource.api}: v$currentVersion -> v${apiSource.baseVersion}")
                } else {
                    skippedCount++
                    Log.v("SourceUpdate", "Skipped ${apiSource.api}: current v$currentVersion >= api v${apiSource.baseVersion}")
                }

                // Handle image base URL updates
                val currentImageVersion = imageSourceVersionMap[apiSource.api] ?: -1

                // Assuming your Source data class has imageBaseUrl and imageUrlVersion fields
                // If not, you'll need to add them to your Source data class
                if (apiSource.imageUrlVersion > currentImageVersion) {
                    propagateImageUrlChanges(apiSource.api, apiSource.imageBaseUrl)

                    sourcesDao.updateImageBaseUrlAndVersionByName(
                        apiSource.api,
                        apiSource.imageBaseUrl,
                        apiSource.imageUrlVersion
                    )
                    imageUpdatedCount++



                    Log.d("SourceUpdate", "Updated image URL for ${apiSource.api}: v$currentImageVersion -> v${apiSource.imageUrlVersion}")
                } else {
                    imageSkippedCount++
                    Log.v("SourceUpdate", "Skipped image URL for ${apiSource.api}: current v$currentImageVersion >= api v${apiSource.imageUrlVersion}")
                }


                val newSiteState = apiSource.state
                val currentSiteState = sourcesDao.getSiteStateByNameSync(apiSource.api)

                if (currentSiteState != newSiteState) {
                    sourcesDao.updateSiteStateByName(apiSource.api, newSiteState)
                    stateUpdatedCount++
                    hasUpdates = true
                    Log.d("SourceUpdate", "Updated site state for ${apiSource.api}: $currentSiteState -> $newSiteState")
                }

                if (!hasUpdates) {
                    Log.v("SourceUpdate", "No updates needed for ${apiSource.api}")
                }
            } catch (e: Exception) {
                Log.e("SourceUpdate", "Failed to update source ${apiSource.api}: ${e.message}", e)
            }
        }

        Log.i("SourceUpdate", "Base URL update completed: $updatedCount updated, $skippedCount skipped")
        Log.i("SourceUpdate", "Image URL update completed: $imageUpdatedCount updated, $imageSkippedCount skipped")
    }

    /**
     * Propagates base URL changes to all related entities in the database
     * This includes both page URLs and image URLs
     */
    private suspend fun propagateUrlChanges(apiName: String, newBaseUrl: String) {
        try {
            Log.d("URLPropagation", "Starting URL and image URL propagation for API: $apiName, new URL: $newBaseUrl")

            // Update URLs and image URLs in all related entities
            updateMangaUrls(apiName, newBaseUrl)
            updateChapterUrls(apiName, newBaseUrl)
            updateHistoryUrls(apiName, newBaseUrl)
            updateNotificationUrls(apiName, newBaseUrl)


            Log.d("URLPropagation", "Completed URL and image URL propagation for API: $apiName")

        } catch (e: Exception) {
            Log.e("URLPropagation", "Failed to propagate URL changes for $apiName: ${e.message}", e)
        }
    }
    /**
     * Propagates image URL changes to all related entities in the database
     */
    private suspend fun propagateImageUrlChanges(apiName: String, newImageBaseUrl: String) {
        try {
            Log.d("ImageURLPropagation", "Starting image URL propagation for API: $apiName, new image URL: $newImageBaseUrl")

            updateMangaImageUrls(apiName, newImageBaseUrl)
            updateHistoryImageUrls(apiName, newImageBaseUrl)
            updateNotificationImageUrls(apiName, newImageBaseUrl)

            Log.d("ImageURLPropagation", "Completed image URL propagation for API: $apiName")

        } catch (e: Exception) {
            Log.e("ImageURLPropagation", "Failed to propagate image URL changes for $apiName: ${e.message}", e)
        }
    }
    private suspend fun updateMangaUrls(apiName: String, newBaseUrl: String) {
        try {
            val mangaList = mangaDao.getMangaByApi(apiName)
            var updatedCount = 0

            mangaList.forEach { manga ->
                val oldUrl = manga.url

                val newUrl = replaceBaseUrl(oldUrl, newBaseUrl)

                if (newUrl != oldUrl ) {
                    val updatedManga = manga.copy(
                        url = newUrl,
                    )
                    mangaDao.update(updatedManga)
                    updatedCount++
                    Log.v("URLPropagation", "Updated manga - URL: $oldUrl -> $newUrl")
                }
            }

            Log.d("URLPropagation", "Updated $updatedCount manga URLs and image URLs for API: $apiName")

        } catch (e: Exception) {
            Log.e("URLPropagation", "Failed to update manga URLs for $apiName: ${e.message}", e)
        }
    }

    private suspend fun updateChapterUrls(apiName: String, newBaseUrl: String) {
        try {
            // Get all manga IDs for this API first
            val mangaIds = mangaDao.getMangaIdsByApi(apiName)
            var updatedCount = 0

            mangaIds.forEach { mangaId ->
                val chapters = chapterDao.getChaptersByMangaIdR(mangaId)

                chapters.forEach { chapter ->
                    val oldUrl = chapter.url
                    val newUrl = replaceBaseUrl(oldUrl, newBaseUrl)

                    if (newUrl != oldUrl) {
                        val updatedChapter = chapter.copy(url = newUrl)
                        chapterDao.update(updatedChapter)
                        updatedCount++
                        Log.v("URLPropagation", "Updated chapter URL: $oldUrl -> $newUrl")
                    }
                }
            }

            Log.d("URLPropagation", "Updated $updatedCount chapter URLs for API: $apiName")

        } catch (e: Exception) {
            Log.e("URLPropagation", "Failed to update chapter URLs for $apiName: ${e.message}", e)
        }
    }

    private suspend fun updateHistoryUrls(apiName: String, newBaseUrl: String) {
        try {
            val historyItems = historyDao.getHistoryByApi(apiName)
            var updatedCount = 0

            historyItems.forEach { historyItem ->
                val oldMangaUrl = historyItem.mangaUrl
                val oldChapterUrl = historyItem.chapterUrl

                val newMangaUrl = replaceBaseUrl(oldMangaUrl, newBaseUrl)
                val newChapterUrl = replaceBaseUrl(oldChapterUrl, newBaseUrl)

                if (newMangaUrl != oldMangaUrl || newChapterUrl != oldChapterUrl  ) {
                    val updatedHistory = historyItem.copy(
                        mangaUrl = newMangaUrl,
                        chapterUrl = newChapterUrl,
                    )
                    historyDao.update(updatedHistory)
                    updatedCount++
                    Log.v("URLPropagation", "Updated history URLs for item ${historyItem.id}")
                }
            }

            Log.d("URLPropagation", "Updated $updatedCount history URLs and image URLs for API: $apiName")

        } catch (e: Exception) {
            Log.e("URLPropagation", "Failed to update history URLs for $apiName: ${e.message}", e)
        }
    }

    private suspend fun updateNotificationUrls(apiName: String, newBaseUrl: String) {
        try {
            val notifications = notificationDao.getNotificationsByApi(apiName)
            var updatedCount = 0

            notifications.forEach { notification ->
                val oldMangaUrl = notification.mangaUrl
                val oldChapterUrl = notification.chapterUrl

                val newMangaUrl = replaceBaseUrl(oldMangaUrl, newBaseUrl)
                val newChapterUrl = replaceBaseUrl(oldChapterUrl, newBaseUrl)

                if (newMangaUrl != oldMangaUrl || newChapterUrl != oldChapterUrl) {
                    val updatedNotification = notification.copy(
                        mangaUrl = newMangaUrl,
                        chapterUrl = newChapterUrl,
                    )
                    notificationDao.update(updatedNotification)
                    updatedCount++
                    Log.v("URLPropagation", "Updated notification URLs for item ${notification.id}")
                }
            }

            Log.d("URLPropagation", "Updated $updatedCount notification URLs and image URLs for API: $apiName")

        } catch (e: Exception) {
            Log.e("URLPropagation", "Failed to update notification URLs for $apiName: ${e.message}", e)
        }
    }

    /**
     * Helper function to replace the base URL in a given URL
     * This assumes URLs follow a pattern like: https://old-domain.com/path/to/resource
     * and replaces it with: https://new-domain.com/path/to/resource
     */
    private fun replaceBaseUrl(originalUrl: String, newBaseUrl: String): String {
        return try {
            val uri = URI(originalUrl)
            val path = uri.path ?: ""
            val query = uri.query?.let { "?$it" } ?: ""
            val fragment = uri.fragment?.let { "#$it" } ?: ""

            // Ensure newBaseUrl doesn't end with '/'
            val cleanNewBaseUrl = newBaseUrl.trimEnd('/')

            "$cleanNewBaseUrl$path$query$fragment"
        } catch (e: Exception) {
            Log.w("URLPropagation", "Failed to parse URL: $originalUrl, returning original")
            originalUrl
        }
    }

    private suspend fun updateMangaImageUrls(apiName: String, newImageBaseUrl: String) {
        try {
            val mangaList = mangaDao.getMangaByApi(apiName)
            var updatedCount = 0

            mangaList.forEach { manga ->
                val oldImageUrl = manga.imageUrl
                val newImageUrl = replaceBaseUrl(oldImageUrl, newImageBaseUrl)

                if (newImageUrl != oldImageUrl) {
                    val updatedManga = manga.copy(imageUrl = newImageUrl)
                    mangaDao.update(updatedManga)
                    updatedCount++
                    Log.v("ImageURLPropagation", "Updated manga image URL: $oldImageUrl -> $newImageUrl")
                }
            }

            Log.d("ImageURLPropagation", "Updated $updatedCount manga image URLs for API: $apiName")

        } catch (e: Exception) {
            Log.e("ImageURLPropagation", "Failed to update manga image URLs for $apiName: ${e.message}", e)
        }
    }


    private suspend fun updateHistoryImageUrls(apiName: String, newImageBaseUrl: String) {
        try {
            val historyItems = historyDao.getHistoryByApi(apiName)
            var updatedCount = 0

            historyItems.forEach { historyItem ->
                val oldMangaImageUrl = historyItem.mangaImageUrl
                val newMangaImageUrl = replaceBaseUrl(oldMangaImageUrl, newImageBaseUrl)

                if (newMangaImageUrl != oldMangaImageUrl) {
                    val updatedHistory = historyItem.copy(mangaImageUrl = newMangaImageUrl)
                    historyDao.update(updatedHistory)
                    updatedCount++
                    Log.v("ImageURLPropagation", "Updated history image URL for item ${historyItem.id}")
                }
            }

            Log.d("ImageURLPropagation", "Updated $updatedCount history image URLs for API: $apiName")

        } catch (e: Exception) {
            Log.e("ImageURLPropagation", "Failed to update history image URLs for $apiName: ${e.message}", e)
        }
    }

    private suspend fun updateNotificationImageUrls(apiName: String, newImageBaseUrl: String) {
        try {
            val notifications = notificationDao.getNotificationsByApi(apiName)
            var updatedCount = 0

            notifications.forEach { notification ->
                val oldMangaImageUrl = notification.mangaImageUrl
                val newMangaImageUrl = replaceBaseUrl(oldMangaImageUrl, newImageBaseUrl)

                if (newMangaImageUrl != oldMangaImageUrl) {
                    val updatedNotification = notification.copy(mangaImageUrl = newMangaImageUrl)
                    notificationDao.update(updatedNotification)
                    updatedCount++
                    Log.v("ImageURLPropagation", "Updated notification image URL for item ${notification.id}")
                }
            }

            Log.d("ImageURLPropagation", "Updated $updatedCount notification image URLs for API: $apiName")

        } catch (e: Exception) {
            Log.e("ImageURLPropagation", "Failed to update notification image URLs for $apiName: ${e.message}", e)
        }
    }



}