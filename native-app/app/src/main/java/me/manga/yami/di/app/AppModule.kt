package me.manga.yamiapk.di.app

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.manga.yamiapk.BuildConfig
import me.manga.yamiapk.core.network_cache.forceCacheForDados
import me.manga.yamiapk.core.network_cache.offlineCacheInterceptor
import me.manga.yamiapk.core.storage.SharedPrefsHelper
import me.manga.yamiapk.presentation.features.complaint.repository.ComplaintRepository
import me.manga.yamiapk.presentation.features.complaint.usecase.SendComplaintUseCase
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainOkHttpClient
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSharedPrefsHelper(@ApplicationContext context: Context): SharedPrefsHelper {
        return SharedPrefsHelper(context)
    }

    @MainOkHttpClient
    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context
    ): OkHttpClient {

        val cacheSize = 200L * 1024 * 1024 // 200 MB
        val cacheDir = File(context.cacheDir, "okhttp_cache")
        val cache = Cache(cacheDir, cacheSize)
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
//            redactHeader("Authorization")
//            redactHeader("Cookie")
        }

        return OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(15, TimeUnit.SECONDS)

            .apply {
                if (BuildConfig.DEBUG) addInterceptor(logging) // ✅ correct
            }
            // 👇 important interceptors
            .addNetworkInterceptor(forceCacheForDados())
            .addInterceptor(offlineCacheInterceptor(context))
            .build()
    }

//    @MainOkHttpClient
//    @Provides
//    @Singleton
//    fun provideOkHttpClient(): OkHttpClient {
//        return OkHttpClient.Builder()
//            .connectTimeout(30, TimeUnit.SECONDS)   // Prevent timeout during connection
//            .readTimeout(60, TimeUnit.SECONDS)      // Prevent timeout while reading headers/body
//            .writeTimeout(60, TimeUnit.SECONDS)     // Prevent timeout during upload
//            .retryOnConnectionFailure(true)         // Auto-retry if HTTP/2 stream fails
//            .pingInterval(15, TimeUnit.SECONDS)     // Prevent HTTP/2 stream timeout
//            .build()
//    }

    @Provides
    @Singleton
    fun provideSendComplaintUseCase(
        repo: ComplaintRepository
    ): SendComplaintUseCase = SendComplaintUseCase(repo)


    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

}