package me.manga.yamiapk.di.coli

import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.manga.yamiapk.core.avif.AvifDecoderCoil
import me.manga.yamiapk.core.progress.ProgressInterceptor
import me.manga.yamiapk.di.app.MainOkHttpClient
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CoilOkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object CoilModule {


    @CoilOkHttpClient
    @Provides
    @Singleton
    fun provideCoilOkHttpClient(
        @MainOkHttpClient baseClient: OkHttpClient
    ): OkHttpClient {
        return baseClient.newBuilder()
            .addNetworkInterceptor(ProgressInterceptor())   //  👈 ADD THIS
            .hostnameVerifier { hostname, session ->

                Log.i("sadfkasjdflksjdgsfgfdgdffg",hostname)
                // Allow any *.s3.wasabisys.com host
                if (hostname.endsWith(".s3.wasabisys.com")) {
                    true
                } else {
                    HttpsURLConnection.getDefaultHostnameVerifier()
                        .verify(hostname, session)
                }
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        @CoilOkHttpClient callFactory: OkHttpClient  // 👈 This is now EXPLICIT
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { callFactory }
                    )
                )
                add(AvifDecoderCoil.Factory())
            }
            .build()
    }
}