package me.manga.yamiapk.presentation.features.whatsnew.data

import android.util.Log
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Singleton
class WhatsNewRemoteDataSource @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    companion object {
        private const val TAG = "WhatsNewRemoteDataSource"
        private const val WHATS_NEW_URL = "https://yamimanga.me/whatsnew/35/whatsnew.json"

        // Fallback language if user's language is not available
        private const val FALLBACK_LANGUAGE = "en"

        // Supported languages
        val SUPPORTED_LANGUAGES = setOf(
            "en", "ar", "de", "es", "fr", "in", "it", "ja", "pt", "ru", "tr"
        )
    }

    suspend fun fetchWhatsNewFeatures(languageCode: String): Result<WhatsNewResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(WHATS_NEW_URL)
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to fetch features: HTTP ${response.code}")
                    return@withContext Result.failure(
                        Exception("HTTP error: ${response.code}")
                    )
                }

                val responseBody =response.body?.use { it.string() }
                if (responseBody.isNullOrEmpty()) {
                    Log.e(TAG, "Empty response body")
                    return@withContext Result.failure(
                        Exception("Empty response")
                    )
                }
                    response.close()

                val whatsNewResponse = json.decodeFromString<WhatsNewResponse>(responseBody)

                Log.d(TAG, "Successfully fetched ${whatsNewResponse.features.size} features")
                Result.success(whatsNewResponse)

            } catch (e: Exception) {
                Log.e(TAG, "Error fetching What's New features", e)
                Result.failure(e)
            }
        }
    }

    fun getLocalizedFeature(
        feature: RemoteWhatsNewFeature,
        languageCode: String
    ): LocalizedFeature {
        val normalizedLanguage = normalizeLanguageCode(languageCode)

        val title = feature.title[normalizedLanguage]
            ?: feature.title[FALLBACK_LANGUAGE]
            ?: feature.title.values.firstOrNull()
            ?: "Unknown Feature"

        val description = feature.description[normalizedLanguage]
            ?: feature.description[FALLBACK_LANGUAGE]
            ?: feature.description.values.firstOrNull()
            ?: "No description available"

        return LocalizedFeature(
            title = title,
            description = description,
            mediaType = feature.mediaType,
            imageRes = feature.imageRes,
            imageList = feature.imageResList,
            imageUrl = feature.imageUrl,
            videoUrl = feature.videoUrl,
            isNew = feature.isNew,
            version = feature.version
        )
    }

    private fun normalizeLanguageCode(languageCode: String): String {
        // Handle language codes like "en-US" -> "en"
        val normalized = languageCode.lowercase().split("-", "_").first()

        return if (normalized in SUPPORTED_LANGUAGES) {
            normalized
        } else {
            FALLBACK_LANGUAGE
        }
    }
}